#!/usr/bin/env groovy

/*******************************************************************************
 * Copyright (C) 2020 EGit Committers and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.egit.jenkins

/**
 * EGit build library collecting operations to determine versions and paths depending on versions.
 */
class Lib implements Serializable {

	private final def script

	Lib(def script) {
		this.script = script
	}

	/**
	 * Checks that {@code config} contains all the keys of {@code mandatory}. For each missing key,
	 * writes the corresponding value from {@code mandatory}, which is thus supposed to contain a
	 * parameter description. Fails the build if not all mandatory keys exists in {@code config}.
	 *
	 * @param config
	 * 		Map to check
	 * @param mandatory
	 * 		Map of mandatory keys and their descriptions
	 */
	def void configCheck(Map config, Map mandatory) {
		boolean missing = false
		for (m in mandatory) {
			if (!config.containsKey(m.key)) {
				missing = true
				script.println('[WARN] Missing parameter ' + m.key + ': ' + m.value)
			}
		}
		if (missing) {
			script.error('Mandatory parameters missing')
		}
	}

	/**
	 * Reads the &lt;version> tag from the given pom file.
	 *
	 * @param pom
	 * 		path to the pom.xml relative to the current working directory
	 */
	def String getOwnVersion(String pom) {
		return (script.readFile(file: pom, encoding: 'UTF-8') =~ /<version>([^<>]*)<\/version>/)[0][1]
	}

	/**
	 * Determines the upstream version.
	 * <p>
	 * If we're a -SNAPSHOT version, get the existing snapshot repositories
	 * and take the highest matching the major.minor number.
	 * </p>
	 * <p>
	 * Otherwise, find the most recent tag matching our own version; i.e.
	 * if we're 5.3.-something we take the most recent tag v5.3.x.yyyymmddhhmm
	 * If we're a release, we only consider release tags (suffix "-r")
	 * </p>
	 *
	 * @param upstreamRepoPath
	 * 		the path at which the upstream is to be found, for instance "orbit" for the Eclipse project "orbit/orbit-recipes"
	 * @param upstreamProject
	 * 		the project name (== repository name), for instance "orbit-recipes" for the Eclipse project "orbit/orbit-recipes"
	 * @param ownVersion
	 * 		the version of this project
	 * @return the needed upstream version
	 */
	def String getUpstreamVersion(String upstreamRepoPath, String upstreamProject, String ownVersion) {
		if (ownVersion.endsWith('-SNAPSHOT')) {
			return getUpstreamSnapshotVersion(upstreamProject, ownVersion)
		}
		def tags = getTags("https://eclipse.gerrithub.io/${upstreamRepoPath}/${upstreamProject}").trim().split(/\v+/)
		def tag = ''
		int max = -1
		long maxTime = -1
		def isRelease = ownVersion.endsWith('-r')
		for (int i = tags.length - 1; i >= 0; i--) {
			def t = tags[i].trim()
			def m = t =~ /.*refs\/tags\/v(\d+\.\d+\.)(\d+)\.(\d+)(-.*)/
			if (m && ownVersion.startsWith(m[0][1])) {
				if (isRelease && !t.endsWith('-r')) {
					continue
				}
				int patch = m[0][2] as Integer
				long date = m[0][3] as Long
				if (patch > max) {
					max = patch
					maxTime = date
					tag = m[0][1] + m[0][2] + '.' + m[0][3] + m[0][4]
				} else if (patch == max && date > maxTime) {
					maxTime = date
					tag = m[0][1] + m[0][2] + '.' + m[0][3] + m[0][4]
				}
			}
		}
		return tag
	}

	private def getUpstreamSnapshotVersion(String upstreamProject, String ownVersion) {
		// Slightly ugly HTML slurping. Is there a better way?
		// def url = new URL("https://repo.eclipse.org/content/unzip/snapshots.unzip/org/eclipse/" + upstreamProject + "/org.eclipse." + upstreamProject + ".repository/")
		// def text = url.getText()
		// The above new URL() is forbidden by Jenkins' script security...
		def text = script.sh(returnStdout: true, script: "curl -s -f -m 10 -L --max-redirs 8 https://repo.eclipse.org/content/unzip/snapshots.unzip/org/eclipse/${upstreamProject}/org.eclipse.${upstreamProject}.repository/")
		// TODO: Windows ?
		def data = text.split(/\v+/)
		def version = ownVersion
		int max = -1
		for (int i = 0; i < data.length; i++) {
			def m = data[i] =~ /<a href="[^"]*\/(\d+\.\d+\.)(\d+)-SNAPSHOT\/"[^>]*>/
			if (m && ownVersion.startsWith(m[0][1])) {
				int patch = m[0][2] as Integer
				if (patch > max) {
					max = patch
					version = m[0][1] + m[0][2] + '-SNAPSHOT'
				}
			}
		}
		return version
	}

	private def String getTags(String url) {
		def cmd = "git ls-remote --tags --refs ${url}"
		if (script.isUnix()) {
			return script.sh(returnStdout: true, script: cmd)
		} else {
			return script.bat(returnStdout: true, script: '@' + cmd)
		}
	}

	/**
	 * Determines the maven command line option giving the upstream repository to use for the maven build.
	 *
	 * @param project
	 * 		name of the upstream project ("jgit" or "egit")
	 * @param version
	 * 		upstream version as determined by {@link #getUpstreamVersion(String, String, String)}
	 *
	 * @return the complete maven command line option "-Djgit-site=" or "-Degit-site="
	 */
	def String getMvnUpstreamRepo(String project, String version) {
		def repo = "-D${project}-site=https://repo.eclipse.org/content/unzip/"
		if (version.endsWith('-SNAPSHOT')) {
			repo += 'snapshots.unzip/org/eclipse/'
		} else {
			repo += 'releases.unzip/org/eclipse/'
		}
		repo += project
		def pkg = "org.eclipse.${project}.repository"
		repo += "/${pkg}/${version}/${pkg}-${version}.zip-unzip/"
		return repo
	}

	/**
	 * Determines the folder to publish to.
	 *
	 * @param branch
	 * 		branch we're building
	 * @param ownVersion
	 * 		version of the project being built
	 * @return The subfolder name to publish the p2 repo to
	 */
	def String getPublishFolder(String branch, String ownVersion) {
		if (ownVersion.endsWith('-SNAPSHOT')) {
			if (branch.contains('master')) {
				return 'updates-nightly';
			} else {
				// We only ever build the last release or master, so two directories are sufficient
				return 'updates-stable-nightly'
			}
		} else {
			// Gotta be a release.
			if (ownVersion.endsWith('-r')) {
				def m = ownVersion =~ /(\d+\.\d+)\.(\d+).*/
				def patch = m[0][2] as Integer
				if (patch == 0) {
					return 'updates-' + m[0][1]
				} else {
					return 'updates-' + m[0][1] + '.' + m[0][2]
				}
			} else {
				return 'staging/v' + ownVersion
			}
		}
	}
}
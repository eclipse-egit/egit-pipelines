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
 * Collection of useful pipeline fragments for use in an EGit build.
 */
class Tools implements Serializable {

	private final def script

	Tools(def script) {
		this.script = script
	}

	/**
	 * Constructs a GerritTrigger "gerritProjects" specification to trigger on the given
	 * {@code repo} and {@code branches}.
	 *
	 * @param match
	 * 		match type for {@code repo}
	 * @param repo
	 * 		to trigger on
	 * @param branches
	 * 		to trigger on; single entry or a Collection of entries, each entry either
	 * 		a simple string or a list containing two strings, the first one the match
	 * 		type and the second one the name or pattern. If empty or {@code null}, no
	 *      branch filter will be added.
	 * @return the "gerritProjects" specification
	 */
	def Object projectsToBuild(String match, String repo, def branches = null) {
		def branchSpecs = []
		if (!branches) {
			return [
				[$class: "GerritProject", compareType: match, pattern: repo]
			]
		}
		if (branches instanceof String) {
			branchSpecs.add([$class: "Branch", compareType: "PLAIN", pattern: b])
		} else {
			for (b in branches) {
				if (b instanceof Collection && b.size() == 2) {
					branchSpecs.add([$class: "Branch", compareType: b[0], pattern: b[1]])
				} else {
					branchSpecs.add([$class: "Branch", compareType: "PLAIN", pattern: b])
				}
			}
		}
		return [
			[$class: "GerritProject", compareType: match, pattern: repo, branches: branchSpecs]
		]
	}

	/**
	 * A complete "Checkout" stage for EGit projects, cloning a given {@code project} using
	 * the given {@code refSpec} and checking out a given {@code branch}.
	 *
	 * @param project
	 * 		to clone; for example "jgit/jgit"
	 * @param branch
	 * 		to check out
	 * @param refSpec
	 * 		to use
	 * @param extras
	 *		map of extra parameters for the checkout() command
	 */
	def void cloneAndCheckout(String project, String branch, String refSpec, Map extras = [:]) {
		def cfg = [
			$class: 'GitSCM',
			branches: [[name: branch]],
			doGenerateSubmoduleConfigurations: false,
			submoduleCfg: [],
			userRemoteConfigs: [
				[url : "git://git.eclipse.org/gitroot/${project}.git", name : 'origin', refspec : refSpec]
			]
		]
		for (extra in extras) {
			cfg.put(extra.key, extra.value)
		}
		script.checkout(cfg)
	}

	/**
	 * Copies all content of a {@code sourceDirectory} to a {@code publishDirectory} on projects-storage.eclipse.org
	 * via ssh/scp. If the {@code publishDirectory} exists already, it is replaced.
	 *
	 * @param genie
	 * 		user name to log in at projects-storage.eclipse.org, typically "genie.egit"
	 * @param credentials
	 * 		Jenkins credential name for the ssh key to use
	 * @param sourceDirectory
	 * 		path relative to ${WORKSPACE} of the directory to copy
	 * @param publishDirectory
	 * 		path on projects-storage.eclipse.org to copy to
	 * @param extraSource
	 * 		to also copy to {@code publishDirectory}
	 */
	def void publishUpdateSite(String genie, String credentials, String sourceDirectory, String publishDirectory, String extraSource = null) {
		def buildNumber = script.currentBuild.number;
		script.sshagent ([credentials]) {
			script.sh """
					ssh ${genie}@projects-storage.eclipse.org rm -rf ${publishDirectory}-tmp${buildNumber}
					ssh ${genie}@projects-storage.eclipse.org mkdir -p ${publishDirectory}-tmp${buildNumber}
					scp -r ${sourceDirectory}/* ${genie}@projects-storage.eclipse.org:${publishDirectory}-tmp${buildNumber}
				"""
			if (extraSource) {
				script.sh """
					scp ${extraSource} ${genie}@projects-storage.eclipse.org:${publishDirectory}-tmp${buildNumber}/
				"""
			}
			// Remove former -old directory. There shouldn't be one, but let's be sure.
			// Ensure the publishDirectory exists before moving it to -old.
			// Then rename -tmp and remove -old.
			script.sh """
					ssh ${genie}@projects-storage.eclipse.org rm -rf ${publishDirectory}-old
					ssh ${genie}@projects-storage.eclipse.org mkdir -p ${publishDirectory}
					ssh ${genie}@projects-storage.eclipse.org mv ${publishDirectory} ${publishDirectory}-old
					ssh ${genie}@projects-storage.eclipse.org mv ${publishDirectory}-tmp${buildNumber} ${publishDirectory}
					ssh ${genie}@projects-storage.eclipse.org rm -rf ${publishDirectory}-old
				"""
		}
	}

	/**
	 * Standard EGit build reporting steps, including artifact archiving.
	 *
	 * @param specificArtifacts
	 * 		Collection of ${WORKSPACE}-relative ant patterns defining the artifacts to archive;
	 * 		screenshots and Eclipse log files are added automatically
	 */
	def void reporting(Collection specificArtifacts = []) {
		// don't use ** if the number of directories is known, this is a huge performance problem
		script.junit '*/target/surefire-reports/*.xml'

		def artifacts = []
		artifacts.addAll(specificArtifacts)
		artifacts.addAll([
			'*/target/screenshots/*',
			'*/target/work/data/.metadata/*log',
		])
		script.archiveArtifacts artifacts.join(',')

		// TODO replace by warnings-next-generation once it is installed
		script.findbugs pattern: '*/target/*bugsXml.xml', defaultEncoding: 'UTF-8'
		script.dry defaultEncoding: 'UTF-8'
	}

	/**
	 * Sends an e-mail depending on the build outcome.
	 *
	 * @param to
	 * 		E-mail recipients; a whitespace-separated sequence of e-mail addresses
	 */
	def void sendMail(String to) {
		if (script.currentBuild.result == null) {
			script.currentBuild.result = script.currentBuild.currentResult
		}
		script.step([
			$class: 'Mailer',
			notifyEveryUnstableBuild: true,
			recipients: to,
			sendToIndividuals: true
		])
	}

	/**
	 * Runs mvn with the given arguments, supplying the EGit default arguments automatically.
	 *
	 * @param arguments
	 * 		for mvn
	 * @param mvnVersion
	 *		Jenkins tool identifier; defaults to the latest mvn version available
	 */
	def void maven(Collection arguments, String mvnVersion = 'apache-maven-latest') {
		def args = []
		args.addAll(arguments)
		// General build setup
		args.addAll([
			// suppress progress output
			'--batch-mode',
			// show maven errors
			'--errors',
			// have a separate maven repo per job
			"-Dmaven.repo.local=${script.env.WORKSPACE}/.repository",
			// avoid flaky or not updated mirrors
			'-Declipse.p2.mirrors=false',
			// temporary directory for egit tests
			"-Degit.test.tmpdir=${script.env.WORKSPACE}/tmp/egit.tmp/",
			// temporary directory for java
			"-Djava.io.tmpdir=${script.env.WORKSPACE}/tmp/",
		])
		// mvn logging setup
		args.addAll([
			// make eclipse log to the build log, not just into a file
			'-Dtest.vmparams=-Declipse.consoleLog=true',
			// enable timestamps in mvn logging
			'-Dorg.slf4j.simpleLogger.showDateTime=true',
			// set timestamp format
			'-Dorg.slf4j.simpleLogger.dateTimeFormat=HH:mm:ss',
			// disable download progress output by allowing warning output only
			'-Dorg.slf4j.simpleLogger.log.org.apache.maven.cli.transfer.Slf4jMavenTransferListener=warn',
			// disable parallel maven build threadsafe warning by allowing error output only
			'-Dorg.slf4j.simpleLogger.log.org.apache.maven.lifecycle.internal.builder.BuilderCommon=error'
		])
		def command = args.join(' ')

		// get the path from the global Jenkins configuration
		def mvnHome = script.tool mvnVersion

		// invoke maven
		if (script.isUnix()) {
			script.sh "'${mvnHome}/bin/mvn' ${command}"
		} else {
			script.bat(/"${mvnHome}\bin\mvn" ${command}/)
		}
	}
}
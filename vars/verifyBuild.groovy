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

/**
 * EGit verify build for a given branch.
 *
 * @param lib
 * 		library to use
 * @param tooling
 * 		to use
 * @param cfg
 * 		configuration
 * @return
 */
def call(def lib, def tooling, Map cfg = [:]) {
	Map config = [timeOut : 60, noTests : false, jdk : 'temurin-jdk17-latest', gpg : false] << cfg
	// Check parameters
	lib.configCheck(config, [
		timeOut : 'Job timeout in minutes, default 60',
		repoPath : 'Full path to the repository to build, for instance "egit/egit".',
		// defaultBranch is optional: branch to build if $GERRIT_BRANCH is not set
		// optional: upstreamRepoPath : 'Path to the upstream repo, for instance the first "jgit" for "jgit/jgit".',
		// optional: upstreamRepo : 'Upstream repository name, for instance the second "jgit" for "jgit/jgit".',
		// upstreamVersion is optional; auto-determined if not set
		p2project : 'Project containing the built update site at target/repository.',
	])
	uiNode(config.timeOut) {
		try {
			if (!env.GERRIT_BRANCH) {
				env.GERRIT_BRANCH = config.defaultBranch
			}
			if (config.jdk) {
				def jdk = tool name: "${config.jdk}", type: 'jdk'
				env.JAVA_HOME = "${jdk}"
			}
			stage('Checkout') {
				sh '$JAVA_HOME/bin/java -version'
				tooling.cloneAndCheckout(config.repoPath, env.GERRIT_BRANCH, '$GERRIT_REFSPEC', [
					extensions: [
						[$class: 'BuildChooserSetting',
							buildChooser: [$class: 'GerritTriggerBuildChooser']
						]
					]
				])
			}
			stage('Initialize PGP') {
				if (config.gpg) {
					withCredentials([
						file(credentialsId: 'secret-subkeys.asc', variable: 'KEYRING')
					]) {
						sh '''
							gpg --batch --import "${KEYRING}"
							for fpr in $(gpg --list-keys --with-colons \
								| awk -F: \'/fpr:/ {print $10}\' \
								| sort -u); do echo -e "5\ny\n" \
								|  gpg --batch --command-fd 0 --expert --edit-key ${fpr} trust; \
							done
						'''
					}
				} else {
					echo "No GPG setup"
				}
			}
			stage('Build') {
				def profiles = config.noTests ? '' : 'static-checks,'
				profiles += 'other-os,eclipse-sign'
				if (config.gpg) {
					profiles += 'gpg-sign'
				}
				def arguments = [
					'clean',
					'install',
					'-P' + profiles
				]
				def upstreamRepo = config.upstreamRepo;
				if (!!upstreamRepo) {
					def ownVersion = lib.getOwnVersion('pom.xml')
					def upstreamVersion = config.upstreamVersion
					if (!upstreamVersion) {
						upstreamVersion = lib.getUpstreamVersion(config.upstreamRepoPath, upstreamRepo, ownVersion)
					}
					arguments.add(lib.getMvnUpstreamRepo(upstreamRepo, upstreamVersion))
				}
				if (config.noTests) {
					arguments.add('-DskipTests=true')
				}
				if (config.gpg) {
					withCredentials([
						string(credentialsId: 'gpg-passphrase', variable: 'EGIT_KEYRING_PASSPHRASE')
					]) {
						tooling.maven(arguments)
					}
				} else {
					tooling.maven(arguments)
				}
			}
		}
		finally {
			// replacement for post actions of Jenkins 1.x
			stage('Results') {
				tooling.archiveArtifacts([
					config.p2project + '/target/repository/**'
				])
				if (!config.noTests) {
					tooling.reporting()
				}
			}
		}
	}
}
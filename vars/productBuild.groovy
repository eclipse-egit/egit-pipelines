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
 * EGit product build for a given branch, either determined by the gerrit trigger's
 * $GERRIT_BRANCH or the {@code cfg.defaultBranch}.
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
	Map config = [timeOut : 60] << cfg
	// Check parameters
	lib.configCheck(config, [
		timeOut : 'Job timeout in minutes, default 60',
		repoPath : 'Full path to the repository to build, for instance "egit/egit".',
		// defaultBranch is optional: branch to build if $GERRIT_BRANCH is not set
		upstreamRepoPath: 'Path to the upstream repo, for instance the first "jgit" for "jgit/jgit".',
		upstreamRepo: 'Upstream repository name, for instance the second "jgit" for "jgit/jgit".',
		// upstreamVersion is optional; auto-determined if not set
		p2project: 'Project containing the built update site at target/repository.',
		// p2zip is optional: p2 repo zip to also copy during deployment,
		publishRoot: 'Folder on p2 publish server under which the repo should be placed. The appropriate subfolder is determined automatically.',
		// downstreamJob is optional: job to trigger after deployment
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
				tooling.cloneAndCheckout(config.repoPath, env.GERRIT_BRANCH, '+refs/heads/*:refs/remotes/origin/*');
			}
			def ownVersion = lib.getOwnVersion('pom.xml')
			def publishFolder = "/${config.publishRoot}/" + lib.getPublishFolder(ownVersion)
			def publishDirectory = '/home/data/httpd/download.eclipse.org' + publishFolder
			def upstreamVersion = config.upstreamVersion
			if (!upstreamVersion) {
				upstreamVersion = lib.getUpstreamVersion(config.upstreamRepoPath, config.upstreamRepo, ownVersion)
			}
			def commonMvnArguments = [
				'-Pstatic-checks,other-os,eclipse-sign',
				lib.getMvnUpstreamRepo(config.upstreamRepo, upstreamVersion),
				// Needed by tycho-eclipserun for the p2 mirrors URL
				"-DPUBLISH_FOLDER=${publishFolder}"
			]
			stage('Build') {
				def arguments = [
					'clean',
					'install'
				]
				arguments.addAll(commonMvnArguments)
				tooling.maven(arguments)
			}
			stage('Deploy') {
				// Nexus
				def arguments = [
					'deploy',
					'-DskipTests=true',
					'-Dskip-ui-tests=true'
				]
				arguments.addAll(commonMvnArguments)
				tooling.maven(arguments)
				// Update site
				def extraSource = null
				if (config.p2zip && ownVersion.endsWith('-r')) {
					// Must be a stable build...
					extraSource = config.p2project + '/target/' + config.p2zip
				}
				tooling.publishUpdateSite(
						'genie.egit',
						'projects-storage.eclipse.org-bot-ssh',
						config.p2project + '/target/repository',
						publishDirectory,
						extraSource)
			}
			if (config.downstreamJob) {
				build(
						job: config.downstreamJob,
						propagate: false,
						wait: false,
						parameters: [
							[$class: 'StringParameterValue', name: 'EGIT_VERSION', value: ownVersion]
						])
			}
		}
		finally { // replacement for post actions of Jenkins 1.x
			stage('Results') {
				tooling.reporting([
					config.p2project + '/target/repository/**'
				])
			}
			tooling.sendMail('egit-build@eclipse.org')
		}
	}
}
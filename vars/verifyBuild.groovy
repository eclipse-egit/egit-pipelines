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
	Map config = [timeOut : 60] << cfg
	// Check parameters
	lib.configCheck(config, [
		timeOut : 'Job timeout in minutes, default 60',
		repoPath : 'Full path to the repository to build, for instance "egit/egit".',
		// defaultBranch is optional: branch to build if $GERRIT_BRANCH is not set
		upstreamRepoPath : 'Path to the upstream repo, for instance the first "jgit" for "jgit/jgit".',
		upstreamRepo : 'Upstream repository name, for instance the second "jgit" for "jgit/jgit".',
		// upstreamVersion is optional; auto-determined if not set
		p2project : 'Project containing the built update site at target/repository.',
	])
	uiNode(config.timeOut) {
		try {
			if (!env.GERRIT_BRANCH) {
				env.GERRIT_BRANCH = config.defaultBranch
			}
			stage('Checkout') {
				tooling.cloneAndCheckout(config.repoPath, env.GERRIT_BRANCH, '$GERRIT_REFSPEC', [
					extensions: [
						[$class: 'BuildChooserSetting',
							buildChooser: [$class: 'GerritTriggerBuildChooser']
						]
					]
				])
			}
			stage('Build') {
				def ownVersion = lib.getOwnVersion('pom.xml')
				def upstreamVersion = config.upstreamVersion
				if (!upstreamVersion) {
					upstreamVersion = lib.getUpstreamVersion(config.upstreamRepoPath, config.upstreamRepo, ownVersion)
				}
				def arguments = [
					'clean',
					'install',
					'-Pstatic-checks,other-os,eclipse-sign',
					lib.getMvnUpstreamRepo(config.upstreamRepo, upstreamVersion)
				]
				tooling.maven(arguments)
			}
		}
		finally { // replacement for post actions of Jenkins 1.x
			stage('Results') {
				tooling.reporting([
					config.p2project + '/target/repository/**'
				])
			}
		}
	}
}
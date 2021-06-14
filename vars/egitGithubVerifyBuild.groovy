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
 * EGit-Github Gerrit patchset verify build for a given branch, either determined by the gerrit trigger's
 * $GERRIT_BRANCH or the {@code defaultBranch}.
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
	Map config = [
		timeOut : 30,
		repoPath : 'egit/egit-github',
		// defaultBranch from cfg
		upstreamRepoPath : 'egit',
		upstreamRepo : 'egit',
		upstreamVersion: params.EGIT_VERSION,
		p2project : 'org.eclipse.mylyn.github-site'
	]
	verifyBuild(lib, tooling, config << cfg)
}
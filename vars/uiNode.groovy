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
 * Runs {@code body} with the given {@code timeOut} on a Linux UI node with Xvnc display
 * and a window manager.
 *
 * @param timeOut in minutes for the whole {@code body}
 * @param body to execute
 * @return
 */
def call(int timeOut, Closure body) {
	timestamps {
		// Run only on nodes capable of UI tests ('ui-test' or 'migration')
		node('migration') {
			timeout(time: timeOut, unit: 'MINUTES') {
				// Start the VNC server
				wrap([$class: 'Xvnc', takeScreenshot: false, useXauthority: true]) {
					stage('Environment') {
						// Start the window manager
						sh 'mutter --replace --sm-disable &'
						// Create EGit tmp dir
						sh 'mkdir -p tmp/egit.tmp'
					}
					// Use a subdirectory to not overlap with tmp
					dir('repo') {
						body()
					}
				}
			}
		}
	}
}
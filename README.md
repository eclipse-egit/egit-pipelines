<!--
 * Copyright (C) 2020 EGit Committers and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
-->

# EGit Jenkins Pipelines

This repository contains the EGit Jenkins pipeline library.

For general information about Jenkins pipeline shared libraries see the
[Jenkins documentation](https://jenkins.io/doc/book/pipeline/shared-libraries/).

Jenkins pipelines are written in Groovy; for development in Eclipse it may
help to install the [Groovy Development Tools](https://marketplace.eclipse.org/content/groovy-development-tools).
Be aware, though, that GDT patches the JDT Java compiler; a particular version
of GDT thus works only with a particular version of JDT. If you use an Eclipse
I-build (nightly development build for the next release), GDT will fail to
install.

The library is intended to be used for the [Jenkins builds](https://ci.eclipse.org/egit/)
of the egit/egit and the egit/egit-github repositories.

It provides several kinds of general pipelines that can be configured:

* `verifyBuild` is a simple pipeline that builds and runs the tests for a Gerrit patch set.
* `productBuild` is intended to be run when a Gerrit patch set is submitted and builds a full distribution (nightly or stable build).

`uiNode` encapsulates the general Jenkins slave setup to run a build including UI tests on a JIRO node.

Directory `src` contains auxiliary Groovy classes encapsulating generally useful operations.

## License

The content of this repository is licensed under the [EPL 2.0](https://www.eclipse.org/legal/epl-2.0).

SPDX-License-Identifier: EPL-2.0

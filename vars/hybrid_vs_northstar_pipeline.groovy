def prepareTool(String osName, Map options) {
    switch(osName) {
        case "Windows":
            unstash("Tool_Windows")
            unzip(zipFile: "HybridVsNorthStar_Windows.zip")
            unstash("enginesDlls")
            break
        case "OSX":
            println("Unsupported OS")
            break
        default:
            println("Unsupported OS")
    }
}


def executeTestCommand(String osName, String asicName, Map options) {
    timeout(time: options.TEST_TIMEOUT, unit: 'MINUTES') {
        dir('scripts') {
            switch(osName) {
                case 'Windows':
                    bat """
                        run.bat ${options.testsPackage} \"${options.tests}\" HybridPro >> \"../${STAGE_NAME}_HybridPro_${options.currentTry}.log\" 2>&1
                    """

                    utils.moveFiles(this, osName, "Work", "Work-Hybrid")

                    bat """
                        run.bat ${options.testsPackage} \"${options.tests}\" Northstar64 >> \"../${STAGE_NAME}_Northstar64_${options.currentTry}.log\" 2>&1
                    """

                    utils.moveFiles(this, osName, "Work", "Work-Northstar64")
                    break

                case 'OSX':
                    println("Unsupported OS")
                    break

                default:
                    println("Unsupported OS")
            }
        }
    }
}


def executeTests(String osName, String asicName, Map options) {
    // used for mark stash results or not. It needed for not stashing failed tasks which will be retried.
    Boolean stashResults = true
    try {
        withNotifications(title: options["stageName"], options: options, logUrl: "${BUILD_URL}", configuration: NotificationConfiguration.DOWNLOAD_TESTS_REPO) {
            timeout(time: "5", unit: "MINUTES") {
                cleanWS(osName)
                checkoutScm(branchName: options.testsBranch, repositoryUrl: "git@github.com:luxteam/jobs_test_hybrid_vs_ns.git")
            }
        }

        withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.DOWNLOAD_SCENES) {
            timeout(time: "5", unit: "MINUTES") {
                unstash("testResources")

                // Bug of tool (it can't work without resources in current dir)
                dir("jobs_test_hybrid_vs_ns/scripts") {
                    unstash("testResources")
                }
            }
        }

        withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.INSTALL_PLUGIN) {
            timeout(time: "5", unit: "MINUTES") {
                dir("jobs_test_hybrid_vs_ns/HybridVsNs") {
                    prepareTool(osName, options)
                }
            }
        }

        withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.EXECUTE_TESTS) {
            executeTestCommand(osName, asicName, options)
        }

        options.executeTestsFinished = true
    } catch (e) {
        if (options.currentTry < options.nodeReallocateTries) {
            stashResults = false
        }
        println e.toString()
        if (e instanceof ExpectedExceptionWrapper) {
            GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, e.getMessage(), "${BUILD_URL}")
            throw new ExpectedExceptionWrapper(e.getMessage(), e.getCause())
        } else {
            GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, NotificationConfiguration.REASON_IS_NOT_IDENTIFIED, "${BUILD_URL}")
            throw new ExpectedExceptionWrapper(NotificationConfiguration.REASON_IS_NOT_IDENTIFIED, e)
        }
    } finally {
        try {
            dir(options.stageName) {
                utils.moveFiles(this, osName, "../*.log", ".")
                utils.moveFiles(this, osName, "../scripts/*.log", ".")
                utils.renameFile(this, osName, "launcher.engine.log", "${options.stageName}_engine_${options.currentTry}.log")
            }
            archiveArtifacts artifacts: "${options.stageName}/*.log", allowEmptyArchive: true

            if (stashResults) {
                dir("Work-Hybrid") {
                    stash includes: '**/*', name: "${options.testResultsName}-Hybrid", allowEmpty: true
                }
                dir("Work-Northstar64") {
                    stash includes: '**/*', name: "${options.testResultsName}-Northstar64", allowEmpty: true
                }
            } else {
                println "[INFO] Task ${options.tests} on ${options.nodeLabels} labels will be retried."
            }
        } catch (e) {
            // throw exception in finally block only if test stage was finished
            if (options.executeTestsFinished) {
                if (e instanceof ExpectedExceptionWrapper) {
                    GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, e.getMessage(), "${BUILD_URL}")
                    throw e
                } else {
                    GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, NotificationConfiguration.FAILED_TO_SAVE_RESULTS, "${BUILD_URL}")
                    throw new ExpectedExceptionWrapper(NotificationConfiguration.FAILED_TO_SAVE_RESULTS, e)
                }
            }
        }
    }
}


def executeBuildWindows(Map options) {
    dir('HybridVsNorthStar') {
        GithubNotificator.updateStatus("Build", "Windows", "in_progress", options, NotificationConfiguration.BUILD_SOURCE_CODE_START_MESSAGE, "${BUILD_URL}/artifact/Build-Windows.log")
        
        bat """
            cmake ./ -B ./build >> ../${STAGE_NAME}.log 2>&1
        """

        dir("build") {
            bat """
                cmake --build ./ --config Release >> ../../${STAGE_NAME}.log 2>&1
            """

            String BUILD_NAME = "HybridVsNorthStar_Windows.zip"

            dir("bin\\Release") {
                zip archive: true, zipFile: "HybridVsNorthStar_Windows.zip"
            }

            stash(includes: "HybridVsNorthStar_Windows.zip", name: "Tool_Windows")

            String archiveUrl = "${BUILD_URL}artifact/${BUILD_NAME}"
            rtp nullAction: '1', parserName: 'HTML', stableText: """<h3><a href="${archiveUrl}">[BUILD: ${BUILD_ID}] ${BUILD_NAME}</a></h3>"""

            GithubNotificator.updateStatus("Build", "Windows", "success", options, NotificationConfiguration.BUILD_SOURCE_CODE_END_MESSAGE, archiveUrl)
        }
    }
}


def executeBuild(String osName, Map options) {
    try {
        dir("HybridVsNorthStar") {
            withNotifications(title: osName, options: options, configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
                checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo, prBranchName: options.prBranchName, prRepoName: options.prRepoName)
            }
        }

        outputEnvironmentInfo(osName)

        withNotifications(title: osName, options: options, configuration: NotificationConfiguration.BUILD_SOURCE_CODE) {
            switch(osName) {
                case "Windows":
                    executeBuildWindows(options)
                    break
                case "OSX":
                    println("Unsupported OS")
                    break
                default:
                    println("Unsupported OS")
            }
        }
    } catch (e) {
        throw e
    } finally {
        archiveArtifacts artifacts: "*.log", allowEmptyArchive: true
    }
}


def executePreBuild(Map options)
{
    // auto job
    if (env.BRANCH_NAME) {
        if (env.CHANGE_URL) {
            println "[INFO] Branch was detected as Pull Request"
            options.executeBuild = true
            options.executeTests = true
            options.testsPackage = "regression.json"
        } else if (env.BRANCH_NAME == "master" || env.BRANCH_NAME == "develop") {
           println "[INFO] ${env.BRANCH_NAME} branch was detected"
           options['executeBuild'] = true
           options['executeTests'] = true
           options['testsPackage'] = "regression.json"
        } else {
            println "[INFO] ${env.BRANCH_NAME} branch was detected"
            options['testsPackage'] = "regression.json"
        }
    // manual job
    } else {
        println "[INFO] Manual job launch detected"
        options['executeBuild'] = true
        options['executeTests'] = true
    }

    dir('HybridVsNorthStar') {
        withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
            checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo, disableSubmodules: true)
        }

        options.commitAuthor = bat (script: "git show -s --format=%%an HEAD ",returnStdout: true).split('\r\n')[2].trim()
        options.commitMessage = bat (script: "git log --format=%%B -n 1", returnStdout: true).split('\r\n')[2].trim()
        options.commitSHA = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
        options.commitShortSHA = options.commitSHA[0..6]

        println(bat (script: "git log --format=%%s -n 1", returnStdout: true).split('\r\n')[2].trim())
        println "The last commit was written by ${options.commitAuthor}."
        println "Commit message: ${options.commitMessage}"
        println "Commit SHA: ${options.commitSHA}"
        println "Commit shortSHA: ${options.commitShortSHA}"

        if (options.projectBranch) {
            currentBuild.description = "<b>Project branch:</b> ${options.projectBranch}<br/>"
        } else {
            currentBuild.description = "<b>Project branch:</b> ${env.BRANCH_NAME}<br/>"
        }

        withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.INCREMENT_VERSION) {
            currentBuild.description += "<b>Version:</b> ${options.pluginVersion}<br/>"
            currentBuild.description += "<b>Commit author:</b> ${options.commitAuthor}<br/>"
            currentBuild.description += "<b>Commit message:</b> ${options.commitMessage}<br/>"
            currentBuild.description += "<b>Commit SHA:</b> ${options.commitSHA}<br/>"
        }
    }

    withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.CONFIGURE_TESTS) {
        dir('jobs_test_hybrid_vs_ns') {
            checkoutScm(branchName: options.testsBranch, repositoryUrl: 'git@github.com:luxteam/jobs_test_hybrid_vs_ns.git')

            options['testsBranch'] = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
            dir('jobs_launcher') {
                options['jobsLauncherBranch'] = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
            }
            println "[INFO] Test branch hash: ${options['testsBranch']}"

            if (options.testsPackage != "none") {
                def groupNames = readJSON(file: "jobs/${options.testsPackage}")["groups"].collect { it.key }
                // json means custom test suite. Split doesn't supported
                options.tests = groupNames.join(" ")
                options.testsPackage = "none"
            }

            options.testsList = ['']
        }
    }

    dir('HybridVsNorthStar') {
        stash includes: "resources/", name: "testResources", allowEmpty: false
        stash includes: "third_party/*.dll", name: "enginesDlls", allowEmpty: false
    }
}


def call(String projectBranch = "",
    String platforms = 'Windows',
    String updateRefs = 'No',
    Boolean enableNotifications = true,
    String testsPackage = "",
    String tests = "",
    Boolean splitTestsExecution = true,
    String parallelExecutionTypeString = "TakeAllNodes"
    )
{
    String projectRepo = "git@github.com:Radeon-Pro/HybridVsNorthStar.git"

    ProblemMessageManager problemMessageManager = new ProblemMessageManager(this, currentBuild)
    Map options = [:]
    options["stage"] = "Init"
    options["problemMessageManager"] = problemMessageManager

    def nodeRetry = []

    try {
        withNotifications(options: options, configuration: NotificationConfiguration.INITIALIZATION) {
            gpusCount = 0
            platforms.split(';').each() { platform ->
                List tokens = platform.tokenize(':')
                if (tokens.size() > 1) {
                    gpuNames = tokens.get(1)
                    gpuNames.split(',').each() {
                        gpusCount += 1
                    }
                }
            }

            def parallelExecutionType = TestsExecutionType.valueOf(parallelExecutionTypeString)

            println "Platforms: ${platforms}"
            println "Tests: ${tests}"
            println "Tests package: ${testsPackage}"
            println "Split tests execution: ${splitTestsExecution}"
            println "Tests execution type: ${parallelExecutionType}"

            options << [projectRepo:projectRepo,
                        projectBranch:projectBranch,
                        updateRefs:updateRefs,
                        enableNotifications:enableNotifications,
                        testsPackage:testsPackage,
                        tests:tests,
                        splitTestsExecution:splitTestsExecution,
                        gpusCount:gpusCount,
                        nodeRetry: nodeRetry,
                        platforms:platforms,
                        BUILD_TIMEOUT: 15,
                        TEST_TIMEOUT: 30,
                        DEPLOY_TIMEOUT: 15,
                        parallelExecutionType:parallelExecutionType,
                        parallelExecutionTypeString: parallelExecutionTypeString
                        ]
        }

        multiplatform_pipeline(platforms, this.&executePreBuild, this.&executeBuild, this.&executeTests, null, options)
    } catch(e) {
        currentBuild.result = "FAILURE"
        println(e.toString())
        println(e.getMessage())
        throw e
    } finally {
        problemMessageManager.publishMessages()
    }

}
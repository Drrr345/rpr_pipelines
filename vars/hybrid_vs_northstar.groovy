import groovy.transform.Field


@Field final String PROJECT_REPO = "git@github.com:Radeon-Pro/HybridVsNorthStar.git"


Boolean updateBinaries(Map params) {
    String newBinaryFile = params["newBinaryFile"]
    String targetFileName = params["targetFileName"]
    String osName = params["osName"]
    Boolean compareChecksum = params.containsKey("compareChecksum") ? params["compareChecksum"] : false

    if (targetFileName != "HybridPro.dll" && targetFileName != "Northstar64.dll") {
       throw new Exception("Unknown target binary file");
    }

    // get absolute file path
    String newBinaryFilePath = ""

    if (isUnix()) {
        newBinaryFilePath = "${env.WORKSPACE}/${newBinaryFile}"
    } else {
        newBinaryFilePath = "${env.WORKSPACE}\\${newBinaryFile}"
    }

    dir("HybridVsNorthStar") {
        checkoutScm(branchName: "main", repositoryUrl: PROJECT_REPO)

        dir("third_party") {
            if (compareChecksum) {
                String newFileSha = sha1(newBinaryFilePath)
                String targetFileSha = sha1(targetFileName)

                if (newFileSha == targetFileSha) {
                    println("[INFO] target binary file and new one have same sha. Skip updating")

                    return false
                }
            }

            utils.removeFile(this, osName, targetFileName)
            utils.copyFile(this, osName, newBinaryFilePath, targetFileName)
        }

        switch(osName) {
            case "Windows":
                bat """
                    git add third_party
                    git commit -m "buildmaster: update ${targetFileName}"
                    git push origin HEAD:main
                """
                break
            case "OSX":
                println("Unsupported OS")
                break
            default:
                println("Unsupported OS")
        }
    }

    return true
}


def createHybridBranch(Map options) {
    try {
        if (options.comparisionBranch) {
            dir("HybridVsNorthstar") {
               checkoutScm(branchName: "main", repositoryUrl: PROJECT_REPO)
               String message = "Triggered by Build #${env.BUILD_NUMBER}"
               if (env.CHANGE_URL) {
                   message += ". PR URL - ${env.CHANGE_URL}"
               }
               bat """
                   git checkout -b ${options.comparisionBranch}
                   git commit --allow-empty -m "${message}"
                   git push origin ${options.comparisionBranch} --force
               """
            }
        }
    } catch (e) {
        println("[ERROR] Failed to create branch in HybridVsNorthstar repo")
        println(e)
    }
}


def prepareTool(String osName, Map options) {
    switch(osName) {
        case "Windows":
            unstash("Tool_Windows")
            unzip(zipFile: "HybridVsNorthStar_Windows.zip")
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

                    utils.moveFiles(this, osName, "..\\Work", "..\\Work-HybridPro")

                    bat """
                        run.bat ${options.testsPackage} \"${options.tests}\" Northstar64 >> \"../${STAGE_NAME}_Northstar64_${options.currentTry}.log\" 2>&1
                    """

                    utils.moveFiles(this, osName, "..\\Work", "..\\Work-Northstar64")
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
            timeout(time: "10", unit: "MINUTES") {
                unstash("testResources")

                // Bug of tool (it can't work without resources in current dir)
                dir("scripts") {
                    unstash("testResources")
                }
            }
        }

        withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.INSTALL_PLUGIN) {
            timeout(time: "5", unit: "MINUTES") {
                dir("HybridVsNs") {
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
                dir("Work-HybridPro/Results/HybridVsNs") {
                    stash includes: "**/*", name: "${options.testResultsName}-HybridPro", allowEmpty: true
                }
                dir("Work-Northstar64/Results/HybridVsNs") {
                    stash includes: "**/*", name: "${options.testResultsName}-Northstar64", allowEmpty: true
                }
            } else {
                println "[INFO] Task ${options.tests} on ${options.nodeLabels} labels will be retried."
            }

            utils.reboot(this, osName)
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
                if (env.BRANCH_NAME && env.BRANCH_NAME.startsWith("hybrid_auto_")) {
                    unstash("Northstar64Dll")
                    unstash("HybridProDll")
                } else {
                    unstash("enginesDlls")
                }

                zip archive: true, zipFile: "HybridVsNorthStar_Windows.zip"
                stash(includes: "HybridVsNorthStar_Windows.zip", name: "Tool_Windows")
            }

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
                checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo)
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
    println "[INFO] Branch was detected as build of autojob"
       options['executeBuild'] = true
       options['executeTests'] = true
       options['tests'] = "General"
       options['testsPackage'] = "none"
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
        dir("third_party") {
            if (env.BRANCH_NAME && env.BRANCH_NAME.startsWith("hybrid_auto_")) {
                stash includes: "Northstar64.dll", name: "Northstar64Dll", allowEmpty: false

                if (options.commitMessage.contains("PR URL")) {
                    def parts = options.commitMessage.replace("Triggered by Build #", "").replace("PR URL - ", "").split(". ")
                    options.hybridBuildNumber = parts[0] as Integer
                    options.hybridPullUrl = parts[1]
                } else {
                    options.hybridBuildNumber = options.commitMessage.replace("Triggered by Build #", "") as Integer
                }

                String branchName = env.BRANCH_NAME.split("_", 3)[2]

                String archiveName = "BaikalNext_Build-Windows.zip"

                String artifactRemotePath = "/volume1/web/RadeonProRender-Hybrid/${branchName}/${options.hybridBuildNumber}/Artifacts/${archiveName}"

                downloadFiles(artifactRemotePath, ".")

                unzip(zipFile: archiveName)

                dir("BaikalNext/bin") {
                    stash includes: "HybridPro.dll", name: "HybridProDll", allowEmpty: false
                }
            } else {
                stash includes: "*.dll", name: "enginesDlls", allowEmpty: false
            }
            
        }

        // if something was merged into master branch of Hybrid it could trigger build in master branch of HybridVsNorthstar autojob
        if (env.BRANCH_NAME && env.BRANCH_NAME == "main") {
            String jenkinsUrl
            withCredentials([string(credentialsId: 'jenkinsURL', variable: 'JENKINS_URL')]) {
                jenkinsUrl = JENKINS_URL
            }

            // get name and color (~status) of each branch/PR in Hybrid autojob
            def hybridJobInfo = httpRequest(
                url: "${jenkinsUrl}/job/RadeonProRender-Hybrid/api/json?tree=jobs[name,color]",
                authentication: "jenkinsCredentials",
                httpMode: "GET"
            )

            def hybridJobInfoParsed = github_release_pipeline.parseResponse(hybridJobInfo.content)["jobs"]

            hybridJobInfoParsed.each { item ->
                if (item["color"] == "disabled") {
                    String possibleBranchName = "hybrid_auto_${item.name}"

                    Boolean branchExists = bat(script: "git ls-remote --heads ${PROJECT_REPO} ${possibleBranchName}",returnStdout: true)
                        .split('\r\n').length > 2

                    if (branchExists) {
                        // branch/PR doesn't exist. Remove remote branch
                        bat """
                            git push -d origin ${possibleBranchName}
                        """

                        println("[INFO] branch ${possibleBranchName} was removed")
                    }
                }
            }
        }
    }
}


def executeDeploy(Map options, List platformList, List testResultList) {
    try {
        if (options['executeTests'] && testResultList) {
            withNotifications(title: "Building test report", options: options, startUrl: "${BUILD_URL}", configuration: NotificationConfiguration.DOWNLOAD_TESTS_REPO) {
                checkoutScm(branchName: options.testsBranch, repositoryUrl: "git@github.com:luxteam/jobs_test_hybrid_vs_ns.git")
            }

            dir("summaryTestResults") {
                testResultList.each {
                    dir("HybridPro") {
                        dir(it.replace("testResult-", "")) {
                            try {
                                unstash("${it}-HybridPro")
                            } catch (e) {
                                println("Can't unstash ${it}-HybridPro")
                                println(e.toString())
                            }
                        }
                    }

                    dir("Northstar64") {
                        dir(it.replace("testResult-", "")) {
                            try {
                                unstash("${it}-Northstar64")
                            } catch (e) {
                                println("Can't unstash ${it}-Northstar64")
                                println(e.toString())
                            }
                        }
                    }
                }
            }

            try {
                GithubNotificator.updateStatus("Deploy", "Building test report", "in_progress", options, NotificationConfiguration.BUILDING_REPORT, "${BUILD_URL}")
                dir ("jobs_launcher") {
                    withEnv(["JOB_STARTED_TIME=${options.JOB_STARTED_TIME}"]) {
                        bat """
                            build_comparison_reports.bat ..\\\\summaryTestResults
                        """
                    }
                }
            } catch (e) {
                String errorMessage = utils.getReportFailReason(e.getMessage())
                GithubNotificator.updateStatus("Deploy", "Building test report", "failure", options, errorMessage, "${BUILD_URL}")
                if (utils.isReportFailCritical(e.getMessage())) {
                    options.problemMessageManager.saveSpecificFailReason(errorMessage, "Deploy")
                    println """
                        [ERROR] Failed to build test report.
                        ${e.toString()}
                    """
                    if (!options.testDataSaved) {
                        try {
                            // Save test data for access it manually anyway
                            utils.publishReport(this, "${BUILD_URL}", "summaryTestResults", "summary_report.html, performance_report.html, compare_report.html", "Test Report", "Summary Report, Performance Report, Compare Report")
                            options.testDataSaved = true
                        } catch(e1) {
                            println """
                                [WARNING] Failed to publish test data.
                                ${e1.toString()}
                                ${e.toString()}
                            """
                        }
                    }
                    throw e
                } else {
                    currentBuild.result = "FAILURE"
                    options.problemMessageManager.saveGlobalFailReason(errorMessage)
                }
            }

            Map summaryTestResults = ["passed": 0, "failed": 0, "error": 0]
            try {
                def summaryReport = readJSON file: 'summaryTestResults/compared_configurations_info.json'

                summaryReport.each { configuration ->
                    summaryTestResults["passed"] += configuration.value["summary"]["passed"]
                    summaryTestResults["failed"] += configuration.value["summary"]["failed"]
                    summaryTestResults["error"] += configuration.value["summary"]["error"]
                }

                if (summaryTestResults["error"] > 0) {
                    println("[INFO] Some tests marked as error. Build result = FAILURE.")
                    currentBuild.result = "FAILURE"
                    options.problemMessageManager.saveGlobalFailReason(NotificationConfiguration.SOME_TESTS_ERRORED)
                }
                else if (summaryTestResults["failed"] > 0) {
                    println("[INFO] Some tests marked as failed. Build result = UNSTABLE.")
                    currentBuild.result = "UNSTABLE"
                    options.problemMessageManager.saveUnstableReason(NotificationConfiguration.SOME_TESTS_FAILED)
                }
            } catch(e) {
                println """
                    [ERROR] CAN'T GET TESTS STATUS
                    ${e.toString()}
                """
                options.problemMessageManager.saveUnstableReason(NotificationConfiguration.CAN_NOT_GET_TESTS_STATUS)
                currentBuild.result = "UNSTABLE"
            }

            withNotifications(title: "Building test report", options: options, configuration: NotificationConfiguration.PUBLISH_REPORT) {
                utils.publishReport(this, "${BUILD_URL}", "summaryTestResults", "summary_report.html, performance_report.html", \
                    "Test Report", "Summary Report, Performance Report")
                if (summaryTestResults) {
                    // add in description of status check information about tests statuses
                    // Example: Report was published successfully (passed: 69, failed: 11, error: 0)
                    GithubNotificator.updateStatus("Deploy", "Building test report", "success", options, "${NotificationConfiguration.REPORT_PUBLISHED} Results: passed - ${summaryTestResults.passed}, failed - ${summaryTestResults.failed}, error - ${summaryTestResults.error}.", "${BUILD_URL}/Test_20Report")
                } else {
                    GithubNotificator.updateStatus("Deploy", "Building test report", "success", options, NotificationConfiguration.REPORT_PUBLISHED, "${BUILD_URL}/Test_20Report")
                }
            }
            if (options.hybridPullUrl) {
                GithubNotificator githubNotificator = new GithubNotificator(this, options)
                githubNotificator.init(options)
                options["githubNotificator"] = githubNotificator
                GithubNotificator.sendPullRequestComment(options.hybridPullUrl, "Hybrid vs Northstar comparison report (MaterialX) - ${BUILD_URL}Test_20Report", options)
            }
        }
    } catch (e) {
        println(e.toString())
        throw e
    }
}


def call(String projectBranch = "",
    String testsBranch = "master",
    String platforms = "Windows:NVIDIA_RTX3080TI,AMD_RX6800XT",
    Boolean enableNotifications = true,
    String testsPackage = "",
    String tests = "",
    Boolean splitTestsExecution = true,
    String tester_tag = "Tester",
    String parallelExecutionTypeString = "TakeAllNodes"
    )
{

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

            options << [projectRepo:PROJECT_REPO,
                        projectBranch:projectBranch,
                        testsBranch:testsBranch,
                        enableNotifications:enableNotifications,
                        testsPackage:testsPackage,
                        tests:tests,
                        PRJ_NAME:"HybridVsNorthStar",
                        splitTestsExecution:splitTestsExecution,
                        gpusCount:gpusCount,
                        nodeRetry: nodeRetry,
                        platforms:platforms,
                        BUILD_TIMEOUT: 15,
                        TEST_TIMEOUT: 60,
                        DEPLOY_TIMEOUT: 15,
                        parallelExecutionType:parallelExecutionType,
                        parallelExecutionTypeString: parallelExecutionTypeString,
                        TESTER_TAG:tester_tag
                        ]
        }

        multiplatform_pipeline(platforms, this.&executePreBuild, this.&executeBuild, this.&executeTests, this.&executeDeploy, options)
    } catch(e) {
        currentBuild.result = "FAILURE"
        println(e.toString())
        println(e.getMessage())
        throw e
    } finally {
        problemMessageManager.publishMessages()
    }

}

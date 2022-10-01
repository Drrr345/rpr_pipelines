import groovy.transform.Field
import groovy.json.JsonOutput
import net.sf.json.JSON
import net.sf.json.JSONSerializer
import net.sf.json.JsonConfig
import TestsExecutionType
import java.util.concurrent.atomic.AtomicInteger


@Field final String PRODUCT_NAME = "AMD%20Radeon™%20ProRender%20Core"

@Field final PipelineConfiguration PIPELINE_CONFIGURATION = new PipelineConfiguration(
    supportedOS: ["Windows", "OSX", "Ubuntu18", "Ubuntu20"],
    productExtensions: ["Windows": "zip", "OSX": "zip", "Ubuntu18": "zip", "Ubuntu20": "zip"],
    artifactNameBase: "binCore",
    testProfile: "engine",
    displayingProfilesMapping: [
        "engine": [
            "Northstar64": "Northstar64",
            "HybridPro": "HybridPro",
            "Hybrid": "Hybrid",
            "HIP": "HIP",
            "HIPvsNS": "HIPvsNS"
        ]
    ]
)

@Field final RPR_SDK_REPO = "git@github.com:GPUOpen-LibrariesAndSDKs/RadeonProRenderSDK.git"

Boolean filter(Map options, String asicName, String osName, String engine) {
    if (osName == "OSX" && engine == "Hybrid") {
        return true
    }

    if (engine.contains("HIP") && !(asicName.contains("AMD") && osName == "Windows")) {
        return true
    }

    return (engine == "HybridPro" && !(asicName.contains("RTX") || asicName.contains("AMD_RX6800")))
}

def executeGenTestRefCommand(String osName, Map options, Boolean delete) 
{
    dir('scripts') {
        switch(osName) {
            case 'Windows':
                bat """
                    make_results_baseline.bat ${delete}
                """
                break
            case 'OSX':
                sh """
                    ./make_results_baseline.sh ${delete}
                """
                break
            default:
                sh """
                    ./make_results_baseline.sh ${delete}
                """
        }
    }
}

def executeTestCommand(String osName, String asicName, Map options) 
{
    switch(osName) {
        case 'Windows':
            dir('scripts') {
                bat """
                    ${options.engine.contains("HIP") ? "set TH_FORCE_HIP=1" : ""}
                    run.bat ${options.testsPackage} \"${options.tests}\" ${options.width} ${options.height} ${options.iterations} ${options.updateRefs} ${options.engine} >> \"../${STAGE_NAME}_${options.currentTry}.log\" 2>&1
                """
            }
            break
        case 'OSX':
            dir('scripts') {
                withEnv(["LD_LIBRARY_PATH=../rprSdk:\$LD_LIBRARY_PATH"]) {
                    sh """
                        ${options.engine.contains("HIP") ? "export TH_FORCE_HIP=1" : ""}
                        ./run.sh ${options.testsPackage} \"${options.tests}\" ${options.width} ${options.height} ${options.iterations} ${options.updateRefs} ${options.engine} >> \"../${STAGE_NAME}_${options.currentTry}.log\" 2>&1
                    """
                }
            }
            break
        default:
            dir('scripts') {
                withEnv(["LD_LIBRARY_PATH=../rprSdk:\$LD_LIBRARY_PATH"]) {
                    sh """
                        ${options.engine.contains("HIP") ? "export TH_FORCE_HIP=1" : ""}
                        ./run.sh ${options.testsPackage} \"${options.tests}\" ${options.width} ${options.height} ${options.iterations} ${options.updateRefs} ${options.engine} >> \"../${STAGE_NAME}_${options.currentTry}.log\" 2>&1
                    """
                }
            }
    }
}

def executeTests(String osName, String asicName, Map options)
{
    // used for mark stash results or not. It needed for not stashing failed tasks which will be retried.
    Boolean stashResults = true

    try {
        withNotifications(title: options["stageName"], options: options, logUrl: "${BUILD_URL}", configuration: NotificationConfiguration.DOWNLOAD_TESTS_REPO) {
            timeout(time: "5", unit: "MINUTES") {
                cleanWS(osName)
                checkoutScm(branchName: options.testsBranch, repositoryUrl: options.testRepo)
            }
        }

        withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.DOWNLOAD_PACKAGE) {
            getProduct(osName, options, "rprSdk")
        }

        withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.DOWNLOAD_SCENES) {
            String assets_dir = isUnix() ? "${CIS_TOOLS}/../TestResources/rpr_core_autotests_assets" : "/mnt/c/TestResources/rpr_core_autotests_assets"
            downloadFiles("/volume1/Assets/rpr_core_autotests/", assets_dir)
        }

        String enginePostfix = options.engine == "HIPvsNS" ? "Northstar64" : options.engine
        String REF_PATH_PROFILE="/volume1/Baselines/rpr_core_autotests/${asicName}-${osName}"

        REF_PATH_PROFILE = enginePostfix ? "${REF_PATH_PROFILE}-${enginePostfix}" : REF_PATH_PROFILE
        options.REF_PATH_PROFILE = REF_PATH_PROFILE

        outputEnvironmentInfo(osName, "", options.currentTry)

        if (options["updateRefs"].contains("Update")) {
            withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.EXECUTE_TESTS) {
                executeTestCommand(osName, asicName, options)
                executeGenTestRefCommand(osName, options, options["updateRefs"].contains("clean"))
                uploadFiles("./Work/GeneratedBaselines/", REF_PATH_PROFILE)
                // delete generated baselines when they're sent 
                switch(osName) {
                    case "Windows":
                        bat "if exist Work\\GeneratedBaselines rmdir /Q /S Work\\GeneratedBaselines"
                        break
                    default:
                        sh "rm -rf ./Work/GeneratedBaselines"        
                }
            }
        } else {
            withNotifications(title: options["stageName"], printMessage: true, options: options, configuration: NotificationConfiguration.COPY_BASELINES) {
                String baseline_dir = isUnix() ? "${CIS_TOOLS}/../TestResources/rpr_core_autotests_baselines" : "/mnt/c/TestResources/rpr_core_autotests_baselines"
                baseline_dir = enginePostfix ? "${baseline_dir}-${enginePostfix}" : baseline_dir
                println "[INFO] Downloading reference images for ${options.tests}-${options.engine}"

                options.tests.split(" ").each() {
                    downloadFiles("${REF_PATH_PROFILE}/${it}", baseline_dir)
                }
            }
            withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.EXECUTE_TESTS) {
                executeTestCommand(osName, asicName, options)
            }
        }
        options.executeTestsFinished = true

        utils.compareDriverVersion(this, "${options.stageName}_${options.currentTry}.log", osName)

        if (options["errorsInSuccession"]["${osName}-${asicName}-${options.engine}"] != -1) {
            // mark that one group was finished and counting of errored groups in succession must be stopped
            options["errorsInSuccession"]["${osName}-${asicName}-${options.engine}"] = new AtomicInteger(-1)
        }

    } catch (e) {
        String additionalDescription = ""
        if (options.currentTry + 1 < options.nodeReallocateTries) {
            stashResults = false
        } else {
            if (!options["errorsInSuccession"]["${osName}-${asicName}-${options.engine}"]) {
                options["errorsInSuccession"]["${osName}-${asicName}-${options.engine}"] = new AtomicInteger(0)
            }
            Integer errorsInSuccession = options["errorsInSuccession"]["${osName}-${asicName}-${options.engine}"]
            // if counting of errored groups in succession must isn't stopped
            if (errorsInSuccession >= 0) {
                errorsInSuccession = options["errorsInSuccession"]["${osName}-${asicName}-${options.engine}"].addAndGet(1)
            
                if (errorsInSuccession >= 3) {
                    additionalDescription = "Number of errored groups in succession exceeded (max - 3). Next groups for this platform will be aborted"
                }
            }
        }
        println(e.toString())
        println(e.getMessage())
        
        if (e instanceof ExpectedExceptionWrapper) {
            GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, "${e.getMessage()} ${additionalDescription}", "${BUILD_URL}")
            throw new ExpectedExceptionWrapper("${e.getMessage()}\n${additionalDescription}", e.getCause())
        } else {
            GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, "${NotificationConfiguration.REASON_IS_NOT_IDENTIFIED} ${additionalDescription}", "${BUILD_URL}")
            throw new ExpectedExceptionWrapper("${NotificationConfiguration.REASON_IS_NOT_IDENTIFIED}\n${additionalDescription}", e)
        }
    } finally {
        try {
            dir("${options.stageName}") {
                utils.moveFiles(this, osName, "../*.log", ".")
                utils.moveFiles(this, osName, "../scripts/*.log", ".")
                utils.renameFile(this, osName, "launcher.engine.log", "${options.stageName}_engine_${options.currentTry}.log")
            }
            archiveArtifacts artifacts: "${options.stageName}/*.log", allowEmptyArchive: true
            if (stashResults) {
                dir('Work') {
                    if (fileExists("Results/Core/session_report.json")) {

                        def sessionReport = null
                        sessionReport = readJSON file: 'Results/Core/session_report.json'

                        if (sessionReport.summary.error > 0) {
                            GithubNotificator.updateStatus("Test", options['stageName'], "action_required", options, NotificationConfiguration.SOME_TESTS_ERRORED, "${BUILD_URL}")
                        } else if (sessionReport.summary.failed > 0) {
                            GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, NotificationConfiguration.SOME_TESTS_FAILED, "${BUILD_URL}")
                        } else {
                            GithubNotificator.updateStatus("Test", options['stageName'], "success", options, NotificationConfiguration.ALL_TESTS_PASSED, "${BUILD_URL}")
                        }

                        println "Stashing test results to : ${options.testResultsName}"
                        
                        utils.stashTestData(this, options, options.storeOnNAS, "**/cache/**")
                        // reallocate node if there are still attempts
                        // if test group is fully errored or number of test cases is equal to zero
                        if (sessionReport.summary.total == sessionReport.summary.error + sessionReport.summary.skipped || sessionReport.summary.total == 0) {
                            // check that group isn't fully skipped
                            if (sessionReport.summary.total != sessionReport.summary.skipped || sessionReport.summary.total == 0){
                                removeInstaller(osName: osName, options: options, extension: "zip")
                                String errorMessage
                                if (options.currentTry < options.nodeReallocateTries) {
                                    errorMessage = "All tests were marked as error. The test group will be restarted."
                                } else {
                                    errorMessage = "All tests were marked as error."
                                }
                                throw new ExpectedExceptionWrapper(errorMessage, new Exception(errorMessage))
                            }
                        }

                        if (options.reportUpdater) {
                            options.reportUpdater.updateReport(options.engine)
                        }
                    }
                }
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
    String artifactURL

    withNotifications(title: "Windows", options: options, logUrl: "${BUILD_URL}/artifact/Build-Windows.log",
        configuration: NotificationConfiguration.BUILD_PACKAGE) {
        String ARTIFACT_NAME = "binCoreWin64.zip"

        dir("RadeonProRenderSDK/RadeonProRender/binWin64") {
            bat(script: '%CIS_TOOLS%\\7-Zip\\7z.exe a' + " \"${ARTIFACT_NAME}\" .")

            artifactURL = makeArchiveArtifacts(name: ARTIFACT_NAME, storeOnNAS: options.storeOnNAS)

            makeStash(includes: ARTIFACT_NAME, name: getProduct.getStashName("Windows", options), preZip: false, storeOnNAS: options.storeOnNAS)
        }
    }

    if (env.BRANCH_NAME == "master") {
        withNotifications(title: "Windows", options: options, configuration: NotificationConfiguration.UPDATE_BINARIES) {

            hybrid_vs_northstar.updateBinaries(
                newBinaryFile: "RadeonProRenderSDK\\RadeonProRender\\binWin64\\Northstar64.dll", 
                targetFileName: "Northstar64.dll", osName: "Windows", compareChecksum: true
            )

        }
    }

    GithubNotificator.updateStatus("Build", "Windows", "success", options, NotificationConfiguration.BUILD_SOURCE_CODE_END_MESSAGE, artifactURL)
}

def executeBuildOSX(Map options) {
    String artifactURL

    withNotifications(title: "OSX", options: options, logUrl: "${BUILD_URL}/artifact/Build-OSX.log",
        configuration: NotificationConfiguration.BUILD_PACKAGE) {
        String ARTIFACT_NAME = "binCoreMacOS.zip"

        dir("RadeonProRenderSDK/RadeonProRender/binMacOS") {
            sh(script: 'zip -r' + " \"${ARTIFACT_NAME}\" .")

            artifactURL = makeArchiveArtifacts(name: ARTIFACT_NAME, storeOnNAS: options.storeOnNAS)

            makeStash(includes: ARTIFACT_NAME, name: getProduct.getStashName("OSX", options), preZip: false, storeOnNAS: options.storeOnNAS)
        }
    }

    GithubNotificator.updateStatus("Build", "OSX", "success", options, NotificationConfiguration.BUILD_SOURCE_CODE_END_MESSAGE, artifactURL)
}

def executeBuildLinux(String osName, Map options) {
    String artifactURL

    withNotifications(title: "${osName}", options: options, logUrl: "${BUILD_URL}/artifact/Build-${osName}.log",
        artifactUrl: "${BUILD_URL}/artifact/binCore${osName}.zip", configuration: NotificationConfiguration.BUILD_PACKAGE) {
        // no artifacts in repo for ubuntu20
        String ARTIFACT_NAME = "binCore${osName}.zip"

        dir("RadeonProRenderSDK/RadeonProRender/binUbuntu18") {
            sh(script: 'zip -r' + " \"${ARTIFACT_NAME}\" .")

            artifactURL = makeArchiveArtifacts(name: ARTIFACT_NAME, storeOnNAS: options.storeOnNAS)

            makeStash(includes: ARTIFACT_NAME, name: getProduct.getStashName(osName, options), preZip: false, storeOnNAS: options.storeOnNAS)
        }
    }

    GithubNotificator.updateStatus("Build", "${osName}", "success", options, NotificationConfiguration.BUILD_SOURCE_CODE_END_MESSAGE, artifactURL)
}

def executeBuild(String osName, Map options)
{
    try {
        dir('RadeonProRenderSDK') {
            withNotifications(title: osName, options: options, configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
                checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo, prBranchName: options.prBranchName, prRepoName: options.prRepoName)
            }
        }

        withNotifications(title: osName, options: options, configuration: NotificationConfiguration.BUILD_PACKAGE) {
            switch(osName) {
                case "Windows":
                    executeBuildWindows(options)
                    break
                case "OSX":
                    executeBuildOSX(options)
                    break
                default:
                    executeBuildLinux(osName, options)
            }
        }

        options[getProduct.getIdentificatorKey(osName, options)] = options.commitSHA
    } catch (e) {
        throw e
    } finally {
        archiveArtifacts artifacts: "*.log", allowEmptyArchive: true
    }
}

def getReportBuildArgs(String engineName, Map options) {
    String buildNumber = options.collectTrackedMetrics ? env.BUILD_NUMBER : ""

    if (options["isPreBuilt"]) {
        return """Core "PreBuilt" "PreBuilt" "PreBuilt" \"${utils.escapeCharsByUnicode(engineName)}\" \"${buildNumber}\""""
    } else {
        return """Core ${options.commitSHA} ${options.projectBranchName} \"${utils.escapeCharsByUnicode(options.commitMessage)}\" \"${utils.escapeCharsByUnicode(engineName)}\" \"${buildNumber}\""""
    }
}

def executePreBuild(Map options) {
    if (options['isPreBuilt']) {
        println "[INFO] Build was detected as prebuilt. Build stage will be skipped"
        currentBuild.description = "<b>Project branch:</b> Prebuilt plugin<br/>"
        options['executeBuild'] = false
        options['executeTests'] = true
    } else if (env.CHANGE_URL) {
        println "Branch was detected as Pull Request"
    } else if (env.BRANCH_NAME == "master") {
        println("[INFO] ${env.BRANCH_NAME} branch was detected")
        options.collectTrackedMetrics = true
    }

    if (!options['isPreBuilt']) {
        dir('RadeonProRenderSDK') {
            withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
                checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo, prBranchName: options.prBranchName, prRepoName: options.prRepoName, disableSubmodules: true)
            }

            options.commitAuthor = bat (script: "git show -s --format=%%an HEAD ",returnStdout: true).split('\r\n')[2].trim()
            options.commitMessage = bat (script: "git log --format=%%s -n 1", returnStdout: true).split('\r\n')[2].trim().replace('\n', '')
            options.commitSHA = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()

            if (options.projectBranch != "") {
                options.branchName = options.projectBranch
            } else {
                options.branchName = env.BRANCH_NAME
            }
            if (options.incrementVersion) {
                options.branchName = "master"
            }

            println("The last commit was written by ${options.commitAuthor}.")
            println("Commit message: ${options.commitMessage}")
            println("Commit SHA: ${options.commitSHA}")
            println "Branch name: ${options.branchName}"

            if (env.BRANCH_NAME) {
                withNotifications(title: "Jenkins build configuration", printMessage: true, options: options, configuration: NotificationConfiguration.CREATE_GITHUB_NOTIFICATOR) {
                    GithubNotificator githubNotificator = new GithubNotificator(this, options)
                    githubNotificator.init(options)
                    options["githubNotificator"] = githubNotificator
                    githubNotificator.initPreBuild("${BUILD_URL}")
                    options.projectBranchName = githubNotificator.branchName
                }
            } else {
                options.projectBranchName = options.projectBranch
            }

            currentBuild.description = "<b>Project branch:</b> ${options.projectBranchName}<br/>"
            currentBuild.description += "<b>Commit author:</b> ${options.commitAuthor}<br/>"
            currentBuild.description += "<b>Commit message:</b> ${options.commitMessage}<br/>"
            currentBuild.description += "<b>Commit SHA:</b> ${options.commitSHA}<br/>"
        }
    }

    withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.CONFIGURE_TESTS) {
        dir('jobs_test_core') {
            checkoutScm(branchName: options.testsBranch, repositoryUrl: options.testRepo)
            dir ('jobs_launcher') {
                options['jobsLauncherBranch'] = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
            }
            options['testsBranch'] = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
            println("[INFO] Test branch hash: ${options['testsBranch']}")

            if (options.testsPackage != "none") {
                def tests = []
                // json means custom test suite. Split doesn't supported
                def tempTests = readJSON file: "jobs/${options.testsPackage}"
                tempTests["groups"].each() {
                    // TODO: fix: duck tape - error with line ending
                    tests << it.key
                }

                options.testsPackage = "none"
                options.tests = tests.join(" ")          
            }

            options.testsList = []

            options.engines.each(){ engine ->
                options.testsList << "${engine}"
            }
        }
    }

    // make lists of raw profiles and lists of beautified profiles (displaying profiles)
    multiplatform_pipeline.initProfiles(options)

    if (options.flexibleUpdates && multiplatform_pipeline.shouldExecuteDelpoyStage(options)) {
        options.reportUpdater = new ReportUpdater(this, env, options)
        options.reportUpdater.init(this.&getReportBuildArgs)
    }

    if (env.BRANCH_NAME && options.githubNotificator) {
        options.githubNotificator.initChecks(options, "${BUILD_URL}")
    }
}


def executeDeploy(Map options, List platformList, List testResultList, String engine)
{
    cleanWS()
    try {
        if (options['executeTests'] && testResultList) {
            String engineName = options.displayingTestProfiles[engine]

            withNotifications(title: "Building test report for ${engineName} engine", options: options, startUrl: "${BUILD_URL}", configuration: NotificationConfiguration.DOWNLOAD_TESTS_REPO) {
                checkoutScm(branchName: options.testsBranch, repositoryUrl: options.testRepo)
            }

            List lostStashes = []

            dir("summaryTestResults") {
                testResultList.each() {
                    if (it.endsWith(engine)) {
                        List testNameParts = it.replace("testResult-", "").split("-") as List
                        String testName = testNameParts.subList(0, testNameParts.size() - 1).join("-")

                        if (filter(options, testNameParts.get(0), testNameParts.get(1), engine)) {
                            return
                        }

                        dir(testName) {
                            try {
                                makeUnstash(name: "$it", storeOnNAS: options.storeOnNAS)
                            } catch(e) {
                                echo "[ERROR] Failed to unstash ${testName}"
                                lostStashes.add("'${testName}'".replace("testResult-", ""))
                                println(e.toString())
                                println(e.getMessage())
                            }
                        }
                    }
                }
            }

            try {
                withCredentials([string(credentialsId: 'buildsRemoteHost', variable: 'REMOTE_HOST')]) {
                    dir("core_tests_configuration") {
                        downloadFiles("/volume1/Assets/rpr_core_autotests/", ".", "--include='*.json' --include='*/' --exclude='*'")
                    }
                }
            } catch (e) {
                println("[ERROR] Can't download json files with core tests configuration")
            }

            try {
                dir("jobs_launcher") {
                    bat """
                        count_lost_tests.bat \"${lostStashes}\" .. ..\\summaryTestResults \"false\" \"${options.testsPackage}\" \"${options.tests.toString()}\" \"${engine}\" \"{}\"
                    """
                }
            } catch (e) {
                println("[ERROR] Can't generate number of lost tests")
            }

            try {
                String metricsRemoteDir = "/volume1/Baselines/TrackedMetrics/${env.JOB_NAME}/${engine}"
                GithubNotificator.updateStatus("Deploy", "Building test report for ${engineName} engine", "in_progress", options, NotificationConfiguration.BUILDING_REPORT, "${BUILD_URL}")

                if (options.collectTrackedMetrics) {
                    utils.downloadMetrics(this, "summaryTestResults/tracked_metrics", "${metricsRemoteDir}/")
                }

                withEnv(["JOB_STARTED_TIME=${options.JOB_STARTED_TIME}", "BUILD_NAME=${options.baseBuildName}"]) {
                    dir("jobs_launcher") {
                        def retryInfo = JsonOutput.toJson(options.nodeRetry)
                        dir("..\\summaryTestResults") {
                            JSON jsonResponse = JSONSerializer.toJSON(retryInfo, new JsonConfig())
                            writeJSON file: 'retry_info.json', json: jsonResponse, pretty: 4
                        }
                    
                        bat "build_reports.bat ..\\summaryTestResults ${getReportBuildArgs(engineName, options)}"

                        bat "get_status.bat ..\\summaryTestResults"
                    }
                }

                if (options.collectTrackedMetrics) {
                    utils.uploadMetrics(this, "summaryTestResults/tracked_metrics", metricsRemoteDir)
                }  
            } catch(e) {
                String errorMessage = utils.getReportFailReason(e.getMessage())
                GithubNotificator.updateStatus("Deploy", "Building test report ${engineName}", "failure", options, errorMessage, "${BUILD_URL}")
                if (utils.isReportFailCritical(e.getMessage())) {
                    options.problemMessageManager.saveSpecificFailReason(errorMessage, "Deploy")
                    println("[ERROR] Failed to build test report.")
                    println(e.toString())
                    println(e.getMessage())

                    if (!options.testDataSaved && !options.storeOnNAS) {
                        try {
                            // Save test data for access it manually anyway
                            utils.publishReport(this, "${BUILD_URL}", "summaryTestResults", "summary_report.html, performance_report.html, compare_report.html", \
                                "Test Report ${engineName}", "Summary Report, Performance Report, Compare Report", options.storeOnNAS, \
                                ["jenkinsBuildUrl": BUILD_URL, "jenkinsBuildName": currentBuild.displayName, "updatable": options.containsKey("reportUpdater")])

                            options.testDataSaved = true 
                        } catch(e1) {
                            println("[WARNING] Failed to publish test data.")
                            println(e.toString())
                            println(e.getMessage())
                        }
                    }
                    throw e
                } else {
                    currentBuild.result = "FAILURE"
                    options.problemMessageManager.saveGlobalFailReason(errorMessage)
                }
            }

            try {
                dir("jobs_launcher") {
                    archiveArtifacts "launcher.engine.log"
                }
            } catch(e) {
                println("[ERROR] during archiving launcher.engine.log")
                println(e.toString())
                println(e.getMessage())
            }

            Map summaryTestResults = [:]
            try {
                def summaryReport = readJSON file: 'summaryTestResults/summary_status.json'
                summaryTestResults['passed'] = summaryReport.passed
                summaryTestResults['failed'] = summaryReport.failed
                summaryTestResults['error'] = summaryReport.error
                if (summaryReport.error > 0) {
                    println("[INFO] Some tests marked as error. Build result = FAILURE.")
                    currentBuild.result = "FAILURE"

                    options.problemMessageManager.saveGlobalFailReason(NotificationConfiguration.SOME_TESTS_ERRORED)
                } else if (summaryReport.failed > 0) {
                    println("[INFO] Some tests marked as failed. Build result = UNSTABLE.")
                    currentBuild.result = "UNSTABLE"

                    options.problemMessageManager.saveUnstableReason(NotificationConfiguration.SOME_TESTS_FAILED)
                }
            } catch(e) {
                println(e.toString())
                println(e.getMessage())
                println("[ERROR] CAN'T GET TESTS STATUS")
                options.problemMessageManager.saveUnstableReason(NotificationConfiguration.CAN_NOT_GET_TESTS_STATUS)
                currentBuild.result = "UNSTABLE"
            }

            try {
                options['testsStatus-' + engineName] = readFile("summaryTestResults/slack_status.json")
            } catch(e) {
                println(e.toString())
                println(e.getMessage())
                options['testsStatus-' + engineName] = ""
            }

            withNotifications(title: "Building test report", options: options, configuration: NotificationConfiguration.PUBLISH_REPORT) {
                utils.publishReport(this, "${BUILD_URL}", "summaryTestResults", "summary_report.html, performance_report.html, compare_report.html", \
                    "Test Report ${engineName}", "Summary Report, Performance Report, Compare Report", options.storeOnNAS, \
                    ["jenkinsBuildUrl": BUILD_URL, "jenkinsBuildName": currentBuild.displayName, "updatable": options.containsKey("reportUpdater")])

                if (summaryTestResults) {
                    // add in description of status check information about tests statuses
                    // Example: Report was published successfully (passed: 69, failed: 11, error: 0)
                    GithubNotificator.updateStatus("Deploy", "Building test report for ${engineName} engine", "success", options, "${NotificationConfiguration.REPORT_PUBLISHED} Results: passed - ${summaryTestResults.passed}, failed - ${summaryTestResults.failed}, error - ${summaryTestResults.error}.", "${BUILD_URL}/Test_20Report")
                } else {
                    GithubNotificator.updateStatus("Deploy", "Building test report for ${engineName} engine", "success", options, NotificationConfiguration.REPORT_PUBLISHED, "${BUILD_URL}/Test_20Report")
                }
            }
        }
    } catch (e) {
        println(e.toString())
        println(e.getMessage())
        throw e
    } finally {}
}

def appendPlatform(String filteredPlatforms, String platform) {
    if (filteredPlatforms) {
        filteredPlatforms +=  ";" + platform
    } else {
        filteredPlatforms += platform
    }
    return filteredPlatforms
}


def call(String projectBranch = "",
         String testsBranch = "master",
         String platforms = 'Windows:AMD_RadeonVII,NVIDIA_RTX3080TI,AMD_RX6800XT;OSX:AMD_RX5700XT,AppleM1;Ubuntu20',
         String updateRefs = 'No',
         Boolean enableNotifications = true,
         String renderDevice = "gpu",
         String testsPackage = "Full.json",
         String tests = "",
         String width = "0",
         String height = "0",
         String iterations = "0",
         String customBuildLinkWindows = "",
         String customBuildLinkUbuntu18 = "",
         String customBuildLinkUbuntu20 = "",
         String customBuildLinkOSX = "",
         String tester_tag = 'Tester',
         String mergeablePR = "",
         String parallelExecutionTypeString = "TakeOneNodePerGPU",
         String enginesNames = "Northstar64,HybridPro,Hybrid",
         Boolean collectTrackedMetrics = false)
{
    ProblemMessageManager problemMessageManager = new ProblemMessageManager(this, currentBuild)
    Map options = [:]
    options["stage"] = "Init"
    options["problemMessageManager"] = problemMessageManager
    options["projectRepo"] = "git@github.com:GPUOpen-LibrariesAndSDKs/RadeonProRenderSDK.git"
    
    def nodeRetry = []
    Map errorsInSuccession = [:]

    try {
        withNotifications(options: options, configuration: NotificationConfiguration.INITIALIZATION) {
            withNotifications(options: options, configuration: NotificationConfiguration.ENGINES_PARAM) {
                if (!enginesNames) {
                    throw new Exception()
                }
            }

            enginesNames = enginesNames.split(',') as List

            Boolean isPreBuilt = customBuildLinkWindows || customBuildLinkOSX || customBuildLinkUbuntu18 || customBuildLinkUbuntu20

            if (isPreBuilt) {
                //remove platforms for which pre built plugin is not specified
                String filteredPlatforms = ""

                platforms.split(';').each() { platform ->
                    List tokens = platform.tokenize(':')
                    String platformName = tokens.get(0)

                    switch(platformName) {
                        case 'Windows':
                            if (customBuildLinkWindows) {
                                filteredPlatforms = appendPlatform(filteredPlatforms, platform)
                            }
                            break
                        case 'OSX':
                            if (customBuildLinkOSX) {
                                filteredPlatforms = appendPlatform(filteredPlatforms, platform)
                            }
                            break
                        case 'Ubuntu18':
                            if (customBuildLinkUbuntu18) {
                                filteredPlatforms = appendPlatform(filteredPlatforms, platform)
                            }
                        // Ubuntu20
                        default:
                            if (customBuildLinkUbuntu20) {
                                filteredPlatforms = appendPlatform(filteredPlatforms, platform)
                            }
                        }
                }

                platforms = filteredPlatforms
            }

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
            println "Tests execution type: ${parallelExecutionType}"

            String prRepoName = ""
            String prBranchName = ""
            if (mergeablePR) {
                String[] prInfo = mergeablePR.split(";")
                prRepoName = prInfo[0]
                prBranchName = prInfo[1]
            }

            options << [configuration: PIPELINE_CONFIGURATION,
                        projectBranch:projectBranch,
                        testRepo:"git@github.com:luxteam/jobs_test_core.git",
                        testsBranch:testsBranch,
                        updateRefs:updateRefs,
                        enableNotifications:enableNotifications,
                        PRJ_NAME:"RadeonProRenderCore",
                        PRJ_ROOT:"rpr-core",
                        TESTER_TAG:tester_tag,
                        slackChannel:"cis_core",
                        renderDevice:renderDevice,
                        testsPackage:testsPackage,
                        tests:tests.replace(',', ' '),
                        executeBuild:true,
                        executeTests:true,
                        reportName:'Test_20Report',
                        engines:enginesNames,
                        TEST_TIMEOUT:180,
                        DEPLOY_TIMEOUT:30,
                        width:width,
                        gpusCount:gpusCount,
                        height:height,
                        iterations:iterations,
                        nodeRetry: nodeRetry,
                        errorsInSuccession: errorsInSuccession,
                        problemMessageManager: problemMessageManager,
                        platforms:platforms,
                        prRepoName:prRepoName,
                        prBranchName:prBranchName,
                        parallelExecutionType:parallelExecutionType,
                        parallelExecutionTypeString: parallelExecutionTypeString,
                        collectTrackedMetrics:collectTrackedMetrics,
                        isPreBuilt:isPreBuilt,
                        customBuildLinkWindows: customBuildLinkWindows,
                        customBuildLinkUbuntu18: customBuildLinkUbuntu18,
                        customBuildLinkUbuntu20: customBuildLinkUbuntu20,
                        customBuildLinkOSX: customBuildLinkOSX,
                        splitTestsExecution: false,
                        storeOnNAS: true,
                        flexibleUpdates: true,
                        skipCallback: this.&filter
                        ]
        }

        multiplatform_pipeline(platforms, this.&executePreBuild, this.&executeBuild, this.&executeTests, this.&executeDeploy, options)
    } catch(e) {
        currentBuild.result = "FAILURE"
        println(e.toString());
        println(e.getMessage());
        throw e
    } finally {
        String problemMessage = options.problemMessageManager.publishMessages()
    }
}

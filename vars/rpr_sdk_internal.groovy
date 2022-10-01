import groovy.transform.Field
import groovy.json.JsonOutput
import net.sf.json.JSON
import net.sf.json.JSONSerializer
import net.sf.json.JsonConfig
import TestsExecutionType


@Field final String PRODUCT_NAME = "AMD%20Radeon™%20ProRender%20Core"

@Field final PipelineConfiguration PIPELINE_CONFIGURATION = new PipelineConfiguration(
    supportedOS: ["Windows"],
    productExtensions: ["Windows": "zip"],
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

def executeUnitTestCommand(String osName)
{
    switch(osName) {
        case 'Windows':
            dir("RadeonProRenderSDK/Northstar/UnitTest") {
                bat """
                    ..\\dist\\release\\bin\\x86_64\\UnitTest64.exe -referencePath ..\\..\\..\\frUnittestdata\\northstar\\gpu\\ --gtest_filter=*NEXT_* --gtest_output=xml:..\\..\\..\\${STAGE_NAME}.gtest.xml >> ..\\..\\..\\${STAGE_NAME}.log  2>&1
                """
            }
            break
        default:
            // Tests for Ubuntu & OSX aren't supported now
            println("Unsupported OS")
    }
}

def executeUnitTests(String osName, String asicName, Map options)
{
    dir("RadeonProRenderSDK") {
        withNotifications(title: options["stageName"], options: options, logUrl: "${BUILD_URL}", configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
            timeout(time: "20", unit: "MINUTES") {
                cleanWS(osName)
                checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo, prBranchName: options.prBranchName, prRepoName: options.prRepoName)
            }
        }
    }

    dir("frUnittestdata") {
        withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.DOWNLOAD_UNIT_TESTS_REPO) {
            timeout(time: "40", unit: "MINUTES") {
                checkoutScm(branchName: options.unitTestsBranch, repositoryUrl: "git@github.com:amdadvtech/frUnittestdata.git")
            }
        }
    }

    try {
        GithubNotificator.updateStatus("Test", options['stageName'], "in_progress", options, NotificationConfiguration.EXECUTE_UNIT_TESTS, "${BUILD_URL}")
        executeUnitTestCommand(osName)
    } catch (e) {
        dir("HTML_Report") {
            checkoutScm(branchName: "master", repositoryUrl: "git@github.com:luxteam/HTMLReportsShared")
            python3("-m pip install --user -r requirements.txt")
            python3("hybrid_report.py --xml_path ../${STAGE_NAME}.gtest.xml --images_basedir ../RadeonProRenderSDK/Northstar/UnitTest/result  --report_path ../${asicName}-${osName}_failures --tool_name \"Core Internal\" --compare_with_refs=False")
        }

        if (!options.storeOnNAS) {
            makeStash(includes: "${asicName}-${osName}_failures/**/*", name: "unitTestFailures-${asicName}-${osName}", allowEmpty: true, storeOnNAS: options.storeOnNAS)
        }

        utils.publishReport(this, "${BUILD_URL}", "${asicName}-${osName}_failures", "report.html", "${STAGE_NAME}_failures", "${STAGE_NAME}_failures", options.storeOnNAS, ["jenkinsBuildUrl": BUILD_URL, "jenkinsBuildName": currentBuild.displayName])

        options["failedConfigurations"].add("unitTestFailures-" + asicName + "-" + osName)
    } finally {
        archiveArtifacts "*.log, *.gtest.xml"
        junit "*.gtest.xml"
    }
}

def executeTestCommand(String osName, String asicName, Map options)
{
    switch(osName) {
        case 'Windows':
            dir('scripts') {
                bat """
                    run.bat ${options.testsPackage} \"${options.tests}\" ${options.width} ${options.height} ${options.iterations} ${options.updateRefs} ${options.enginesNames} >> \"../${STAGE_NAME}_${options.currentTry}.log\" 2>&1
                """
            }
            break
        case 'OSX':
            dir('scripts') {
                withEnv(["LD_LIBRARY_PATH=../rprSdk:\$LD_LIBRARY_PATH"]) {
                    sh """
                        ./run.sh ${options.testsPackage} \"${options.tests}\" ${options.width} ${options.height} ${options.iterations} ${options.updateRefs} ${options.enginesNames} >> \"../${STAGE_NAME}_${options.currentTry}.log\" 2>&1
                    """
                }
            }
            break
        default:
            dir('scripts') {
                withEnv(["LD_LIBRARY_PATH=../rprSdk:\$LD_LIBRARY_PATH"]) {
                    sh """
                        ./run.sh ${options.testsPackage} \"${options.tests}\" ${options.width} ${options.height} ${options.iterations} ${options.updateRefs} ${options.enginesNames} >> \"../${STAGE_NAME}_${options.currentTry}.log\" 2>&1
                    """
                }
            }
    }
}

def executeTests(String osName, String asicName, Map options)
{
    try {
        executeUnitTests(osName, asicName, options)
    } catch (e) {
        println "[ERROR] Failed to execute unit tests on ${asicName}-${osName}"
        println "Exception: ${e.toString()}"
        println "Exception message: ${e.getMessage()}"
        println "Exception cause: ${e.getCause()}"
        println "Exception stack trace: ${e.getStackTrace()}"
    }

    // check that current platform is in list of platforms for which render should be executed
    if (!(options.renderPlatforms.contains(osName) && options.renderPlatforms.contains(asicName))) {
        return
    }

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
            String assetsDir = isUnix() ? "${CIS_TOOLS}/../TestResources/rpr_core_autotests_assets" : "/mnt/c/TestResources/rpr_core_autotests_assets"
            downloadFiles("/volume1/web/Assets/rpr_core_autotests/", assetsDir)
        }

        String REF_PATH_PROFILE="/volume1/Baselines/rpr_core_autotests/${asicName}-${osName}"
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

    } catch (e) {
        if (options.currentTry < options.nodeReallocateTries) {
            stashResults = false
        }
        println(e.toString())
        println(e.getMessage())
        if (e instanceof ExpectedExceptionWrapper) {
            GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, e.getMessage(), "${BUILD_URL}")
            throw e
        } else {
            String errorMessage = "The reason is not automatically identified. Please contact support."
            GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, errorMessage, "${BUILD_URL}")
            throw new ExpectedExceptionWrapper(errorMessage, e)
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

                        println("Stashing test results to : ${options.testResultsName}")

                        utils.stashTestData(this, options, options.storeOnNAS, "**/cache/**")

                        // reallocate node if there are still attempts
                        if (sessionReport.summary.total == sessionReport.summary.error + sessionReport.summary.skipped || sessionReport.summary.total == 0) {
                            if (sessionReport.summary.total != sessionReport.summary.skipped){
                                // remove brocken core package
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
    withNotifications(title: "Windows", options: options, logUrl: "${BUILD_URL}/artifact/Build-Windows.log",
        artifactUrl: "${BUILD_URL}/artifact/binCoreWin64.zip", configuration: NotificationConfiguration.BUILD_PACKAGE) {
        dir("RadeonProRenderSDK/RPR/RadeonProRender/lib/x64") {
            zip archive: true, dir: ".", glob: "", zipFile: "binCoreWin64.zip"
            makeStash(includes: "binCoreWin64.zip", name: getProduct.getStashName("Windows", options), preZip: false, storeOnNAS: options.storeOnNAS)
        }
    }
}

def executeBuild(String osName, Map options)
{
    try {
        dir("RadeonProRenderSDK") {
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
                    throw new Exception("Not supported")
                    break
                default:
                    throw new Exception("Not supported")
            }
        }

        options[getProduct.getIdentificatorKey(osName, options)] = options.commitSHA
    } catch (e) {
        throw e
    } finally {
        archiveArtifacts artifacts: "*.log", allowEmptyArchive: true
    }
}

def getReportBuildArgs(Map options) {
    String buildNumber = options.collectTrackedMetrics ? env.BUILD_NUMBER : ""

    return """Core ${options.commitSHA} ${options.projectBranchName} \"${utils.escapeCharsByUnicode(options.commitMessage)}\" \"\" \"${buildNumber}\""""
}

def executePreBuild(Map options)
{
    if (env.CHANGE_URL) {
        println "Branch was detected as Pull Request"
    }

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

        println "The last commit was written by ${options.commitAuthor}."
        println "Commit message: ${options.commitMessage}"
        println "Commit SHA: ${options.commitSHA}"
        println "Commit shortSHA: ${options.commitShortSHA}"
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

    withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.CONFIGURE_TESTS) {
        dir('jobs_test_core') {
            checkoutScm(branchName: options.testsBranch, repositoryUrl: options.testRepo)
            dir ('jobs_launcher') {
                options['jobsLauncherBranch'] = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
            }
            options['testsBranch'] = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
            println "[INFO] Test branch hash: ${options['testsBranch']}"

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

                        if (filter(options, testNameParts.get(0), testNameParts.get(1), testNameParts.get(2), engine)) {
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
                String metricsRemoteDir = "/volume1/Baselines/TrackedMetrics/${env.JOB_NAME}/Northstar64"
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


def call(String projectBranch = "",
         String testsBranch = "master",
         String unitTestsBranch = "master",
         String platforms = 'Windows:AMD_RadeonVII,NVIDIA_RTX3080TI,AMD_RX6800XT',
         String renderPlatforms = 'Windows:AMD_RX6800XT,NVIDIA_RTX3080TI',
         String updateRefs = 'No',
         Boolean enableNotifications = false,
         String renderDevice = "gpu",
         String testsPackage = "Full-Internal.json",
         String tests = "",
         String width = "0",
         String height = "0",
         String iterations = "0",
         String mergeablePR = "",
         String parallelExecutionTypeString = "TakeOneNodePerGPU",
         Boolean collectTrackedMetrics = true,
         String enginesNames = "Northstar64")
{
    ProblemMessageManager problemMessageManager = new ProblemMessageManager(this, currentBuild)
    Map options = [:]
    options["stage"] = "Init"
    options["problemMessageManager"] = problemMessageManager
    options["projectRepo"] = "git@github.com:amdadvtech/RadeonProRenderSDKInternal.git"
    
    def nodeRetry = []
    Map errorsInSuccession = [:]

    try {
        withNotifications(options: options, configuration: NotificationConfiguration.INITIALIZATION) {

            gpusCount = 0
            renderPlatforms.split(';').each() { platform ->
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
            println "Render platforms: ${renderPlatforms}"
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
                        unitTestsBranch:unitTestsBranch,
                        updateRefs:updateRefs,
                        enableNotifications:enableNotifications,
                        PRJ_NAME:"RadeonProRenderCore",
                        PRJ_ROOT:"rpr-core",
                        slackChannel:"${SLACK_CORE_CHANNEL}",
                        renderDevice:renderDevice,
                        testsPackage:testsPackage,
                        tests:tests.replace(',', ' '),
                        executeBuild:true,
                        executeTests:true,
                        reportName:'Test_20Report',
                        enginesNames:enginesNames,
                        TEST_TIMEOUT:180,
                        width:width,
                        gpusCount:gpusCount,
                        height:height,
                        iterations:iterations,
                        nodeRetry: nodeRetry,
                        problemMessageManager: problemMessageManager,
                        platforms:platforms,
                        renderPlatforms:renderPlatforms,
                        prRepoName:prRepoName,
                        prBranchName:prBranchName,
                        parallelExecutionType:parallelExecutionType,
                        collectTrackedMetrics:collectTrackedMetrics,
                        failedConfigurations: [],
                        storeOnNAS: true,
                        flexibleUpdates: true
                        ]
        }

        multiplatform_pipeline(platforms, this.&executePreBuild, this.&executeBuild, this.&executeTests, this.&executeDeploy, options)
    } catch(e)  {
        currentBuild.result = "FAILURE"
        println(e.toString())
        println(e.getMessage())
        throw e
    } finally {
        String problemMessage = problemMessageManager.publishMessages()
    }
}

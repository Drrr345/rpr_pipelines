import groovy.transform.Field
import groovy.json.JsonOutput
import utils
import net.sf.json.JSON
import net.sf.json.JSONSerializer
import net.sf.json.JsonConfig
import TestsExecutionType
import java.util.concurrent.atomic.AtomicInteger


@Field final String ANARI_SDK_REPO = "git@github.com:Piromancer/ANARI-SDK.git"
@Field final String RPR_ANARI_REPO = "git@github.com:GPUOpen-LibrariesAndSDKs/RadeonProRenderANARI.git"


@Field final PipelineConfiguration PIPELINE_CONFIGURATION = new PipelineConfiguration(
    supportedOS: ["Windows", "MacOS", "Ubuntu20"],
    productExtensions: ["Windows": "zip", "MacOS": "tar", "Ubuntu20": "tar"],
    artifactNameBase: "Anari_"
)


def executeGenTestRefCommand(String osName, Map options, Boolean delete) {
    dir("scripts") {
        switch(osName) {
            case "Windows":
                bat """
                    make_results_baseline.bat ${delete}
                """
                break
            default:
                sh """
                    ./make_results_baseline.sh ${delete}
                """
        }
    }
}

def buildRenderCache(String osName, String logName, Integer currentTry) {
    dir("scripts") {
        switch(osName) {
            case 'Windows':
                bat "build_cache.bat rpr >> \"..\\${logName}_${currentTry}.cb.log\"  2>&1"
                break
            default:
                sh "./build_cache.sh rpr >> \"../${logName}_${currentTry}.cb.log\" 2>&1"        
        }
    }
}

def executeTestCommand(String osName, String asicName, Map options) {
    switch(osName) {
        case "Windows":
            dir("scripts") {
                bat """
                    run.bat ${options.testsPackage} \"${options.tests}\" ${options.updateRefs} >> \"../${STAGE_NAME}_${options.currentTry}.log\" 2>&1
                """
            }
            break
        default:
            dir("scripts") {
                sh """
                    ./run.sh ${options.testsPackage} \"${options.tests}\" ${options.updateRefs} >> \"../${STAGE_NAME}_${options.currentTry}.log\" 2>&1
                """
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
                checkoutScm(branchName: options.testsBranch, repositoryUrl: options.testRepo)
            }
        }

        withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.DOWNLOAD_PACKAGE) {
            if (osName == "MacOS" || osName == "MacOS_ARM") {
                sh("brew install glfw3")
            } else if (osName == "Windows") {
                downloadFiles("/volume1/CIS/${options.PRJ_ROOT}/${options.PRJ_NAME}/VS_dlls/*", "Anari")
            } else {
                try {
                    sh """
                        sudo apt-get install libgl1-mesa-dev -y
                    """
                } catch (e) {
                    println("[WARNING] Some error during libs installation")
                }

                try {
                    sh """
                        sudo apt-get install libglfw3-dev -y
                    """
                } catch (e) {
                    println("[WARNING] Some error during libs installation")
                }

                // Clear Anari build directories as root
                sh("sudo rm -rf AnariSDK")
                sh("sudo rm -rf RadeonProRenderAnari")

                // Anari for Ubuntu should be build from source to test Anari device installation
                dir("AnariSDK") {
                    checkoutScm(branchName: options.anariSdkBranch, repositoryUrl: options.anariSdkRepo)
                }
                dir("RadeonProRenderAnari") {
                    checkoutScm(branchName: options.rprAnariBranch, repositoryUrl: options.rprAnariRepo)
                }

                sh("sudo " + '$CIS_TOOLS' + "/uninstall_anari_sdk.sh")

                dir("AnariSDK/build") {
                    sh """
                        export BUILD_TESTING=ON
                        $CMAKE_PATH -DBUILD_VIEWER=ON .. >> ../../${STAGE_NAME}_${options.currentTry}.log 2>&1
                        sudo $CMAKE_PATH --build . -t install >> ../../${STAGE_NAME}_${options.currentTry}.log 2>&1
                    """
                }

                dir("RadeonProRenderAnari/build") {
                    sh """
                        $CMAKE_PATH .. >> ../../${STAGE_NAME}_${options.currentTry}.log 2>&1
                        sudo $CMAKE_PATH --build . -t install >> ../../${STAGE_NAME}_${options.currentTry}.log 2>&1
                    """
                }
            }

            getProduct(osName, options, "Anari", false)

            if (osName == "Ubuntu20") {
                dir("Anari") {
                    // remove all RPR .so files to test RPR device installation
                    sh """
                        rm libRadeonProRender64.so libNorthstar64.so HybridPro.so
                    """
                }
            }
        }

        String REF_PATH_PROFILE="/volume1/Baselines/rpr_anari_autotests/${asicName}-${osName}"

        outputEnvironmentInfo(osName, "", options.currentTry)

        withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.BUILD_CACHE) {
            try{
                timeout(time: "10", unit: "MINUTES") {
                    buildRenderCache(osName, options.stageName, options.currentTry)
                }
            } catch (e) {
                // FIXME: ignore any problems for now
            }
        }

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
                String baseline_dir = isUnix() ? "${CIS_TOOLS}/../TestResources/rpr_anari_autotests_baselines" : "/mnt/c/TestResources/rpr_anari_autotests_baselines"
                println "[INFO] Downloading reference images for ${options.tests}"
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
    } catch (e) {
        String additionalDescription = ""
        if (options.currentTry + 1 < options.nodeReallocateTries) {
            stashResults = false
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
        if (osName == "MacOS" || osName == "MacOS_ARM") {
            sh("brew uninstall glfw3")
        } else if (osName == "Ubuntu20") {
            try {
                sh """
                    sudo apt-get purge libgl1-mesa-dev -y
                """
            } catch (e) {
                println("[WARNING] Some error during libs uninstallation")
            }

            try {
                sh """
                    sudo apt-get purge libglfw3-dev -y
                """
            } catch (e) {
                println("[WARNING] Some error during libs uninstallation")
            }
        }

        try {
            dir("${options.stageName}") {
                utils.moveFiles(this, osName, "../*.log", ".")
                utils.moveFiles(this, osName, "../scripts/*.log", ".")
                utils.renameFile(this, osName, "launcher.engine.log", "${options.stageName}_engine_${options.currentTry}.log")
            }
            archiveArtifacts artifacts: "${options.stageName}/*.log", allowEmptyArchive: true

            if (stashResults) {
                dir('Work') {
                    if (fileExists("Results/Anari/session_report.json")) {

                        def sessionReport = null
                        sessionReport = readJSON file: 'Results/Anari/session_report.json'

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
    GithubNotificator.updateStatus("Build", "Windows", "in_progress", options, NotificationConfiguration.BUILD_SOURCE_CODE_START_MESSAGE, "${BUILD_URL}/artifact/${STAGE_NAME}.log")

    utils.removeDir(this, "Windows", "%ProgramFiles(x86)%\\anari")

    dir("AnariSDK\\build") {
        bat """
            set BUILD_TESTING=ON
            cmake -DBUILD_VIEWER=ON .. >> ../../${STAGE_NAME}.log 2>&1
            cmake --build . -t install >> ../../${STAGE_NAME}.log 2>&1
        """
    }

    dir("RadeonProRenderAnari\\build") {
        bat """
            cmake .. >> ../../${STAGE_NAME}.log 2>&1
            cmake --build . >> ../../${STAGE_NAME}.log 2>&1
        """

        dir("Debug") {
            bat """
                copy "%ProgramFiles(x86)%\\anari\\bin" .
                copy C:\\vcpkg\\packages\\glfw3_x64-windows\\bin\\glfw3.dll .
                copy C:\\Windows\\System32\\ucrtbase.dll .
                %CIS_TOOLS%\\7-Zip\\7z.exe a Anari_Windows.zip .
            """

            makeArchiveArtifacts(name: "Anari_Windows.zip", storeOnNAS: options.storeOnNAS)
            makeStash(includes: "Anari_Windows.zip", name: getProduct.getStashName("Windows", options), preZip: false, storeOnNAS: options.storeOnNAS)
        }
    }

    GithubNotificator.updateStatus("Build", "Windows", "success", options, NotificationConfiguration.BUILD_SOURCE_CODE_END_MESSAGE)
}


def executeBuildLinux(String osName, Map options) {
    GithubNotificator.updateStatus("Build", osName, "in_progress", options, NotificationConfiguration.BUILD_SOURCE_CODE_START_MESSAGE, "${BUILD_URL}/artifact/${STAGE_NAME}.log")

    sh("sudo " + '$CIS_TOOLS' + "/uninstall_anari_sdk.sh")

    dir("AnariSDK/build") {
        sh """
            export BUILD_TESTING=ON
            cmake -DBUILD_VIEWER=ON .. >> ../../${STAGE_NAME}.log 2>&1
            sudo cmake --build . -t install >> ../../${STAGE_NAME}.log 2>&1
        """
    }

    dir("RadeonProRenderAnari/build") {
        sh """
            cmake .. >> ../../${STAGE_NAME}.log 2>&1
            cmake --build . >> ../../${STAGE_NAME}.log 2>&1
        """

        dir("results") {
            try {
                sh "cp -d /usr/local/lib/*anari* ."
            } catch (e) {
                sh "cp -d /usr/local/lib/x86_64-linux-gnu/*anari* ."
            }

            sh """
                cp -d /usr/local/bin/*anari* .
                cp -d ../*.so .
                tar cf Anari_${osName}.tar *
            """

            makeArchiveArtifacts(name: "Anari_${osName}.tar", storeOnNAS: options.storeOnNAS)
            makeStash(includes: "Anari_${osName}.tar", name: getProduct.getStashName(osName, options), preZip: false, storeOnNAS: options.storeOnNAS)
        }
    }

    GithubNotificator.updateStatus("Build", osName, "success", options, NotificationConfiguration.BUILD_SOURCE_CODE_END_MESSAGE)
}


def executeBuildMacOS(Map options, Boolean isx86 = true) {
    GithubNotificator.updateStatus("Build", isx86 ? "MacOS" : "MacOS_ARM", "in_progress", options, NotificationConfiguration.BUILD_SOURCE_CODE_START_MESSAGE, "${BUILD_URL}/artifact/${STAGE_NAME}.log")

    sh('$CIS_TOOLS' + "/uninstall_anari_sdk.sh")

    dir("AnariSDK/build") {
        sh """
            export BUILD_TESTING=ON
            cmake -DBUILD_VIEWER=ON .. >> ../../${STAGE_NAME}.log 2>&1
            cmake --build . -t install >> ../../${STAGE_NAME}.log 2>&1
        """
    }

    dir("RadeonProRenderAnari/build") {
        sh """
            cmake .. >> ../../${STAGE_NAME}.log 2>&1
            cmake --build . >> ../../${STAGE_NAME}.log 2>&1
        """

        dir("results") {
            sh """
                cp -R /usr/local/lib/*anari* .
                cp -R /usr/local/bin/*anari* .
                cp -R ../*.dylib .
                install_name_tool -add_rpath "@executable_path" anariRenderTests
                tar cf Anari_${isx86 ? 'MacOS' : 'MacOS_ARM'}.tar *
            """

            makeArchiveArtifacts(name: "Anari_${isx86 ? 'MacOS' : 'MacOS_ARM'}.tar", storeOnNAS: options.storeOnNAS)
            makeStash(includes: "Anari_${isx86 ? 'MacOS' : 'MacOS_ARM'}.tar", name: getProduct.getStashName(isx86 ? "MacOS" : "MacOS_ARM", options), preZip: false, storeOnNAS: options.storeOnNAS)
        }
    }

    GithubNotificator.updateStatus("Build", isx86 ? "MacOS" : "MacOS_ARM", "success", options, NotificationConfiguration.BUILD_SOURCE_CODE_END_MESSAGE)
}


def executeBuild(String osName, Map options) {
    try {
        withNotifications(title: osName, options: options, configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
            dir('AnariSDK') {
                checkoutScm(branchName: options.anariSdkBranch, repositoryUrl: options.anariSdkRepo)
            }
            dir('RadeonProRenderAnari') {
                checkoutScm(branchName: options.rprAnariBranch, repositoryUrl: options.rprAnariRepo)
            }
        }

        outputEnvironmentInfo(osName)

        withNotifications(title: osName, options: options, configuration: NotificationConfiguration.BUILD_SOURCE_CODE) {
            switch(osName) {
                case "Windows":
                    executeBuildWindows(options)
                    break
                case "MacOS":
                case "MacOS_ARM":
                    executeBuildMacOS(options, osName == "MacOS")
                    break
                default:
                    executeBuildLinux(osName, options)
                    break
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
    boolean collectTrackedMetrics = true

    if (options["isPreBuilt"]) {
        return """Anari "PreBuilt" "PreBuilt" "PreBuilt" \"\" ${collectTrackedMetrics ? env.BUILD_NUMBER : ""}"""
    } else {
        return """Anari ${options.commitSHA} ${options.rprAnariBranch} \"${utils.escapeCharsByUnicode(options.commitMessage)}\" \"\" ${collectTrackedMetrics ? env.BUILD_NUMBER : ""}"""
    }
}


def executePreBuild(Map options) {
    if (options['isPreBuilt']) {
        println "[INFO] Build was detected as prebuilt. Build stage will be skipped"
        currentBuild.description = "<b>Project branch:</b> Prebuilt plugin<br/>"
        options['executeBuild'] = false
        options['executeTests'] = true
    } else {
        options['executeBuild'] = true
        options['executeTests'] = true
    }

    // manual job
    if (!env.BRANCH_NAME) {
        println "[INFO] Manual job launch detected"
        options.projectBranchName = options.rprAnariBranch
    // auto job
    } else {
        if (env.CHANGE_URL) {
            println "[INFO] Branch was detected as Pull Request"
        } else if (env.BRANCH_NAME == "master" || env.BRANCH_NAME == "develop") {
           println "[INFO] ${env.BRANCH_NAME} branch was detected"
        } else {
            println "[INFO] ${env.BRANCH_NAME} branch was detected"
        }
    }

    if (!options['isPreBuilt']) {
        dir('RadeonProRenderAnari') {
            withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
                checkoutScm(branchName: options.rprAnariBranch, repositoryUrl: options.rprAnariRepo, disableSubmodules: true)
            }

            options.commitAuthor = bat (script: "git show -s --format=%%an HEAD ",returnStdout: true).split('\r\n')[2].trim()
            options.commitMessage = bat (script: "git log --format=%%B -n 1", returnStdout: true).split('\r\n')[2].trim()
            options.commitSHA = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
            options.commitShortSHA = options.commitSHA[0..6]
            options.branchName = env.BRANCH_NAME ?: options.projectBranch

            println(bat (script: "git log --format=%%s -n 1", returnStdout: true).split('\r\n')[2].trim());
            println "The last commit was written by ${options.commitAuthor}."
            println "Commit message: ${options.commitMessage}"
            println "Commit SHA: ${options.commitSHA}"
            println "Commit shortSHA: ${options.commitShortSHA}"
            println "Branch name: ${options.branchName}"

            withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.INCREMENT_VERSION) {
                options.anariMajorVersion = version_read("${env.WORKSPACE}\\RadeonProRenderAnari\\version.h", '#define RPR_ANARI_VERSION_MAJOR', ' ')
                options.anariMinorVersion = version_read("${env.WORKSPACE}\\RadeonProRenderAnari\\version.h", '#define RPR_ANARI_VERSION_MINOR', ' ')
                options.anariPatchVersion = version_read("${env.WORKSPACE}\\RadeonProRenderAnari\\version.h", '#define RPR_ANARI_VERSION_PATCH', ' ')

                if (env.BRANCH_NAME) {
                    withNotifications(title: "Jenkins build configuration", printMessage: true, options: options, configuration: NotificationConfiguration.CREATE_GITHUB_NOTIFICATOR) {
                        GithubNotificator githubNotificator = new GithubNotificator(this, options)
                        githubNotificator.init(options)
                        options["githubNotificator"] = githubNotificator
                        githubNotificator.initPreBuild("${BUILD_URL}")
                        options.projectBranchName = githubNotificator.branchName
                    }

                    if (env.BRANCH_NAME == "develop" && options.commitAuthor != "radeonprorender") {
                        println "[INFO] Incrementing version of change made by ${options.commitAuthor}."
                        println "[INFO] Current build version: ${options.anariPatchVersion}"

                        def newVersion = version_inc(options.anariPatchVersion, 1, ' ')
                        println "[INFO] New build version: ${newVersion}"
                        version_write("${env.WORKSPACE}\\RadeonProRenderAnari\\version.h", '#define RPR_ANARI_VERSION_PATCH', newVersion, ' ')

                        options.anariPatchVersion = version_read("${env.WORKSPACE}\\RadeonProRenderAnari\\version.h", '#define RPR_ANARI_VERSION_PATCH', ' ')
                        println "[INFO] Updated build version: ${options.anariPatchVersion}"

                        bat """
                            git add version.h
                            git commit -m "buildmaster: version update to ${options.anariPatchVersion}"
                            git push origin HEAD:develop
                        """

                        //get commit's sha which have to be build
                        options.commitSHA = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
                        options.projectBranch = options.commitSHA
                        println "[INFO] Project branch hash: ${options.projectBranch}"
                    }
                }
            }

            if (options.projectBranch) {
                currentBuild.description = "<b>Project branch:</b> ${options.projectBranch}<br/>"
            } else {
                currentBuild.description = "<b>Project branch:</b> ${env.BRANCH_NAME}<br/>"
            }

            currentBuild.description = "<b>Anari SDK branch:</b> ${options.anariSdkBranch}<br/>"
            currentBuild.description = "<b>RPR Anari branch:</b> ${options.rprAnariBranch}<br/>"
            currentBuild.description += "<b>Version:</b> ${options.anariMajorVersion}.${options.anariMinorVersion}.${options.anariPatchVersion}<br/>"
            currentBuild.description += "<b>Commit author:</b> ${options.commitAuthor}<br/>"
            currentBuild.description += "<b>Commit message:</b> ${options.commitMessage}<br/>"
            currentBuild.description += "<b>Commit SHA:</b> ${options.commitSHA}<br/>"
        }
    }

    withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.CONFIGURE_TESTS) {
        dir('jobs_test_anari') {
            checkoutScm(branchName: options.testsBranch, repositoryUrl: options.testRepo)
            dir ('jobs_launcher') {
                options['jobsLauncherBranch'] = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
            }
            options['testsBranch'] = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
            println("[INFO] Test branch hash: ${options['testsBranch']}")

            if (options.testsPackage != "none") {
                // json means custom test suite. Split doesn't supported
                def packageInfo = readJSON file: "jobs/${options.testsPackage}"
                options.tests = []
                packageInfo["groups"].each() {
                    options.tests << it.key
                }
                options.testsPackage = "none"
                options.tests = options.tests.join(" ")
            }

            options.testsList = ['']
        }

        if (env.BRANCH_NAME && options.githubNotificator) {
            options.githubNotificator.initChecks(options, "${BUILD_URL}")
        }
    }

    if (options.flexibleUpdates && multiplatform_pipeline.shouldExecuteDelpoyStage(options)) {
        options.reportUpdater = new ReportUpdater(this, env, options)
        options.reportUpdater.init(this.&getReportBuildArgs)
    }
}


def executeDeploy(Map options, List platformList, List testResultList) {
    try {
        if (options['executeTests'] && testResultList) {

            withNotifications(title: "Building test report", options: options, startUrl: "${BUILD_URL}", configuration: NotificationConfiguration.DOWNLOAD_TESTS_REPO) {
                checkoutScm(branchName: options.testsBranch, repositoryUrl: options.testRepo)
            }

            List lostStashes = []

            dir("summaryTestResults") {
                testResultList.each() {
                    dir("$it".replace("testResult-", "")) {
                        try {
                            makeUnstash(name: "$it", storeOnNAS: options.storeOnNAS)
                        } catch(e) {
                            echo "Can't unstash ${it}"
                            lostStashes.add("'$it'".replace("testResult-", ""))
                            println(e.toString())
                            println(e.getMessage())
                        }

                    }
                }
            }

            try {
                dir("jobs_launcher") {
                    bat """
                        count_lost_tests.bat \"${lostStashes}\" .. ..\\summaryTestResults \"false\" \"${options.testsPackage}\" \"${options.tests.toString()}\" \"\" \"{}\"
                    """
                }
            } catch (e) {
                println("[ERROR] Can't generate number of lost tests")
            }

            // always show tracked metrics
            boolean useTrackedMetrics = true
            boolean saveTrackedMetrics = env.JOB_NAME.contains("Weekly")
            String metricsRemoteDir = "/volume1/Baselines/TrackedMetrics/RPR-Anari"

            if (useTrackedMetrics) {
                utils.downloadMetrics(this, "summaryTestResults/tracked_metrics", "${metricsRemoteDir}/")
            }

            try {
                GithubNotificator.updateStatus("Deploy", "Building test report", "in_progress", options, NotificationConfiguration.BUILDING_REPORT, "${BUILD_URL}")

                withEnv(["JOB_STARTED_TIME=${options.JOB_STARTED_TIME}", "BUILD_NAME=${options.baseBuildName}"]) {
                    dir("jobs_launcher") {
                        bat "build_reports.bat ..\\summaryTestResults ${getReportBuildArgs(options)}"
                        bat "get_status.bat ..\\summaryTestResults"
                    }
                }

                if (saveTrackedMetrics) {
                    utils.uploadMetrics(this, "summaryTestResults/tracked_metrics", metricsRemoteDir)
                }
            } catch(e) {
                String errorMessage = utils.getReportFailReason(e.getMessage())
                GithubNotificator.updateStatus("Deploy", "Building test report", "failure", options, errorMessage, "${BUILD_URL}")
                if (utils.isReportFailCritical(e.getMessage())) {
                    options.problemMessageManager.saveSpecificFailReason(errorMessage, "Deploy")
                    println("[ERROR] Failed to build test report.")
                    println(e.toString())
                    println(e.getMessage())

                    if (!options.testDataSaved && !options.storeOnNAS) {
                        try {
                            // Save test data for access it manually anyway
                            utils.publishReport(this, "${BUILD_URL}", "summaryTestResults", "summary_report.html, performance_report.html, compare_report.html", \
                                "Test Report", "Summary Report, Performance Report, Compare Report", options.storeOnNAS, \
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
                options.testsStatus = readFile("summaryTestResults/slack_status.json")
            } catch(e) {
                println(e.toString())
                println(e.getMessage())
                options.testsStatus = ""
            }

            withNotifications(title: "Building test report", options: options, configuration: NotificationConfiguration.PUBLISH_REPORT) {
                utils.publishReport(this, "${BUILD_URL}", "summaryTestResults", "summary_report.html, performance_report.html, compare_report.html", \
                    "Test Report", "Summary Report, Performance Report, Compare Report", options.storeOnNAS, \
                    ["jenkinsBuildUrl": BUILD_URL, "jenkinsBuildName": currentBuild.displayName, "updatable": options.containsKey("reportUpdater")])

                if (summaryTestResults) {
                    // add in description of status check information about tests statuses
                    // Example: Report was published successfully (passed: 69, failed: 11, error: 0)
                    GithubNotificator.updateStatus("Deploy", "Building test report", "success", options, "${NotificationConfiguration.REPORT_PUBLISHED} Results: passed - ${summaryTestResults.passed}, failed - ${summaryTestResults.failed}, error - ${summaryTestResults.error}.", "${BUILD_URL}/Test_20Report")
                } else {
                    GithubNotificator.updateStatus("Deploy", "Building test report", "success", options, NotificationConfiguration.REPORT_PUBLISHED, "${BUILD_URL}/Test_20Report")
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


def call(String anariSdkRepo = ANARI_SDK_REPO,
    String anariSdkBranch = "main",
    String rprAnariRepo = RPR_ANARI_REPO,
    String rprAnariBranch = "",
    String testsBranch = "master",
    String platforms = "Windows:NVIDIA_RTX3080TI,AMD_RadeonVII,AMD_RX6800XT;Ubuntu20;MacOS:AMD_RX5700XT",
    String updateRefs = "No",
    String testsPackage = "none",
    String tests = "General_Northstar",
    String customBuildLinkWindows = "",
    String customBuildLinkUbuntu20 = "",
    String customBuildLinkMacOS = "",
    String testerTag = "Tester")
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

            Boolean isPreBuilt = customBuildLinkWindows || customBuildLinkMacOS || customBuildLinkUbuntu20

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
                        case 'MacOS':
                            if (customBuildLinkMacOS) {
                                filteredPlatforms = appendPlatform(filteredPlatforms, platform)
                            }
                            break
                        default:
                            if (customBuildLinkUbuntu20) {
                                filteredPlatforms = appendPlatform(filteredPlatforms, platform)
                            }
                        }
                }

                platforms = filteredPlatforms
            }

            println "Platforms: ${platforms}"
            println "Tests: ${tests}"
            println "Tests package: ${testsPackage}"

            options << [configuration: PIPELINE_CONFIGURATION,
                        anariSdkRepo: anariSdkRepo,
                        anariSdkBranch: anariSdkBranch,
                        rprAnariRepo: rprAnariRepo,
                        rprAnariBranch:rprAnariBranch,
                        projectRepo:RPR_ANARI_REPO,
                        testRepo:"git@github.com:luxteam/jobs_test_anari.git",
                        testsBranch:testsBranch,
                        updateRefs:updateRefs,
                        testsPackage:testsPackage,
                        testsPackageOriginal:testsPackage,
                        tests:tests,
                        PRJ_NAME:"RadeonProRenderAnari",
                        PRJ_ROOT:"rpr-plugins",
                        reportName:'Test_20Report',
                        gpusCount:gpusCount,
                        TEST_TIMEOUT:30,
                        ADDITIONAL_XML_TIMEOUT:15,
                        NON_SPLITTED_PACKAGE_TIMEOUT:30,
                        DEPLOY_TIMEOUT:20,
                        nodeRetry: nodeRetry,
                        platforms:platforms,
                        storeOnNAS: true,
                        flexibleUpdates: true,
                        isPreBuilt:isPreBuilt,
                        customBuildLinkWindows: customBuildLinkWindows,
                        customBuildLinkUbuntu20: customBuildLinkUbuntu20,
                        customBuildLinkMacOS: customBuildLinkMacOS,
                        TESTER_TAG: testerTag
                        ]
        }

        multiplatform_pipeline(platforms, this.&executePreBuild, this.&executeBuild, this.&executeTests, this.&executeDeploy, options)
    } catch(e) {
        throw e
    } finally {
        String problemMessage = options.problemMessageManager.publishMessages()
    }
}

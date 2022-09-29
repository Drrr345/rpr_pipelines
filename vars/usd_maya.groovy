import groovy.transform.Field
import groovy.json.JsonOutput;
import net.sf.json.JSON
import net.sf.json.JSONSerializer
import net.sf.json.JsonConfig
import TestsExecutionType
import java.util.concurrent.atomic.AtomicInteger


@Field final String PRODUCT_NAME = "AMD%20Radeon™%20ProRender%20Maya%20USD"

@Field final PipelineConfiguration PIPELINE_CONFIGURATION = new PipelineConfiguration(
    supportedOS: ["Windows"],
    productExtensions: ["Windows": "exe"],
    artifactNameBase: "RPRMayaUSD_Setup",
    testProfile: "engine",
    displayingProfilesMapping: [
        "engine": [
            "HybridPro": "HybridPro",
            "Northstar": "Northstar"
        ]
    ]
)


Boolean filter(Map options, String asicName, String osName, String testName, String engine) {
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
            // OSX 
            default:
                sh """
                    ./make_results_baseline.sh ${delete}
                """
                break
        }
    }
}

def uninstallRPRMayaPlugin(String osName, Map options) {
    println "[INFO] Uninstalling RPR Maya plugin"
    switch(osName) {
        case 'Windows':
            uninstallMSI("Radeon%Maya%", options.stageName, options.currentTry)
            break
        default:
            println "[WARNING] ${osName} is not supported"
    }
}

def installRPRMayaUSDPlugin(String osName, Map options) {

    if (options['isPreBuilt']) {
        options['pluginWinSha'] = "${options[getProduct.getIdentificatorKey('Windows', options)]}"
    } else {
        options['pluginWinSha'] = "${options.commitSHA}"
    }

    try {
        println "[INFO] Install RPR Maya USD Plugin"

        bat """
            start /wait ${CIS_TOOLS}\\..\\PluginsBinaries\\${options.pluginWinSha}.exe /SILENT /NORESTART /LOG=${options.stageName}_${options.currentTry}.install.log
        """

    } catch (e) {
        throw new Exception("Failed to install plugin")
    }

    String modulesPath = "C:\\Program Files\\Common Files\\Autodesk Shared\\Modules\\maya\\${options.toolVersion}"

    // Move mayausd.mod due to conflict with RPRMayaUSD.mod
    status = bat(returnStatus: true, script: "MOVE /Y \"${modulesPath}\\mayausd.mod\" \"${modulesPath}\\..\"")
    
    if (status == 0) {
        println "[INFO] mayausd.mod moved"
    } else {
        println "[INFO] mayausd.mod not found"
    }
}

def uninstallRPRMayaUSDPlugin(String osName, Map options) {
    println "[INFO] Uninstalling RPR Maya USD plugin"
    switch(osName) {
        case "Windows":
            String defaultUninstallerPath = "C:\\Program Files\\RPRMayaUSD\\unins000.exe"

            try {
                if (fileExists(defaultUninstallerPath)) {
                    bat """
                        start "" /wait "${defaultUninstallerPath}" /SILENT
                    """
                } else {
                    println "[INFO] RPR Maya USD plugin not found"
                }
            } catch (e) {
                throw new Exception("Failed to uninstall RPR Maya USD plugin")
            }
            break
        default:
            println "[WARNING] ${osName} is not supported for RPR Maya USD"
    }
}

def buildRenderCache(String osName, String toolVersion, String log_name, Integer currentTry, String engine) {
    try {
        dir("scripts") {
            switch(osName) {
                case 'Windows':
                    bat "build_rpr_cache.bat ${toolVersion} ${engine} >> \"..\\${log_name}_${currentTry}.cb.log\"  2>&1"
                    break
                default:
                    println "[WARNING] ${osName} is not supported"
            }
        }
    } catch (e) {
        throw e
    }
}

def executeTestCommand(String osName, String asicName, Map options) {
    def testTimeout = options.timeouts["${options.tests}"]
    String testsNames
    String testsPackageName

    if (options.testsPackage != "none" && !options.isPackageSplitted) {
        if (options.tests.contains(".json")) {
            // if tests package isn't splitted and it's execution of this package - replace test package by test group and test group by empty string
            testsPackageName = options.tests
            testsNames = ""
        } else {
            // if tests package isn't splitted and it isn't execution of this package - replace tests package by empty string
            testsPackageName = "none"
            testsNames = options.tests
        }
    } else {
        testsPackageName = "none"
        testsNames = options.tests
    }

    println "Set timeout to ${testTimeout}"

    timeout(time: testTimeout, unit: 'MINUTES') { 
        switch(osName) {
            case 'Windows':
                dir('scripts') {
                    bat """
                        run.bat ${options.renderDevice} \"${testsPackageName}\" \"${testsNames}\" ${options.resX} ${options.resY} ${options.SPU} ${options.iter} ${options.theshold} ${options.toolVersion} ${options.engine} ${options.testCaseRetries} ${options.updateRefs} 1>> \"../${options.stageName}_${options.currentTry}.log\"  2>&1
                    """
                }
                break
            default:
                println("[WARNING] ${osName} is not supported")
        }
    }
}

def executeTests(String osName, String asicName, Map options) {
    // used for mark stash results or not. It needed for not stashing failed tasks which will be retried.
    Boolean stashResults = true
    try {
        withNotifications(title: options["stageName"], options: options, logUrl: "${BUILD_URL}", configuration: NotificationConfiguration.DOWNLOAD_TESTS_REPO) {
            timeout(time: "15", unit: "MINUTES") {                
                cleanWS(osName)
                checkoutScm(branchName: options.testsBranch, repositoryUrl: options.testRepo)
            }
        }

        withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.DOWNLOAD_SCENES) {
            String assets_dir = isUnix() ? "${CIS_TOOLS}/../TestResources/usd_maya_autotests" : "/mnt/c/TestResources/usd_maya_autotests"
            downloadFiles("/volume1/web/Assets/usd_maya_autotests/", assets_dir)
        }
        try {
            Boolean newPluginInstalled = false
            withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.DOWNLOAD_PACKAGE) {
                timeout(time: "15", unit: "MINUTES") {
                    getProduct(osName, options)
                }
            }

            timeout(time: "15", unit: "MINUTES") {
                uninstallRPRMayaPlugin(osName, options)
                uninstallRPRMayaUSDPlugin(osName, options)
            }

            println "Start plugin installation"

            timeout(time: "15", unit: "MINUTES") {
                installRPRMayaUSDPlugin(osName, options)
                newPluginInstalled = true
                removeInstaller(osName: osName, options: options)
            }

            withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.BUILD_CACHE) {
                if (newPluginInstalled) {
                    timeout(time: "20", unit: "MINUTES") {
                        String cacheImgPath = "./Work/Results/Maya/cache_building.jpg"
                        utils.removeFile(this, osName, cacheImgPath)
                        buildRenderCache(osName, options.toolVersion, options.stageName, options.currentTry, options.engine)
                        if(!fileExists(cacheImgPath)){
                            throw new ExpectedExceptionWrapper(NotificationConfiguration.NO_OUTPUT_IMAGE, new Exception(NotificationConfiguration.NO_OUTPUT_IMAGE))
                        }
                    }
                }
            }
        } catch(e) {
            println(e.toString())
            println("[ERROR] Failed to install plugin on ${env.NODE_NAME}.")
            // deinstalling broken addon
            uninstallRPRMayaUSDPlugin(osName, options)
            // remove installer of broken addon
            removeInstaller(osName: osName, options: options)
            throw e
        }

        String enginePostfix = ""
        String REF_PATH_PROFILE="/volume1/Baselines/usd_maya_autotests/${asicName}-${osName}"
        switch(options.engine) {
            case 'Northstar':
                enginePostfix = "NorthStar"
                break
            case 'HybridPro':
                enginePostfix = "HybridPro"
                break
        }
        REF_PATH_PROFILE = enginePostfix ? "${REF_PATH_PROFILE}-${enginePostfix}" : REF_PATH_PROFILE

        options.REF_PATH_PROFILE = REF_PATH_PROFILE

        outputEnvironmentInfo(osName, options.stageName, options.currentTry)

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
                }
            }
        } else {
            withNotifications(title: options["stageName"], printMessage: true, options: options, configuration: NotificationConfiguration.COPY_BASELINES) {
                String baseline_dir = "/mnt/c/TestResources/usd_maya_autotests_baselines"
                baseline_dir = enginePostfix ? "${baseline_dir}-${enginePostfix}" : baseline_dir
                println "[INFO] Downloading reference images for ${options.tests}-${options.engine}"

                options.tests.split(" ").each() {
                    if (it.contains(".json")) {
                        downloadFiles("${REF_PATH_PROFILE}/", baseline_dir)
                    } else {
                        downloadFiles("${REF_PATH_PROFILE}/${it}", baseline_dir)
                    }
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

        utils.reboot(this, osName)

        if (e instanceof ExpectedExceptionWrapper) {
            GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, "${e.getMessage()} ${additionalDescription}", "${BUILD_URL}")
            throw new ExpectedExceptionWrapper("${e.getMessage()}\n${additionalDescription}", e.getCause())
        } else {
            String errorMessage = "The reason is not automatically identified. Please contact support."
            GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, "${errorMessage} ${additionalDescription}", "${BUILD_URL}")
            throw new ExpectedExceptionWrapper("${errorMessage}\n${additionalDescription}", e)
        }
    } finally {
        try {
            dir("${options.stageName}") {
                utils.moveFiles(this, osName, "../*.log", ".")
                utils.moveFiles(this, osName, "../scripts/*.log", ".")

                // if some json files are broken - rerun (e.g. Maya crash can cause that)
                try {
                    String engineLogContent = readFile("${options.stageName}_${options.currentTry}.log")
                    if (engineLogContent.contains("json.decoder.JSONDecodeError")) {
                        throw new ExpectedExceptionWrapper(NotificationConfiguration.FILES_CRASHED, e)
                    }
                } catch (ExpectedExceptionWrapper e1) {
                    throw e1
                } catch (e1) {
                    println("[WARNING] Could not analyze autotests log")
                }

                utils.renameFile(this, osName, "launcher.engine.log", "${options.stageName}_engine_${options.currentTry}.log")
            }
            archiveArtifacts artifacts: "${options.stageName}/*.log", allowEmptyArchive: true
            if (stashResults) {
                dir('Work') {
                    if (fileExists("Results/Maya/session_report.json")) {

                        def sessionReport = null
                        sessionReport = readJSON file: 'Results/Maya/session_report.json'

                        if (sessionReport.summary.error > 0) {
                            GithubNotificator.updateStatus("Test", options['stageName'], "action_required", options, NotificationConfiguration.SOME_TESTS_ERRORED, "${BUILD_URL}")
                        } else if (sessionReport.summary.failed > 0) {
                            GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, NotificationConfiguration.SOME_TESTS_FAILED, "${BUILD_URL}")
                        } else {
                            GithubNotificator.updateStatus("Test", options['stageName'], "success", options, NotificationConfiguration.ALL_TESTS_PASSED, "${BUILD_URL}")
                        }

                        println("Stashing test results to : ${options.testResultsName}")
                        utils.stashTestData(this, options, options.storeOnNAS)

                        // deinstalling broken addon
                        // if test group is fully errored or number of test cases is equal to zero
                        if (sessionReport.summary.total == sessionReport.summary.error + sessionReport.summary.skipped || sessionReport.summary.total == 0) {
                            // check that group isn't fully skipped
                            if (sessionReport.summary.total != sessionReport.summary.skipped || sessionReport.summary.total == 0){
                                uninstallRPRMayaUSDPlugin(osName, options)
                                removeInstaller(osName: osName, options: options, extension: "exe")
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
            } else {
                println "[INFO] Task ${options.tests}-${options.engine} on ${options.nodeLabels} labels will be retried."
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
    dir('RPRMayaUSD') {
        // Temporary remove system python from PATH (otherwise it can affect building of plugin)
        withEnv(["PATH=C:\\Program Files (x86)\\Inno Setup 6\\;${PATH.replace('Python', '')}"]) {
            outputEnvironmentInfo("Windows", "${STAGE_NAME}.EnvVariables")

            withNotifications(title: "Windows", options: options, logUrl: "${BUILD_URL}/artifact/${STAGE_NAME}.log", configuration: NotificationConfiguration.BUILD_SOURCE_CODE) {
                bat """
                    call "C:\\Program Files (x86)\\Microsoft Visual Studio\\2017\\Community\\VC\\Auxiliary\\Build\\vcvars64.bat" >> ..\\${STAGE_NAME}.EnvVariables.log 2>&1

                    build_with_devkit.bat > ..\\${STAGE_NAME}.devkit.log 2>&1
                """
            }
            dir('installation') {
                bat """
                    rename RPRMayaUSDHdRPR_Setup* RPRMayaUSDHdRPR_Setup_${options.hdrprPluginVersion}.exe
                """

                if (options.branch_postfix) {
                    bat """
                        rename RPRMayaUSDHdRPR_Setup_${options.hdrprPluginVersion}.exe RPRMayaUSDHdRPR_Setup_${options.hdrprPluginVersion}_(${options.branch_postfix}).exe
                    """
                }

                String ARTIFACT_NAME = options.branch_postfix ? "RPRMayaUSDHdRPR_Setup_${options.hdrprPluginVersion}_(${options.branch_postfix}).exe" : "RPRMayaUSDHdRPR_Setup_${options.hdrprPluginVersion}.exe"
                String artifactURL = makeArchiveArtifacts(name: ARTIFACT_NAME, storeOnNAS: options.storeOnNAS)
            }

            // vcvars64.bat sets VS/msbuild env
            withNotifications(title: "Windows", options: options, logUrl: "${BUILD_URL}/artifact/${STAGE_NAME}.log", configuration: NotificationConfiguration.BUILD_SOURCE_CODE) {
                // FIXME: patch TIFF url, because it's invalid. This code must be removed when USD submodule will be updated
                String buildScriptContent = readFile(file: "USD/build_scripts/build_usd.py")

                buildScriptContent = buildScriptContent.replace(
                    "https://gitlab.com/libtiff/libtiff/-/archive/Release-v4-0-7/libtiff-Release-v4-0-7.tar.gz",
                    "https://gitlab.com/libtiff/libtiff/-/archive/v4.0.7/libtiff-v4.0.7.tar.gz"
                )

                writeFile(file: "USD/build_scripts/build_usd.py", text: buildScriptContent)

                bat """
                    call "C:\\Program Files (x86)\\Microsoft Visual Studio\\2017\\Community\\VC\\Auxiliary\\Build\\vcvars64.bat" >> ..\\${STAGE_NAME}.EnvVariables.log 2>&1

                    build.bat > ..\\${STAGE_NAME}.log 2>&1
                """
            }
            dir('installation') {
                bat """
                    rename RPRMayaUSD_Setup* RPRMayaUSD_Setup.exe
                """

                makeStash(includes: "RPRMayaUSD_Setup.exe", name: getProduct.getStashName("Windows", options), preZip: false, storeOnNAS: options.storeOnNAS)

                if (options.branch_postfix) {
                    bat """
                        rename RPRMayaUSD_Setup.exe RPRMayaUSD_Setup_${options.usdPluginVersion}_(${options.branch_postfix}).exe
                    """
                } else {
                    bat """
                        rename RPRMayaUSD_Setup.exe RPRMayaUSD_Setup_${options.usdPluginVersion}.exe
                    """
                }

                String ARTIFACT_NAME = options.branch_postfix ? "RPRMayaUSD_Setup_${options.usdPluginVersion}_(${options.branch_postfix}).exe" : "RPRMayaUSD_Setup_${options.usdPluginVersion}.exe"
                String artifactURL = makeArchiveArtifacts(name: ARTIFACT_NAME, storeOnNAS: options.storeOnNAS)

                GithubNotificator.updateStatus("Build", "Windows", "success", options, NotificationConfiguration.BUILD_SOURCE_CODE_END_MESSAGE, artifactURL)
            }
        }
    }
}

def executeBuild(String osName, Map options) {
    try {
        dir("RPRMayaUSD") {
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
                default:
                    println "[WARNING] ${osName} is not supported"
            }
        }

        options[getProduct.getIdentificatorKey(osName, options)] = options.commitSHA
    } catch (e) {
        throw e
    } finally {
        archiveArtifacts "*.log"
    }
}

def getReportBuildArgs(String engineName, Map options) {
    boolean collectTrackedMetrics = (env.JOB_NAME.contains("Weekly") || (env.JOB_NAME.contains("Manual") && options.testsPackageOriginal == "Full.json"))

    if (options["isPreBuilt"]) {
        return """"MayaUSD" "PreBuilt" "PreBuilt" "PreBuilt" \"${utils.escapeCharsByUnicode(engineName)}\" ${collectTrackedMetrics ? env.BUILD_NUMBER : ""}"""
    } else {
        return """"MayaUSD" ${options.commitSHA} ${options.projectBranchName} \"${utils.escapeCharsByUnicode(options.commitMessage)}\" \"${utils.escapeCharsByUnicode(engineName)}\" ${collectTrackedMetrics ? env.BUILD_NUMBER : ""}"""
    }
}

def executePreBuild(Map options) {
    // manual job with prebuilt plugin
    if (options.isPreBuilt) {
        println "[INFO] Build was detected as prebuilt. Build stage will be skipped"
        currentBuild.description = "<b>Project branch:</b> Prebuilt plugin<br/>"
        options.executeBuild = false
        options.executeTests = true
    // manual job
    } else if (options.forceBuild) {
        println "[INFO] Manual job launch detected"
        options['executeBuild'] = true
        options['executeTests'] = true
    // auto job
    } else {
        if (env.CHANGE_URL) {
            println "[INFO] Branch was detected as Pull Request"
            options['executeBuild'] = true
            options['executeTests'] = true
            options['testsPackage'] = "Full.json"
        } else if (env.BRANCH_NAME == "main" || env.BRANCH_NAME == "develop") {
           println "[INFO] ${env.BRANCH_NAME} branch was detected"
           options['executeBuild'] = true
           options['executeTests'] = true
           options['testsPackage'] = "Full.json"
        } else {
            println "[INFO] ${env.BRANCH_NAME} branch was detected"
            options['testsPackage'] = "Full.json"
        }
    }

    // branch postfix
    options["branch_postfix"] = ""
    if (env.BRANCH_NAME && env.BRANCH_NAME == "main") {
        options["branch_postfix"] = "release"
    } else if(env.BRANCH_NAME && env.BRANCH_NAME != "main" && env.BRANCH_NAME != "develop") {
        options["branch_postfix"] = env.BRANCH_NAME.replace('/', '-')
    } else if(options.projectBranch && options.projectBranch != "main" && options.projectBranch != "develop") {
        options["branch_postfix"] = options.projectBranch.replace('/', '-')
    }

    if (!options['isPreBuilt']) {
        dir('RPRMayaUSD') {
            withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
                checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo, prBranchName: options.prBranchName, prRepoName: options.prRepoName, disableSubmodules: true)
            }

            options.commitAuthor = bat (script: "git show -s --format=%%an HEAD ",returnStdout: true).split('\r\n')[2].trim()
            options.commitMessage = bat (script: "git log --format=%%B -n 1", returnStdout: true).split('\r\n')[2].trim()
            options.commitSHA = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
            options.commitShortSHA = options.commitSHA[0..6]
            options.branchName = env.BRANCH_NAME ?: options.projectBranch

            println "The last commit was written by ${options.commitAuthor}."
            println "Commit message: ${options.commitMessage}"
            println "Commit SHA: ${options.commitSHA}"
            println "Commit shortSHA: ${options.commitShortSHA}"
            println "Branch name: ${options.branchName}"

            withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.INCREMENT_VERSION) {
                // Temporary hardcode version due to different formats of version in master and PR-8
                options.usdPluginVersion = version_read("${env.WORKSPACE}\\RPRMayaUSD\\installation\\installation.iss", '#define AppVersionString ').replace("\'", "")
                options.hdrprPluginVersion = version_read("${env.WORKSPACE}\\RPRMayaUSD\\installation\\installation_hdrpr_only.iss", '#define AppVersionString ').replace("\'", "")

                if (options['incrementVersion']) {
                    withNotifications(title: "Jenkins build configuration", printMessage: true, options: options, configuration: NotificationConfiguration.CREATE_GITHUB_NOTIFICATOR) {
                        GithubNotificator githubNotificator = new GithubNotificator(this, options)
                        githubNotificator.init(options)
                        options["githubNotificator"] = githubNotificator
                        githubNotificator.initPreBuild("${BUILD_URL}")
                        options.projectBranchName = githubNotificator.branchName
                    }
                    
                    if(env.BRANCH_NAME == "develop" && options.commitAuthor != "radeonprorender") {
                        // Do not have permissions to make a new commit
                        println "[INFO] Incrementing version of change made by ${options.commitAuthor}."
                        println "[INFO] Current USD plugin version: ${options.usdPluginVersion}"
                        println "[INFO] Current HdRPR plugin version: ${options.hdrprPluginVersion}"

                        def newUsdPluginVersion = version_inc(options.usdPluginVersion, 3)
                        def newHdrprPluginVersion = version_inc(options.hdrprPluginVersion, 3)
                        println "[INFO] New USD plugin version: ${newUsdPluginVersion}"
                        println "[INFO] New HdRPR plugin version: ${newHdrprPluginVersion}"
                        version_write("${env.WORKSPACE}\\RPRMayaUSD\\installation\\installation.iss", '#define AppVersionString ', "${newUsdPluginVersion}")
                        version_write("${env.WORKSPACE}\\RPRMayaUSD\\installation\\installation_hdrpr_only.iss", '#define AppVersionString ', "${newHdrprPluginVersion}")

                        options.usdPluginVersion = version_read("${env.WORKSPACE}\\RPRMayaUSD\\installation\\installation.iss", '#define AppVersionString ').replace("\'", "")
                        options.hdrprPluginVersion = version_read("${env.WORKSPACE}\\RPRMayaUSD\\installation\\installation_hdrpr_only.iss", '#define AppVersionString ').replace("\'", "")
                        println "[INFO] Updated USD plugin version: ${options.usdPluginVersion}"
                        println "[INFO] Updated HdRPR plugin version: ${options.hdrprPluginVersion}"

                        bat """
                            git add ${env.WORKSPACE}\\RPRMayaUSD\\installation\\installation.iss
                            git add ${env.WORKSPACE}\\RPRMayaUSD\\installation\\installation_hdrpr_only.iss
                            git commit -m "buildmaster: USD plugin version update to ${options.usdPluginVersion}. HdRPR plugin version update to ${options.hdrprPluginVersion}."
                            git push origin HEAD:develop
                        """

                        //get commit's sha which have to be build
                        options.commitSHA = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
                        options.projectBranch = options.commitSHA
                        println "[INFO] Project branch hash: ${options.projectBranch}"
                    } else {
                        if (options.commitMessage.contains("CIS:BUILD")) {
                            options['executeBuild'] = true
                        }

                        if (options.commitMessage.contains("CIS:TESTS")) {
                            options['executeBuild'] = true
                            options['executeTests'] = true
                        }
                        // get a list of tests from commit message for auto builds
                        options.tests = utils.getTestsFromCommitMessage(options.commitMessage)
                        println "[INFO] Test groups mentioned in commit message: ${options.tests}"
                    }
                } else {
                    options.projectBranchName = options.projectBranch
                }

                currentBuild.description = "<b>Project branch:</b> ${options.projectBranchName}<br/>"
                currentBuild.description += "<b>USD plugin version:</b> ${options.usdPluginVersion}<br/>"
                currentBuild.description += "<b>HdRPR plugin version:</b> ${options.hdrprPluginVersion}<br/>"
                currentBuild.description += "<b>Commit author:</b> ${options.commitAuthor}<br/>"
                currentBuild.description += "<b>Commit message:</b> ${options.commitMessage}<br/>"
                currentBuild.description += "<b>Commit SHA:</b> ${options.commitSHA}<br/>"
            }
        }
    }

    def tests = []
    options.timeouts = [:]

    withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.CONFIGURE_TESTS) {
        dir('jobs_test_usdmaya')  {
            checkoutScm(branchName: options.testsBranch, repositoryUrl: options.testRepo)

            options['testsBranch'] = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
            dir ('jobs_launcher') {
                options['jobsLauncherBranch'] = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
            }
            println "[INFO] Test branch hash: ${options['testsBranch']}"

            def packageInfo

            if (options.testsPackage != "none") {
                packageInfo = readJSON file: "jobs/${options.testsPackage}"
                options.isPackageSplitted = packageInfo["split"]
                // if it's build of manual job and package can be splitted - use list of tests which was specified in params (user can change list of tests before run build)
                if (options.forceBuild && options.isPackageSplitted && options.tests) {
                    options.testsPackage = "none"
                }
            }

            if (options.testsPackage != "none") {
                def tempTests = []

                if (options.isPackageSplitted) {
                    println("[INFO] Tests package '${options.testsPackage}' can be splitted")
                } else {
                    // save tests which user wants to run with non-splitted tests package
                    if (options.tests) {
                        tempTests = options.tests.split(" ") as List
                    }
                    println("[INFO] Tests package '${options.testsPackage}' can't be splitted")
                }

                // modify name of tests package if tests package is non-splitted (it will be use for run package few time with different engines)
                String modifiedPackageName = "${options.testsPackage}~"

                // receive list of group names from package
                List groupsFromPackage = []

                if (packageInfo["groups"] instanceof Map) {
                    groupsFromPackage = packageInfo["groups"].keySet() as List
                } else {
                    // iterate through all parts of package
                    packageInfo["groups"].each() {
                        groupsFromPackage.addAll(it.keySet() as List)
                    }
                }

                groupsFromPackage.each() {
                    if (options.isPackageSplitted) {
                        tempTests << it
                    } else {
                        if (tempTests.contains(it)) {
                            // add duplicated group name in name of package group name for exclude it
                            modifiedPackageName = "${modifiedPackageName},${it}"
                        }
                    }
                }
                options.tests = utils.uniteSuites(this, "jobs/weights.json", tempTests)
                options.tests.each() {
                    def xml_timeout = utils.getTimeoutFromXML(this, "${it}", "simpleRender.py", options.ADDITIONAL_XML_TIMEOUT)
                    options.timeouts["${it}"] = (xml_timeout > 0) ? xml_timeout : options.TEST_TIMEOUT
                }
                options.engines.each { engine ->
                    options.tests.each() {
                        tests << "${it}-${engine}"
                    }
                }

                modifiedPackageName = modifiedPackageName.replace('~,', '~')

                if (options.isPackageSplitted) {
                    options.testsPackage = "none"
                } else {
                    options.testsPackage = modifiedPackageName
                    // check that package is splitted to parts or not
                    if (packageInfo["groups"] instanceof Map) {
                        options.engines.each { engine ->
                            tests << "${modifiedPackageName}-${engine}"
                        } 
                        options.timeouts[options.testsPackage] = options.NON_SPLITTED_PACKAGE_TIMEOUT + options.ADDITIONAL_XML_TIMEOUT
                    } else {
                        // add group stub for each part of package
                        options.engines.each { engine ->
                            for (int i = 0; i < packageInfo["groups"].size(); i++) {
                                tests << "${modifiedPackageName}-${engine}".replace(".json", ".${i}.json")
                            }
                        }

                        for (int i = 0; i < packageInfo["groups"].size(); i++) {
                            options.timeouts[options.testsPackage.replace(".json", ".${i}.json")] = options.NON_SPLITTED_PACKAGE_TIMEOUT + options.ADDITIONAL_XML_TIMEOUT
                        }
                    }
                }
            } else if (options.tests) {
                options.tests = utils.uniteSuites(this, "jobs/weights.json", options.tests.split(" ") as List)
                options.tests.each() {
                    def xml_timeout = utils.getTimeoutFromXML(this, it, "simpleRender.py", options.ADDITIONAL_XML_TIMEOUT)
                    options.timeouts["${it}"] = (xml_timeout > 0) ? xml_timeout : options.TEST_TIMEOUT
                }
                options.engines.each { engine ->
                    options.tests.each() {
                        tests << "${it}-${engine}"
                    }
                }
            } else {
                options.executeTests = false
            }
            options.tests = tests
        }

        options.testsList = options.tests

        println "timeouts: ${options.timeouts}"
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

def executeDeploy(Map options, List platformList, List testResultList, String engine) {
    cleanWS()
    try {
        String engineName = options.displayingTestProfiles[engine]

        if (options['executeTests'] && testResultList) {
            withNotifications(title: "Building test report for ${engineName}", options: options, startUrl: "${BUILD_URL}", configuration: NotificationConfiguration.DOWNLOAD_TESTS_REPO) {
                checkoutScm(branchName: options.testsBranch, repositoryUrl: options.testRepo)
            }

            List lostStashes = []

            dir("summaryTestResults") {
                testResultList.each() {
                    if (it.endsWith(engine)) {
                        List testNameParts = it.replace("testResult-", "").split("-") as List

                        if (filter(options, testNameParts.get(0), testNameParts.get(1), testNameParts.get(2), engine)) {
                            return
                        }

                        String testName = testNameParts.subList(0, testNameParts.size() - 1).join("-")
                        dir(testName) {
                            try {
                                makeUnstash(name: "$it", storeOnNAS: options.storeOnNAS)
                            } catch(e) {
                                println("[ERROR] Failed to unstash ${it}")
                                lostStashes.add("'${testName}'")
                                println(e.toString())
                                println(e.getMessage())
                            }
                        }
                    }
                }
            }
            
            try {
                dir("jobs_launcher") {
                    bat """
                        count_lost_tests.bat \"${lostStashes}\" .. ..\\summaryTestResults \"${options.splitTestsExecution}\" \"${options.testsPackage}\" \"[]\" \"${engine}\" \"{}\"
                    """
                }
            } catch (e) {
                println("[ERROR] Can't generate number of lost tests")
            }

            try {
                boolean useTrackedMetrics = (env.JOB_NAME.contains("Weekly") || (env.JOB_NAME.contains("Manual") && options.testsPackageOriginal == "Full.json"))
                boolean saveTrackedMetrics = env.JOB_NAME.contains("Weekly")
                String metricsRemoteDir = "/volume1/Baselines/TrackedMetrics/USD-MayaPlugin/${engine}"

                GithubNotificator.updateStatus("Deploy", "Building test report for ${engineName}", "in_progress", options, NotificationConfiguration.BUILDING_REPORT, "${BUILD_URL}")
                
                if (useTrackedMetrics) {
                    utils.downloadMetrics(this, "summaryTestResults/tracked_metrics", "${metricsRemoteDir}/")
                }

                withEnv(["JOB_STARTED_TIME=${options.JOB_STARTED_TIME}", "BUILD_NAME=${options.baseBuildName}"]) {
                    dir("jobs_launcher") {
                        List retryInfoList = utils.deepcopyCollection(this, options.nodeRetry)
                        retryInfoList.each{ gpu ->
                            gpu['Tries'].each{ group ->
                                group.each{ groupKey, retries ->
                                    if (groupKey.endsWith(engine)) {
                                        List testNameParts = groupKey.split("-") as List
                                        String parsedName = testNameParts.subList(0, testNameParts.size() - 1).join("-")
                                        group[parsedName] = retries
                                    }
                                    group.remove(groupKey)
                                }
                            }
                            gpu['Tries'] = gpu['Tries'].findAll{ it.size() != 0 }
                        }
                        def retryInfo = JsonOutput.toJson(retryInfoList)
                        dir("..\\summaryTestResults") {
                            JSON jsonResponse = JSONSerializer.toJSON(retryInfo, new JsonConfig());
                            writeJSON file: 'retry_info.json', json: jsonResponse, pretty: 4
                        }
                        try {
                            bat "build_reports.bat ..\\summaryTestResults ${getReportBuildArgs(engineName, options)}"
                        } catch (e) {
                            String errorMessage = utils.getReportFailReason(e.getMessage())
                            GithubNotificator.updateStatus("Deploy", "Building test report for ${engineName}", "failure", options, errorMessage, "${BUILD_URL}")
                            if (utils.isReportFailCritical(e.getMessage())) {
                                throw e
                            } else {
                                currentBuild.result = "FAILURE"
                                options.problemMessageManager.saveGlobalFailReason(errorMessage)
                            }
                        }
                    }
                }

                if (saveTrackedMetrics) {
                    utils.uploadMetrics(this, "summaryTestResults/tracked_metrics", metricsRemoteDir)
                }
            } catch(e) {
                String errorMessage = utils.getReportFailReason(e.getMessage())
                options.problemMessageManager.saveSpecificFailReason(errorMessage, "Deploy")
                GithubNotificator.updateStatus("Deploy", "Building test report for ${engineName}", "failure", options, errorMessage, "${BUILD_URL}")
                println("[ERROR] Failed to build test report.")
                println(e.toString())
                println(e.getMessage())
                if (!options.testDataSaved && !options.storeOnNAS) {
                    try {
                        // Save test data for access it manually anyway
                        utils.publishReport(this, "${BUILD_URL}", "summaryTestResults", "summary_report.html, performance_report.html, compare_report.html", \
                            "Test Report ${engineName}", "Summary Report, Performance Report, Compare Report" , options.storeOnNAS, \
                            ["jenkinsBuildUrl": BUILD_URL, "jenkinsBuildName": currentBuild.displayName, "updatable": options.containsKey("reportUpdater")])

                        options.testDataSaved = true 
                    } catch(e1) {
                        println("[WARNING] Failed to publish test data.")
                        println(e.toString())
                        println(e.getMessage())
                    }
                }
                throw e
            }

            try {
                dir("jobs_launcher") {
                    bat "get_status.bat ..\\summaryTestResults"
                }
            } catch(e) {
                println("[ERROR] during slack status generation")
                println(e.toString())
                println(e.getMessage())
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
                }
                else if (summaryReport.failed > 0) {
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
                options["testsStatus-${engine}"] = readFile("summaryTestResults/slack_status.json")
            } catch(e) {
                println(e.toString())
                println(e.getMessage())
                options["testsStatus-${engine}"] = ""
            }

            withNotifications(title: "Building test report for ${engineName}", options: options, configuration: NotificationConfiguration.PUBLISH_REPORT) {
                utils.publishReport(this, "${BUILD_URL}", "summaryTestResults", "summary_report.html, performance_report.html, compare_report.html", \
                    "Test Report ${engineName}", "Summary Report, Performance Report, Compare Report" , options.storeOnNAS, \
                    ["jenkinsBuildUrl": BUILD_URL, "jenkinsBuildName": currentBuild.displayName, "updatable": options.containsKey("reportUpdater")])

                if (summaryTestResults) {
                    // add in description of status check information about tests statuses
                    // Example: Report was published successfully (passed: 69, failed: 11, error: 0)
                    GithubNotificator.updateStatus("Deploy", "Building test report for ${engineName}", "success", options, "${NotificationConfiguration.REPORT_PUBLISHED} Results: passed - ${summaryTestResults.passed}, failed - ${summaryTestResults.failed}, error - ${summaryTestResults.error}.", "${BUILD_URL}/Test_20Report")
                } else {
                    GithubNotificator.updateStatus("Deploy", "Building test report for ${engineName}", "success", options, NotificationConfiguration.REPORT_PUBLISHED, "${BUILD_URL}/Test_20Report")
                }
            }
        }
    } catch (e) {
        println(e.toString())
        println(e.getMessage())
        throw e
    } finally {
        if (!options.storeOnNAS) {
            utils.generateOverviewReport(this, this.&getReportBuildArgs, options)
        }
    }
}

def appendPlatform(String filteredPlatforms, String platform) {
    if (filteredPlatforms) {
        filteredPlatforms +=  ";" + platform
    } else  {
        filteredPlatforms += platform
    }
    return filteredPlatforms
}

def call(String projectRepo = "git@github.com:GPUOpen-LibrariesAndSDKs/RadeonProRenderMayaUSD.git",
        String projectBranch = "",
        String testsBranch = "master",
        String platforms = 'Windows:NVIDIA_RTX3080TI,AMD_RadeonVII,AMD_RX6800XT',
        String updateRefs = 'No',
        Boolean enableNotifications = true,
        Boolean incrementVersion = true,
        String renderDevice = "gpu",
        String testsPackage = "",
        String tests = "",
        String toolVersion = "2023",
        Boolean forceBuild = false,
        Boolean splitTestsExecution = true,
        String resX = '0',
        String resY = '0',
        String SPU = '25',
        String iter = '50',
        String theshold = '0.05',
        String customBuildLinkWindows = "",
        String enginesNames = "Northstar,HybridPro",
        String tester_tag = 'Maya',
        String mergeablePR = "",
        String parallelExecutionTypeString = "TakeAllNodes",
        Integer testCaseRetries = 3)
{
    ProblemMessageManager problemMessageManager = new ProblemMessageManager(this, currentBuild)
    Map options = [:]
    options["stage"] = "Init"
    options["problemMessageManager"] = problemMessageManager

    resX = (resX == 'Default') ? '0' : resX
    resY = (resY == 'Default') ? '0' : resY
    SPU = (SPU == 'Default') ? '25' : SPU
    iter = (iter == 'Default') ? '50' : iter
    theshold = (theshold == 'Default') ? '0.05' : theshold
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
            def formattedEngines = []
            enginesNames.each {
                formattedEngines.add(it.replace(' ', '_'))
            }

            Boolean isPreBuilt = customBuildLinkWindows

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
            println "Split tests execution: ${splitTestsExecution}"
            println "Tests execution type: ${parallelExecutionType}"

            String prRepoName = ""
            String prBranchName = ""
            if (mergeablePR) {
                String[] prInfo = mergeablePR.split(";")
                prRepoName = prInfo[0]
                prBranchName = prInfo[1]
            }

            options << [configuration: PIPELINE_CONFIGURATION,
                        projectRepo:projectRepo,
                        projectBranch:projectBranch,
                        testRepo:"git@github.com:luxteam/jobs_test_usdmaya.git",
                        testsBranch:testsBranch,
                        updateRefs:updateRefs,
                        enableNotifications:enableNotifications,
                        PRJ_NAME:"RPRMayaUSD",
                        PRJ_ROOT:"rpr-plugins",
                        incrementVersion:incrementVersion,
                        renderDevice:renderDevice,
                        testsPackage:testsPackage,
                        testsPackageOriginal:testsPackage,
                        tests:tests,
                        toolVersion:toolVersion,
                        executeBuild:false,
                        executeTests:isPreBuilt,
                        isPreBuilt:isPreBuilt,
                        forceBuild:forceBuild,
                        reportName:'Test_20Report',
                        splitTestsExecution:splitTestsExecution,
                        gpusCount:gpusCount,
                        BUILD_TIMEOUT: 180,
                        TEST_TIMEOUT:150,
                        ADDITIONAL_XML_TIMEOUT:15,
                        NON_SPLITTED_PACKAGE_TIMEOUT:75,
                        DEPLOY_TIMEOUT:180,
                        BUILDER_TAG:"MayaUSDBuilder",
                        TESTER_TAG:tester_tag,
                        resX: resX,
                        resY: resY,
                        SPU: SPU,
                        iter: iter,
                        theshold: theshold,
                        customBuildLinkWindows: customBuildLinkWindows,
                        engines: formattedEngines,
                        nodeRetry: nodeRetry,
                        errorsInSuccession: errorsInSuccession,
                        problemMessageManager: problemMessageManager,
                        platforms:platforms,
                        prRepoName:prRepoName,
                        prBranchName:prBranchName,
                        parallelExecutionType:parallelExecutionType,
                        parallelExecutionTypeString: parallelExecutionTypeString,
                        testCaseRetries:testCaseRetries,
                        storeOnNAS: true,
                        flexibleUpdates: true,
                        skipCallback: this.&filter
                        ]
        }

        multiplatform_pipeline(platforms, this.&executePreBuild, this.&executeBuild, this.&executeTests, this.&executeDeploy, options)
    } catch(e) {
        currentBuild.result = "FAILURE"
        println(e.toString())
        println(e.getMessage())
        throw e
    } finally {
        String problemMessage = options.problemMessageManager.publishMessages()
    }

}

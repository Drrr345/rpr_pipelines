import groovy.transform.Field
import groovy.json.JsonOutput
import utils
import net.sf.json.JSON
import net.sf.json.JSONSerializer
import net.sf.json.JsonConfig
import TestsExecutionType
import java.util.concurrent.atomic.AtomicInteger
import java.text.SimpleDateFormat


@Field final PipelineConfiguration PIPELINE_CONFIGURATION = new PipelineConfiguration(
    supportedOS: ["Windows", "OSX", "Ubuntu20"],
    productExtensions: ["Windows": "zip", "OSX": "zip", "Ubuntu20": "zip"],
    artifactNameBase: "BlenderUSDHydraAddon",
    testProfile: "engine",
    displayingProfilesMapping: [
        "engine": [
            "HdRprPlugin": "RPR",
            "HdStormRendererPlugin": "GL",
            "Hybrid": "Hybrid",
            "HdPrmanLoaderRendererPlugin": "Prman"
        ]
    ]
)

@Field final String PROJECT_REPO = "git@github.com:GPUOpen-LibrariesAndSDKs/BlenderUSDHydraAddon.git"


Boolean filterTests(Map options, String asicName, String osName, String testName, String engine) {
    if (osName.startsWith("Ubuntu") && engine == "HdStormRendererPlugin") {
        return true
    }

    if (testName == "RenderMan" && engine != "HdRprPlugin") {
        return true
    }

    // run HybridPro only on RTX cards
    return (engine == "Hybrid" && !(asicName.contains("RTX") || asicName.contains("AMD_RX6800")))
}


def executeGenTestRefCommand(String osName, Map options, Boolean delete) {
    dir('scripts') {
        switch(osName) {
            case 'Windows':
                bat """
                    make_results_baseline.bat ${delete}
                """
                break
            // OSX & Ubuntu
            default:
                sh """
                    ./make_results_baseline.sh ${delete}
                """
        }
    }
}

def buildRenderCache(String osName, String toolVersion, String log_name, Integer currentTry) {
    try {
        dir("scripts") {
            switch(osName) {
                case 'Windows':
                    bat "build_cache.bat ${toolVersion} >> \"..\\${log_name}_${currentTry}.cb.log\"  2>&1"
                    break
                default:
                    sh "./build_cache.sh ${toolVersion} >> \"../${log_name}_${currentTry}.cb.log\" 2>&1"        
            }
        }
    } catch (e) {
        String cacheBuildingLog = readFile("${log_name}_${currentTry}.cb.log")
        if (cacheBuildingLog.contains("engine not found 'HdUSD'")) {
            throw new ExpectedExceptionWrapper(NotificationConfiguration.PLUGIN_NOT_FOUND, e)
        }
        throw e
    }
}

def executeTestCommand(String osName, String asicName, Map options) {
    def testTimeout = options.timeouts["${options.tests}"]
    String testsNames
    String testsPackageName

    if (options.tests != "none" && !options.isPackageSplitted) {
        if (options.tests.contains(".json")) {
            // if tests package isn't splitted and it's execution of this package - replace test group for non-splitted package by empty string
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
                    run.bat \"${testsPackageName}\" \"${testsNames}\" ${options.resX} ${options.resY} ${options.iter} ${options.threshold} ${options.engine} ${options.toolVersion} ${options.testCaseRetries} ${options.updateRefs} 1>> \"..\\${options.stageName}_${options.currentTry}.log\"  2>&1
                """
            }
            break
        // OSX & Ubuntu
        default:
            dir("scripts") {
                sh """
                    ./run.sh \"${testsPackageName}\" \"${testsNames}\" ${options.resX} ${options.resY} ${options.iter} ${options.threshold} ${options.engine} ${options.toolVersion} ${options.testCaseRetries} ${options.updateRefs} 1>> \"../${options.stageName}_${options.currentTry}.log\" 2>&1
                """
            }
        }
    }
}


def executeTests(String osName, String asicName, Map options) {
    // used for mark stash results or not. It needed for not stashing failed tasks which will be retried.
    Boolean stashResults = true

    // FIXME: wait for Matlib optimization
    if (env.NODE_NAME == "PC-RENDERER-KABUL-WIN10") {
        if (options.tests.contains("Export_Import")) {
            throw new ExpectedExceptionWrapper(
                "System doesn't support Export_Import group", 
                new Exception("System doesn't support Export_Import group")
            )
        }
    }

    // FIXME: Wait for new drivers / new plugin version to check difference
    if (env.NODE_NAME == "PC-TESTER-TOKYO-WIN10") {
        if (options.tests.contains("Camera") || options.tests.contains("regression.0")) {
            throw new ExpectedExceptionWrapper(
                "System doesn't support Camera group", 
                new Exception("System doesn't support Camera group")
            )
        }
    }

    if (env.NODE_NAME == "PC-SR-ONTARIO-6800XT-WIN10") {
        if (options.tests.contains("USD_Nodes")) {
            throw new ExpectedExceptionWrapper(
                "System doesn't support USD_Nodes group", 
                new Exception("System doesn't support USD_Nodes group")
            )
        }
    }

    try {
        withNotifications(title: options["stageName"], options: options, logUrl: "${BUILD_URL}", configuration: NotificationConfiguration.DOWNLOAD_TESTS_REPO) {
            timeout(time: "5", unit: "MINUTES") {
                cleanWS(osName)
                checkoutScm(branchName: options.testsBranch, repositoryUrl: options.testRepo)
            }
        }

        withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.DOWNLOAD_SCENES) {
            String assets_dir = isUnix() ? "${CIS_TOOLS}/../TestResources/usd_blender_autotests_assets" : "/mnt/c/TestResources/usd_blender_autotests_assets"
            downloadFiles("/volume1/web/Assets/usd_blender_autotests/", assets_dir)
        }

        try {
            Boolean newPluginInstalled = false
            withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.INSTALL_PLUGIN) {
                timeout(time: "12", unit: "MINUTES") {
                    getProduct(osName, options, "", false)
                    newPluginInstalled = installBlenderAddon(osName, 'hdusd', options.toolVersion, options)
                    println "[INFO] Install function on ${env.NODE_NAME} return ${newPluginInstalled}"
                }
            }
        
            withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.BUILD_CACHE) {
                if (newPluginInstalled) {                         
                    timeout(time: "12", unit: "MINUTES") {
                        buildRenderCache(osName, options.toolVersion, options.stageName, options.currentTry)
                        String cacheImgPath = "./Work/Results/BlenderUSDHydra/cache_building.jpg"
                        if (!fileExists(cacheImgPath)) {
                            throw new ExpectedExceptionWrapper(NotificationConfiguration.NO_OUTPUT_IMAGE, new Exception(NotificationConfiguration.NO_OUTPUT_IMAGE))
                        }
                    }
                }
            }  
        } catch(e) {
            println(e.toString())
            println("[ERROR] Failed to install plugin on ${env.NODE_NAME}")
            // deinstalling broken addon
            installBlenderAddon(osName, 'hdusd', options.toolVersion, options, false, true)
            // remove installer of broken addon
            removeInstaller(osName: osName, options: options, extension: "zip")
            throw e
        }

        String enginePostfix = ""
        String REF_PATH_PROFILE="/volume1/Baselines/usd_blender_autotests/${asicName}-${osName}"
        switch(options.engine) {
            case 'HdRprPlugin':
                enginePostfix = "RPR"
                break
            case 'HdStormRendererPlugin':
                enginePostfix = "GL"
                break
            case 'Hybrid':
                enginePostfix = "Hybrid"
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
                    case 'Windows':
                        bat "if exist Work\\GeneratedBaselines rmdir /Q /S Work\\GeneratedBaselines"
                        break
                    default:
                        sh "rm -rf ./Work/GeneratedBaselines"        
                }
            }
        } else {
            // TODO: receivebaseline for json suite
            withNotifications(title: options["stageName"], printMessage: true, options: options, configuration: NotificationConfiguration.COPY_BASELINES) {
                String baseline_dir = isUnix() ? "${CIS_TOOLS}/../TestResources/usd_blender_autotests_baselines" : "/mnt/c/TestResources/usd_blender_autotests_baselines"
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
                    if (fileExists("Results/BlenderUSDHydra/session_report.json")) {

                        def sessionReport = null
                        sessionReport = readJSON file: 'Results/BlenderUSDHydra/session_report.json'

                        if (sessionReport.summary.error > 0) {
                            GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, NotificationConfiguration.SOME_TESTS_ERRORED, "${BUILD_URL}")
                        } else if (sessionReport.summary.failed > 0) {
                            GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, NotificationConfiguration.SOME_TESTS_FAILED, "${BUILD_URL}")
                        } else {
                            GithubNotificator.updateStatus("Test", options['stageName'], "success", options, NotificationConfiguration.ALL_TESTS_PASSED, "${BUILD_URL}")
                        }

                        println "Stashing test results to : ${options.testResultsName}"
                        utils.stashTestData(this, options, options.storeOnNAS)

                        // deinstalling broken addon
                        // if test group is fully errored or number of test cases is equal to zero
                        if (sessionReport.summary.total == sessionReport.summary.error + sessionReport.summary.skipped || sessionReport.summary.total == 0) {
                            // check that group isn't fully skipped
                            if (sessionReport.summary.total != sessionReport.summary.skipped || sessionReport.summary.total == 0) {
                                installBlenderAddon(osName, 'hdusd', options.toolVersion, options, false, true)
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


def executeBuildWindows(String osName, Map options, String pyVersion = "3.9") {
    try {
        def additionalKeys = "--prman --prman-location \"C:\\Program Files\\Pixar\\RenderManProServer-24.4\""
        dir('BlenderUSDHydraAddon') {
            GithubNotificator.updateStatus("Build", "Windows", "in_progress", options, NotificationConfiguration.BUILD_SOURCE_CODE_START_MESSAGE, "${BUILD_URL}/artifact/Build-Windows.log")

            def paths

            // Python 3.9 build requires Python 3.9.10
            if (pyVersion == "3.9") {
                paths = ["C:\\Python3910\\",
                             "C:\\Python3910\\scripts\\",
                             "C:\\CMake323\\bin"]
            } else {
                paths = ["C:\\Python${pyVersion.replace(".","")}\\",
                             "C:\\Python${pyVersion.replace(".","")}\\scripts\\",
                             "C:\\CMake323\\bin"]
            }

            withEnv(["PATH=${paths.join(";")};${PATH}"]) {
                if (options.rebuildDeps) {
                    bat """
                        if exist ..\\bin rmdir /Q /S ..\\bin
                        if exist ..\\libs rmdir /Q /S ..\\libs
                        python --version >> ..\\${STAGE_NAME}_${pyVersion}.log  2>&1
                        python -m pip install -r requirements.txt >> ../${STAGE_NAME}_${pyVersion}.log 2>&1
                        call "C:\\Program Files (x86)\\Microsoft Visual Studio\\2019\\Professional\\VC\\Auxiliary\\Build\\vcvarsall.bat" amd64 >> ..\\${STAGE_NAME}_${pyVersion}.log  2>&1
                        waitfor 1 /t 10 2>NUL || type nul>nul
                        python tools\\build.py -all -clean -bin-dir ..\\bin -G "Visual Studio 16 2019" ${additionalKeys} >> ..\\${STAGE_NAME}_${pyVersion}.log  2>&1
                    """
                    
                    if (options.updateDeps) {
                        uploadFiles("../bin/*", "/volume1/CIS/${options.PRJ_ROOT}/${options.PRJ_NAME}/3rdparty/${osName}_${pyVersion}/bin")
                    }
                } else {
                    bat """
                        python --version >> ..\\${STAGE_NAME}_${pyVersion}.log  2>&1
                        call "C:\\Program Files (x86)\\Microsoft Visual Studio\\2019\\Professional\\VC\\Auxiliary\\Build\\vcvarsall.bat" amd64 >> ..\\${STAGE_NAME}_${pyVersion}.log  2>&1
                        waitfor 1 /t 10 2>NUL || type nul>nul
                        python tools\\build.py -libs -mx-classes -addon -bin-dir ..\\bin -G "Visual Studio 16 2019" ${additionalKeys} >> ..\\${STAGE_NAME}_${pyVersion}.log  2>&1
                    """
                }
            }
            dir("install") {
                println "Stashing Artifact for Python ${pyVersion}"

                String ARTIFACT_NAME  = "BlenderUSDHydraAddon_${options.pluginVersion}_${pyVersion}_Windows"

                ARTIFACT_NAME += options.branch_postfix ? ".(${options.branch_postfix}).zip" : ".zip"

                bat """
                    rename hdusd*.zip ${ARTIFACT_NAME}
                """

                String artifactURL = makeArchiveArtifacts(name: ARTIFACT_NAME, storeOnNAS: options.storeOnNAS)
                
                if (options.toolVersion == "3.0" && pyVersion == "3.9" || options.toolVersion != "3.0" && pyVersion != "3.9") {
                    bat """
                        rename ${ARTIFACT_NAME} BlenderUSDHydraAddon_Windows.zip
                    """

                    makeStash(includes: "BlenderUSDHydraAddon_Windows.zip", name: getProduct.getStashName("Windows", options), preZip: false, storeOnNAS: options.storeOnNAS)

                    GithubNotificator.updateStatus("Build", "Windows", "success", options, NotificationConfiguration.BUILD_SOURCE_CODE_END_MESSAGE, artifactURL)
                }
            }
        }
    } catch(e) {
        println "[ERROR] Python ${pyVersion} build was failed"
        if (options.toolVersion == "3.0" && pyVersion == "3.9" || options.toolVersion != "3.0" && pyVersion != "3.9") {
            println "[ERROR] Failed main version of build"
            throw e
        }
    }  finally {
        archiveArtifacts artifacts: "*.log ", allowEmptyArchive: true
    }
}


def executeBuildOSX(String osName, Map options) {
}


def executeBuildLinux(String osName, Map options, String pyVersion = "3.9") {
    try {
        def additionalKeys = "--prman --prman-location \"/opt/pixar/RenderManProServer-24.4\""
        dir('BlenderUSDHydraAddon') {
            GithubNotificator.updateStatus("Build", "${osName}", "in_progress", options, NotificationConfiguration.BUILD_SOURCE_CODE_START_MESSAGE, "${BUILD_URL}/artifact/Build-${osName}.log")
            if (options.rebuildDeps) {
                sh """
                    rm -rf ../bin
                    rm -rf ../libs
                """
                sh """#!/bin/bash
                    virtualenv -p python${pyVersion} venv >> ../${STAGE_NAME}_${pyVersion}.log  2>&1
                    source venv/bin/activate >> ../${STAGE_NAME}_${pyVersion}.log 2>&1
                    export CPATH=/usr/include/python${pyVersion} >> ../${STAGE_NAME}_${pyVersion}.log 2>&1
                    export OS= >> ../${STAGE_NAME}_${pyVersion}.log 2>&1
                    python --version >> ../${STAGE_NAME}_${pyVersion}.log 2>&1
                    python -m pip install -r requirements.txt >> ../${STAGE_NAME}_${pyVersion}.log  2>&1
                    pip install -r requirements.txt >> ../${STAGE_NAME}_${pyVersion}.log  2>&1
                    python tools/build.py -all -clean -bin-dir ../bin ${additionalKeys} >> ../${STAGE_NAME}_${pyVersion}.log 2>&1
                """
                
                if (options.updateDeps) {
                    uploadFiles("../bin/", "/volume1/CIS/${options.PRJ_ROOT}/${options.PRJ_NAME}/3rdparty/${osName}_${pyVersion}/bin")
                }
            } else {
                sh """#!/bin/bash
                    virtualenv -p python${pyVersion} venv >> ../${STAGE_NAME}_${pyVersion}.log 2>&1
                    source venv/bin/activate >> ../${STAGE_NAME}_${pyVersion}.log 2>&1
                    export CPATH=/usr/include/python${pyVersion} >> ../${STAGE_NAME}_${pyVersion}.log 2>&1
                    export OS=
                    python${pyVersion} --version >> ../${STAGE_NAME}_${pyVersion}.log  2>&1
                    python --version >> ../${STAGE_NAME}_${pyVersion}.log 2>&1
                    python -m pip install -r requirements.txt >> ../${STAGE_NAME}_${pyVersion}.log 2>&1
                    pip install -r requirements.txt >> ../${STAGE_NAME}_${pyVersion}.log 2>&1
                    python${pyVersion} tools/build.py -libs -mx-classes -addon -bin-dir ../bin ${additionalKeys} >> ../${STAGE_NAME}_${pyVersion}.log 2>&1
                """
            }

            dir("install") {
                println "Stashing Artifact for Python ${pyVersion}"

                String ARTIFACT_NAME  = "BlenderUSDHydraAddon_${options.pluginVersion}_${pyVersion}_${osName}"

                ARTIFACT_NAME += options.branch_postfix ? ".${options.branch_postfix}.zip" : ".zip"

                sh """
                    mv hdusd*.zip ${ARTIFACT_NAME}
                """
                
                String artifactURL = makeArchiveArtifacts(name: ARTIFACT_NAME, storeOnNAS: options.storeOnNAS)

                sh """
                    mv BlenderUSDHydraAddon*.zip BlenderUSDHydraAddon_${osName}.zip
                """

                if (options.toolVersion == "3.0" && pyVersion == "3.9" || options.toolVersion != "3.0" && pyVersion != "3.9") {
                    makeStash(includes: "BlenderUSDHydraAddon_${osName}.zip", name: getProduct.getStashName(osName, options), preZip: false, storeOnNAS: options.storeOnNAS)

                    GithubNotificator.updateStatus("Build", "${osName}", "success", options, NotificationConfiguration.BUILD_SOURCE_CODE_END_MESSAGE, artifactURL)
                }
            }
        }
    } catch(e) {
        println "[ERROR] Python ${pyVersion} build was failed"
        if (options.toolVersion == "3.0" && pyVersion == "3.9" || options.toolVersion != "3.0" && pyVersion != "3.9") {
            println "[ERROR] Failed main version of build"
            throw e
        }
    } finally {
        archiveArtifacts artifacts: "*.log ", allowEmptyArchive: true
    }
}

def executeBuild(String osName, Map options) {
    try {
        def pyVersions = ["3.9"]
        (options.toolVersion == "3.0") ?: pyVersions << "3.10"

        pyVersions.each() {
            cleanWS(osName)
            if (!options.rebuildDeps) {
                downloadFiles("/volume1/CIS/${options.PRJ_ROOT}/${options.PRJ_NAME}/3rdparty/${osName}_${it}/bin", ".")

                dir("bin") {
                    def files = findFiles()

                    for (file in files) {
                        if (file.name == "USD") {
                            def dateFormat = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss")
                            def formattedTime = dateFormat.format(new Date(file.lastModified))
                            currentBuild.description += "<b>Dependencies creation time (${osName}):</b> ${formattedTime}<br/>"
                            break
                        }
                    }
                }
            } else {
                currentBuild.description += "<b>Rebuild dependencies (${osName}):</b><br/>"
            }

            dir("BlenderUSDHydraAddon") {
                withNotifications(title: osName, options: options, configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
                    checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo, prBranchName: options.prBranchName, prRepoName: options.prRepoName)
                }

                if (env.BRANCH_NAME && env.BRANCH_NAME.startsWith(hybrid_to_blender_workflow.BRANCH_NAME_PREFIX)) {
                    dir("deps/HdRPR/deps/RPR") {
                        hybrid_to_blender_workflow.replaceHybrid(osName, options)
                    }
                }
            }

            outputEnvironmentInfo(osName)

            withNotifications(title: osName, options: options, configuration: NotificationConfiguration.BUILD_SOURCE_CODE) {
                switch(osName) {
                    case "Windows":
                        executeBuildWindows(osName, options, it)
                        break
                    case "OSX":
                        println("Unsupported OS")
                        break
                    default:
                        executeBuildLinux(osName, options, it)                
                }
            }
            options[getProduct.getIdentificatorKey(osName, options)] = options.commitSHA
        }
    } catch (e) {
        throw e
    }
}

def getReportBuildArgs(String engineName, Map options) {
    boolean collectTrackedMetrics = (env.JOB_NAME.contains("Weekly") || (env.JOB_NAME.contains("Manual") && options.testsPackageOriginal == "Full.json"))

    if (options["isPreBuilt"]) {
        return """${utils.escapeCharsByUnicode("Blender ")}${options.toolVersion} "PreBuilt" "PreBuilt" "PreBuilt" \"${utils.escapeCharsByUnicode(engineName)}\" ${collectTrackedMetrics ? env.BUILD_NUMBER : ""}"""
    } else {
        return """${utils.escapeCharsByUnicode("Blender ")}${options.toolVersion} ${options.commitSHA} ${options.projectBranchName} \"${utils.escapeCharsByUnicode(options.commitMessage)}\" \"${utils.escapeCharsByUnicode(engineName)}\" ${collectTrackedMetrics ? env.BUILD_NUMBER : ""}"""
    }
}

def executePreBuild(Map options)
{
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
            options.executeBuild = true
            options.executeTests = true
            options.testsPackage = "regression.json"
        } else if (env.BRANCH_NAME == "master") {
            println "[INFO] ${env.BRANCH_NAME} branch was detected"
            options['executeBuild'] = true
            options['executeTests'] = true
            options['testsPackage'] = "regression.json"
        } else {
            println "[INFO] ${env.BRANCH_NAME} branch was detected"
            options['executeBuild'] = true
            options['executeTests'] = true
            options['testsPackage'] = "regression.json"
        }
    }

    // branch postfix
    options["branch_postfix"] = ""
    if (env.BRANCH_NAME && env.BRANCH_NAME != "master") {
        options["branch_postfix"] = env.BRANCH_NAME.replace('/', '-')
    } else if (options.projectBranch && options.projectBranch != "master" && options.projectBranch != "develop") {
        options["branch_postfix"] = options.projectBranch.replace('/', '-')
    }

    if (!options['isPreBuilt']) {
        dir('BlenderUSDHydraAddon') {
            withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
                checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo, prBranchName: options.prBranchName, prRepoName: options.prRepoName, disableSubmodules: true)
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

            withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.INCREMENT_VERSION) {
                options.pluginVersion = version_read("${env.WORKSPACE}\\BlenderUSDHydraAddon\\src\\hdusd\\__init__.py", '"version": (', ', ').replace(', ', '.')

                if (options['incrementVersion']) {
                    withNotifications(title: "Jenkins build configuration", printMessage: true, options: options, configuration: NotificationConfiguration.CREATE_GITHUB_NOTIFICATOR) {
                        GithubNotificator githubNotificator = new GithubNotificator(this, options)
                        githubNotificator.init(options)
                        options["githubNotificator"] = githubNotificator
                        githubNotificator.initPreBuild("${BUILD_URL}")
                        options.projectBranchName = githubNotificator.branchName
                    }
                    
                    if (env.BRANCH_NAME == "master" && options.commitAuthor != "radeonprorender") {

                        options.pluginVersion = version_read("${env.WORKSPACE}\\BlenderUSDHydraAddon\\src\\hdusd\\__init__.py", '"version": (', ', ')
                        println "[INFO] Incrementing version of change made by ${options.commitAuthor}."
                        println "[INFO] Current build version: ${options.pluginVersion}"

                        def new_version = version_inc(options.pluginVersion, 3, ', ')
                        println "[INFO] New build version: ${new_version}"
                        version_write("${env.WORKSPACE}\\BlenderUSDHydraAddon\\src\\hdusd\\__init__.py", '"version": (', new_version, ', ')

                        options.pluginVersion = version_read("${env.WORKSPACE}\\BlenderUSDHydraAddon\\src\\hdusd\\__init__.py", '"version": (', ', ', "true").replace(', ', '.')
                        println "[INFO] Updated build version: ${options.pluginVersion}"

                        bat """
                            git add src/hdusd/__init__.py
                            git commit -m "buildmaster: version update to ${options.pluginVersion}"
                            git push origin HEAD:master
                        """

                        //get commit's sha which have to be build
                        options.commitSHA = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
                        options.projectBranch = options.commitSHA
                        println "[INFO] Project branch hash: ${options.projectBranch}"

                        def possiblePRNumber = (options.commitMessage =~ /\(#\d+\)/).findAll()
                        
                        if (possiblePRNumber.size() > 0) {
                            GithubApiProvider apiProvider = new GithubApiProvider(this)

                            def prNumber = possiblePRNumber[possiblePRNumber.size() - 1].replace("#", "").replace("(", "").replace(")", "")
                            def prInfo = apiProvider.getPullRequest(options["projectRepo"].replace("git@github.com:", "https://github.com/").replaceAll(".git\$", "") + "/pull/${prNumber}")

                            if (prInfo["body"].contains("CIS:REBUILD_DEPS")) {
                                options['rebuildDeps'] = true
                                options['updateDeps'] = true
                            }
                        }
                    } else {
                        if (options.githubNotificator && options.githubNotificator.prDescription) {
                            println("[INFO] PR description: ${options.githubNotificator.prDescription}")

                            if (options.githubNotificator.prDescription.contains("CIS:BUILD")) {
                                options['executeBuild'] = true
                            }

                            if (options.githubNotificator.prDescription.contains("CIS:TESTS")) {
                                options['executeBuild'] = true
                                options['executeTests'] = true
                            }

                            if (options.githubNotificator.prDescription.contains("CIS:REBUILD_DEPS")) {
                                options['rebuildDeps'] = true
                            }
                        }

                        // TODO: replace by parsing of PR description
                        // get a list of tests from commit message for auto builds
                        options.tests = utils.getTestsFromCommitMessage(options.commitMessage)
                        println "[INFO] Test groups mentioned in commit message: ${options.tests}"
                    }
                } else {
                    options.projectBranchName = options.projectBranch
                }

                currentBuild.description = "<b>Project branch:</b> ${options.projectBranch}<br/>"
                currentBuild.description += "<b>Version:</b> ${options.pluginVersion}<br/>"
                currentBuild.description += "<b>Commit author:</b> ${options.commitAuthor}<br/>"
                currentBuild.description += "<b>Commit message:</b> ${options.commitMessage}<br/>"
                currentBuild.description += "<b>Commit SHA:</b> ${options.commitSHA}<br/>"
            }
        }
    }

    def tests = []
    options.timeouts = [:]

    withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.CONFIGURE_TESTS) {
        dir('jobs_test_usdblender') {
            checkoutScm(branchName: options.testsBranch, repositoryUrl: options.testRepo)

            options['testsBranch'] = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
            dir('jobs_launcher') {
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
                packageInfo["groups"].each() {
                    if (options.isPackageSplitted) {
                        tempTests << it.key
                    } else {
                        if (tempTests.contains(it.key)) {
                            // add duplicated group name in name of package group name for exclude it
                            modifiedPackageName = "${modifiedPackageName},${it.key}"
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
                    options.engines.each { engine ->
                        tests << "${modifiedPackageName}-${engine}"
                    }
                    options.timeouts[options.testsPackage] = options.NON_SPLITTED_PACKAGE_TIMEOUT + options.ADDITIONAL_XML_TIMEOUT
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

    if (env.BRANCH_NAME && env.BRANCH_NAME == "master") {
        // if something was merged into master branch it could trigger build in master branch of autojob
        hybrid_to_blender_workflow.clearOldBranches("BlenderUSDHydraAddon", PROJECT_REPO, options)
    }

    if (env.BRANCH_NAME && env.BRANCH_NAME.startsWith(hybrid_to_blender_workflow.BRANCH_NAME_PREFIX)) {
        // rebuild deps if new HybridPro is being tested
        options["rebuildDeps"] = true
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

                        if (filterTests(options, testNameParts.get(0), testNameParts.get(1), testNameParts.get(2), engine)) {
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
                        count_lost_tests.bat \"${lostStashes}\" .. ..\\summaryTestResults \"${options.splitTestsExecution}\" \"${options.testsPackage}\" \"[]\" \"${it}\" \"{}\"
                    """
                }
            } catch (e) {
                println("[ERROR] Can't generate number of lost tests")
            }

            String branchName = env.BRANCH_NAME ?: options.projectBranch
            boolean useTrackedMetrics = (env.JOB_NAME.contains("Weekly") || (env.JOB_NAME.contains("Manual") && options.testsPackageOriginal == "Full.json"))
            boolean saveTrackedMetrics = env.JOB_NAME.contains("Weekly")
            String metricsRemoteDir = "/volume1/Baselines/TrackedMetrics/USD-BlenderPlugin/${engine}"

            if (useTrackedMetrics) {
                utils.downloadMetrics(this, "summaryTestResults/tracked_metrics", "${metricsRemoteDir}/")
            }
            
            try {
                GithubNotificator.updateStatus("Deploy", "Building test report for ${engineName}", "in_progress", options, NotificationConfiguration.BUILDING_REPORT, "${BUILD_URL}")
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
                if (!options.testDataSaved) {
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
                println("[ERROR] Failed to generate slack status.")
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

            println "BUILD RESULT: ${currentBuild.result}"
            println "BUILD CURRENT RESULT: ${currentBuild.currentResult}"
        }
    } catch(e) {
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
    } else {
        filteredPlatforms += platform
    }
    return filteredPlatforms
}


def call(String projectRepo = PROJECT_REPO,
    String projectBranch = "",
    String testsBranch = "master",
    String platforms = 'Windows:AMD_RadeonVII,AMD_RX6800XT,NVIDIA_RTX3080TI',
    Boolean rebuildDeps = false,
    Boolean updateDeps = false,
    String updateRefs = 'No',
    Boolean enableNotifications = true,
    Boolean incrementVersion = true,
    String testsPackage = "",
    String tests = "",
    Boolean forceBuild = false,
    Boolean splitTestsExecution = true,
    String resX = '0',
    String resY = '0',
    String iter = '50',
    String threshold = '0.05',
    String customBuildLinkWindows = "",
    String customBuildLinkUbuntu20 = "",
    String customBuildLinkOSX = "",
    String enginesNames = "RPR,GL,Hybrid",
    String tester_tag = "Blender",
    String toolVersion = "3.2",
    String mergeablePR = "",
    String parallelExecutionTypeString = "TakeAllNodes",
    Integer testCaseRetries = 3
    )
{
    ProblemMessageManager problemMessageManager = new ProblemMessageManager(this, currentBuild)
    Map options = [:]
    options["stage"] = "Init"
    options["problemMessageManager"] = problemMessageManager

    resX = (resX == 'Default') ? '0' : resX
    resY = (resY == 'Default') ? '0' : resY
    iter = (iter == 'Default') ? '50' : iter
    threshold = (threshold == 'Default') ? '0.05' : threshold
    def nodeRetry = []
    Map errorsInSuccession = [:]

    try {
        withNotifications(options: options, configuration: NotificationConfiguration.INITIALIZATION) {
            withNotifications(options: options, configuration: NotificationConfiguration.DELEGATES_PARAM) {
                if (!enginesNames) {
                    throw new Exception()
                }
            }

            if (env.BRANCH_NAME && env.BRANCH_NAME.startsWith(hybrid_to_blender_workflow.BRANCH_NAME_PREFIX)) {
                enginesNames = "Hybrid"
            }

            enginesNames = enginesNames.split(",") as List
            def formattedEngines = []
            enginesNames.each {
                formattedEngines.add((it == "RPR") ? "HdRprPlugin" : ((it == "GL") ? "HdStormRendererPlugin" : ((it == "Prman") ? "HdPrmanLoaderRendererPlugin" : "Hybrid")))
            }

            Boolean isPreBuilt = customBuildLinkWindows || customBuildLinkOSX || customBuildLinkUbuntu20

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
                        BUILD_TIMEOUT:120,
                        projectRepo:projectRepo,
                        projectBranch:projectBranch,
                        testRepo:"git@github.com:luxteam/jobs_test_usdblender.git",
                        testsBranch:testsBranch,
                        updateRefs:updateRefs,
                        enableNotifications:enableNotifications,
                        PRJ_NAME:"BlenderUSDHydraPlugin",
                        PRJ_ROOT:"rpr-plugins",
                        incrementVersion:incrementVersion,
                        rebuildDeps:rebuildDeps,
                        updateDeps:updateDeps,
                        testsPackage:testsPackage,
                        tests:tests,
                        toolVersion:toolVersion,
                        isPreBuilt:isPreBuilt,
                        forceBuild:forceBuild,
                        reportName:'Test_20Report',
                        splitTestsExecution:splitTestsExecution,
                        gpusCount:gpusCount,
                        TEST_TIMEOUT:240,
                        ADDITIONAL_XML_TIMEOUT:30,
                        NON_SPLITTED_PACKAGE_TIMEOUT:90,
                        DEPLOY_TIMEOUT:30,
                        BUILDER_TAG:'BuilderHydra',
                        TESTER_TAG:tester_tag,
                        resX: resX,
                        resY: resY,
                        iter: iter,
                        threshold: threshold,
                        customBuildLinkWindows: customBuildLinkWindows,
                        customBuildLinkUbuntu20: customBuildLinkUbuntu20,
                        customBuildLinkOSX: customBuildLinkOSX,
                        engines: formattedEngines,
                        nodeRetry: nodeRetry,
                        errorsInSuccession: errorsInSuccession,
                        platforms:platforms,
                        prRepoName:prRepoName,
                        prBranchName:prBranchName,
                        parallelExecutionType:parallelExecutionType,
                        parallelExecutionTypeString: parallelExecutionTypeString,
                        testCaseRetries:testCaseRetries,
                        storeOnNAS:true,
                        flexibleUpdates: true,
                        skipCallback: this.&filterTests,
                        forceReinstall: true,
                        testsPackageOriginal: testsPackage
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

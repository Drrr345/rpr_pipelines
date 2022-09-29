import groovy.transform.Field
import groovy.json.JsonOutput
import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiFunction
import net.sf.json.JSON
import net.sf.json.JSONSerializer
import net.sf.json.JsonConfig
import TestsExecutionType


@Field final String PRODUCT_NAME = "AMD%20Radeon™%20ProRender%20for%20USDViewer"
@Field final String CUSTOM_INSTALL_PATH = "C:\\Program Files\\testRPRViewer\\subdir"
@Field final def installsPerformedMap = new ConcurrentHashMap()
@Field final String devicesConfigPath = "%APPDATA%\\RPRViewer\\config\\devicesConfig.txt"

@Field final PipelineConfiguration PIPELINE_CONFIGURATION = new PipelineConfiguration(
    supportedOS: ["Windows"],
    productExtensions: ["Windows": "exe"],
    artifactNameBase: "RPRViewer_Setup"
)


@NonCPS
def shouldInstallationPerform(def key, String installationType, Integer maxTries) {
    def installationInfo = installsPerformedMap.get(key)[installationType]
    return installationInfo['tries'] < maxTries && installationInfo['status'] != 'success'
}

@NonCPS
def updateMap(def keyName, String installationType, String status) {
    if (installationType == 'dirt') {
        installsPerformedMap.computeIfPresent(keyName, (BiFunction){ key, value -> ['dirt': ['tries': value['dirt']['tries'] + 1, 'status': status], 'custom_path': value['custom_path']] })
    } else if (installationType == 'custom_path') {
        installsPerformedMap.computeIfPresent(keyName, (BiFunction){ key, value -> ['custom_path': ['tries': value['custom_path']['tries'] + 1, 'status': status], 'dirt': value['dirt']] })
    }
}


def checkExistenceOfPlugin(String osName, Map options) {
    String defaultUninstallerPath = "C:\\Program Files\\RPRViewer\\unins000.exe"
    String customUninstallerPath = "${CUSTOM_INSTALL_PATH}\\unins000.exe"

    return fileExists(defaultUninstallerPath) || fileExists(customUninstallerPath)
}

def getUninstallerPath() {
    String defaultUninstallerPath = "C:\\Program Files\\RPRViewer\\unins000.exe"
    String customUninstallerPath = "${CUSTOM_INSTALL_PATH}\\unins000.exe"

    if (fileExists(defaultUninstallerPath)) {
        return defaultUninstallerPath
    } else if (fileExists(customUninstallerPath)) {
        return customUninstallerPath
    } else {
        return null
    }
}


def installInventorPlugin(String osName, Map options, Boolean cleanInstall=true, Boolean customPathInstall=false, String customPluginName = "") {
    String installerName = ""
    String dirOption = ""
    String logPostfix

    if (cleanInstall) {
        if (customPathInstall) {
            logPostfix = "custom_path"
            dirOption = "/DIR=\"${CUSTOM_INSTALL_PATH}\""
        } else {
            logPostfix = "clean"
        }
    } else {
        logPostfix = "dirt"
    }

    if (customPluginName) {
        installerName = customPluginName
        logPostfix = "_custom"
    } else if (options['isPreBuilt']) {
        installerName = "${options[getProduct.getIdentificatorKey('Windows', options)]}.exe"
    } else {
        installerName = "${options.commitSHA}.exe"
    }

    try {
        if (cleanInstall && checkExistenceOfPlugin(osName, options)) {
            println "[INFO] Uninstalling Inventor Plugin"
            bat """
                start "" /wait "${getUninstallerPath()}" /SILENT /NORESTART /LOG=${options.stageName}_${logPostfix}_${options.currentTry}.uninstall.log
            """
        }
    } catch (e) {
        throw new Exception("Failed to uninstall old plugin")
    } 

    try {
        println "[INFO] Install Inventor Plugin"

        bat """
            start /wait ${CIS_TOOLS}\\..\\PluginsBinaries\\${installerName} /SILENT /NORESTART ${dirOption} /LOG=${options.stageName}${logPostfix}_${options.currentTry}.install${logPostfix}.log
        """
    } catch (e) {
        throw new Exception("Failed to install new plugin")
    } 
}


def buildRenderCache(String osName, Map options, Boolean cleanInstall=true, Boolean customPathInstall=false) {
    String logPostfix = cleanInstall ? "clean" : "dirt"
    logPostfix = customPathInstall ? "custom_path" : logPostfix
    String toolPath = customPathInstall ? "${CUSTOM_INSTALL_PATH}\\RPRViewer.exe" : "C:\\Program Files\\RPRViewer\\RPRViewer.exe"

    dir("scripts") {
        switch(osName) {
            case 'Windows':
                bat """
                    if exist ${devicesConfigPath} del ${devicesConfigPath}
                    build_usd_cache.bat RPRViewer "" "${toolPath}" >> "..\\${options.stageName}_${logPostfix}_${options.currentTry}.cb.log"  2>&1
                """
                break
            case "OSX":
                println "OSX isn't supported"
                break
            default:
                println "Linux isn't supported"   
        }
    }
}


def executeGenTestRefCommand(String osName, Map options, Boolean delete) {
    dir("scripts") {
        switch (osName) {
            case "Windows":
                bat """
                    make_results_baseline.bat ${delete} RPRViewer
                """
                break

            case "OSX":
                println "OSX isn't supported"
                break

            default:
                println "Linux isn't supported"
        }
    }
}


def executeTestCommand(String osName, String asicName, Map options) {
    def testTimeout = options.timeouts["${options.tests}"]
    String testsNames = options.tests
    String testsPackageName = options.testsPackage
    if (options.testsPackage != "none" && !options.isPackageSplitted) {
        if (testsNames.contains(".json")) {
            // if tests package isn't splitted and it's execution of this package - replace test package by test group and test group by empty string
            testsPackageName = options.tests
            testsNames = ""
        } else {
            // if tests package isn't splitted and it isn't execution of this package - replace tests package by empty string
            testsPackageName = ""
        }
    }

    println "Set timeout to ${testTimeout}"

    withEnv(["RPRVIEWER_RENDER_TIMINGS_LOG_FILE_NAME=$WORKSPACE\\render.log"]) {
        timeout(time: testTimeout, unit: 'MINUTES') {
            switch (osName) {
                case "Windows":
                    dir('scripts') {
                        bat """
                            set TOOL_VERSION=${options.pluginVersion}
                            run.bat \"${testsPackageName}\" \"${testsNames}\" RPRViewer 2022 ${options.testCaseRetries} ${options.updateRefs} 1>> \"../${options.stageName}_${options.currentTry}.log\"  2>&1
                        """
                    }
                    break

                case "OSX":
                    println "OSX isn't supported"
                    break

                default:
                    println "Linux isn't supported"
            }
        }
    }
}


def executeTests(String osName, String asicName, Map options) {
    // used for mark stash results or not. It needed for not stashing failed tasks which will be retried.
    Boolean stashResults = true
    try {
        if (env.NODE_NAME == "PC-TESTER-MILAN-WIN10") {
            if (options.tests.contains("CPU") || options.tests.contains("weekly.2") || options.tests.contains("regression.2")) {
                throw new ExpectedExceptionWrapper(
                    "System shouldn't execute CPU group (render is too slow)", 
                    new Exception("System shouldn't execute CPU group (render is too slow)")
                )
            }
        }

        withNotifications(title: options["stageName"], options: options, logUrl: "${BUILD_URL}", configuration: NotificationConfiguration.DOWNLOAD_TESTS_REPO) {
            timeout(time: "10", unit: "MINUTES") {
                cleanWS(osName)
                checkoutScm(branchName: options.testsBranch, repositoryUrl: options.testRepo)
            }
        }

        withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.DOWNLOAD_PACKAGE) {
            timeout(time: "40", unit: "MINUTES") {
                getProduct(osName, options)
            }
        }

        withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.DOWNLOAD_SCENES) {
            String assetsDir = isUnix() ? "${CIS_TOOLS}/../TestResources/usd_rprviewer_autotests_assets" : "/mnt/c/TestResources/usd_rprviewer_autotests_assets"
            downloadFiles("/volume1/web/Assets/usd_rprviewer_autotests/", assetsDir)
        }

        installsPerformedMap.putIfAbsent("${asicName}-${osName}", ['dirt': ['tries': 0, 'status': 'active'], 'custom_path': ['tries': 0, 'status': 'active']])

        if (shouldInstallationPerform("${asicName}-${osName}", 'dirt', options.nodeReallocateTries)) {
            try {
                withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.INSTALL_PLUGIN_DIRT) {
                    println "Uninstall old plugin and install \"baseline\" plugin"

                    String baselinePluginPath = options["baselinePluginPath"]

                    // Download "baseline" plugin
                    timeout(time: "15", unit: "MINUTES") {
                        downloadFiles(baselinePluginPath, "${CIS_TOOLS}/../PluginsBinaries/".replace("C:", "/mnt/c").replace("\\", "/"))
                    }

                    println "Install \"baseline\" plugin"

                    timeout(time: "15", unit: "MINUTES") {
                        installInventorPlugin(osName, options, true, false, baselinePluginPath.split("/")[-1])
                    }

                    println "Start \"dirt\" installation"

                    timeout(time: "8", unit: "MINUTES") {
                        installInventorPlugin(osName, options, false)
                    }
                }
            

                withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.BUILD_CACHE_DIRT) {                        
                    timeout(time: "15", unit: "MINUTES") {
                        try {
                            buildRenderCache(osName, options, false)
                        } catch (e) {
                            throw e
                        } finally {
                            dir("scripts") {
                                utils.renameFile(this, osName, "cache_building_results", "${options.stageName}_dirt_${options.currentTry}")
                                archiveArtifacts artifacts: "${options.stageName}_dirt_${options.currentTry}/*.jpg", allowEmptyArchive: true
                            }
                        }
                        dir("scripts") {
                            String cacheImgPath = "./${options.stageName}_dirt_${options.currentTry}/RESULT.jpg"
                            if(!fileExists(cacheImgPath)){
                                throw new ExpectedExceptionWrapper(NotificationConfiguration.NO_OUTPUT_IMAGE, new Exception(NotificationConfiguration.NO_OUTPUT_IMAGE))
                            }
                        }
                    }
                }
            } catch (e) {
                updateMap("${asicName}-${osName}", 'dirt', 'failed')
                throw e
            }

            updateMap("${asicName}-${osName}", 'dirt', 'success')
        }

        if (shouldInstallationPerform("${asicName}-${osName}", 'custom_path', options.nodeReallocateTries)) {
            try {
                withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.INSTALL_PLUGIN_CUSTOM_PATH) {
                    timeout(time: "15", unit: "MINUTES") {
                        installInventorPlugin(osName, options, true, true)
                    }
                }
            
                withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.BUILD_CACHE_CUSTOM_PATH) {                        
                    timeout(time: "15", unit: "MINUTES") {
                        try {
                            buildRenderCache(osName, options, true, true)
                        } catch (e) {
                            throw e
                        } finally {
                            dir("scripts") {
                                utils.renameFile(this, osName, "cache_building_results", "${options.stageName}_custom_path_${options.currentTry}")
                                archiveArtifacts artifacts: "${options.stageName}_custom_path_${options.currentTry}/*.jpg", allowEmptyArchive: true
                            }
                        }
                        dir("scripts") {
                            String cacheImgPath = "./${options.stageName}_custom_path_${options.currentTry}/RESULT.jpg"
                            if(!fileExists(cacheImgPath)){
                                throw new ExpectedExceptionWrapper(NotificationConfiguration.NO_OUTPUT_IMAGE, new Exception(NotificationConfiguration.NO_OUTPUT_IMAGE))
                            }
                        }
                    }
                }
            } catch (e) {
                updateMap("${asicName}-${osName}", 'custom_path', 'failed')
                throw e
            }

            updateMap("${asicName}-${osName}", 'custom_path', 'success')
        }

        withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.INSTALL_PLUGIN_CLEAN) {
            timeout(time: "15", unit: "MINUTES") {
                installInventorPlugin(osName, options, true)
            }
        }
    
        withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.BUILD_CACHE_CLEAN) {                        
            timeout(time: "15", unit: "MINUTES") {
                try {
                    buildRenderCache(osName, options, true)
                } catch (e) {
                    throw e
                } finally {
                    dir("scripts") {
                        utils.renameFile(this, osName, "cache_building_results", "${options.stageName}_clean_${options.currentTry}")
                        archiveArtifacts artifacts: "${options.stageName}_clean_${options.currentTry}/*.jpg", allowEmptyArchive: true
                    }
                }
                dir("scripts") {
                    String cacheImgPath = "./${options.stageName}_clean_${options.currentTry}/RESULT.jpg"
                    if(!fileExists(cacheImgPath)){
                        throw new ExpectedExceptionWrapper(NotificationConfiguration.NO_OUTPUT_IMAGE, new Exception(NotificationConfiguration.NO_OUTPUT_IMAGE))
                    }
                }
            }
        }

        String REF_PATH_PROFILE="/volume1/Baselines/usd_rprviewer_autotests/${asicName}-${osName}"
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
                        bat """
                            if exist Work\\GeneratedBaselines rmdir /Q /S Work\\GeneratedBaselines
                        """
                        break

                    default:
                        sh """
                            rm -rf ./Work/GeneratedBaselines
                        """
                }
            }
        } else {
            withNotifications(title: options["stageName"], printMessage: true, options: options, configuration: NotificationConfiguration.COPY_BASELINES) {
                String baselineDir = isUnix() ? "${CIS_TOOLS}/../TestResources/usd_rprviewer_autotests_baselines" : "/mnt/c/TestResources/usd_rprviewer_autotests_baselines"
                println "[INFO] Downloading reference images for ${options.tests}"
                options.tests.split(" ").each { downloadFiles("${REF_PATH_PROFILE}/${it.contains(".json") ? "" : it}", baselineDir) }
            }
            withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.EXECUTE_TESTS) {
                executeTestCommand(osName, asicName, options)
            }
        }
        options.executeTestsFinished = true

        utils.compareDriverVersion(this, "${options.stageName}_${options.currentTry}.log", osName)
    } catch (e) {
        if (options.currentTry < options.nodeReallocateTries - 1) {
            stashResults = false
        } else {
            currentBuild.result = "FAILURE"
        }
        println e.toString()
        if (e instanceof ExpectedExceptionWrapper) {
            GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, "${e.getMessage()}", "${BUILD_URL}")
            throw new ExpectedExceptionWrapper("${e.getMessage()}", e.getCause())
        } else {
            GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, "${NotificationConfiguration.REASON_IS_NOT_IDENTIFIED}", "${BUILD_URL}")
            throw new ExpectedExceptionWrapper("${NotificationConfiguration.REASON_IS_NOT_IDENTIFIED}", e)
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
                dir('Work') {
                    if (fileExists("Results/RPRViewer/session_report.json")) {

                        def sessionReport = readJSON file: 'Results/RPRViewer/session_report.json'

                        if (sessionReport.summary.error > 0) {
                            GithubNotificator.updateStatus("Test", options['stageName'], "action_required", options, NotificationConfiguration.SOME_TESTS_ERRORED, "${BUILD_URL}")
                        } else if (sessionReport.summary.failed > 0) {
                            GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, NotificationConfiguration.SOME_TESTS_FAILED, "${BUILD_URL}")
                        } else {
                            GithubNotificator.updateStatus("Test", options['stageName'], "success", options, NotificationConfiguration.ALL_TESTS_PASSED, "${BUILD_URL}")
                        }

                        println "Stashing test results to : ${options.testResultsName}"

                        utils.stashTestData(this, options, options.storeOnNAS)
                        // reallocate node if there are still attempts
                        if (sessionReport.summary.total == sessionReport.summary.error + sessionReport.summary.skipped || sessionReport.summary.total == 0) {
                            if (sessionReport.summary.total != sessionReport.summary.skipped) {
                                // remove broken usdviewer
                                removeInstaller(osName: osName, options: options, extension: "zip")
                                String errorMessage = (options.currentTry < options.nodeReallocateTries) ? "All tests were marked as error. The test group will be restarted." : "All tests were marked as error."
                                throw new ExpectedExceptionWrapper(errorMessage, new Exception(errorMessage))
                            }
                        }

                        if (options.reportUpdater) {
                            options.reportUpdater.updateReport(options.engine)
                        }
                    }
                }
            }
        } catch(e) {
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
        if (!options.executeTestsFinished) {
            bat """
                shutdown /r /f /t 0
            """
        }
    }
}


def executeBuildWindows(Map options) {
    withEnv(["PATH=c:\\python37\\;c:\\CMake323\\bin;c:\\python37\\scripts\\;${PATH}", "WORKSPACE=${env.WORKSPACE.toString().replace('\\', '/')}"]) {
        outputEnvironmentInfo("Windows", "${STAGE_NAME}.EnvVariables")

        // vcvars64.bat sets VS/msbuild env
        withNotifications(title: "Windows", options: options, logUrl: "${BUILD_URL}/artifact/${STAGE_NAME}.HdRPRPlugin.log", configuration: NotificationConfiguration.BUILD_SOURCE_CODE) {
            bat """
                call "C:\\Program Files (x86)\\Microsoft Visual Studio\\2017\\Community\\VC\\Auxiliary\\Build\\vcvars64.bat" >> ${STAGE_NAME}.EnvVariables.log 2>&1

                RPRViewer\\tools\\build_usd_windows.bat >> ${STAGE_NAME}.USDPixar.log 2>&1
            """

            bat """
                RPRViewer\\tools\\build_hdrpr_windows.bat >> ${STAGE_NAME}.HdRPRPlugin.log 2>&1
            """

            bat """
                RPRViewer\\tools\\build_compatibility_checker_windows.bat >> ${STAGE_NAME}.CompatibilityChecker.log 2>&1
            """
        }
        String buildName = "RadeonProUSDViewer_Windows.zip"
        withNotifications(title: "Windows", options: options, configuration: NotificationConfiguration.BUILD_PACKAGE_USD_VIEWER)  {
            // delete files before zipping
            bat """
                del RPRViewer\\binary\\windows\\inst\\pxrConfig.cmake
                rmdir /Q /S RPRViewer\\binary\\windows\\inst\\cmake
                rmdir /Q /S RPRViewer\\binary\\windows\\inst\\include
                rmdir /Q /S RPRViewer\\binary\\windows\\inst\\lib\\cmake
                rmdir /Q /S RPRViewer\\binary\\windows\\inst\\lib\\pkgconfig
                del RPRViewer\\binary\\windows\\inst\\bin\\*.lib
                del RPRViewer\\binary\\windows\\inst\\bin\\*.pdb
                del RPRViewer\\binary\\windows\\inst\\lib\\*.lib
                del RPRViewer\\binary\\windows\\inst\\lib\\*.pdb
                del RPRViewer\\binary\\windows\\inst\\plugin\\usd\\*.lib
            """

            withEnv(["PYTHONPATH=%INST%\\lib\\python;%INST%\\lib"]) {
                bat """
                    RPRViewer\\tools\\build_package_windows.bat >> ${STAGE_NAME}.USDViewerPackage.log 2>&1
                """

                //TODO: remove after fix
                bat """
                    copy LICENSE.txt RPRViewer\\LICENSE.txt
                """

                dir("RPRViewer") {
                    bat """
                        "C:\\Program Files (x86)\\Inno Setup 6\\ISCC.exe" installer.iss >> ..\\${STAGE_NAME}.USDViewerInstaller.log 2>&1
                    """

                    makeStash(includes: "RPRViewer_Setup.exe", name: getProduct.getStashName("Windows", options), preZip: false, storeOnNAS: options.storeOnNAS)

                    if (options.branch_postfix) {
                        bat """
                            rename RPRViewer_Setup.exe RPRViewer_Setup_${options.pluginVersion}_(${options.branch_postfix}).exe
                        """
                    }

                    String ARTIFACT_NAME = options.branch_postfix ? "RPRViewer_Setup_${options.pluginVersion}_(${options.branch_postfix}).exe" : "RPRViewer_Setup.exe"
                    String artifactURL = makeArchiveArtifacts(name: ARTIFACT_NAME, storeOnNAS: options.storeOnNAS)

                    GithubNotificator.updateStatus("Build", "Windows", "success", options, NotificationConfiguration.BUILD_SOURCE_CODE_END_MESSAGE, artifactURL)
                }
            }
        }
    }
}


def executeBuildOSX(Map options)
{
    
    outputEnvironmentInfo("OSX", "${STAGE_NAME}.EnvVariables")

    // vcvars64.bat sets VS/msbuild env
    withNotifications(title: "OSX", options: options, logUrl: "${BUILD_URL}/artifact/${STAGE_NAME}.HdRPRPlugin.log", configuration: NotificationConfiguration.BUILD_SOURCE_CODE) {
        sh """
            export OS=Darwin
            export PATH="~/Qt/5.15.2/clang_64/bin:\$PATH"
            
            echo \$PATH
            rm -rf RPRViewer/binary/mac/*
            export PYENV_ROOT="\$HOME/.pyenv"
            export PATH="\$PYENV_ROOT/shims:\$PYENV_ROOT/bin:\$PATH"
            pyenv rehash

            python --version

            chmod u+x RPRViewer/tools/build_usd_mac.sh
            RPRViewer/tools/build_usd_mac.sh >> ${STAGE_NAME}.USDPixar.log 2>&1
            
            chmod u+x RPRViewer/tools/build_hdrpr_mac.sh
            RPRViewer/tools/build_hdrpr_mac.sh >> ${STAGE_NAME}.HdRPRPlugin.log 2>&1
        """
    }

    // delete files before zipping
    withNotifications(title: "OSX", options: options, artifactUrl: "${BUILD_URL}/artifact/RadeonProUSDViewer_OSX.zip", configuration: NotificationConfiguration.BUILD_PACKAGE_USD_VIEWER) {
        withEnv(["PYTHONPATH=%INST%/lib/python;%INST%/lib"]) {
            sh """
                export PYENV_ROOT="\$HOME/.pyenv"
                export PATH="\$PYENV_ROOT/shims:\$PYENV_ROOT/bin:\$PATH"
                pyenv rehash
                
                python --version
            
                chmod u+x RPRViewer/tools/build_package_mac.sh
                RPRViewer/tools/build_package_mac.sh >> ${STAGE_NAME}.USDViewerPackage.log 2>&1
            """

            zip archive: false, dir: "RPRViewer/binary/mac/inst/dist/RPRViewer", glob: '', zipFile: "RadeonProUSDViewer_Package_OSX.zip"
        
            if (options.branch_postfix) {
                sh """
                    mv RadeonProUSDViewer_Package_OSX.zip "RadeonProUSDViewer_Package_OSX_${options.pluginVersion}_(${options.branch_postfix}).zip"
                """
            }

            archiveArtifacts artifacts: "RadeonProUSDViewer_Package*.zip", allowEmptyArchive: false
            String BUILD_NAME = options.branch_postfix ? "RPRViewer_Setup_${options.pluginVersion}_(${options.branch_postfix}).zip" : "RadeonProUSDViewer_Package_OSX.zip"
            String pluginUrl = "${BUILD_URL}artifact/${BUILD_NAME}"
            rtp nullAction: "1", parserName: "HTML", stableText: """<h3><a href="${pluginUrl}">[BUILD: ${BUILD_ID}] ${BUILD_NAME}</a></h3>"""
        }
    }
    
}


def executeBuild(String osName, Map options) {
    try {
        withNotifications(title: osName, options: options, configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
            checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo)
        }
        withNotifications(title: osName, options: options, configuration: NotificationConfiguration.BUILD_SOURCE_CODE) {
            switch (osName) {
                case "Windows":
                    executeBuildWindows(options)
                    break
                case "OSX":
                    executeBuildOSX(options)
                    break
                default:
                    println "Linux isn't supported"
            }
        }

        options[getProduct.getIdentificatorKey(osName, options)] = options.commitSHA
    }
    finally {
        archiveArtifacts artifacts: "*.log", allowEmptyArchive: true
    }
}

def getReportBuildArgs(Map options) {
    boolean collectTrackedMetrics = (env.JOB_NAME.contains("Weekly") || (env.JOB_NAME.contains("Manual") && (options.testsPackageOriginal == "weekly.json" || options.testsPackageOriginal == "Full.json")))
    
    if (options["isPreBuilt"]) {
        return """USDViewer "PreBuilt" "PreBuilt" "PreBuilt" \"\" ${collectTrackedMetrics ? env.BUILD_NUMBER : ""}"""
    } else {
        return """USDViewer ${options.commitSHA} ${options.projectBranchName} \"${utils.escapeCharsByUnicode(options.commitMessage)}\" \"\" ${collectTrackedMetrics ? env.BUILD_NUMBER : ""}"""
    }
}

def executePreBuild(Map options) {
    if (options['isPreBuilt']) {
        println "[INFO] Build was detected as prebuilt. Build stage will be skipped"
        currentBuild.description = "<b>Project branch:</b> Prebuilt plugin<br/>"
        options['executeBuild'] = false
        options['executeTests'] = true
    // manual job
    } else if (options.forceBuild) {
        println "[INFO] Manual job launch detected"
        options['executeBuild'] = true
        options['executeTests'] = true
    // auto job (master)
    } else if (env.BRANCH_NAME && env.BRANCH_NAME == "master") {
        options.testsPackage = "regression.json"
    // auto job
    } else if (env.BRANCH_NAME) {
        options.testsPackage = "regression.json"
    }

    options["branch_postfix"] = ""
    if (env.BRANCH_NAME && env.BRANCH_NAME == "master") {
        options["branch_postfix"] = "release"
    } else if (env.BRANCH_NAME && env.BRANCH_NAME != "master" && env.BRANCH_NAME != "develop") {
        options["branch_postfix"] = env.BRANCH_NAME.replace('/', '-')
    } else if(options.projectBranch && options.projectBranch != "master" && options.projectBranch != "develop") {
        options["branch_postfix"] = options.projectBranch.replace('/', '-')
    }

    
    if (!options['isPreBuilt']) {

        withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
            checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo, disableSubmodules: true)
        }

        options.commitAuthor = utils.getBatOutput(this, "git show -s --format=%%an HEAD ")
        options.commitMessage = utils.getBatOutput(this, "git log --format=%%s -n 1").replace('\n', '')
        options.commitSHA = utils.getBatOutput(this, "git log --format=%%H -1 ")
        options.branchName = env.BRANCH_NAME ?: options.projectBranch

        println """
            The last commit was written by ${options.commitAuthor}.
            Commit message: ${options.commitMessage}
            Commit SHA: ${options.commitSHA}
            Branch name: ${options.branchName}
        """

        withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.INCREMENT_VERSION) {
            options.pluginVersion = version_read("${env.WORKSPACE}\\RPRViewer\\src\\application\\version.py", 'USD_VIEWER_BUILD_VERSION = "')

            if (options['incrementVersion']) {
                withNotifications(title: "Jenkins build configuration", printMessage: true, options: options, configuration: NotificationConfiguration.CREATE_GITHUB_NOTIFICATOR) {
                    GithubNotificator githubNotificator = new GithubNotificator(this, options)
                    githubNotificator.init(options)
                    options["githubNotificator"] = githubNotificator
                    githubNotificator.initPreBuild("${BUILD_URL}")
                    options.projectBranchName = githubNotificator.branchName
                }
                
                if (env.BRANCH_NAME == "master" && options.commitAuthor != "radeonprorender") {

                    println "[INFO] Incrementing version of change made by ${options.commitAuthor}."
                    println "[INFO] Current build version: ${options.pluginVersion}"

                    def new_plugin_version = version_inc(options.pluginVersion, 3)
                    println "[INFO] New build version: ${new_plugin_version}"
                    version_write("${env.WORKSPACE}\\RPRViewer\\src\\application\\version.py", 'USD_VIEWER_BUILD_VERSION = "', new_plugin_version)

                    options.pluginVersion = version_read("${env.WORKSPACE}\\RPRViewer\\src\\application\\version.py", 'USD_VIEWER_BUILD_VERSION = "')
                    println "[INFO] Updated build version: ${options.pluginVersion}"

                    options.installerVersion = version_read("${env.WORKSPACE}\\RPRViewer\\installer.iss", 'AppVersion=')
                    println "[INFO] Current installer version: ${options.installerVersion}"

                    // TODO: delete this code
                    if (options.installerVersion == "1.0") {
                        options.installerVersion = "1.0.0"
                        println "[INFO] Updated installer version: ${options.installerVersion}"
                    }

                    def new_installer_version = version_inc(options.installerVersion, 3)
                    println "[INFO] New installer version: ${new_installer_version}"
                    version_write("${env.WORKSPACE}\\RPRViewer\\installer.iss", 'AppVersion=', new_installer_version)

                    bat """
                        git add ${env.WORKSPACE}\\RPRViewer\\src\\application\\version.py
                        git add ${env.WORKSPACE}\\RPRViewer\\installer.iss
                        git commit -m "buildmaster: version update to ${options.pluginVersion}"
                        git push origin HEAD:master
                    """

                    // Get commit's sha which have to be build
                    options.commitSHA = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
                    options.projectBranch = options.commitSHA
                    println "[INFO] Project branch hash: ${options.projectBranch}"

                    // update RPRViewer submodule in Inventor installer repository
                    dir("Inst") {
                        checkoutScm(branchName: "master", repositoryUrl: "git@github.com:Radeon-Pro/RadeonProRenderInventorPluginInstaller.git", submoduleDepth: 1)

                        bat """
                            cd RPRViewer
                            git checkout master
                            cd ..
                            git add RPRViewer
                            git commit -m "buildmaster: update RPRViewer submodule to ${options.pluginVersion}"
                            git push origin HEAD:master
                        """
                    }
                } else {

                    if (options.commitMessage.contains("CIS:BUILD")) {
                        options['executeBuild'] = true
                    }

                    if (options.commitMessage.contains("CIS:TESTS")) {
                        options['executeBuild'] = true
                        options['executeTests'] = true
                    }

                }
            } else {
                options.projectBranchName = options.projectBranch
            }

            currentBuild.description = "<b>Project branch:</b> ${options.projectBranchName}<br/>"
            currentBuild.description += "<b>Version:</b> ${options.pluginVersion}<br/>"
            currentBuild.description += "<b>Commit author:</b> ${options.commitAuthor}<br/>"
            currentBuild.description += "<b>Commit message:</b> ${options.commitMessage}<br/>"
            currentBuild.description += "<b>Commit SHA:</b> ${options.commitSHA}<br/>"
        }

    }

    def tests = []
    options.timeouts = [:]

    withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.CONFIGURE_TESTS) {
        dir('jobs_test_usdviewer') {
            checkoutScm(branchName: options.testsBranch, repositoryUrl: options.testRepo)
            options['testsBranch'] = utils.getBatOutput(this, "git log --format=%%H -1 ")
            dir('jobs_launcher') {
                options['jobsLauncherBranch'] = utils.getBatOutput(this, "git log --format=%%H -1 ")
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
                if (options.isPackageSplitted) {
                    println "[INFO] Tests package '${options.testsPackage}' can be splitted"
                } else {
                    // save tests which user wants to run with non-splitted tests package
                    if (options.tests) {
                        tests = options.tests.split(" ") as List
                    }
                    println "[INFO] Tests package '${options.testsPackage}' can't be splitted"
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

                groupsFromPackage.each {
                    if (options.isPackageSplitted) {
                        tests << it
                    } else {
                        if (tests.contains(it)) {
                            // add duplicated group name in name of package group name for exclude it
                            modifiedPackageName = "${modifiedPackageName},${it}"
                        }
                    }
                }
                options.tests = utils.uniteSuites(this, "jobs/weights.json", tests)
                options.tests.each {
                    def xml_timeout = utils.getTimeoutFromXML(this, "${it}", "simpleRender.py", options.ADDITIONAL_XML_TIMEOUT)
                    options.timeouts["${it}"] = (xml_timeout > 0) ? xml_timeout : options.TEST_TIMEOUT
                }
                modifiedPackageName = modifiedPackageName.replace('~,', '~')

                if (options.isPackageSplitted) {
                    options.testsPackage = "none"
                } else {
                    options.testsPackage = modifiedPackageName
                    // check that package is splitted to parts or not
                    if (packageInfo["groups"] instanceof Map) {
                        tests << "${modifiedPackageName}"
                        options.timeouts[options.testsPackage] = options.NON_SPLITTED_PACKAGE_TIMEOUT + options.ADDITIONAL_XML_TIMEOUT
                    } else {
                        // add group stub for each part of package
                        for (int i = 0; i < packageInfo["groups"].size(); i++) {
                            tests << "${modifiedPackageName}".replace(".json", ".${i}.json")
                            options.timeouts[options.testsPackage.replace(".json", ".${i}.json")] = options.NON_SPLITTED_PACKAGE_TIMEOUT + options.ADDITIONAL_XML_TIMEOUT
                        }
                    }
                    
                    options.tests = tests
                }
            } else {
                options.tests = utils.uniteSuites(this, "jobs/weights.json", options.tests.split(" ") as List)
                options.tests.each {
                    def xml_timeout = utils.getTimeoutFromXML(this, "${it}", "simpleRender.py", options.ADDITIONAL_XML_TIMEOUT)
                    options.timeouts["${it}"] = (xml_timeout > 0) ? xml_timeout : options.TEST_TIMEOUT
                }
            }
        }
        if (env.BRANCH_NAME && options.githubNotificator) {
            options.githubNotificator.initChecks(options, "${BUILD_URL}")
        }
        options.testsList = options.tests
        println "timeouts: ${options.timeouts}"
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
                testResultList.each {
                    dir("$it".replace("testResult-", "")) {
                        try {
                            makeUnstash(name: "$it", storeOnNAS: options.storeOnNAS)
                        } catch (e) {
                            println """
                                [ERROR] Failed to unstash ${it}
                                ${e.toString()}
                            """
                            lostStashes << ("'${it}'".replace("testResult-", ""))
                        }
                    }
                }
            }

            try {
                dir("jobs_launcher") {
                    bat """
                        count_lost_tests.bat \"${lostStashes}\" .. ..\\summaryTestResults \"${options.splitTestsExecution}\" \"${options.testsPackage}\" \"[]\" \"\" \"{}\"
                    """
                }
            } catch (e) {
                println "[ERROR] Can't generate number of lost tests"
            }
            
            boolean useTrackedMetrics = (env.JOB_NAME.contains("Weekly") || (env.JOB_NAME.contains("Manual") && (options.testsPackageOriginal == "weekly.json" || options.testsPackageOriginal == "Full.json")))
            boolean saveTrackedMetrics = env.JOB_NAME.contains("Weekly")
            String metricsRemoteDir = "/volume1/Baselines/TrackedMetrics/USD-Viewer"

            if (useTrackedMetrics) {
                utils.downloadMetrics(this, "summaryTestResults/tracked_metrics", "${metricsRemoteDir}/")
            }

            try {
                GithubNotificator.updateStatus("Deploy", "Building test report", "in_progress", options, NotificationConfiguration.BUILDING_REPORT, "${BUILD_URL}")
                withEnv(["JOB_STARTED_TIME=${options.JOB_STARTED_TIME}", "BUILD_NAME=${options.baseBuildName}"]) {
                    dir("jobs_launcher") {
                        def retryInfo = JsonOutput.toJson(options.nodeRetry)
                        dir("..\\summaryTestResults") {
                            writeJSON file: 'retry_info.json', json: JSONSerializer.toJSON(retryInfo, new JsonConfig()), pretty: 4
                        }
                        bat "build_reports.bat ..\\summaryTestResults ${getReportBuildArgs(options)}"
                    }
                }
                if (saveTrackedMetrics) {
                    utils.uploadMetrics(this, "summaryTestResults/tracked_metrics", metricsRemoteDir)
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
                    if (!options.testDataSaved && !options.storeOnNAS) {
                        try {
                            // Save test data for access it manually anyway
                            utils.publishReport(this, "${BUILD_URL}", "summaryTestResults", "summary_report.html, compare_report.html", \
                                "Test Report", "Summary Report, Compare Report", options.storeOnNAS, \
                                ["jenkinsBuildUrl": BUILD_URL, "jenkinsBuildName": currentBuild.displayName, "updatable": options.containsKey("reportUpdater")])

                            options.testDataSaved = true 
                        } catch (e1) {
                            println """
                                [WARNING] Failed to publish test data.
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

            try {
                dir("jobs_launcher") {
                    bat """
                        get_status.bat ..\\summaryTestResults
                    """
                }
            } catch (e) {
                println """
                    [ERROR] during slack status generation.
                    ${e.toString()}
                """
            }

            try {
                dir("jobs_launcher") {
                    archiveArtifacts "launcher.engine.log"
                }
            } catch(e) {
                println """
                    [ERROR] during archiving launcher.engine.log
                    ${e.toString()}
                """
            }

            Map summaryTestResults = [:]
            try {
                def summaryReport = readJSON file: 'summaryTestResults/summary_status.json'
                summaryTestResults = [passed: summaryReport.passed, failed: summaryReport.failed, error: summaryReport.error]
                if (summaryReport.error > 0) {
                    println "[INFO] Some tests marked as error. Build result = FAILURE."
                    currentBuild.result = "FAILURE"
                    options.problemMessageManager.saveGlobalFailReason(NotificationConfiguration.SOME_TESTS_ERRORED)
                } else if (summaryReport.failed > 0) {
                    println "[INFO] Some tests marked as failed. Build result = UNSTABLE."
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

            try {
                options.testsStatus = readFile("summaryTestResults/slack_status.json")
            } catch (e) {
                println e.toString()
                options.testsStatus = ""
            }

            withNotifications(title: "Building test report", options: options, configuration: NotificationConfiguration.PUBLISH_REPORT) {
                utils.publishReport(this, "${BUILD_URL}", "summaryTestResults", "summary_report.html, compare_report.html", \
                    "Test Report", "Summary Report, Compare Report", options.storeOnNAS, \
                    ["jenkinsBuildUrl": BUILD_URL, "jenkinsBuildName": currentBuild.displayName, "updatable": options.containsKey("reportUpdater")])

                if (summaryTestResults) {
                    GithubNotificator.updateStatus("Deploy", "Building test report", "success", options,
                            "${NotificationConfiguration.REPORT_PUBLISHED} Results: passed - ${summaryTestResults.passed}, failed - ${summaryTestResults.failed}, error - ${summaryTestResults.error}.", "${BUILD_URL}/Test_20Report")
                } else {
                    GithubNotificator.updateStatus("Deploy", "Building test report", "success", options,
                            NotificationConfiguration.REPORT_PUBLISHED, "${BUILD_URL}/Test_20Report")
                }
            }
        }
    } catch (e) {
        println e.toString()
        throw e
    }
}


def call(String projectBranch = "",
         String testsBranch = "master",
         String platforms = 'Windows:AMD_RadeonVII,AMD_RX6800XT,NVIDIA_RTX3080TI',
         String updateRefs = 'No',
         Boolean enableNotifications = true,
         String testsPackage = "",
         String tests = "",
         Boolean splitTestsExecution = true,
         Boolean incrementVersion = true,
         String tester_tag = 'USDViewer',
         String customBuildLinkWindows = "",
         String parallelExecutionTypeString = "TakeAllNodes",
         Integer testCaseRetries = 3,
         String baselinePluginPath = "/volume1/CIS/bin-storage/RPRViewer_Setup.release-99.exe") {
    ProblemMessageManager problemMessageManager = new ProblemMessageManager(this, currentBuild)
    Map options = [stage: "Init", problemMessageManager: problemMessageManager]
    try {
        withNotifications(options: options, configuration: NotificationConfiguration.INITIALIZATION) {

            if (env.BRANCH_NAME && (env.BRANCH_NAME == "PR-265" || env.BRANCH_NAME == "PR-276" || env.BRANCH_NAME == "PR-277")) {
                testsBranch = "inemankov/update_render_device"
            }

            Boolean isPreBuilt = customBuildLinkWindows

            Integer testStageTimeout

            if (tests.contains("Material_Library") || testsPackage.contains("weekly")) {
                testStageTimeout = 360
            } else {
                testStageTimeout = 195
            }

            println """
                Platforms: ${platforms}
                Tests: ${tests}
                Tests package: ${testsPackage}
                Tests execution type: ${parallelExecutionTypeString}
                Test stage timeout: ${testStageTimeout}
            """
            options << [configuration: PIPELINE_CONFIGURATION,
                        projectBranch: projectBranch,
                        testRepo:"git@github.com:luxteam/jobs_test_inventor.git",
                        testsBranch: testsBranch,
                        updateRefs: updateRefs,
                        enableNotifications: enableNotifications,
                        PRJ_NAME: 'StandaloneUSDViewer',
                        PRJ_ROOT: 'rpr-core',
                        projectRepo: 'git@github.com:Radeon-Pro/RPRViewer.git',
                        BUILDER_TAG: 'BuilderUSDViewer',
                        isPreBuilt:isPreBuilt,
                        TESTER_TAG: tester_tag,
                        incrementVersion:incrementVersion,
                        executeBuild: true,
                        executeTests: true,
                        splitTestsExecution: splitTestsExecution,
                        DEPLOY_FOLDER: "StandaloneUSDViewer",
                        testsPackage: testsPackage,
                        BUILD_TIMEOUT: 120,
                        TEST_TIMEOUT: testStageTimeout,
                        ADDITIONAL_XML_TIMEOUT: 15,
                        NON_SPLITTED_PACKAGE_TIMEOUT: 180,
                        DEPLOY_TIMEOUT: 45,
                        tests: tests,
                        customBuildLinkWindows: customBuildLinkWindows,
                        nodeRetry: [],
                        problemMessageManager: problemMessageManager,
                        platforms: platforms,
                        parallelExecutionType: TestsExecutionType.valueOf(parallelExecutionTypeString),
                        parallelExecutionTypeString: parallelExecutionTypeString,
                        testCaseRetries: testCaseRetries,
                        baselinePluginPath: baselinePluginPath,
                        storeOnNAS: true,
                        flexibleUpdates: true,
                        testsPackageOriginal: testsPackage
                        ]
        }
        multiplatform_pipeline(platforms, this.&executePreBuild, this.&executeBuild, this.&executeTests, this.&executeDeploy, options)
    } catch(e) {
        currentBuild.result = "FAILURE"
        println e.toString()
        throw e
    } finally {
        String problemMessage = options.problemMessageManager.publishMessages()
    }
}

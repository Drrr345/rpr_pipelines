import groovy.transform.Field
import groovy.json.JsonOutput
import net.sf.json.JSON
import net.sf.json.JSONSerializer
import net.sf.json.JsonConfig

@Field final String PROJECT_REPO = "git@github.com:Radeon-Pro/WebUsdViewer.git"
@Field final String TEST_REPO = "git@github.com:luxteam/jobs_test_web_viewer.git"

@Field final Integer MAX_TEST_INSTANCE_NUMBER = 10

@Field final PipelineConfiguration PIPELINE_CONFIGURATION = new PipelineConfiguration(
    supportedOS: ["Windows"],
    productExtensions: ["Windows": "msi"],
    artifactNameBase: "AMD_RenderStudio",
    testProfile: "mode",
    displayingProfilesMapping: [
        "mode": [
            "Desktop": "Desktop",
            "Web": "Web"
        ]
    ]
)


Boolean filter(Map options, String asicName, String osName, String testName, String mode) {
    if ((osName == "Windows" && mode == "Desktop") || (osName == "Web" && mode == "Web")) {
        return false
    }

    return true
}


@NonCPS
def parseResponse(String response) {
    def jsonSlurper = new groovy.json.JsonSlurperClassic()
    return jsonSlurper.parseText(response)
}


Integer getNextTestInstanceNumber(Map options) {
    downloadFiles("/volume1/CIS/WebUSD/State/TestingInstancesInfo.json", ".")

    def instancesInfo = readJSON(file: "TestingInstancesInfo.json")

    Integer testingNumber

    if (instancesInfo["prs"].containsKey(env.BRANCH_NAME)) {
        testingNumber = instancesInfo["prs"][env.BRANCH_NAME]
    } else {
        testingNumber = instancesInfo["globalCounter"]
        instancesInfo["prs"][env.BRANCH_NAME] = testingNumber
        instancesInfo["globalCounter"] = instancesInfo["globalCounter"] >= MAX_TEST_INSTANCE_NUMBER ? 1 : instancesInfo["globalCounter"] + 1
    }

    options.deployEnvironment = "test${testingNumber}"

    def jsonOutputInfo = JsonOutput.toJson(instancesInfo)
    JSON serializedInfo = JSONSerializer.toJSON(jsonOutputInfo, new JsonConfig());
    writeJSON(file: "TestingInstancesInfo.json", json: serializedInfo, pretty: 4)

    uploadFiles("TestingInstancesInfo.json", "/volume1/CIS/WebUSD/State")

    return testingNumber
}


def executeGenTestRefCommand(String osName, Map options, Boolean delete) {
    dir('scripts') {
        bat """
            make_results_baseline.bat ${delete}
        """
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
                        set TOOL_VERSION=${options.version}
                        run.bat \"${testsPackageName}\" \"${testsNames}\" ${options.mode.toLowerCase()} ${options.testCaseRetries} ${options.updateRefs} 1>> \"../${options.stageName}_${options.currentTry}.log\"  2>&1
                    """
                }
                break
            case 'Web':
                // TODO: rename system name
                dir("scripts") {
                    sh """
                        set TOOL_VERSION=${options.version}
                        run.bat \"${testsPackageName}\" \"${testsNames}\" ${options.mode.toLowerCase()} ${options.testCaseRetries} ${options.updateRefs} 1>> \"../${options.stageName}_${options.currentTry}.log\"  2>&1
                    """
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
                cleanWS("Windows")
                checkoutScm(branchName: options.testsBranch, repositoryUrl: options.testRepo)

                dir("driver") {
                    if (osName == "Windows") {
                        downloadFiles("/volume1/CIS/WebUSD/Drivers/chromedriver_desktop.exe", ".")
                        bat("rename chromedriver_desktop.exe chromedriver.exe")
                    } else {
                        downloadFiles("/volume1/CIS/WebUSD/Drivers/chromedriver_web.exe", ".")
                        bat("rename chromedriver_web.exe chromedriver.exe")
                    }
                }
            }
        }

        if (osName == "Windows") {
            withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.INSTALL_PLUGIN) {
                timeout(time: "10", unit: "MINUTES") {
                    getProduct("Windows", options)

                    def installedProductCode = powershell(script: """(Get-WmiObject -Class Win32_Product -Filter \"Name LIKE 'AMD RenderStudio'\").IdentifyingNumber""", returnStdout: true)

                    // TODO: compare product code of built application and installed application
                    if (installedProductCode) {
                        println("[INFO] Found installed AMD RenderStudio. Uninstall it...")
                        uninstallMSI("AMD RenderStudio", options.stageName, options.currentTry)
                    }

                    dir("${CIS_TOOLS}\\..\\PluginsBinaries") {
                        bat "msiexec.exe /i ${options[getProduct.getIdentificatorKey('Windows', options)]}.msi /qb"
                    }
                }
            }

            withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.DOWNLOAD_SCENES) {
                String assetsDir = isUnix() ? "${CIS_TOOLS}/../TestResources/render_studio_autotests_assets" : "/mnt/c/TestResources/render_studio_autotests_assets"
                downloadFiles("/volume1/web/Assets/render_studio_autotests/", assetsDir)
            }
        } else {
            withCredentials([string(credentialsId: "WebUsdUrlTemplate", variable: "TEMPLATE")]) {
                String url

                if (options.deployEnvironment == "prod") {
                    url = TEMPLATE.replace("<instance>.", "")
                } else {
                    url = TEMPLATE.replace("<instance>", options.deployEnvironment)
                }

                String localConfigContent = readFile("local_config.py").replace("<domain_name>", url)
                writeFile(file: "local_config.py", text: localConfigContent)
            }
        }

        options.REF_PATH_PROFILE = "/volume1/Baselines/render_studio_autotests/${asicName}-${osName}-${options.mode}"

        outputEnvironmentInfo("Windows", "", options.currentTry)

        if (options["updateRefs"].contains("Update")) {
            withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.EXECUTE_TESTS) {
                executeTestCommand("Windows", asicName, options)
                executeGenTestRefCommand("Windows", options, options["updateRefs"].contains("clean"))
                uploadFiles("./Work/GeneratedBaselines/", options.REF_PATH_PROFILE)
                // delete generated baselines when they're sent 
                bat """
                    if exist Work\\GeneratedBaselines rmdir /Q /S Work\\GeneratedBaselines
                """
            }
        } else {
            withNotifications(title: options["stageName"], printMessage: true, options: options, configuration: NotificationConfiguration.COPY_BASELINES) {
                String baselineDir = isUnix() ? "${CIS_TOOLS}/../TestResources/render_studio_autotests_baselines" : "/mnt/c/TestResources/render_studio_autotests_baselines"
                println "[INFO] Downloading reference images for ${options.tests}-${options.mode}"
                options.tests.split(" ").each() {
                    if (it.contains(".json")) {
                        downloadFiles("${options.REF_PATH_PROFILE}/", baselineDir)
                    } else {
                        downloadFiles("${options.REF_PATH_PROFILE}/${it}", baselineDir)
                    }
                }
            }
            withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.EXECUTE_TESTS) {
                executeTestCommand("Windows", asicName, options)
            }
        }
        options.executeTestsFinished = true

        utils.compareDriverVersion(this, "${options.stageName}_${options.currentTry}.log", "Windows")
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
                utils.moveFiles(this, "Windows", "../*.log", ".")
                utils.moveFiles(this, "Windows", "../scripts/*.log", ".")
                utils.renameFile(this, "Windows", "launcher.engine.log", "${options.stageName}_${options.currentTry}.log")
            }
            archiveArtifacts artifacts: "${options.stageName}/*.log", allowEmptyArchive: true
            if (stashResults) {
                dir('Work') {
                    if (fileExists("Results/RenderStudio/session_report.json")) {

                        def sessionReport = readJSON file: 'Results/RenderStudio/session_report.json'

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
                                uninstallMSI("AMD RenderStudio", options.stageName, options.currentTry)
                                removeInstaller(osName: "Windows", options: options, extension: "msi")
                                String errorMessage = (options.currentTry < options.nodeReallocateTries) ? "All tests were marked as error. The test group will be restarted." : "All tests were marked as error."
                                throw new ExpectedExceptionWrapper(errorMessage, new Exception(errorMessage))
                            }
                        }

                        if (options.reportUpdater) {
                            options.reportUpdater.updateReport(options.mode)
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
    }
}


String patchSubmodule() {
    String commitSHA

    if (isUnix()) {
        commitSHA = sh (script: "git log --format=%h -1 ", returnStdout: true).trim()
    } else {
        commitSHA = bat (script: "git log --format=%%h -1 ", returnStdout: true).split('\r\n')[2].trim()
    }

    String version = readFile("VERSION.txt").trim()
    writeFile(file: "VERSION.txt", text: "Version: ${version}. Hash: ${commitSHA}")
}


def patchVersions(Map options) {
    dir("WebUsdLiveServer") {
        patchSubmodule()
    }

    dir("WebUsdRouteServer") {
        patchSubmodule()
    }

    dir("WebUsdStorageServer") {
        patchSubmodule()
    }

    dir("WebUsdFrontendServer") {
        patchSubmodule()
    }

    dir("WebUsdStreamServer") {
        patchSubmodule()
    }

    String version = readFile("VERSION.txt").trim()

    if (env.CHANGE_URL) {
        writeFile(file: "VERSION.txt", text: "Version: ${version}. PR: #${env.CHANGE_ID}. Build: #${env.BUILD_NUMBER}. Hash: ${options.commitShortSHA}")
    } else {
        writeFile(file: "VERSION.txt", text: "Version: ${version}. Branch: ${env.BRANCH_NAME ?: options.projectBranch}. Build: #${env.BUILD_NUMBER}. Hash: ${options.commitShortSHA}")
    }
}


def executeBuildWindows(Map options) {
    options["stage"] = "Build"

    withNotifications(title: "Windows", options: options, configuration: NotificationConfiguration.BUILD_SOURCE_CODE_WEBUSD) {
        utils.reboot(this, "Windows")

        Boolean failure = false
        String webrtcPath = "C:\\JN\\thirdparty\\webrtc"
        String amfPath = "C:\\JN\\thirdparty\\amf"

        downloadFiles("/volume1/CIS/radeon-pro/webrtc-win/", webrtcPath.replace("C:", "/mnt/c").replace("\\", "/"), , "--quiet")
        downloadFiles("/volume1/CIS/WebUSD/AMF-WIN", amfPath.replace("C:", "/mnt/c").replace("\\", "/"), , "--quiet")

        downloadFiles("/volume1/CIS/WebUSD/Additional/envs/webusd.env.win", "${env.WORKSPACE.replace('C:', '/mnt/c').replace('\\', '/')}/WebUsdFrontendServer", "--quiet")
        bat "move WebUsdFrontendServer\\webusd.env.win WebUsdFrontendServer\\.env.production"

        String frontendVersion
        String renderStudioVersion

        dir("WebUsdFrontendServer") {
            frontendVersion = readFile("VERSION.txt").trim()
        }

        renderStudioVersion = readFile("VERSION.txt").trim()

        String envProductionContent = readFile("./WebUsdFrontendServer/.env.production")
        envProductionContent = envProductionContent + "VUE_APP_FRONTEND_VERSION=${frontendVersion}\nVUE_APP_RENDER_STUDIO_VERSION=${renderStudioVersion}"
        writeFile(file: "./WebUsdFrontendServer/.env.production", text: envProductionContent)

        try {
            withEnv(["PATH=c:\\CMake322\\bin;c:\\python37\\;c:\\python37\\scripts\\;${PATH}"]) {
                bat """
                    call "C:\\Program Files (x86)\\Microsoft Visual Studio\\2019\\Professional\\VC\\Auxiliary\\Build\\vcvars64.bat" >> ${STAGE_NAME}.EnvVariables.log 2>&1
                    cmake --version >> ${STAGE_NAME}.Build.log 2>&1
                    python--version >> ${STAGE_NAME}.Build.log 2>&1
                    python -m pip install conan >> ${STAGE_NAME}.Build.log 2>&1
                    mkdir Build
                    echo [WebRTC] >> Build\\LocalBuildConfig.txt
                    echo path = ${webrtcPath.replace("\\", "/")}/src >> Build\\LocalBuildConfig.txt
                    echo [AMF] >> Build/LocalBuildConfig.txt
                    echo path = ${amfPath.replace("\\", "/")}/AMF-WIN >> Build\\LocalBuildConfig.txt
                    python Tools/Build.py -v >> ${STAGE_NAME}.Build.log 2>&1
                """
                println("[INFO] Start building installer")
                bat """
                    python Tools/Package.py -v >> ${STAGE_NAME}.Package.log 2>&1
                """

                println("[INFO] Saving exe files to NAS")

                dir("WebUsdFrontendServer\\dist_electron") {
                    def exeFile = findFiles(glob: '*.msi')
                    println("Found MSI files: ${exeFile}")
                    for (file in exeFile) {
                        renamedFilename = file.toString().replace(" ", "_")

                        if (options.branchPostfix) {
                            String filenameWithPostfix = file.toString().replace(" ", "_").replace(".msi", "(${options.branchPostfix}).msi")

                            bat """
                                rename "${file}" "${filenameWithPostfix}"
                            """

                            makeArchiveArtifacts(name: filenameWithPostfix, storeOnNAS: true)

                            bat """
                                rename "${filenameWithPostfix}" "${renamedFilename}"
                            """
                        } else {
                            bat """
                                rename "${file}" "${renamedFilename}"
                            """  

                            makeArchiveArtifacts(name: renamedFilename, storeOnNAS: true)
                        }

                        makeStash(includes: renamedFilename, name: getProduct.getStashName("Windows", options), preZip: false, storeOnNAS: options.storeOnNAS)
                    }
                }
            }
        } catch(e) {
            println("Error during build on Windows")
            println(e.toString())
            failure = true
        } finally {
            archiveArtifacts "*.log"
            if (failure) {
                currentBuild.result = "FAILED"
                error "error during build"
            }
        } 
    }
}


def executeBuildLinux(Map options) {
    Boolean failure = false

    String webUsdUrlBase

    withCredentials([string(credentialsId: "WebUsdHost", variable: "remoteHost")]) {
        webUsdUrlBase = remoteHost
    }

    Integer testingNumber

    if (options.deployEnvironment == "pr") {
        testingNumber = getNextTestInstanceNumber(options)
        options.deployEnvironment = "test${testingNumber}"
    }

    String envProductionContent

    if (!options.customDomain) {
        downloadFiles("/volume1/CIS/WebUSD/Additional/envs/webusd.env.${options.deployEnvironment}", "./WebUsdFrontendServer", "--quiet")
        sh "mv ./WebUsdFrontendServer/webusd.env.${options.deployEnvironment} ./WebUsdFrontendServer/.env.production"
    } else {
        downloadFiles("/volume1/CIS/WebUSD/Additional/envs/template", "./WebUsdFrontendServer", "--quiet")
        sh "mv ./WebUsdFrontendServer/template ./WebUsdFrontendServer/.env.production"

        envProductionContent = readFile("./WebUsdFrontendServer/.env.production")
        envProductionContent = envProductionContent.replaceAll("<custom_domain>", options.customDomain)
        writeFile(file: "./WebUsdFrontendServer/.env.production", text: envProductionContent)
    }

    if (options.disableSsl) {
        envProductionContent = readFile("./WebUsdFrontendServer/.env.production")
        envProductionContent = envProductionContent.replaceAll("https", "http").replaceAll("wss", "ws")
        writeFile(file: "./WebUsdFrontendServer/.env.production", text: envProductionContent)
    }

    String frontendVersion
    String renderStudioVersion

    dir("WebUsdFrontendServer") {
        frontendVersion = readFile("VERSION.txt").trim()
    }

    renderStudioVersion = readFile("VERSION.txt").trim()

    envProductionContent = readFile("./WebUsdFrontendServer/.env.production")
    envProductionContent = envProductionContent + "\nVUE_APP_FRONTEND_VERSION=${frontendVersion}\nVUE_APP_RENDER_STUDIO_VERSION=${renderStudioVersion}"
    writeFile(file: "./WebUsdFrontendServer/.env.production", text: envProductionContent)

    options["stage"] = "Build"

    withNotifications(title: "Web", options: options, configuration: NotificationConfiguration.BUILD_SOURCE_CODE_WEBUSD) {
        println "[INFO] Start build" 
        println "[INFO] Download Web-rtc and AMF" 
        downloadFiles("/volume1/CIS/radeon-pro/webrtc-linux/", "${CIS_TOOLS}/../thirdparty/webrtc", "--quiet")
        downloadFiles("/volume1/CIS/WebUSD/AMF/", "${CIS_TOOLS}/../thirdparty/AMF", "--quiet")
        try {
            println "[INFO] Start build"

            sh """
                mkdir --parents Build/Install
            """

            sh """
                cmake --version >> ${STAGE_NAME}.Build.log 2>&1
                python3 --version >> ${STAGE_NAME}.Build.log 2>&1
                python3 -m pip install conan >> ${STAGE_NAME}.Build.log 2>&1
                echo "[WebRTC]" >> Build/LocalBuildConfig.txt
                echo "path = ${CIS_TOOLS}/../thirdparty/webrtc/src" >> Build/LocalBuildConfig.txt
                echo "[AMF]" >> Build/LocalBuildConfig.txt
                echo "path = ${CIS_TOOLS}/../thirdparty/AMF/Install" >> Build/LocalBuildConfig.txt
                export OS=
                python3 Tools/Build.py -v >> ${STAGE_NAME}.Build.log 2>&1
            """

            println("[INFO] Start building & sending docker containers to repo")
            String deployArgs = "-ba -da"
            containersBaseName = "docker.${webUsdUrlBase}/"

            env["WEBUSD_BUILD_REMOTE_HOST"] = webUsdUrlBase
            env["WEBUSD_BUILD_LIVE_CONTAINER_NAME"] = containersBaseName + "live"
            env["WEBUSD_BUILD_ROUTE_CONTAINER_NAME"] = containersBaseName + "route"
            env["WEBUSD_BUILD_STORAGE_CONTAINER_NAME"] = containersBaseName + "storage"
            env["WEBUSD_BUILD_STREAM_CONTAINER_NAME"] = containersBaseName + "stream"
            env["WEBUSD_BUILD_WEB_CONTAINER_NAME"] = containersBaseName + "web"

            sh """python3 Tools/Docker.py $deployArgs -v -c $options.deployEnvironment >> ${STAGE_NAME}.Docker.log 2>&1"""

            println("[INFO] Finish building & sending docker containers to repo")

            if (options.generateArtifact){
                sh """
                    tar -C Build/Install -czvf "WebUsdViewer_Ubuntu20.tar.gz" .
                """
                archiveArtifacts "WebUsdViewer_Ubuntu20.tar.gz"
            }
        } catch(e) {
            println("Error during build on Linux")
            println(e.toString())
            failure = true
        } finally {
            archiveArtifacts "*.log"
            if (failure) {
                currentBuild.result = "FAILED"
                error "error during build"
            }
        }
        
    }

    withNotifications(title: "Web", options: options, configuration: NotificationConfiguration.DEPLOY_APPLICATION) {
        if (options.deploy) {
            println "[INFO] Start deploying on $options.deployEnvironment environment"
            failure = false

            try {
                println "[INFO] Start deploy"

                render_studio_deploy(options.deployEnvironment)

                withCredentials([string(credentialsId: "WebUsdUrlTemplate", variable: "TEMPLATE")]) {
                    String url

                    if (options.deployEnvironment == "prod") {
                        url = TEMPLATE.replace("<instance>.", "")
                    } else {
                        url = TEMPLATE.replace("<instance>", options.deployEnvironment)
                    }

                    rtp(nullAction: "1", parserName: "HTML", stableText: """<h3><a href="${url}">[${options.deployEnvironment}] Link to web application</a></h3>""")
                }
            } catch (e) {
                println "[ERROR] Error during deploy"
                println(e.toString())
                failure = true
            } finally {
                if (failure) {
                    currentBuild.result = "FAILED"
                    error "error during deploy"
                }
            }
        }
    }

    withNotifications(title: "Web", options: options, configuration: NotificationConfiguration.NOTIFY_BY_TG) {
        if (options.deploy) {
            notifyByTg(options)
        }
    }
}


def executeBuild(String osName, Map options) {  
    try {
        withNotifications(title: osName, options: options, configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
            cleanWS(osName == "Windows" ? "Windows" : "Ubuntu20")

            checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo)

            patchVersions(options)

            if (options.customHybridLinux && isUnix()) {
                sh """
                    curl --insecure --retry 5 -L -o HybridPro.tar.xz ${options.customHybridLinux}
                """

                sh "tar -xJf HybridPro.tar.xz"

                sh """
                    yes | cp -rf BaikalNext/bin/* WebUsdStreamServer/RadeonProRenderUSD/deps/RPR/RadeonProRender/binUbuntu18
                    yes | cp -rf BaikalNext/inc/* WebUsdStreamServer/RadeonProRenderUSD/deps/RPR/RadeonProRender/inc
                    yes | cp -rf BaikalNext/inc/Rpr/* WebUsdStreamServer/RadeonProRenderUSD/deps/RPR/RadeonProRender/inc
                """

                dir ("WebUsdStreamServer/RadeonProRenderUSD/deps/RPR/RadeonProRender/rprTools") {
                    downloadFiles("/volume1/CIS/WebUSD/Additional/RadeonProRenderCpp.cpp", ".")
                }
            } else if (options.customHybridWin && !isUnix()) {
                bat """
                    curl --insecure --retry 5 -L -o HybridPro.zip ${options.customHybridWin}
                """

                unzip dir: '.', glob: '', zipFile: 'HybridPro.zip'

                bat """
                    copy /Y BaikalNext\\bin\\* WebUsdStreamServer\\RadeonProRenderUSD\\deps\\RPR\\RadeonProRender\\binWin64
                    copy /Y BaikalNext\\inc\\* WebUsdStreamServer\\RadeonProRenderUSD\\deps\\RPR\\RadeonProRender\\inc
                    copy /Y BaikalNext\\inc\\Rpr\\* WebUsdStreamServer\\RadeonProRenderUSD\\deps\\RPR\\RadeonProRender\\inc
                    copy /Y BaikalNext\\lib\\* WebUsdStreamServer\\RadeonProRenderUSD\\deps\\RPR\\RadeonProRender\\libWin64
                """

                dir ("WebUsdStreamServer/RadeonProRenderUSD/deps/RPR/RadeonProRender/rprTools") {
                    downloadFiles("/volume1/CIS/WebUSD/Additional/RadeonProRenderCpp.cpp", ".")
                }
            }
        }

        outputEnvironmentInfo(osName == "Windows" ? "Windows" : "Ubuntu20")

        switch(osName) {
            case 'Windows':
                options[getProduct.getIdentificatorKey(osName, options)] = bat(script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
                executeBuildWindows(options)
                break
            case 'Web':
                executeBuildLinux(options)
                break
            default:
                println "[WARNING] ${osName} is not supported"
        }
    } catch (e) {
        currentBuild.result = "FAILED"
        throw e
    } finally {
        archiveArtifacts "*.log"
    }
}


def notifyByTg(Map options){
    println currentBuild.result

    String statusMessage = (currentBuild.result != null && currentBuild.result.contains("FAILED")) ? "Failed" : "Success"
    Boolean isPR = env.CHANGE_URL != null
    String branchName = env.CHANGE_URL ?: options.projectBranch

    if (branchName.contains("origin")){
        branchName = branchName.split("/", 2)[1]
    }

    String branchURL = isPR ? env.CHANGE_URL : "https://github.com/Radeon-Pro/WebUsdViewer/tree/${branchName}" 
    withCredentials([string(credentialsId: "WebUsdTGBotHost", variable: "tgBotHost")]){
        res = sh(
            script: "curl -X POST ${tgBotHost}/auto/notifications -H 'Content-Type: application/json' -d '{\"status\":\"${statusMessage}\",\"build_url\":\"${env.BUILD_URL}\", \"branch_url\": \"${branchURL}\", \"is_pr\": ${isPR}, \"user\": \"${options.commitAuthor}\"}'",
            returnStdout: true,
            returnStatus: true
        )
    }
}


def getReportBuildArgs(String mode, Map options) {
    if (options["isPreBuilt"]) {
        return """RenderStudio "PreBuilt" "PreBuilt" "PreBuilt" \"${utils.escapeCharsByUnicode(mode)}\" """
    } else {
        return """RenderStudio ${options.commitSHA} ${options.projectBranchName} \"${utils.escapeCharsByUnicode(options.commitMessage)}\" \"${utils.escapeCharsByUnicode(mode)}\" """
    }
}


def fillDescription(Map options) {
    currentBuild.description = "<b>Project branch:</b> ${options.projectBranchName}<br/>"
    currentBuild.description += "<b>Commit author:</b> ${options.commitAuthor}<br/>"
    currentBuild.description += "<b>Commit message:</b> ${options.commitMessage}<br/>"
    currentBuild.description += "<b>Commit SHA:</b> ${options.commitSHA}<br/>"

    currentBuild.description += "<br/>"

    currentBuild.description += "<b>Render Studio verion:</b> ${options.version}<br/>"

    dir("WebUsdFrontendServer") {
        String version = readFile("VERSION.txt").trim()
        currentBuild.description += "<b>Frontend verion:</b> ${version}<br/>"
    }

    dir("WebUsdStreamServer") {
        String version = readFile("VERSION.txt").trim()
        currentBuild.description += "<b>Streamer verion:</b> ${version}<br/>"
    }

    dir("WebUsdLiveServer") {
        String version = readFile("VERSION.txt").trim()
        currentBuild.description += "<b>Live server verion:</b> ${version}<br/>"
    }

    dir("WebUsdRouteServer") {
        String version = readFile("VERSION.txt").trim()
        currentBuild.description += "<b>Router verion:</b> ${version}<br/>"
    }

    dir("WebUsdStorageServer") {
        String version = readFile("VERSION.txt").trim()
        currentBuild.description += "<b>Storage verion:</b> ${version}<br/>"
    }
}


def executePreBuild(Map options) {
    options.executeTests = true

    ws("WebUSD-prebuild") {
        checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo)
        options.commitAuthor = bat (script: "git show -s --format=%%an HEAD ",returnStdout: true).split('\r\n')[2].trim()
        options.commitMessage = bat (script: "git log --format=%%B -n 1", returnStdout: true).split('\r\n')[2].trim()
        options.commitSHA = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
        options.commitShortSHA = bat (script: "git log --format=%%h -1 ", returnStdout: true).split('\r\n')[2].trim()

        if (options["executeBuild"]) {
            // get links to the latest built HybridPro
            def rawInfo = httpRequest(
                url: "${env.JENKINS_URL}/job/RadeonProRender-Hybrid/job/master/api/json?tree=lastCompletedBuild[number,url]",
                authentication: 'jenkinsCredentials',
                httpMode: 'GET'
            )

            def parsedInfo = parseResponse(rawInfo.content)

            withCredentials([string(credentialsId: "nasURLFrontend", variable: "REMOTE_HOST")]) {
                options.customHybridWin = "${REMOTE_HOST}/RadeonProRender-Hybrid/master/${parsedInfo.lastCompletedBuild.number}/Artifacts/BaikalNext_Build-Windows.zip"
                options.customHybridLinux = "${REMOTE_HOST}/RadeonProRender-Hybrid/master/${parsedInfo.lastCompletedBuild.number}/Artifacts/BaikalNext_Build-Ubuntu20.tar.xz"
            }

            rtp(nullAction: "1", parserName: "HTML", stableText: """<h3><a href="${parsedInfo.lastCompletedBuild.url}">[HybridPro] Link to the used HybridPro build</a></h3>""")

            // branch postfix
            options["branchPostfix"] = ""
            if (env.BRANCH_NAME) {
                options["branchPostfix"] = "auto" + "_" + env.BRANCH_NAME.replace('/', '-').replace('origin-', '') + "_" + env.BUILD_NUMBER
            } else {
                options["branchPostfix"] = "manual" + "_" + options.projectBranch.replace('/', '-').replace('origin-', '') + "_" + env.BUILD_NUMBER
            }
        }

        withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.INCREMENT_VERSION) {
            String version = readFile("VERSION.txt").trim()

            if (env.BRANCH_NAME) {
                withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.CREATE_GITHUB_NOTIFICATOR) {
                    GithubNotificator githubNotificator = new GithubNotificator(this, options)
                    githubNotificator.init(options)
                    options["githubNotificator"] = githubNotificator
                    githubNotificator.initPreBuild("${BUILD_URL}")
                    options.projectBranchName = githubNotificator.branchName
                }
            
                if ((env.BRANCH_NAME == "develop" || env.BRANCH_NAME == "main") && options.commitAuthor != "radeonprorender") {
                    println "[INFO] Incrementing version of change made by ${options.commitAuthor}."
                    println "[INFO] Current build version: ${version}"

                    if (env.BRANCH_NAME == "main") {
                        version = version_inc(version, 2)
                    } else {
                        version = version_inc(version, 3)
                    }

                    println "[INFO] New build version: ${version}"
                    writeFile(file: "VERSION.txt", text: version)

                    bat """
                        git commit VERSION.txt -m "buildmaster: version update to ${version}"
                        git push origin HEAD:${env.BRANCH_NAME}
                    """

                    //get commit's sha which have to be build
                    options.commitSHA = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
                    options.commitShortSHA = bat (script: "git log --format=%%h -1 ", returnStdout: true).split('\r\n')[2].trim()
                    options.projectBranch = options.commitSHA
                    println "[INFO] Project branch hash: ${options.projectBranch}"
                }
            } else {
                options.projectBranchName = options.projectBranch
            }

            options.version = version

            fillDescription(options)
        }
    }

    def tests = []
    options.timeouts = [:]

    withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.CONFIGURE_TESTS) {
        dir('jobs_test_web_viewer') {
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

                // modify name of tests package if tests package is non-splitted (it will be use for run package few time with different modes)
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

                options.tests = tempTests

                options.tests.each() {
                    def xml_timeout = utils.getTimeoutFromXML(this, "${it}", "simpleRender.py", options.ADDITIONAL_XML_TIMEOUT)
                    options.timeouts["${it}"] = (xml_timeout > 0) ? xml_timeout : options.TEST_TIMEOUT
                }
                options.modes.each { mode ->
                    options.tests.each() {
                        tests << "${it}-${mode}"
                    }
                }

                modifiedPackageName = modifiedPackageName.replace('~,', '~')

                if (options.isPackageSplitted) {
                    options.testsPackage = "none"
                } else {
                    options.testsPackage = modifiedPackageName
                    // check that package is splitted to parts or not
                    if (packageInfo["groups"] instanceof Map) {
                        options.modes.each { mode ->
                            tests << "${modifiedPackageName}-${mode}"
                        } 
                        options.timeouts[options.testsPackage] = options.NON_SPLITTED_PACKAGE_TIMEOUT + options.ADDITIONAL_XML_TIMEOUT
                    } else {
                        // add group stub for each part of package
                        options.modes.each { mode ->
                            for (int i = 0; i < packageInfo["groups"].size(); i++) {
                                tests << "${modifiedPackageName}-${mode}".replace(".json", ".${i}.json")
                            }
                        }

                        for (int i = 0; i < packageInfo["groups"].size(); i++) {
                            options.timeouts[options.testsPackage.replace(".json", ".${i}.json")] = options.NON_SPLITTED_PACKAGE_TIMEOUT + options.ADDITIONAL_XML_TIMEOUT
                        }
                    }
                }
            } else if (options.tests) {
                options.tests =  options.tests.split(" ") as List
                options.tests.each() {
                    def xml_timeout = utils.getTimeoutFromXML(this, it, "simpleRender.py", options.ADDITIONAL_XML_TIMEOUT)
                    options.timeouts["${it}"] = (xml_timeout > 0) ? xml_timeout : options.TEST_TIMEOUT
                }
                options.modes.each { mode ->
                    options.tests.each() {
                        tests << "${it}-${mode}"
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


def executeDeploy(Map options, List platformList, List testResultList, String mode) {
    cleanWS()
    try {
        String modeName = options.displayingTestProfiles[mode]

        if (options['executeTests'] && testResultList) {
            withNotifications(title: "Building test report for ${modeName}", options: options, startUrl: "${BUILD_URL}", configuration: NotificationConfiguration.DOWNLOAD_TESTS_REPO) {
                checkoutScm(branchName: options.testsBranch, repositoryUrl: options.testRepo)
            }

            List lostStashes = []

            dir("summaryTestResults") {
                testResultList.each() {
                    if (it.endsWith(mode)) {
                        List testNameParts = it.replace("testResult-", "").split("-") as List

                        if (filter(options, testNameParts.get(0), testNameParts.get(1), testNameParts.get(2), mode)) {
                            return
                        }

                        String testName = testNameParts.subList(0, testNameParts.size() - 1).join("-")
                        dir(testName) {
                            try {
                                makeUnstash(name: "$it", storeOnNAS: options.storeOnNAS)
                            } catch(e) {
                                echo "[ERROR] Failed to unstash ${it}"
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
                        count_lost_tests.bat \"${lostStashes}\" .. ..\\summaryTestResults \"${options.splitTestsExecution}\" \"${options.testsPackage}\" \"[]\" \"${mode}\" \"{}\"
                    """
                }
            } catch (e) {
                println("[ERROR] Can't generate number of lost tests")
            }

            try {
                GithubNotificator.updateStatus("Deploy", "Building test report for ${modeName}", "in_progress", options, NotificationConfiguration.BUILDING_REPORT, "${BUILD_URL}")

                withEnv(["JOB_STARTED_TIME=${options.JOB_STARTED_TIME}", "BUILD_NAME=${options.baseBuildName}"]) {
                    dir("jobs_launcher") {
                        List retryInfoList = utils.deepcopyCollection(this, options.nodeRetry)
                        retryInfoList.each{ gpu ->
                            gpu['Tries'].each{ group ->
                                group.each{ groupKey, retries ->
                                    if (groupKey.endsWith(mode)) {
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
                            bat "build_reports.bat ..\\summaryTestResults ${getReportBuildArgs(modeName, options)}"
                        } catch (e) {
                            String errorMessage = utils.getReportFailReason(e.getMessage())
                            GithubNotificator.updateStatus("Deploy", "Building test report for ${modeName}", "failure", options, errorMessage, "${BUILD_URL}")
                            if (utils.isReportFailCritical(e.getMessage())) {
                                throw e
                            } else {
                                currentBuild.result = "FAILURE"
                                options.problemMessageManager.saveGlobalFailReason(errorMessage)
                            }
                        }
                    }
                }
            } catch(e) {
                String errorMessage = utils.getReportFailReason(e.getMessage())
                options.problemMessageManager.saveSpecificFailReason(errorMessage, "Deploy")
                GithubNotificator.updateStatus("Deploy", "Building test report for ${modeName}", "failure", options, errorMessage, "${BUILD_URL}")
                println("[ERROR] Failed to build test report.")
                println(e.toString())
                println(e.getMessage())
                if (!options.testDataSaved && !options.storeOnNAS) {
                    try {
                        // Save test data for access it manually anyway
                        utils.publishReport(this, "${BUILD_URL}", "summaryTestResults", "summary_report.html, compare_report.html", \
                            "Test Report ${modeName}", "Summary Report, Compare Report" , options.storeOnNAS, \
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
                options["testsStatus-${mode}"] = readFile("summaryTestResults/slack_status.json")
            } catch(e) {
                println(e.toString())
                println(e.getMessage())
                options["testsStatus-${mode}"] = ""
            }

            withNotifications(title: "Building test report for ${modeName}", options: options, configuration: NotificationConfiguration.PUBLISH_REPORT) {
                utils.publishReport(this, "${BUILD_URL}", "summaryTestResults", "summary_report.html, compare_report.html", \
                    "Test Report ${modeName}", "Summary Report, Compare Report" , options.storeOnNAS, \
                    ["jenkinsBuildUrl": BUILD_URL, "jenkinsBuildName": currentBuild.displayName, "updatable": options.containsKey("reportUpdater")])

                if (summaryTestResults) {
                    // add in description of status check information about tests statuses
                    // Example: Report was published successfully (passed: 69, failed: 11, error: 0)
                    GithubNotificator.updateStatus("Deploy", "Building test report for ${modeName}", "success", options, "${NotificationConfiguration.REPORT_PUBLISHED} Results: passed - ${summaryTestResults.passed}, failed - ${summaryTestResults.failed}, error - ${summaryTestResults.error}.", "${BUILD_URL}/Test_20Report")
                } else {
                    GithubNotificator.updateStatus("Deploy", "Building test report for ${modeName}", "success", options, NotificationConfiguration.REPORT_PUBLISHED, "${BUILD_URL}/Test_20Report")
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


def call(
    String projectBranch = "",
    String testsBranch = "master",
    String platforms = 'Windows:AMD_RX6800XT;Web:Chrome',
    Boolean enableNotifications = false,
    Boolean generateArtifact = true,
    Boolean deploy = true,
    String deployEnvironment = 'pr',
    String customDomain = '',
    Boolean disableSsl = false,
    String testsPackage = "none",
    String tests = 'Viewport FinalRender',
    String updateRefs = 'No',
    Integer testCaseRetries = 5,
    Boolean skipBuild = false,
    String customBuildLinkWindows = ""
) {
    ProblemMessageManager problemMessageManager = new ProblemMessageManager(this, currentBuild)

    if (env.BRANCH_NAME) {
        switch (env.BRANCH_NAME) {
            case "develop":
                deployEnvironment = "dev"
                break
            case "main":
                deployEnvironment = "prod"
                break
        }
    }

    if (skipBuild && !customBuildLinkWindows && platforms.contains("Windows:")) {
        skipBuild = false
    } else if (customBuildLinkWindows && !skipBuild) {
        skipBuild = true
    }

    Boolean isPreBuilt = skipBuild

    List modes = []

    if (platforms.contains("Windows:")) {
        modes.add("Desktop")
    }
    if (platforms.contains("Web:")) {
        modes.add("Web")
    }

    println """
        Deploy: ${deploy}
        Deploy environment: ${deployEnvironment}
        Custom domain: ${customDomain}
        Disable SSL: ${disableSsl}
        Is prebuilt: ${isPreBuilt}
        Modes: ${modes}
    """
    def options = [configuration: PIPELINE_CONFIGURATION,
                                platforms: platforms,
                                projectBranch:projectBranch,
                                projectRepo:PROJECT_REPO,
                                testRepo:TEST_REPO,
                                testsBranch:testsBranch,
                                enableNotifications:enableNotifications,
                                deployEnvironment: deployEnvironment,
                                customDomain: customDomain,
                                disableSsl: disableSsl,
                                deploy:deploy, 
                                PRJ_NAME:'WebUsdViewer',
                                PRJ_ROOT:'radeon-pro',
                                BUILDER_TAG:'BuilderWebUsdViewer',
                                executeBuild:!isPreBuilt,
                                executeTests:true,
                                BUILD_TIMEOUT:'120',
                                TEST_TIMEOUT:60,
                                problemMessageManager:problemMessageManager,
                                isPreBuilt:isPreBuilt,
                                retriesForTestStage:1,
                                splitTestsExecution: true,
                                storeOnNAS: true,
                                flexibleUpdates: true,
                                skipCallback: this.&filter,
                                modes: modes,
                                testsPackage:testsPackage,
                                tests:tests,
                                updateRefs:updateRefs,
                                testCaseRetries:testCaseRetries,
                                executeBuild: !skipBuild,
                                customBuildLinkWindows:customBuildLinkWindows,
                                ADDITIONAL_XML_TIMEOUT:15
                                ]
    try {
        multiplatform_pipeline(platforms, this.&executePreBuild, this.&executeBuild, this.&executeTests, this.&executeDeploy, options)
        if (currentBuild.result == null) {
            currentBuild.result = "SUCCESS"
        }
    } catch(e) {
        currentBuild.result = "FAILURE"
        println(e.toString())
        println(e.getMessage())
        throw e
    } finally {
        String problemMessage = problemMessageManager.publishMessages()
        if (env.CHANGE_URL){
            GithubNotificator.sendPullRequestComment("Jenkins build finished as ${currentBuild.result}", options)
        } 
    }
}
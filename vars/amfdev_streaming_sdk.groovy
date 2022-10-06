import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap
import groovy.json.JsonOutput
import net.sf.json.JSON
import net.sf.json.JSONSerializer
import net.sf.json.JsonConfig
import TestsExecutionType


@Field final String PROJECT_REPO = "https://github.com/amfdev/StreamingSDK.git"
@Field final String TESTS_REPO = "https://github.com/luxteam/jobs_test_streaming_sdk.git"
@Field final String DRIVER_REPO = "https://github.com/amfdev/AMDVirtualDrivers.git"
@Field final String AMF_TESTS_REPO = "https://github.com/amfdev/AMFTests.git"
@Field final Map driverTestsExecuted = new ConcurrentHashMap()
@Field final List WEEKLY_REGRESSION_CONFIGURATION = ["HeavenDX11", "HeavenOpenGL", "ValleyDX11", "ValleyOpenGL", "Dota2Vulkan"]

@Field final PipelineConfiguration PIPELINE_CONFIGURATION = new PipelineConfiguration(
    supportedOS: ["Windows", "Android", "Ubuntu20"],
    testProfile: "engine",
    displayingProfilesMapping: [
        "engine": [
            "Valorant": "Valorant",
            "LoL": "LoL",
            "HeavenDX9": "HeavenDX9",
            "HeavenDX11": "HeavenDX11",
            "HeavenOpenGL": "HeavenOpenGL",
            "ValleyDX9": "ValleyDX9",
            "ValleyDX11": "ValleyDX11",
            "ValleyOpenGL":"ValleyOpenGL", 
            "Dota2DX11": "Dota2DX11",
            "Dota2Vulkan": "Dota2Vulkan", 
            "CSGO": "CSGO",
            "Nothing": "Nothing",
            "Empty": "Empty"
        ]
    ]
)


String getClientLabels(Map options) {
    return "Windows && ${options.TESTER_TAG} && ${options.CLIENT_TAG}"
}

String getMulticonnectionClientLabels(Map options) {
    return "${options.osName} && ${options.TESTER_TAG} && ${options.MULTICONNECTION_CLIENT_TAG}"
}


def getReportBuildArgs(String engineName, Map options) {
    String branchName = env.BRANCH_NAME ?: options.projectBranch
    return "StreamingSDK ${options.commitSHA} ${branchName} \"${utils.escapeCharsByUnicode(options.commitMessage)}\""
}


Boolean isIdleClient(Map options) {
    if (options["osName"] == "Windows") {
        Boolean result = false

        // wait client machine
        def suitableNodes = nodesByLabel label: getClientLabels(options), offline: false

        for (node in suitableNodes) {
            if (utils.isNodeIdle(node)) {
                result = true
            }
        }

        if (options.multiconnectionConfiguration.second_win_client.any { options.tests.contains(it) } || options.tests == "regression.1.json~" || options.tests == "regression.3.json~") {
            result = false

            // wait multiconnection client machine
            suitableNodes = nodesByLabel label: getMulticonnectionClientLabels(options), offline: false

            for (node in suitableNodes) {
                if (utils.isNodeIdle(node)) {
                    result = true
                }
            }
        }

        return result
    } else if (options["osName"] == "Android") {
        // wait when Windows artifact will be built
        return options["finishedBuildStages"]["Windows"]
    } else if (options["osName"] == "Ubuntu20") {
        Boolean result = false

        // wait client machine
        def suitableNodes = nodesByLabel label: getClientLabels(options), offline: false

        for (node in suitableNodes) {
            if (utils.isNodeIdle(node)) {
                result = true
            }
        }

        if (!options["finishedBuildStages"]["Windows"]) {
            result = false
        }

        return result
    }
}


def getClientScreenWidth(String osName, Map options) {
    try {
        switch(osName) {
            case "Windows":
                return powershell(script: "wmic path Win32_VideoController get CurrentHorizontalResolution", returnStdout: true).split()[-1].trim()
            case "Ubuntu20":
                return sh(script: "xdpyinfo | awk '/dimensions/{split(\$2,a,\"x\"); print a[1]}'", returnStdout: true).trim()
            case "OSX":
                println("Unsupported OS")
                break
            default:
                println("Unsupported OS")
        }
    } catch (e) {
        println("[ERROR] Failed to get client screen width")
        println(e)

        return 1920
    }
}


def getClientScreenHeight(String osName, Map options) {
    try {
        switch(osName) {
            case "Windows":
                return powershell(script: "wmic path Win32_VideoController get CurrentVerticalResolution", returnStdout: true).split()[-1].trim()
            case "Ubuntu20":
                return sh(script: "xdpyinfo | awk '/dimensions/{split(\$2,a,\"x\"); print a[2]}'", returnStdout: true).trim()
            case "OSX":
                println("Unsupported OS")
                break
            default:
                println("Unsupported OS")
        }
    } catch (e) {
        println("[ERROR] Failed to get client screen height")
        println(e)

        return 1080
    }
}


def prepareTool(String osName, Map options) {
    switch(osName) {
        case "Windows":
            makeUnstash(name: "ToolWindows", unzip: false, storeOnNAS: options.storeOnNAS)
            unzip(zipFile: "${options.winTestingBuildName}.zip")

            if (options["engine"] == "Empty" && options["parsedTests"].contains("Latency")) {
                makeUnstash(name: "LatencyToolWindows", unzip: false, storeOnNAS: options.storeOnNAS)
                unzip(zipFile: "LatencyTool_Windows.zip")
            }
            break
        case "Android":
            makeUnstash(name: "ToolAndroid", unzip: false, storeOnNAS: options.storeOnNAS)
            unzip(zipFile: "android_${options.androidTestingBuildName}.zip")
            utils.renameFile(this, "Windows", "app-arm-${options.androidTestingBuildName}.apk", "app-arm.apk")
            break
        case "Ubuntu20":
            makeUnstash(name: "ToolUbuntu20", unzip: false, storeOnNAS: options.storeOnNAS)
            unzip(zipFile: "StreamingSDK_Ubuntu20.zip")
            sh("chmod u+x RemoteGameServer")
            break
        case "OSX":
            println("Unsupported OS")
            break
        default:
            println("Unsupported OS")
    }
}


def unpackDriver(String osName, Map options) {
    switch(osName) {
        case "Windows":
            makeUnstash(name: "DriverWindows", unzip: false, storeOnNAS: options.storeOnNAS)
            unzip(zipFile: "${options.winTestingDriverName}.zip")
            break
        default:
            println("Unsupported OS")
    } 
}


def uninstallDriver(Map options) {
    try {
        powershell """
            \$command = "cd ${WORKSPACE}\\AMDVirtualDrivers; .\\uninstall.bat | Out-File ..\\${options.stageName}_${options.currentTry}_uninstall_driver.log"
            Start-Process powershell "\$command" -Verb RunAs -Wait
        """
    } catch (e) {
        println("[ERROR] Failed to uninstall driver")
        throw e
    }
}


def runDriverTests(Map options) {
    String title = "Driver tests"
    String logName = "${options.stageName}_${options.currentTry}_test_driver.log"
    String url = "${env.BUILD_URL}/artifact/${logName}"

    try {
        // start script which agree to install unsigned driver
        powershell """
            \$command = "C:\\Python39\\python.exe \$env:CIS_TOOLS\\register_dev_driver.py"
            Start-Process powershell "\$command" -Verb RunAs
        """

        powershell """
            \$command = "cd ${WORKSPACE}\\AMDVirtualDrivers; .\\AMDHidTests.exe | Out-File ..\\${logName}"
            Start-Process powershell "\$command" -Verb RunAs -Wait
        """

        dir("..") {
            archiveArtifacts artifacts: logName, allowEmptyArchive: true
        }

        String driverTestsLog = readFile("..\\${logName}")
        String status = driverTestsLog.contains("FAILED") ? "action_required" : "success"
        String description = driverTestsLog.contains("FAILED") ? "Testing finished with error" : "Testing successfully finished"

        GithubNotificator.updateStatus("Test", title, status, options, description, url)
    } catch (e) {
        println("[ERROR] Failed to run driver tests")
        throw e
    } finally {
        driverTestsExecuted["executed"] = true
    }
}


def getServerIpAddress(String osName) {
    switch(osName) {
        case "Windows":
            return bat(script: "echo %IP_ADDRESS%",returnStdout: true).split('\r\n')[2].trim()
            break
        case "Ubuntu20":
            return sh(script: "echo \$IP_ADDRESS",returnStdout: true).trim()
            break
        default:
            println("Unsupported OS")
    }
}


def getGPUName(String osName) {
    try {
        dir("jobs_launcher") {
            dir("core") {
                switch(osName) {
                    case "Windows":
                        return python3("-c \"from system_info import get_gpu; print(get_gpu())\"").split('\r\n')[2].trim()
                        break
                    case "Ubuntu20":
                        return python3("-c \"from system_info import get_gpu; print(get_gpu())\"").trim()
                        break
                    default:
                        println("Unsupported OS")
                }
            }
        }
    } catch (e) {
        println("[ERROR] Failed to get GPU name")
        throw e
    }
}


def getOSName(String osName) {
    try {
        dir("jobs_launcher") {
            dir("core") {
                switch(osName) {
                    case "Windows":
                        return python3("-c \"from system_info import get_os; print(get_os())\"").split('\r\n')[2].trim()
                        break
                    case "Ubuntu20":
                        return python3("-c \"from system_info import get_os; print(get_os())\"").trim()
                        break
                    default:
                        println("Unsupported OS")
                }
            }
        }
    } catch (e) {
        println("[ERROR] Failed to get OS name")
        throw e
    }
}


def getCommunicationPort(String osName) {
    switch(osName) {
        case "Windows":
            return bat(script: "echo %COMMUNICATION_PORT%",returnStdout: true).split('\r\n')[2].trim()
            break
        case "Ubuntu20":
            return sh(script: "echo \$COMMUNICATION_PORT",returnStdout: true).trim()
            break
        default:
            println("Unsupported OS")
    }
}


def closeGames(String osName, Map options, String gameName) {
    try {
        switch(osName) {
            case "Windows":
            case "Android":
                if (gameName == "All") {
                    bat """
                        taskkill /f /im \"borderlands3.exe\"
                        taskkill /f /im \"VALORANT-Win64-Shipping.exe\"
                        taskkill /f /im \"r5apex.exe\"
                        taskkill /f /im \"LeagueClient.exe\"
                        taskkill /f /im \"League of Legends.exe\"
                        taskkill /f /im \"browser_x86.exe\"
                        taskkill /f /im \"Heaven.exe\"
                        taskkill /f /im \"Valley.exe\"
                        taskkill /f /im \"launcher.exe\"
                        taskkill /f /im \"superposition.exe\"
                        taskkill /f /im \"dota2.exe\"
                        taskkill /f /im \"csgo.exe\"
                    """
                } else if (gameName == "Borderlands3") {
                    bat """
                        taskkill /f /im \"borderlands3.exe\"
                    """
                } else if (gameName == "Valorant") {
                    bat """
                        taskkill /f /im \"VALORANT-Win64-Shipping.exe\"
                    """
                } else if (gameName == "ApexLegends") {
                    bat """
                        taskkill /f /im \"r5apex.exe\"
                    """
                } else if (gameName == "LoL") {
                    bat """
                        taskkill /f /im \"LeagueClient.exe\"
                        taskkill /f /im \"League of Legends.exe\"
                    """
                } else if (gameName == "HeavenDX9" || gameName == "HeavenDX11" || gameName == "HeavenOpenGL") {
                    bat """
                        taskkill /f /im \"browser_x86.exe\"
                        taskkill /f /im \"Heaven.exe\"
                    """
                } else if (gameName == "ValleyDX9" || gameName == "ValleyDX11" || gameName == "ValleyOpenGL") {
                    bat """
                        taskkill /f /im \"browser_x86.exe\"
                        taskkill /f /im \"Valley.exe\"
                    """
                } else if (gameName == "Superposition") {
                    bat """
                        taskkill /f /im \"launcher.exe\"
                        taskkill /f /im \"superposition.exe\"
                    """
                } else if (gameName == "Dota2DX11" || gameName == "Dota2Vulkan") {
                    bat """
                        taskkill /f /im \"dota2.exe\"
                    """
                } else if (gameName == "CSGO") {
                    bat """
                        taskkill /f /im \"csgo.exe\"
                    """
                }

                break
            case "Ubuntu20":
                if (gameName == "All") {
                    sh """
                        pkill "browser_x64"
                        pkill "heaven_x64"
                        pkill "valley_x64"
                    """
                } else if (gameName == "HeavenOpenGL") {
                    sh """
                        pkill "browser_x64"
                        pkill "heaven_x64"
                    """
                } else if (gameName == "ValleyOpenGL") {
                    sh """
                        pkill "browser_x64"
                        pkill "valley_x64"
                    """
                }
                break
            case "OSX":
                println("Unsupported OS")
                break
            default:
                println("Unsupported OS")
        }
    } catch (e) {
        println("[ERROR] Failed to close games")
        println(e)
    }
}


def executeTestCommand(String osName, String asicName, Map options, String executionType = "") {
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

    // regression.json suite in weekly
    if (testsNames.contains("regression")) {
        testsPackageName = options.tests
        testsNames = ""
    }

    String collectTraces = "False"

    if ((executionType == "server" && options.serverCollectTraces) || (executionType == "client" && options.clientCollectTraces)) {
        collectTraces = options.collectTracesType
    }

    dir("scripts") {
        switch (osName) {
            case "Windows":
                if (executionType == "mcClient") {
                    bat """
                        run_mc.bat \"${testsPackageName}\" \"${testsNames}\" \"${options.serverInfo.ipAddress}\" \"${options.serverInfo.communicationPort}\" \"${options.serverInfo.gpuName}\" \"${options.serverInfo.osName}\" 1>> \"../${options.stageName}_${options.currentTry}_${executionType}.log\"  2>&1
                    """
                } else if (executionType == "client") {
                    if (options.serverInfo.osName.contains("Windows")) {
                        bat """
                            run_windows_client_for_windows.bat \"${testsPackageName}\" \"${testsNames}\" \"${options.serverInfo.ipAddress}\" \"${options.serverInfo.communicationPort}\" \"${options.serverInfo.gpuName}\" \"${options.serverInfo.osName}\" \"${options.engine}\" ${collectTraces} 1>> \"../${options.stageName}_${options.currentTry}_${executionType}.log\"  2>&1
                        """
                    } else if (options.serverInfo.osName.contains("Ubuntu")) {
                        bat """
                            run_windows_client_for_ubuntu.bat \"${testsPackageName}\" \"${testsNames}\" \"${options.serverInfo.ipAddress}\" \"${options.serverInfo.communicationPort}\" \"${options.serverInfo.gpuName}\" \"${options.serverInfo.osName}\" \"${options.engine}\" ${collectTraces} 1>> \"../${options.stageName}_${options.currentTry}_${executionType}.log\"  2>&1
                        """
                    } else {
                        throw new Exception("Unknown server OS name ${options.serverInfo.osName}")
                    }
                } else {
                    def screenResolution = "${options.clientInfo.screenWidth}x${options.clientInfo.screenHeight}"

                    bat """
                        run_windows_server.bat \"${testsPackageName}\" \"${testsNames}\" \"${options.serverInfo.ipAddress}\" \"${options.serverInfo.communicationPort}\" \"${screenResolution}\" \"${options.engine}\" ${collectTraces} 1>> \"../${options.stageName}_${options.currentTry}_${executionType}.log\"  2>&1
                    """
                }

                break

            case "Android":
                bat """
                    run_android.bat \"${testsPackageName}\" \"${testsNames}\" \"${options.engine}\" 1>> \"../${options.stageName}_${options.currentTry}_${executionType}.log\"  2>&1
                """

                break

            case "Ubuntu20":
                def screenResolution = "${options.clientInfo.screenWidth}x${options.clientInfo.screenHeight}"

                sh """
                    ./run_ubuntu_server.sh \"${testsPackageName}\" \"${testsNames}\" \"${options.serverInfo.ipAddress}\" \"${options.serverInfo.communicationPort}\" \"${screenResolution}\" \"${options.engine}\" ${collectTraces} 1>> \"../${options.stageName}_${options.currentTry}_${executionType}.log\"  2>&1
                """
                break

            default:
                println "Linux isn't supported"
        }
    }
}


def saveResults(String osName, Map options, String executionType, Boolean stashResults, Boolean executeTestsFinished) {
    try {
        dir(options.stageName) {
            utils.moveFiles(this, osName, "../*.log", ".")
            utils.moveFiles(this, osName, "../scripts/*.log", ".")
            utils.renameFile(this, osName, "launcher.engine.log", "${options.stageName}_engine_${options.currentTry}_${executionType}.log")
        }

        archiveArtifacts artifacts: "${options.stageName}/*.log", allowEmptyArchive: true

        if (stashResults) {
            dir("Work") {
                def sessionReport

                if (fileExists("Results/StreamingSDK/session_report.json")) {

                    sessionReport = readJSON file: "Results/StreamingSDK/session_report.json"

                    if (executionType == "client" || executionType == "android") {
                        String stashPostfix = executionType == "client" ? "_client" : ""

                        println "Stashing all test results to : ${options.testResultsName}${stashPostfix}"
                        makeStash(includes: '**/*', name: "${options.testResultsName}${stashPostfix}", allowEmpty: true, storeOnNAS: options.storeOnNAS)
                    } else if (executionType == "mcClient") {
                         println "Stashing results of multiconnection client"
                        makeStash(includes: '**/*_second_client.log,**/*.jpg,**/*.webp,**/*.mp4', name: "${options.testResultsName}_sec_cl", allowEmpty: true, storeOnNAS: options.storeOnNAS)
                        makeStash(includes: '**/*.json', name: "${options.testResultsName}_sec_cl_j", allowEmpty: true, storeOnNAS: options.storeOnNAS)
                    } else {
                        if (sessionReport.summary.error > 0) {
                            GithubNotificator.updateStatus("Test", options['stageName'], "action_required", options, NotificationConfiguration.SOME_TESTS_ERRORED, "${BUILD_URL}")
                        } else if (sessionReport.summary.failed > 0) {
                            GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, NotificationConfiguration.SOME_TESTS_FAILED, "${BUILD_URL}")
                        } else {
                            GithubNotificator.updateStatus("Test", options['stageName'], "success", options, NotificationConfiguration.ALL_TESTS_PASSED, "${BUILD_URL}")
                        }

                        println "Stashing logs to : ${options.testResultsName}_server"
                        makeStash(includes: '**/*_server.log,**/*_android.log', name: "${options.testResultsName}_serv_l", allowEmpty: true, storeOnNAS: options.storeOnNAS)
                        makeStash(includes: '**/*.json', name: "${options.testResultsName}_server", allowEmpty: true, storeOnNAS: options.storeOnNAS)
                        makeStash(includes: '**/*.jpg,**/*.webp,**/*.mp4', name: "${options.testResultsName}_and_cl", allowEmpty: true, storeOnNAS: options.storeOnNAS)
                        makeStash(includes: '**/*_server.zip', name: "${options.testResultsName}_ser_t", allowEmpty: true, storeOnNAS: options.storeOnNAS)
                    }
                }

                // number of errors > 50% -> do retry
                if (sessionReport.summary.total * 0.5 < sessionReport.summary.error) {
                    String errorMessage
                    if (options.currentTry < options.nodeReallocateTries) {
                        errorMessage = "Many tests were marked as error. The test group will be restarted."
                    } else {
                        errorMessage = "Many tests were marked as error."
                    }
                    throw new ExpectedExceptionWrapper(errorMessage, new Exception(errorMessage))
                }
            }
        }
    } catch(e) {
        if (executeTestsFinished) {
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


def executeTestsClient(String osName, String asicName, Map options) {
    Boolean stashResults = true

    try {

        //utils.reboot(this, osName)

        timeout(time: "10", unit: "MINUTES") {
            cleanWS(osName)
            checkoutScm(branchName: options.testsBranch, repositoryUrl: TESTS_REPO)
        }

        timeout(time: "5", unit: "MINUTES") {
            dir("jobs_launcher/install"){
                bat """
                    install_pylibs.bat
                """
            }

            dir("StreamingSDK") {
                prepareTool(osName, options)
            }
        }

        options["clientInfo"]["screenWidth"] = getClientScreenWidth(osName, options)
        println("[INFO] Screen width on client machine: ${options.clientInfo.screenWidth}")

        options["clientInfo"]["screenHeight"] = getClientScreenHeight(osName, options)
        println("[INFO] Screen height on client machine: ${options.clientInfo.screenHeight}")

        if (options.isDevelopBranch) {
            if (!driverTestsExecuted.containsKey("executed") || !driverTestsExecuted["executed"]) {
                try {
                    println("[INFO] Execute driver tests")

                    dir("AMDVirtualDrivers") {
                        unpackDriver(osName, options)
                        uninstallDriver(options)
                        runDriverTests(options)
                    }
                } catch (e) {
                    println(e)
                    GithubNotificator.updateStatus("Test", "Drivet tests", "action_required", options, "Failed to test driver")
                }
            }
        }

        options["clientInfo"]["ready"] = true
        println("[INFO] Client is ready to run tests")

        while (!options["serverInfo"]["ready"]) {
            if (options["serverInfo"]["failed"]) {
                throw new Exception("Server was failed")
            }

            sleep(5)
        }

        println("Client is synchronized with state of server. Start tests")
        
        executeTestCommand(osName, asicName, options, "client")

        options["clientInfo"]["executeTestsFinished"] = true

    } catch (e) {
        options["clientInfo"]["ready"] = false
        options["clientInfo"]["failed"] = true
        options["clientInfo"]["exception"] = e

        if (options.currentTry < options.nodeReallocateTries - 1) {
            stashResults = false
        } else {
            currentBuild.result = "FAILURE"
        }

        println "[ERROR] Failed during tests on client"
        println "Exception: ${e.toString()}"
        println "Exception message: ${e.getMessage()}"
        println "Exception cause: ${e.getCause()}"
        println "Exception stack trace: ${e.getStackTrace()}"
    } finally {
        options["clientInfo"]["finished"] = true

        saveResults(osName, options, "client", stashResults, options["clientInfo"]["executeTestsFinished"])
    }
}


def executeTestsServer(String osName, String asicName, Map options) {
    Boolean stashResults = true

    try {
        //utils.reboot(this, osName)

        withNotifications(title: options["stageName"], options: options, logUrl: "${BUILD_URL}", configuration: NotificationConfiguration.DOWNLOAD_TESTS_REPO) {
            timeout(time: "10", unit: "MINUTES") {
                cleanWS(osName)
                checkoutScm(branchName: options.testsBranch, repositoryUrl: TESTS_REPO)
            }
        }

        withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.INSTALL_PLUGIN) {
            timeout(time: "5", unit: "MINUTES") {
                dir("jobs_launcher/install"){
                    if (isUnix()) {
                        sh """
                            chmod u+x install_pylibs.sh
                            ./install_pylibs.sh
                        """
                    } else {
                        bat """
                            install_pylibs.bat
                        """
                    }
                }

                dir("StreamingSDK") {
                    prepareTool(osName, options)
                }

                // Android autotests support only Windows server machines
                if (osName == "Windows") {
                    initAndroidDevice()

                    if (options.multiconnectionConfiguration.android_client.any { options.tests.contains(it) } || options.tests == "regression.2.json~" || options.tests == "regression.3.json~") {
                        dir("StreamingSDKAndroid") {
                            prepareTool("Android", options)
                            installAndroidClient()
                        }
                    }
                }
            }
        }

        options["serverInfo"]["ipAddress"] = getServerIpAddress(osName)
        println("[INFO] IPv4 address of server: ${options.serverInfo.ipAddress}")

        options["serverInfo"]["gpuName"] = getGPUName(osName)
        println("[INFO] Name of GPU on server machine: ${options.serverInfo.gpuName}")

        options["serverInfo"]["osName"] = getOSName(osName)
        println("[INFO] Name of OS on server machine: ${options.serverInfo.osName}")

        options["serverInfo"]["communicationPort"] = getCommunicationPort(osName)
        println("[INFO] Communication port: ${options.serverInfo.communicationPort}")

        options["serverInfo"]["ready"] = true
        println("[INFO] Server is ready to run tests")

        while (!options["clientInfo"]["ready"]) {
            if (options["clientInfo"]["failed"]) {
                throw new Exception("Client was failed")
            }

            sleep(5)
        }

        if (options.multiconnectionConfiguration.second_win_client.any { options.tests.contains(it) } || options.tests == "regression.1.json~" || options.tests == "regression.3.json~") {
            while (!options["mcClientInfo"]["ready"]) {
                if (options["mcClientInfo"]["failed"]) {
                    throw new Exception("Multiconnection client was failed")
                }

                sleep(5)
            }
        }

        println("Server is synchronized with state of client. Start tests")

        withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.EXECUTE_TESTS) {
            executeTestCommand(osName, asicName, options, "server")
        }

        options["serverInfo"]["executeTestsFinished"] = true

    } catch (e) {
        options["serverInfo"]["ready"] = false
        options["serverInfo"]["failed"] = true
        options["serverInfo"]["exception"] = e

        if (options.currentTry < options.nodeReallocateTries - 1) {
            stashResults = false
        } else {
            currentBuild.result = "FAILURE"
        }

        println "[ERROR] Failed during tests on server"
        println "Exception: ${e.toString()}"
        println "Exception message: ${e.getMessage()}"
        println "Exception cause: ${e.getCause()}"
        println "Exception stack trace: ${e.getStackTrace()}"
    } finally {
        options["serverInfo"]["finished"] = true

        saveResults(osName, options, "server", stashResults, options["serverInfo"]["executeTestsFinished"])

        closeGames(osName, options, options.engine)
    }
}


def executeTestsMulticonnectionClient(String osName, String asicName, Map options) {
    Boolean stashResults = true

    try {

        timeout(time: "10", unit: "MINUTES") {
            cleanWS(osName)
            checkoutScm(branchName: options.testsBranch, repositoryUrl: TESTS_REPO)
        }

        timeout(time: "5", unit: "MINUTES") {
            dir("jobs_launcher/install"){
                bat """
                    install_pylibs.bat
                """
            }

            dir("StreamingSDK") {
                prepareTool(osName, options)
            }
        }

        options["mcClientInfo"]["ready"] = true
        println("[INFO] Multiconnection client is ready to run tests")

        while (!options["serverInfo"]["ready"]) {
            if (options["serverInfo"]["failed"]) {
                throw new Exception("Server was failed")
            }

            sleep(5)
        }

        println("Multiconnection client is synchronized with state of server. Start tests")
        
        executeTestCommand(osName, asicName, options, "mcClient")

        options["mcClientInfo"]["executeTestsFinished"] = true

    } catch (e) {
        options["mcClientInfo"]["ready"] = false
        options["mcClientInfo"]["failed"] = true
        options["mcClientInfo"]["exception"] = e

        if (options.currentTry < options.nodeReallocateTries - 1) {
            stashResults = false
        } else {
            currentBuild.result = "FAILURE"
        }

        println "[ERROR] Failed during tests on multiconnection client"
        println "Exception: ${e.toString()}"
        println "Exception message: ${e.getMessage()}"
        println "Exception cause: ${e.getCause()}"
        println "Exception stack trace: ${e.getStackTrace()}"
    } finally {
        options["mcClientInfo"]["finished"] = true

        saveResults(osName, options, "mcClient", stashResults, options["mcClientInfo"]["executeTestsFinished"])
    }
}


def rebootAndroidDevice() {
    try {
        bat "adb reboot"
        println "[INFO] Android device rebooted"
    } catch (Exception e) {
        println "[ERROR] Failed to reboot Android device"
    }
}


def initAndroidDevice() {
    try {
        withCredentials([string(credentialsId: "androidDeviceIp", variable: "ANDROID_DEVICE_IP")]) {
            bat "adb kill-server"
            println "[INFO] ADB server is killed"
        }
    } catch (Exception e) {
        println "[ERROR] Failed to kill adb server"
    }

    try {
        withCredentials([string(credentialsId: "androidDeviceIp", variable: "ANDROID_DEVICE_IP")]) {
            bat "adb connect " + ANDROID_DEVICE_IP + ":5555"
            println "[INFO] Connected to Android device"
        }
    } catch (Exception e) {
        println "[ERROR] Failed to connect to Android device"
    }

    try {
        bat "adb shell rm -rf sdcard/video*"
        println "[INFO] Android deviced is cleared"
    } catch (Exception e) {
        println "[ERROR] Failed to clear Android device"
    }

    try {
        bat "adb shell am force-stop com.amd.remotegameclient"
        println "[INFO] Android client is closed"
    } catch (Exception e) {
        println "[ERROR] Failed to close Android client"
    }
}


def installAndroidClient() {
    try {
        utils.copyFile(this, "Windows", "%STREAMING_SCRIPTS_LOCATION%\\*", ".")
    } catch(Exception e) {
        println("[ERROR] Failed to copy installation scripts")
        throw e
    }

    try {
        bat "uninstall.bat"
        println "[INFO] Android client was uninstalled"
    } catch (Exception e) {
        println "[ERROR] Failed to uninstall Android client"
        println(e)
    }

    try {
        bat "install.bat"
        sleep(15)
        println "[INFO] Android client was installed"
    } catch (Exception e) {
        println "[ERROR] Failed to install Android client"
        throw e
    }
}


def executeTestsAndroid(String osName, String asicName, Map options) {
    Boolean stashResults = true

    try {
        //utils.reboot(this, "Windows")

        initAndroidDevice()

        withNotifications(title: options["stageName"], options: options, logUrl: "${BUILD_URL}", configuration: NotificationConfiguration.DOWNLOAD_TESTS_REPO) {
            timeout(time: "10", unit: "MINUTES") {
                cleanWS(osName)
                checkoutScm(branchName: options.testsBranch, repositoryUrl: TESTS_REPO)
            }
        }

        withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.INSTALL_PLUGIN) {
            timeout(time: "5", unit: "MINUTES") {
                dir("jobs_launcher/install"){
                    bat """
                        install_pylibs.bat
                    """
                }

                dir("StreamingSDK") {
                    prepareTool("Windows", options)
                }
                dir("StreamingSDKAndroid") {
                    prepareTool("Android", options)
                    installAndroidClient()
                }
            }
        }

        withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.EXECUTE_TESTS) {
            executeTestCommand(osName, asicName, options)
        }

    } catch (e) {
        if (options.currentTry < options.nodeReallocateTries - 1) {
            stashResults = false
        } else {
            currentBuild.result = "FAILURE"
        }

        println "[ERROR] Failed during Android tests"
        println "Exception: ${e.toString()}"
        println "Exception message: ${e.getMessage()}"
        println "Exception cause: ${e.getCause()}"
        println "Exception stack trace: ${e.getStackTrace()}"
    } finally {
        saveResults("Windows", options, "android", stashResults, true)

        closeGames(osName, options, options.engine)
    }
}


def executeTests(String osName, String asicName, Map options) {
    // used for mark stash results or not. It needed for not stashing failed tasks which will be retried.
    Boolean stashResults = true

    try {
        if (osName == "Windows" || osName == "Ubuntu20") {
            options["clientInfo"] = new ConcurrentHashMap()
            options["serverInfo"] = new ConcurrentHashMap()
            options["mcClientInfo"] = new ConcurrentHashMap()

            println("[INFO] Start Client and Server processes for ${asicName}-${osName}")
            // create client and server threads and run them parallel
            Map threads = [:]

            threads["${options.stageName}-client"] = { 
                node(getClientLabels(options)) {
                    timeout(time: options.TEST_TIMEOUT, unit: "MINUTES") {
                        ws("WS/${options.PRJ_NAME}_Test") {
                            executeTestsClient("Windows", asicName, options)
                        }
                    }
                }
            }

            if (options.multiconnectionConfiguration.second_win_client.any { options.tests.contains(it) } || options.tests == "regression.1.json~" || options.tests == "regression.3.json~") {
                threads["${options.stageName}-multiconnection-client"] = { 
                    node(getMulticonnectionClientLabels(options)) {
                        timeout(time: options.TEST_TIMEOUT, unit: "MINUTES") {
                            ws("WS/${options.PRJ_NAME}_Test") {
                                executeTestsMulticonnectionClient("Windows", asicName, options)
                            }
                        }
                    }
                }
            }

            threads["${options.stageName}-server"] = { executeTestsServer(osName, asicName, options) }

            parallel threads

            if (options["serverInfo"]["failed"]) {
                def exception = options["serverInfo"]["exception"]
                throw new ExpectedExceptionWrapper("Server side tests got an error: ${exception.getMessage()}", exception)
            } else if (options["clientInfo"]["failed"]) {
                def exception = options["clientInfo"]["exception"]
                throw new ExpectedExceptionWrapper("Client side tests got an error: ${exception.getMessage()}", exception)
            }
        } else if (osName == "Android") {
            executeTestsAndroid(osName, asicName, options)
        } else {
            println("Unsupported OS")
        }
    } catch (e) {
        if (e instanceof ExpectedExceptionWrapper) {
            GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, "${e.getMessage()}", "${BUILD_URL}")
            throw new ExpectedExceptionWrapper("${e.getMessage()}", e.getCause())
        } else {
            GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, "${NotificationConfiguration.REASON_IS_NOT_IDENTIFIED}", "${BUILD_URL}")
            throw new ExpectedExceptionWrapper("${NotificationConfiguration.REASON_IS_NOT_IDENTIFIED}", e)
        }
    }
}


def executeBuildWindows(Map options) {
    options.winBuildConfiguration.each() { winBuildConf ->

        println "Current build configuration: ${winBuildConf}."

        String winBuildName = "${winBuildConf}_vs2019"
        String logName = "${STAGE_NAME}.${winBuildName}.log"
        String logNameDriver = "${STAGE_NAME}.${winBuildName}.driver.log"
        String logNameLatencyTool = "${STAGE_NAME}.${winBuildName}.latency_tool.log"

        String buildSln = "StreamingSDK_vs2019.sln"
        String msBuildPath = bat(script: "echo %VS2019_PATH%",returnStdout: true).split('\r\n')[2].trim()
        String winArtifactsDir = "vs2019x64${winBuildConf.substring(0, 1).toUpperCase() + winBuildConf.substring(1).toLowerCase()}"
        String winDriverDir = "x64/${winBuildConf.substring(0, 1).toUpperCase() + winBuildConf.substring(1).toLowerCase()}"
        String winLatencyToolDir = "amf/bin/vs2019x64${winBuildConf.substring(0, 1).toUpperCase() + winBuildConf.substring(1).toLowerCase()}"

        if (options.isDevelopBranch) {
            dir("AMDVirtualDrivers") {
                withNotifications(title: "Windows", options: options, configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
                    checkoutScm(branchName: "develop", repositoryUrl: DRIVER_REPO)
                }

                GithubNotificator.updateStatus("Build", "Windows", "in_progress", options, NotificationConfiguration.BUILD_SOURCE_CODE_START_MESSAGE, "${BUILD_URL}/artifact/${logNameDriver}")

                bat """
                    set AMD_VIRTUAL_DRIVER=${WORKSPACE}\\AMDVirtualDrivers
                    set STREAMING_SDK=${WORKSPACE}\\StreamingSDK
                    set msbuild="${msBuildPath}"
                    %msbuild% AMDVirtualDrivers.sln /target:build /maxcpucount /nodeReuse:false /property:Configuration=${winBuildConf};Platform=x64 >> ..\\${logNameDriver} 2>&1
                """

                dir(winDriverDir) {
                    String DRIVER_NAME = "Driver_Windows_${winBuildConf}.zip"

                    bat("%CIS_TOOLS%\\7-Zip\\7z.exe a ${DRIVER_NAME} .")

                    makeArchiveArtifacts(name: DRIVER_NAME, storeOnNAS: options.storeOnNAS)

                    if (options.winTestingDriverName == winBuildConf) {
                        utils.moveFiles(this, "Windows", DRIVER_NAME, "${options.winTestingDriverName}.zip")
                        makeStash(includes: "${options.winTestingDriverName}.zip", name: "DriverWindows", preZip: false, storeOnNAS: options.storeOnNAS)
                    }
                }
            }
        }

        if (options.winTestingBuildName == winBuildName && options.engines.contains("Empty")) {
            dir("AMFTests") {
                withNotifications(title: "Windows", options: options, configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
                    checkoutScm(branchName: "master", repositoryUrl: AMF_TESTS_REPO)
                }

                GithubNotificator.updateStatus("Build", "Windows", "in_progress", options, NotificationConfiguration.BUILD_SOURCE_CODE_START_MESSAGE, "${BUILD_URL}/artifact/${logNameLatencyTool}")

                dir("amf\\protected\\samples") {
                    bat """
                        set msbuild="${msBuildPath}"
                        %msbuild% LatancyTest_vs2019.sln /target:build /maxcpucount /nodeReuse:false /property:Configuration=${winBuildConf};Platform=x64 >> ..\\..\\..\\..\\${logNameLatencyTool} 2>&1
                    """
                }

                dir(winLatencyToolDir) {
                    String LATENCY_TOOL_NAME = "LatencyTool_Windows.zip"

                    bat("%CIS_TOOLS%\\7-Zip\\7z.exe a ${LATENCY_TOOL_NAME} .")

                    makeArchiveArtifacts(name: LATENCY_TOOL_NAME, storeOnNAS: options.storeOnNAS)

                    if (options.winTestingDriverName == winBuildConf) {
                        makeStash(includes: LATENCY_TOOL_NAME, name: "LatencyToolWindows", preZip: false, storeOnNAS: options.storeOnNAS)
                    }
                }
            }
        }

        dir("StreamingSDK\\amf\\protected\\samples") {
            GithubNotificator.updateStatus("Build", "Windows", "in_progress", options, NotificationConfiguration.BUILD_SOURCE_CODE_START_MESSAGE, "${BUILD_URL}/artifact/${logName}")

            bat """
                set AMD_VIRTUAL_DRIVER=${WORKSPACE}\\AMDVirtualDrivers
                set STREAMING_SDK=${WORKSPACE}\\StreamingSDK
                set msbuild="${msBuildPath}"
                %msbuild% ${buildSln} /target:build /maxcpucount /nodeReuse:false /property:Configuration=${winBuildConf};Platform=x64 >> ..\\..\\..\\..\\${logName} 2>&1
            """
        }

        String archiveUrl = ""

        dir("StreamingSDK\\amf\\bin\\${winArtifactsDir}") {
            String BUILD_NAME = "StreamingSDK_Windows_${winBuildName}.zip"

            zip archive: true, zipFile: BUILD_NAME

            if (options.winTestingBuildName == winBuildName) {
                utils.moveFiles(this, "Windows", BUILD_NAME, "${options.winTestingBuildName}.zip")
                makeStash(includes: "${options.winTestingBuildName}.zip", name: "ToolWindows", preZip: false, storeOnNAS: options.storeOnNAS)
            }

            archiveUrl = "${BUILD_URL}artifact/${BUILD_NAME}"
            rtp nullAction: "1", parserName: "HTML", stableText: """<h3><a href="${archiveUrl}">[BUILD: ${BUILD_ID}] ${BUILD_NAME}</a></h3>"""
        }

    }

    GithubNotificator.updateStatus("Build", "Windows", "success", options, NotificationConfiguration.BUILD_SOURCE_CODE_END_MESSAGE)
}


def executeBuildAndroid(Map options) {
    withEnv(["PATH=C:\\Program Files\\Java\\jdk1.8.0_271\\bin;C:\\Program Files\\Java\\jdk1.8.0_241\\bin;${PATH}"]) {
        options.androidBuildConfiguration.each() { androidBuildConf ->

            println "Current build configuration: ${androidBuildConf}."

            String androidBuildName = "${androidBuildConf}"
            String logName = "${STAGE_NAME}.${androidBuildName}.log"

            String androidBuildKeys = "assemble${androidBuildConf.substring(0, 1).toUpperCase() + androidBuildConf.substring(1).toLowerCase()}"

            dir("StreamingSDK/amf/protected/samples/CPPSamples/RemoteGameClientAndroid") {
                GithubNotificator.updateStatus("Build", "Android", "in_progress", options, NotificationConfiguration.BUILD_SOURCE_CODE_START_MESSAGE, "${BUILD_URL}/artifact/${logName}")

                bat """
                    gradlew.bat ${androidBuildKeys} >> ..\\..\\..\\..\\..\\..\\${logName} 2>&1
                """

                String archiveUrl = ""

                dir("app/build/outputs/apk/arm/${androidBuildConf}") {
                    String BUILD_NAME = "StreamingSDK_Android_${androidBuildName}.zip"

                    zip archive: true, zipFile: BUILD_NAME, glob: "app-arm-${androidBuildConf}.apk"

                    if (options.androidTestingBuildName == androidBuildConf) {
                        utils.moveFiles(this, "Windows", BUILD_NAME, "android_${options.androidTestingBuildName}.zip")
                        makeStash(includes: "android_${options.androidTestingBuildName}.zip", name: "ToolAndroid", preZip: false, storeOnNAS: options.storeOnNAS)
                    }

                    archiveUrl = "${BUILD_URL}artifact/${BUILD_NAME}"
                    rtp nullAction: "1", parserName: "HTML", stableText: """<h3><a href="${archiveUrl}">[BUILD: ${BUILD_ID}] ${BUILD_NAME}</a></h3>"""
                }
            }

        }

        GithubNotificator.updateStatus("Build", "Android", "success", options, NotificationConfiguration.BUILD_SOURCE_CODE_END_MESSAGE)
    }
}


def executeBuildUbuntu(Map options) {
    String logName = "${STAGE_NAME}.log"

    dir("StreamingSDK/amf/public/src/components/ComponentsFFMPEG") {
        GithubNotificator.updateStatus("Build", "Ubuntu20", "in_progress", options, NotificationConfiguration.BUILD_SOURCE_CODE_START_MESSAGE, "${BUILD_URL}/artifact/${logName}")

        sh """
            make >> ../../../../../../${logName} 2>&1
        """
    }

    dir("StreamingSDK/amf/protected/samples/CPPSamples/RemoteGameServer") {
        // TODO: temporary ducktape. Waiting for fix from side of developers
        if (!fileExists("../../../../../Thirdparty/VulkanSDK/1.2.189.2")) {
            sh """
                mkdir -p ../../../../../Thirdparty/VulkanSDK/1.2.189.2
                cp -r \$VK_SDK_PATH ../../../../../Thirdparty/VulkanSDK/1.2.189.2/x86_64
            """
        }

        sh """
            chmod u+x ../../../../../Thirdparty/file_to_header/Linux64/file_to_header
            make >> ../../../../../../${logName} 2>&1
        """

        String archiveUrl = ""
    }

    dir("StreamingSDK/amf/bin/dbg_64") {
        String BUILD_NAME = "StreamingSDK_Ubuntu20.zip"
        
        sh("cp ../../bin/wirelessvr/build/lnx64a/B_dbg/libawvrrt64.so.1.4.10 libawvrrt64.so.1")

        zip archive: true, zipFile: BUILD_NAME

        makeStash(includes: BUILD_NAME, name: "ToolUbuntu20", preZip: false, storeOnNAS: options.storeOnNAS)

        archiveUrl = "${BUILD_URL}artifact/${BUILD_NAME}"
        rtp nullAction: "1", parserName: "HTML", stableText: """<h3><a href="${archiveUrl}">[BUILD: ${BUILD_ID}] ${BUILD_NAME}</a></h3>"""
    }

    GithubNotificator.updateStatus("Build", "Ubuntu20", "success", options, NotificationConfiguration.BUILD_SOURCE_CODE_END_MESSAGE)
}


def executeBuild(String osName, Map options) {
    try {
        //utils.reboot(this, osName != "Android" ? osName : "Windows")

        dir("StreamingSDK") {
            withNotifications(title: osName, options: options, configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
                checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo)
            }
        }

        utils.removeFile(this, osName != "Android" ? osName : "Windows", "*.log")

        outputEnvironmentInfo(osName != "Android" ? osName : "Windows")

        withNotifications(title: osName, options: options, configuration: NotificationConfiguration.BUILD_SOURCE_CODE) {
            switch(osName) {
                case "Windows":
                    executeBuildWindows(options)
                    break
                case "Android":
                    executeBuildAndroid(options)
                    break
                case "Ubuntu20":
                    executeBuildUbuntu(options)
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


def executePreBuild(Map options) {
    // manual job
    if (!env.BRANCH_NAME) {
        options.executeBuild = true
        options.executeTests = true
    // auto job
    } else {
        options.executeBuild = true
        options.executeTests = true

        if (env.CHANGE_URL) {
            println "[INFO] Branch was detected as Pull Request"
        } else if("${env.BRANCH_NAME}" == "master") {
            println "[INFO] master branch was detected"
        } else {
            println "[INFO] ${env.BRANCH_NAME} branch was detected"
        }
    }

    Boolean collectTraces = (options.clientCollectTraces || options.serverCollectTraces)

    if ("StreamingSDK") {
        checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo, disableSubmodules: true)
    }

    if (options.projectBranch) {
        currentBuild.description = "<b>Project branch:</b> ${options.projectBranch}<br/>"
    } else {
        currentBuild.description = "<b>Project branch:</b> ${env.BRANCH_NAME}<br/>"
    }

    options.commitAuthor = bat (script: "git show -s --format=%%an HEAD ",returnStdout: true).split('\r\n')[2].trim()
    options.commitMessage = bat (script: "git log --format=%%B -n 1", returnStdout: true).split('\r\n')[2].trim()
    options.commitSHA = bat(script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
    options.commitShortSHA = options.commitSHA[0..6]

    println "The last commit was written by ${options.commitAuthor}."
    println "Commit message: ${options.commitMessage}"
    println "Commit SHA: ${options.commitSHA}"
    println "Commit shortSHA: ${options.commitShortSHA}"

    if (env.BRANCH_NAME) {
        withNotifications(title: "Jenkins build configuration", printMessage: true, options: options, configuration: NotificationConfiguration.CREATE_GITHUB_NOTIFICATOR) {
            GithubNotificator githubNotificator = new GithubNotificator(this, options)
            githubNotificator.init(options)
            options["githubNotificator"] = githubNotificator
            githubNotificator.initPreBuild("${BUILD_URL}")
            options.projectBranchName = githubNotificator.branchName
        }
    }

    currentBuild.description += "<b>Commit author:</b> ${options.commitAuthor}<br/>"
    currentBuild.description += "<b>Commit message:</b> ${options.commitMessage}<br/>"
    currentBuild.description += "<b>Commit SHA:</b> ${options.commitSHA}<br/>"

    def tests = []

    withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.CONFIGURE_TESTS) {
        dir("jobs_test_streaming_sdk") {
            checkoutScm(branchName: options.testsBranch, repositoryUrl: TESTS_REPO)
            options["testsBranch"] = utils.getBatOutput(this, "git log --format=%%H -1 ")
            dir('jobs_launcher') {
                options['jobsLauncherBranch'] = utils.getBatOutput(this, "git log --format=%%H -1 ")
            }
            println "[INFO] Test branch hash: ${options['testsBranch']}"

            def packageInfo

            if (options.testsPackage != "none") {
                if (fileExists("jobs/${options.testsPackage}")) {
                    packageInfo = readJSON file: "jobs/${options.testsPackage}"
                } else {
                    packageInfo = readJSON file: "jobs/${options.testsPackage.replace('.json', '-windows.json')}"
                }

                options.isPackageSplitted = packageInfo["split"]
                // if it's build of manual job and package can be splitted - use list of tests which was specified in params (user can change list of tests before run build)
                if (!env.BRANCH_NAME && options.isPackageSplitted && options.tests) {
                    options.testsPackage = "none"
                }
            }

            if (options.testsPackage != "none") {
                def tempTests = []

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
                        tempTests << it
                    } else {
                        if (tempTests.contains(it)) {
                            // add duplicated group name in name of package group name for exclude it
                            modifiedPackageName = "${modifiedPackageName},${it}"
                        }
                    }
                }
                options.tests = utils.uniteSuites(this, "jobs/weights.json", tempTests, collectTraces ? 90 : 70)

                options.engines.each { engine ->
                    if (env.JOB_NAME.contains("Weekly") && WEEKLY_REGRESSION_CONFIGURATION.contains(engine)) {
                        packageInfo = readJSON file: "jobs/regression-windows.json"

                        for (int i = 0; i < packageInfo["groups"].size(); i++) {
                            tests << "regression.${i}.json~-${engine}"
                        }
                    } else {
                        options.tests.each() {
                            tests << "${it}-${engine}"
                        }
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
                    } else {
                        // add group stub for each part of package
                        options.engines.each { engine ->
                            for (int i = 0; i < packageInfo["groups"].size(); i++) {
                                tests << "${modifiedPackageName}-${engine}".replace(".json", ".${i}.json")
                            }
                        }
                    }
                }
            } else if (options.tests) {
                options.tests = utils.uniteSuites(this, "jobs/weights.json", options.tests.split(" ") as List, collectTraces ? 90 : 70)
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

        // clear games list if there isn't any test group in build or games string is empty
        if (!options.tests || !options.games) {
            options.tests = []
            options.engines = []
        }

        options.testsList = options.tests

        println "Groups: ${options.testsList}"

        dir("jobs_test_streaming_sdk") {
            options.multiconnectionConfiguration = readJSON file: "jobs/multiconnection.json"

            // Multiconnection group required Android client
            if (!options.platforms.contains("Android") && (options.multiconnectionConfiguration.android_client.any { options.testsList.join("").contains(it) } || options.testsPackage == "regression.json~")) {
                println(options.platforms)
                options.platforms = options.platforms + ";Android"
                println(options.platforms)

                options.androidBuildConfiguration = ["debug"]
                options.androidTestingBuildName = "debug"

                println """
                    Android build configuration was updated: ${options.androidBuildConfiguration}
                    Android testing build name was updated: ${options.androidTestingBuildName}
                """
            }
        }

        if (!options.tests && options.testsPackage == "none") {
            options.executeTests = false
        }

        // make lists of raw profiles and lists of beautified profiles (displaying profiles)
        multiplatform_pipeline.initProfiles(options)

        if (env.BRANCH_NAME && options.githubNotificator) {
            options.githubNotificator.initChecks(options, "${BUILD_URL}")

            if (options.isDevelopBranch) {
                GithubNotificator.createStatus('Test', "Driver tests", 'queued', options, 'Scheduled', "${env.JOB_URL}")
            }
        }
    }
}


def executeDeploy(Map options, List platformList, List testResultList, String game) {
    try {

        if (options["executeTests"] && testResultList) {
            withNotifications(title: "Building test report for ${game}", options: options, startUrl: "${BUILD_URL}", configuration: NotificationConfiguration.DOWNLOAD_TESTS_REPO) {
                checkoutScm(branchName: options.testsBranch, repositoryUrl: TESTS_REPO)
            }

            List lostStashesWindows = []
            List lostStashesAndroid = []
            dir("summaryTestResults") {
                testResultList.each {
                    Boolean groupLost = false

                    if (it.endsWith(game)) {
                        List testNameParts = it.split("-") as List

                        String testName = testNameParts.subList(0, testNameParts.size() - 1).join("-")
                        dir(testName.replace("testResult-", "")) {
                            if (it.contains("Android")) {
                                try {
                                    makeUnstash(name: "${it}", storeOnNAS: options.storeOnNAS)
                                } catch (e) {
                                    println """
                                        [ERROR] Failed to unstash ${it}
                                        ${e.toString()}
                                    """

                                    lostStashesAndroid << ("'${it}'".replace("testResult-", ""))
                                }
                            } else {
                                try {
                                    makeUnstash(name: "${it}_serv_l", storeOnNAS: options.storeOnNAS)
                                } catch (e) {
                                    println """
                                        [ERROR] Failed to unstash ${it}_serv_l
                                        ${e.toString()}
                                    """

                                    groupLost = true
                                }

                                if (options.multiconnectionConfiguration.second_win_client.any { testGroup -> it.contains(testGroup) } || testName.contains("regression.1.json~") || testName.contains("regression.3.json~")) {
                                    try {
                                        makeUnstash(name: "${it}_sec_cl", storeOnNAS: options.storeOnNAS)
                                    } catch (e) {
                                        println """
                                            [ERROR] Failed to unstash ${it}_sec_cl
                                            ${e.toString()}
                                        """
                                    }
                                }

                                try {
                                    makeUnstash(name: "${it}_client", storeOnNAS: options.storeOnNAS)
                                } catch (e) {
                                    println """
                                        [ERROR] Failed to unstash ${it}_client
                                        ${e.toString()}
                                    """

                                    groupLost = true
                                }

                                try {
                                    makeUnstash(name: "${it}_and_cl", storeOnNAS: options.storeOnNAS)
                                } catch (e) {
                                    println """
                                        [ERROR] Failed to unstash ${it}_and_cl
                                        ${e.toString()}
                                    """
                                }

                                try {
                                    makeUnstash(name: "${it}_ser_t", storeOnNAS: options.storeOnNAS)
                                } catch (e) {
                                    println """
                                        [ERROR] Failed to unstash ${it}_ser_t
                                        ${e.toString()}
                                    """
                                }

                                if (groupLost) {
                                    lostStashesWindows << ("'${it}'".replace("testResult-", ""))
                                }
                            }
                        }
                    }
                }
            }

            dir("serverTestResults") {
                testResultList.each {
                    if (it.endsWith(game)) {
                        List testNameParts = it.split("-") as List

                        String testName = testNameParts.subList(0, testNameParts.size() - 1).join("-")
                        dir(testName.replace("testResult-", "")) {
                            try {
                                makeUnstash(name: "${it}_server", storeOnNAS: options.storeOnNAS)
                            } catch (e) {
                                println """
                                    [ERROR] Failed to unstash ${it}_server
                                    ${e.toString()}
                                """
                            }
                        }
                    }
                }
            }

            dir("secondClientTestResults") {
                testResultList.each {
                    if (it.endsWith(game)) {
                        List testNameParts = it.split("-") as List

                        String testName = testNameParts.subList(0, testNameParts.size() - 1).join("-")

                        if (options.multiconnectionConfiguration.second_win_client.any { testGroup -> it.contains(testGroup) } || testName.contains("regression.1.json~") || testName.contains("regression.3.json~")) {
                            dir(testName.replace("testResult-", "")) {
                                try {
                                    makeUnstash(name: "${it}_sec_cl_j", storeOnNAS: options.storeOnNAS)
                                } catch (e) {
                                    println """
                                        [ERROR] Failed to unstash ${it}_sec_cl_j
                                        ${e.toString()}
                                    """
                                }
                            }
                        }
                    }
                }
            }

            try {
                dir ("scripts") {
                    python3("unite_case_results.py --target_dir \"..\\summaryTestResults\" --source_dir \"..\\serverTestResults\" --second_client_dir \"..\\secondClientTestResults\"")
                }
            } catch (e) {
                println "[ERROR] Can't unite server and client test results"
            }

            try {
                dir("scripts") {
                    python3("prepare_test_cases.py --os_name \"Windows\"")
                }

                dir("jobs_launcher") {
                    bat """
                        count_lost_tests.bat \"${lostStashesWindows}\" .. ..\\summaryTestResults \"${options.splitTestsExecution}\" \"${options.testsPackage}\" \"[]\" \"${game}\" \"{}\"
                    """
                }

                dir("scripts") {
                    python3("prepare_test_cases.py --os_name \"Android\"")
                }

                dir("jobs_launcher") {
                    bat """
                        count_lost_tests.bat \"${lostStashesAndroid}\" .. ..\\summaryTestResults \"${options.splitTestsExecution}\" \"${options.testsPackage}\" \"[]]\" \"${game}\" \"{}\"
                    """
                }

                dir("scripts") {
                    python3("prepare_test_cases.py --os_name \"Ubuntu\"")
                }

                dir("jobs_launcher") {
                    bat """
                        count_lost_tests.bat \"${lostStashesAndroid}\" .. ..\\summaryTestResults \"${options.splitTestsExecution}\" \"${options.testsPackage}\" \"[]]\" \"${game}\" \"{}\"
                    """
                }
            } catch (e) {
                println "[ERROR] Can't generate number of lost tests"
            }

            String branchName = env.BRANCH_NAME ?: options.projectBranch
            try {
                Boolean showGPUViewTraces = options.clientCollectTraces || options.serverCollectTraces

                GithubNotificator.updateStatus("Deploy", "Building test report for ${game}", "in_progress", options, NotificationConfiguration.BUILDING_REPORT, "${BUILD_URL}")
                withEnv(["JOB_STARTED_TIME=${options.JOB_STARTED_TIME}", "BUILD_NAME=${options.baseBuildName}", "SHOW_GPUVIEW_TRACES=${showGPUViewTraces}"]) {
                    dir("jobs_launcher") {
                        List retryInfoList = utils.deepcopyCollection(this, options.nodeRetry)
                        retryInfoList.each{ gpu ->
                            gpu['Tries'].each{ group ->
                                group.each{ groupKey, retries ->
                                    if (groupKey.endsWith(game)) {
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

                        bat """
                            build_reports.bat ..\\summaryTestResults "StreamingSDK" ${options.commitSHA} ${branchName} \"${utils.escapeCharsByUnicode(options.commitMessage)}\" \"${utils.escapeCharsByUnicode(game)}\"
                        """
                    }
                }
            } catch (e) {
                String errorMessage = utils.getReportFailReason(e.getMessage())
                GithubNotificator.updateStatus("Deploy", "Building test report for ${game}", "failure", options, errorMessage, "${BUILD_URL}")
                if (utils.isReportFailCritical(e.getMessage())) {
                    options.problemMessageManager.saveSpecificFailReason(errorMessage, "Deploy")
                    println """
                        [ERROR] Failed to build test report.
                        ${e.toString()}
                    """
                    if (!options.testDataSaved) {
                        try {
                            // Save test data for access it manually anyway
                            // FIXME: save reports on NAS
                            utils.publishReport(this, "${BUILD_URL}", "summaryTestResults", "summary_report.html, compare_report.html", \
                                "Test Report ${game}", "Summary Report, Compare Report", false, \
                                ["jenkinsBuildUrl": BUILD_URL, "jenkinsBuildName": currentBuild.displayName])
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

            withNotifications(title: "Building test report for ${game}", options: options, configuration: NotificationConfiguration.PUBLISH_REPORT) {
                // FIXME: save reports on NAS
                utils.publishReport(this, "${BUILD_URL}", "summaryTestResults", "summary_report.html, compare_report.html", \
                    "Test Report ${game}", "Summary Report, Compare Report", false, \
                    ["jenkinsBuildUrl": BUILD_URL, "jenkinsBuildName": currentBuild.displayName])

                if (summaryTestResults) {
                    GithubNotificator.updateStatus("Deploy", "Building test report for ${game}", "success", options,
                            "${NotificationConfiguration.REPORT_PUBLISHED} Results: passed - ${summaryTestResults.passed}, failed - ${summaryTestResults.failed}, error - ${summaryTestResults.error}.", "${BUILD_URL}/Test_20Report")
                } else {
                    GithubNotificator.updateStatus("Deploy", "Building test report for ${game}", "success", options,
                            NotificationConfiguration.REPORT_PUBLISHED, "${BUILD_URL}/Test_20Report")
                }
            }
        }
    } catch (e) {
        println(e.toString())
        throw e
    } finally {
        utils.generateOverviewReport(this, this.&getReportBuildArgs, options)
    }
}


def call(String projectBranch = "",
    String testsBranch = "master",
    String platforms = "Windows:AMD_RX6700XT;Android:AMD_RX6700XT",
    String clientTag = "PC-TESTER-VILNIUS-WIN10",
    String winBuildConfiguration = "release,debug",
    String winTestingBuildName = "debug_vs2019",
    String testsPackage = "regression.json",
    String tests = "",
    String testerTag = "StreamingSDK",
    Integer testCaseRetries = 2,
    Boolean clientCollectTraces = false,
    Boolean serverCollectTraces = false,
    String collectTracesType = "AfterTests",
    String games = "Valorant",
    String androidBuildConfiguration = "release,debug",
    String androidTestingBuildName = "debug",
    Boolean storeOnNAS = false
    )
{
    ProblemMessageManager problemMessageManager = new ProblemMessageManager(this, currentBuild)
    Map options = [:]
    options["stage"] = "Init"
    options["problemMessageManager"] = problemMessageManager

    def nodeRetry = []

    try {
        withNotifications(options: options, configuration: NotificationConfiguration.INITIALIZATION) {
            // Anroid tests required built Windows Streaming SDK to run server side
            if ((platforms.contains("Android:") || platforms.contains("Ubuntu20:")) && !platforms.contains("Windows")) {
                platforms = platforms + ";Windows"

                winBuildConfiguration = "debug"
                winTestingBuildName = "debug_vs2019"
            }

            String winTestingDriverName = winTestingBuildName ? winTestingBuildName.split("_")[0] : ""

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

            println """
                Platforms: ${platforms}
                Tests: ${tests}
                Tests package: ${testsPackage}
                Store on NAS: ${storeOnNAS}
            """

            winBuildConfiguration = winBuildConfiguration.split(',')

            println """
                Win build configuration: ${winBuildConfiguration}"
                Win testing build name: ${winTestingBuildName}
                Win driver build name: ${winTestingDriverName}
            """

            androidBuildConfiguration = androidBuildConfiguration.split(',')

            println """
                Android build configuration: ${androidBuildConfiguration}"
            """

            String branchName = env.BRANCH_NAME ?: projectBranch
            Boolean isDevelopBranch = (branchName == "origin/develop" || branchName == "develop")

            options << [configuration: PIPELINE_CONFIGURATION,
                        projectRepo: PROJECT_REPO,
                        projectBranch: projectBranch,
                        testsBranch: testsBranch,
                        enableNotifications: false,
                        testsPackage:testsPackage,
                        tests:tests,
                        PRJ_NAME: "StreamingSDK",
                        splitTestsExecution: true,
                        winBuildConfiguration: winBuildConfiguration,
                        winTestingBuildName: winTestingBuildName,
                        winTestingDriverName: winTestingDriverName,
                        androidBuildConfiguration: androidBuildConfiguration,
                        androidTestingBuildName: androidTestingBuildName,
                        gpusCount: gpusCount,
                        nodeRetry: nodeRetry,
                        platforms: platforms,
                        clientTag: clientTag,
                        BUILD_TIMEOUT: 15,
                        // update timeouts dynamicly based on number of cases + traces are generated or not
                        TEST_TIMEOUT: 180,
                        DEPLOY_TIMEOUT: 150,
                        ADDITIONAL_XML_TIMEOUT: 15,
                        BUILDER_TAG: "BuilderStreamingSDK",
                        TESTER_TAG: testerTag,
                        CLIENT_TAG: "StreamingSDKClient && (${clientTag})",
                        MULTICONNECTION_CLIENT_TAG: "StreamingSDKClientMulticonnection",
                        testsPreCondition: this.&isIdleClient,
                        testCaseRetries: testCaseRetries,
                        engines: games.split(",") as List,
                        games: games,
                        clientCollectTraces:clientCollectTraces,
                        serverCollectTraces:serverCollectTraces,
                        collectTracesType:collectTracesType,
                        storeOnNAS: storeOnNAS,
                        finishedBuildStages: new ConcurrentHashMap(),
                        isDevelopBranch: isDevelopBranch
                        ]
        }

        multiplatform_pipeline(platforms, this.&executePreBuild, this.&executeBuild, this.&executeTests, this.&executeDeploy, options)
    } catch(e) {
        currentBuild.result = "FAILURE"
        println(e.toString())
        println(e.getMessage())
        throw e
    } finally {
        GithubNotificator.closeUnfinishedSteps(options, NotificationConfiguration.SOME_STAGES_FAILED)
        problemMessageManager.publishMessages()
    }

}

import net.sf.json.JSON
import net.sf.json.JSONSerializer
import net.sf.json.JsonConfig
import java.util.concurrent.ConcurrentHashMap


def executeGenTestRefCommand(String asicName, String osName, Map options, String apiValue = "vulkan") {
    dir('BaikalNext/RprTest') {
        if (options.testsQuality) {
            switch(osName) {
                case 'Windows':
                    bat """
                        ..\\bin\\RprTest -quality ${options.RENDER_QUALITY} -genref 1 --gtest_output=xml:../../${STAGE_NAME}.${options.RENDER_QUALITY}.gtest.xml >> ..\\..\\${STAGE_NAME}.${options.RENDER_QUALITY}.log 2>&1
                    """
                    break
                case 'OSX':
                    sh """
                        export LD_LIBRARY_PATH=../bin:\$LD_LIBRARY_PATH
                        ../bin/RprTest -quality ${options.RENDER_QUALITY} -genref 1 --gtest_output=xml:../../${STAGE_NAME}.${options.RENDER_QUALITY}.gtest.xml >> ../../${STAGE_NAME}.${options.RENDER_QUALITY}.log 2>&1
                    """
                    break
                default:
                    sh """
                        export LD_LIBRARY_PATH=../bin:\$LD_LIBRARY_PATH
                        ../bin/RprTest -quality ${options.RENDER_QUALITY} -genref 1 --gtest_output=xml:../../${STAGE_NAME}.${options.RENDER_QUALITY}.gtest.xml >> ../../${STAGE_NAME}.${options.RENDER_QUALITY}.log 2>&1
                    """
            }

        } else {
            
            options.enableRTX = ""
            if (!asicName.contains("RTX")) {
                println "[INFO] Enable rrn for ${asicName}"
                options.enableRTX = "-enable-rrn"
            }

            switch(osName) {
                case 'Windows':
                    bat """
                        ..\\bin\\RprTest ${options.enableRTX} --videoapi=${apiValue} -genref 1 --gtest_output=xml:../../${STAGE_NAME}_${apiValue}.gtest.xml >> ..\\..\\${STAGE_NAME}_${apiValue}.log 2>&1
                    """
                    break
                case 'OSX':
                    sh """
                        export LD_LIBRARY_PATH=../bin:\$LD_LIBRARY_PATH
                        ../bin/RprTest ${options.enableRTX} --videoapi=${apiValue} -genref 1 --gtest_output=xml:../../${STAGE_NAME}_${apiValue}.gtest.xml >> ../../${STAGE_NAME}_${apiValue}.log 2>&1
                    """
                    break
                default:
                    sh """
                        export LD_LIBRARY_PATH=../bin:\$LD_LIBRARY_PATH
                        ../bin/RprTest ${options.enableRTX} --videoapi=${apiValue} -genref 1 --gtest_output=xml:../../${STAGE_NAME}_${apiValue}.gtest.xml >> ../../${STAGE_NAME}_${apiValue}.log 2>&1
                    """
            }
        }
    }
}

def executeTestCommand(String asicName, String osName, Map options, String apiValue = "vulkan") {
    dir('BaikalNext/RprTest') {
        if (options.testsQuality) {
            switch(osName) {
                case 'Windows':
                    bat """
                        ..\\bin\\RprTest -quality ${options.RENDER_QUALITY} --gtest_output=xml:../../${STAGE_NAME}.${options.RENDER_QUALITY}.gtest.xml >> ..\\..\\${STAGE_NAME}.${options.RENDER_QUALITY}.log 2>&1
                    """
                    break
                case 'OSX':
                    sh """
                        export LD_LIBRARY_PATH=../bin:\$LD_LIBRARY_PATH
                        ../bin/RprTest -quality ${options.RENDER_QUALITY} --gtest_output=xml:../../${STAGE_NAME}.${options.RENDER_QUALITY}.gtest.xml >> ../../${STAGE_NAME}.${options.RENDER_QUALITY}.log 2>&1
                    """
                    break
                default:
                    sh """
                        export LD_LIBRARY_PATH=../bin:\$LD_LIBRARY_PATH
                        ../bin/RprTest -quality ${options.RENDER_QUALITY} --gtest_output=xml:../../${STAGE_NAME}.${options.RENDER_QUALITY}.gtest.xml >> ../../${STAGE_NAME}.${options.RENDER_QUALITY}.log 2>&1
                    """
            }

        } else {
            
            options.enableRTX = ""
            if (!asicName.contains("RTX")) {
                println "[INFO] Enable rrn for ${asicName}"
                options.enableRTX = "-enable-rrn"
            }

            switch(osName) {
                case 'Windows':
                    bat """
                        ..\\bin\\RprTest ${options.enableRTX} --videoapi=${apiValue} --gtest_output=xml:../../${STAGE_NAME}_${apiValue}.gtest.xml >> ..\\..\\${STAGE_NAME}_${apiValue}.log 2>&1
                    """
                    break
                case 'OSX':
                    sh """
                        export LD_LIBRARY_PATH=../bin:\$LD_LIBRARY_PATH
                        ../bin/RprTest ${options.enableRTX} --videoapi=${apiValue} --gtest_output=xml:../../${STAGE_NAME}_${apiValue}.gtest.xml >> ../../${STAGE_NAME}_${apiValue}.log 2>&1
                    """
                    break
                default:
                    sh """
                        export LD_LIBRARY_PATH=../bin:\$LD_LIBRARY_PATH
                        ../bin/RprTest ${options.enableRTX} --videoapi=${apiValue} --gtest_output=xml:../../${STAGE_NAME}_${apiValue}.gtest.xml >> ../../${STAGE_NAME}_${apiValue}.log 2>&1
                    """
            }
        }
    }
}


def executeTestsCustomQuality(String osName, String asicName, Map options, String apiValue = "vulkan") {
       
    cleanWS(osName)
    String error_message = ""
    String REF_PATH_PROFILE

    if (options.testsQuality) {
        REF_PATH_PROFILE="/volume1/Baselines/rpr_hybrid_autotests/${options.RENDER_QUALITY}/${asicName}-${osName}"
        outputEnvironmentInfo(osName, "${STAGE_NAME}.${options.RENDER_QUALITY}")
    } else {
        REF_PATH_PROFILE="/volume1/Baselines/rpr_hybrid_autotests/${apiValue}/${asicName}-${osName}"
        outputEnvironmentInfo(osName, "${STAGE_NAME}")
    }
    
    try {
        makeUnstash(name: "app${osName}", storeOnNAS: options.storeOnNAS)
        switch(osName) {
            case 'Windows':
                unzip dir: '.', glob: '', zipFile: 'BaikalNext_Build-Windows.zip'
                break
            default:
                sh "tar -xJf BaikalNext_Build*"
        }

        if (options['updateRefs']) {
            println "Updating Reference Images"
            executeGenTestRefCommand(asicName, osName, options, apiValue)
            uploadFiles("./BaikalNext/RprTest/ReferenceImages/", REF_PATH_PROFILE)
        } else {
            println "Execute Tests"
            downloadFiles("${REF_PATH_PROFILE}/", "./BaikalNext/RprTest/ReferenceImages/")
            executeTestCommand(asicName, osName, options, apiValue)
        }
    } catch (e) {
        println(e.getMessage())
        error_message = e.getMessage()
        options.successfulTests["unit"] = false

        if (options.testsQuality) {
            println("Exception during [${options.RENDER_QUALITY}] quality tests execution")
            try {
                if (options['updateRefs']) {
                    currentBuild.description += "<span style='color: #b03a2e'>References weren't updated for ${asicName}-${osName}-{options.RENDER_QUALITY} due to non-zero exit code returned by RprTest tool</span><br/>"
                }

                if (options['updateRefs']) {
                    println "Updating Reference Images"
                    executeGenTestRefCommand(asicName, osName, options)
                    uploadFiles('./BaikalNext/RprTest/ReferenceImages/', REF_PATH_PROFILE)
                }

                dir('HTML_Report') {
                    checkoutScm(branchName: "master", repositoryUrl: "git@github.com:luxteam/HTMLReportsShared")
                    python3("-m pip install --user -r requirements.txt")
                    python3("hybrid_report.py --xml_path ../${STAGE_NAME}.${options.RENDER_QUALITY}.gtest.xml --images_basedir ../BaikalNext/RprTest --report_path ../${asicName}-${osName}-${options.RENDER_QUALITY}_failures")
                }

                if (!options.storeOnNAS) {
                    makeStash(includes: "${asicName}-${osName}-${options.RENDER_QUALITY}_failures/**/*", name: "testResult-${asicName}-${osName}-${options.RENDER_QUALITY}", allowEmpty: true)
                }

                utils.publishReport(this, "${BUILD_URL}", "${asicName}-${osName}-${options.RENDER_QUALITY}_failures", "report.html", "${STAGE_NAME}_${options.RENDER_QUALITY}_failures", "${STAGE_NAME}_${options.RENDER_QUALITY}_failures", options.storeOnNAS, ["jenkinsBuildUrl": BUILD_URL, "jenkinsBuildName": currentBuild.displayName])

                options["failedConfigurations"].add("testResult-" + asicName + "-" + osName + "-" + options.RENDER_QUALITY)
            } catch (err) {
                println("Error during HTML report publish")
                println(err.getMessage())
            }
        } else {
            println("Exception during tests execution")
            try {
                if (options['updateRefs']) {
                    currentBuild.description += "<span style='color: #b03a2e'>References weren't updated for ${asicName}-${osName}-${apiValue} due to non-zero exit code returned by RprTest tool</span><br/>"
                }

                dir('HTML_Report') {
                    checkoutScm(branchName: "master", repositoryUrl: "git@github.com:luxteam/HTMLReportsShared")
                    python3("-m pip install -r requirements.txt")
                    python3("hybrid_report.py --xml_path ../${STAGE_NAME}_${apiValue}.gtest.xml --images_basedir ../BaikalNext/RprTest --report_path ../${asicName}-${osName}-${apiValue}-Failures")
                }

                if (!options.storeOnNAS) {
                    makeStash(includes: "${asicName}-${osName}-${apiValue}-Failures/**/*", name: "testResult-${asicName}-${osName}-${apiValue}", allowEmpty: true)
                }

                utils.publishReport(this, "${BUILD_URL}", "${asicName}-${osName}-${apiValue}-Failures", "report.html", "${STAGE_NAME}_${apiValue}_Failures", "${STAGE_NAME}_${apiValue}_Failures", options.storeOnNAS, ["jenkinsBuildUrl": BUILD_URL, "jenkinsBuildName": currentBuild.displayName])

                options["failedConfigurations"].add("testResult-" + asicName + "-" + osName + "-" + apiValue)
            } catch (err) {
                println("[ERROR] Failed to publish HTML report.")
                println(err.getMessage())
            }
        }
    } finally {
        if (options['RENDER_QUALITY']) {
            options['processedQualities'] << options['RENDER_QUALITY']
        }

        if (options.testsQuality) {
            String title = "${asicName}-${osName}-${options.RENDER_QUALITY}"
            String description = error_message ? "Testing finished with error message: ${error_message}" : "Testing finished"
            String status = error_message ? "action_required" : "success"
            String url = error_message ? "${env.BUILD_URL}/${STAGE_NAME}_${options.RENDER_QUALITY}_failures" : "${env.BUILD_URL}/artifact/${STAGE_NAME}.${options.RENDER_QUALITY}.log"
            GithubNotificator.updateStatus('Test', title, status, options, description, url)

        } else {
            String title = "${asicName}-${osName}-${apiValue}"
            String description = error_message ? "Testing finished with error message: ${error_message}" : "Testing finished"
            String status = error_message ? "action_required" : "success"
            String url = error_message ? "${env.BUILD_URL}/${STAGE_NAME}_${apiValue}_Failures" : "${env.BUILD_URL}/artifact/${STAGE_NAME}_${apiValue}.log"
            GithubNotificator.updateStatus('Test', title, status, options, description, url)
        }

        archiveArtifacts "*.log"
        archiveArtifacts "*.gtest.xml"
        junit "*.gtest.xml"
    }
}


def createPerfDirs() {
    if (isUnix()) {
        sh """
            mkdir -p Scenarios
            mkdir -p Telemetry
            mkdir -p Metrics
            mkdir -p Reports
            mkdir -p References
        """
    } else {
        bat """
            if not exist Scenarios mkdir Scenarios
            if not exist Telemetry mkdir Telemetry
            if not exist Metrics mkdir Metrics
            if not exist Reports mkdir Reports
            if not exist References mkdir References
        """
    }
}


def executeGenPerfTestRefCommand(String asicName, String osName, Map options) {
    dir('BaikalNext/RprPerfTest') {
        createPerfDirs()
        switch(osName) {
            case 'Windows':
                python3("ScenarioPlayer.py -s ${options.scenarios} -E ..\\bin >> ..\\..\\${STAGE_NAME}.perf.log 2>&1", "39")
                break
            case 'OSX':
                withEnv(["LD_LIBRARY_PATH=../bin:\$LD_LIBRARY_PATH"]) {
                    python3("ScenarioPlayer.py -s ${options.scenarios} -E ../bin >> ../../${STAGE_NAME}.perf.log 2>&1", "3.9")
                }
                break
            default:
                withEnv(["LD_LIBRARY_PATH=../bin:\$LD_LIBRARY_PATH"]) {
                    python3("ScenarioPlayer.py -s ${options.scenarios} -E ../bin >> ../../${STAGE_NAME}.perf.log 2>&1", "3.9")
                }
        }
    }
}


def executePerfTestCommand(String asicName, String osName, Map options) {
    dir('BaikalNext/RprPerfTest') {
        createPerfDirs()
        switch(osName) {
            case 'Windows':
                python3("ScenarioPlayer.py -s ${options.scenarios} -E ..\\bin -P >> ..\\..\\${STAGE_NAME}.perf.log 2>&1", "39")
                break
            case 'OSX':
                withEnv(["LD_LIBRARY_PATH=../bin:\$LD_LIBRARY_PATH"]) {
                    python3("ScenarioPlayer.py -s ${options.scenarios} -E ../bin -P >> ../../${STAGE_NAME}.perf.log 2>&1", "3.9")
                }
                break
            default:
                withEnv(["LD_LIBRARY_PATH=../bin:\$LD_LIBRARY_PATH"]) {
                    python3("ScenarioPlayer.py -s ${options.scenarios} -E ../bin -P >> ../../${STAGE_NAME}.perf.log 2>&1", "3.9")
                }
        }
    }
}


def executePerfTests(String osName, String asicName, Map options) {
    String error_message = ""
    String REF_PATH_PROFILE

    REF_PATH_PROFILE="/volume1/Baselines/rpr_hybrid_autotests/perf/${asicName}-${osName}"
    outputEnvironmentInfo(osName, "${STAGE_NAME}.perf")
    
    try {
        String assetsDir = isUnix() ? "${CIS_TOOLS}/../TestResources/rpr_hybrid_autotests_assets" : "/mnt/c/TestResources/rpr_hybrid_autotests_assets"
        downloadFiles("/volume1/web/Assets/rpr_hybrid_autotests/", assetsDir)

        dir("BaikalNext") {
            dir("bin") {
                makeUnstash(name: "perf${osName}", unzip: false, storeOnNAS: options.storeOnNAS)
            }

            dir("RprPerfTest") {
                makeUnstash(name: "perfTestsConf", storeOnNAS: options.storeOnNAS)
            }

            dir("RprPerfTest/Scenarios") {
                if (options.scenarios == "all") {
                    List scenariosList = []
                    def files = findFiles(glob: "*.json")
                    for (file in files) {
                        scenariosList << file.name
                    }
                    options.scenarios = scenariosList.join(" ")
                }
                for (scenarioName in options.scenarios.split()) {
                    def scenarioContent = readJSON file: scenarioName
                    // check that it's scene path not a case which is implemented programmatically
                    if (scenarioContent["scene_name"].contains("/")) {
                        String[] scenePathParts = scenarioContent["scene_name"].split("/")
                        scenarioContent["scene_name"] = assetsDir.replace("\\", "/") + "/" + scenePathParts[-2] + "/" + scenePathParts[-1]
                        JSON serializedJson = JSONSerializer.toJSON(scenarioContent, new JsonConfig());
                        writeJSON file: scenarioName, json: serializedJson, pretty: 4
                    }
                }
            }
        }

        if (options["updateRefsPerf"]) {
            println "Updating references for performance tests"
            executeGenPerfTestRefCommand(asicName, osName, options)
            uploadFiles('./BaikalNext/RprPerfTest/Telemetry/', REF_PATH_PROFILE)
        } else {
            println "Execute Tests"
            downloadFiles("${REF_PATH_PROFILE}/", "./BaikalNext/RprPerfTest/References/")
            executePerfTestCommand(asicName, osName, options)
        }
    } catch (e) {
        println(e.getMessage())
        error_message = e.getMessage()
        options.successfulTests["perf"] = false
        currentBuild.result = "UNSTABLE"
    } finally {
        archiveArtifacts "*.log"

        dir("BaikalNext/RprPerfTest/Reports") {
            makeStash(includes: "*.json", name: "testPerfResult-${asicName}-${osName}", allowEmpty: true, storeOnNAS: options.storeOnNAS)

            // check results
            if (!options.updateRefsPerf) {
                List reportsList = []
                def reports = findFiles(glob: "*.json")
                Boolean cliffDetected
                loop: for (report in reports) {
                    def reportContent = readJSON file: report.name
                    for (metric in reportContent) {
                        if (metric.value["Cliff_detected"]) {
                            cliffDetected = true
                            options.successfulTests["cliff_detected"] = true
                        } else if (metric.value["Unexpected_acceleration"]) {
                            unexpectedAcceleration = true
                            options.successfulTests["unexpected_acceleration"] = true
                        }
                    }
                }
                if (cliffDetected) {
                    currentBuild.result = "UNSTABLE"
                    options.successfulTests["perf"] = false
                }
                if (cliffDetected) {
                    error_message += " Testing finished with 'cliff detected'."
                }

                error_message = error_message.trim()
            }
        }

        if (env.BRANCH_NAME) {
            String title = "${asicName}-${osName}"
            String description = error_message ? "Testing finished with error message: ${error_message}" : "Testing finished"
            String status = error_message ? "action_required" : "success"
            String url = "${env.BUILD_URL}/artifact/${STAGE_NAME}.perf.log"
            GithubNotificator.updateStatus('Test-Perf', title, status, options, description, url)
        }
    }
}


def executeTests(String osName, String asicName, Map options) {
    GithubNotificator.updateStatus("Test", "${asicName}-${osName}", "in_progress", options, "In progress...")

    Boolean someStageFail = false 
    if (options.testsQuality) {
        options['testsQuality'].split(",").each() {
            try {
                // create list with processed qualities for do not run them twice in case of retries
                if (!options['processedQualities']) {
                    options['processedQualities'] = []
                }
                options['RENDER_QUALITY'] = "${it}"
                if (!options['processedQualities'].contains(options['RENDER_QUALITY'])) {
                    executeTestsCustomQuality(osName, asicName, options)
                }
            } catch (e) {
                // suppress exception for start next quality test
                someStageFail = true
                println(e.toString())
                println(e.getMessage())
            }
        }
    } else {
        options["apiValues"].each() { apiValue ->
            try {
                if (apiValue == "d3d12" && osName.contains("Ubuntu")) {
                    // DX12 tests are supported only on Windows
                    return
                }

                // run in parallel to display api value in UI of JUnit plugin
                Map stages = ["${apiValue}" : { executeTestsCustomQuality(osName, asicName, options, apiValue) }]
                parallel stages
            } catch (e) {
                someStageFail = true
                println(e.toString())
                println(e.getMessage())
            }
        }

        if (options.scenarios) {
            try {
                executePerfTests(osName, asicName, options)
            } catch (e) {
                someStageFail = true
                println(e.toString())
                println(e.getMessage())
            }
        }
    }

    if (someStageFail) {
        // send error signal for mark stage as failed
        error "Error during tests execution"
    }
}


def executeBuildWindows(Map options) {
    String build_type = options['cmakeKeys'].contains("-DCMAKE_BUILD_TYPE=Debug") ? "Debug" : "Release"
    bat """
        mkdir Build
        cd Build
        cmake ${options['cmakeKeys']} -G "Visual Studio 15 2017 Win64" .. >> ..\\${STAGE_NAME}.log 2>&1
        cmake --build . --target PACKAGE --config ${build_type} >> ..\\${STAGE_NAME}.log 2>&1
        rename BaikalNext.zip BaikalNext_${STAGE_NAME}.zip
    """
    dir("Build/bin/${build_type}") {
        makeStash(includes: "RprPerfTest.exe", name: "perfWindows", allowEmpty: true, preZip: false, storeOnNAS: options.storeOnNAS)
    }

    if (env.BRANCH_NAME == "material_x") {
        withNotifications(title: "Windows", options: options, configuration: NotificationConfiguration.UPDATE_BINARIES) {

            hybrid_vs_northstar_pipeline.updateBinaries(
                newBinaryFile: "Build\\_CPack_Packages\\win64\\ZIP\\BaikalNext\\bin\\HybridPro.dll", 
                targetFileName: "HybridPro.dll", osName: "Windows", compareChecksum: true
            )
        }
    }

    // Hybrid vs NS auto is disabled
    //hybrid_vs_northstar_pipeline.createHybridBranch(options)
}


def executeBuildOSX(Map options) {
    sh """
        mkdir Build
        cd Build
        cmake ${options['cmakeKeys']} .. >> ../${STAGE_NAME}.log 2>&1
        make -j 4 >> ../${STAGE_NAME}.log 2>&1
        make package >> ../${STAGE_NAME}.log 2>&1
        mv BaikalNext.tar.xz BaikalNext_${STAGE_NAME}.tar.xz
    """
    dir("Build/bin") {
        makeStash(includes: "RprPerfTest", name: "perfOSX", allowEmpty: true, preZip: false, storeOnNAS: options.storeOnNAS)
    }
}


def executeBuildLinux(Map options) {
    sh """
        mkdir Build
        cd Build
        cmake ${options['cmakeKeys']} .. >> ../${STAGE_NAME}.log 2>&1
        make -j 8 >> ../${STAGE_NAME}.log 2>&1
        make package >> ../${STAGE_NAME}.log 2>&1
        mv BaikalNext.tar.xz BaikalNext_${STAGE_NAME}.tar.xz
    """
    dir("Build/bin") {
        makeStash(includes: "RprPerfTest", name: "perfUbuntu18", allowEmpty: true, preZip: false, storeOnNAS: options.storeOnNAS)
    }
}


def executeBuild(String osName, Map options) {
    String error_message = ""
    String context = "[BUILD] ${osName}"
    try {
        checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo)

        outputEnvironmentInfo(osName)
        
        withNotifications(title: osName, options: options, configuration: NotificationConfiguration.BUILD_SOURCE_CODE) {
            GithubNotificator.updateStatus("Build", osName, "in_progress", options, "Checkout has been finished. Trying to build...")
            switch(osName) {
                case 'Windows':
                    executeBuildWindows(options)
                    break
                case 'OSX':
                    executeBuildOSX(options)
                    break
                default:
                    executeBuildLinux(options)
            }
        }

        dir('Build') {
            makeStash(includes: "BaikalNext_${STAGE_NAME}*", name: "app${osName}", storeOnNAS: options.storeOnNAS)
        }
    } catch (e) {
        println(e.getMessage())
        error_message = e.getMessage()
        currentBuild.result = "FAILED"
        throw e
    } finally {
        archiveArtifacts "*.log"

        String artifactName

        switch (osName) {
            case 'Windows': 
                artifactName = "BaikalNext_${STAGE_NAME}.zip"
                break
            case 'OSX':
                artifactName = "BaikalNext_${STAGE_NAME}.zip"
                break
            default: 
                artifactName = "BaikalNext_${STAGE_NAME}.tar.xz"
        }

        dir("Build") {
            makeArchiveArtifacts(name: artifactName, storeOnNAS: options.storeOnNAS)
        }

        String status = error_message ? "action_required" : "success"
        GithubNotificator.updateStatus("Build", osName, status, options, "Build finished as '${status}'", "${env.BUILD_URL}/artifact/${STAGE_NAME}.log")
    }
}

def executePreBuild(Map options) {
   
    checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo, disableSubmodules: true)

    options.commitAuthor = bat (script: "git show -s --format=%%an HEAD ",returnStdout: true).split('\r\n')[2].trim()
    commitMessage = bat (script: "git log --format=%%B -n 1", returnStdout: true)
    options.commitSHA = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
    println "The last commit was written by ${options.commitAuthor}."
    println "Commit message: ${commitMessage}"
    println "Commit SHA: ${options.commitSHA}"
    
    if ((commitMessage.contains("[CIS:GENREFALL]") || commitMessage.contains("[CIS:GENREF]")) && env.BRANCH_NAME && env.BRANCH_NAME == "master") {
        options.updateRefs = true
        println("[CIS:GENREF] or [CIS:GENREFALL] have been found in comment")
    }

    if ((commitMessage.contains("[CIS:GENREFALL]") || commitMessage.contains("[CIS:GENREFPERF]")) && env.BRANCH_NAME && env.BRANCH_NAME == "master") {
        options.updateRefsPerf = true
        println("[CIS:GENREFPERF] or [CIS:GENREFALL] have been found in comment")
    }

    if (env.CHANGE_URL) {
        println("Build was detected as Pull Request")
    }

    dir("RprPerfTest") {
        makeStash(includes: "**/*", name: "perfTestsConf", allowEmpty: true, storeOnNAS: options.storeOnNAS)
    }

    options.commitMessage = []
    commitMessage = commitMessage.split('\r\n')
    commitMessage[2..commitMessage.size()-1].collect(options.commitMessage) { it.trim() }
    options.commitMessage = options.commitMessage.join('\n')

    println "Commit list message: ${options.commitMessage}"

    if (!options.isLegacyBranch && env.CHANGE_URL) {
        // save name of new branch for hybrid_vs_northstar
        String comparisionBranch = "hybrid_auto_${env.BRANCH_NAME}"

        dir("HybridVsNorthstar") {
            String comparisionRepoUrl = hybrid_vs_northstar_pipeline.PROJECT_REPO

            checkoutScm(branchName: "main", repositoryUrl: comparisionRepoUrl)

            Boolean branchNotExists = bat(script: "git ls-remote --heads ${comparisionRepoUrl} ${comparisionBranch}", returnStdout: true)
                .split('\r\n').length == 2

            if (branchNotExists) {
                options.comparisionBranch = comparisionBranch
            }
        }
    }
    
    // set pending status for all
    if (env.CHANGE_ID) {
        withNotifications(title: "Jenkins build configuration", printMessage: true, options: options, configuration: NotificationConfiguration.CREATE_GITHUB_NOTIFICATOR) {
            GithubNotificator githubNotificator = new GithubNotificator(this, options)
            githubNotificator.init(options)
            options["githubNotificator"] = githubNotificator
        }
        options['platforms'].split(';').each() { platform ->
            List tokens = platform.tokenize(':')
            String osName = tokens.get(0)
            // Statuses for builds
            GithubNotificator.createStatus('Build', osName, 'queued', options, 'Scheduled', "${env.JOB_URL}")
            if (tokens.size() > 1) {
                gpuNames = tokens.get(1)
                gpuNames.split(',').each() { gpuName ->
                    if (options.testsQuality) {
                        options.testsQuality.split(",").each() { testQuality ->
                            // Statuses for tests
                            GithubNotificator.createStatus('Test', "${gpuName}-${osName}-${testQuality}", 'queued', options, 'Scheduled', "${env.JOB_URL}")
                        }
                    } else {
                        options["apiValues"].each() { apiValue ->
                            if (apiValue == "d3d12" && osName.contains("Ubuntu")) {
                                // DX12 tests are supported only on Windows
                                return
                            }

                            // Statuses for tests
                            GithubNotificator.createStatus('Test', "${gpuName}-${osName}-${apiValue}", 'queued', options, 'Scheduled', "${env.JOB_URL}")
                        }
                    }
                    if (options.scenarios) {
                        // Statuses for performance tests
                        GithubNotificator.createStatus('Test-Perf', "${gpuName}-${osName}", 'queued', options, 'Scheduled', "${env.JOB_URL}")
                    }
                }
            }
        }
    }
}

def executeDeploy(Map options, List platformList, List testResultList) {
    cleanWS()
    if (options['executeTests'] && testResultList) {
        if (options.testsQuality) {
            try {
                String reportFiles = ""
                dir("SummaryReport") {
                    options['testsQuality'].split(",").each() { quality ->
                        testResultList.each() {
                            try {
                                if (!options.storeOnNAS) {
                                    makeUnstash(name: "${it}-${quality}", storeOnNAS: options.storeOnNAS)
                                    reportFiles += ", ${it}-${quality}_failures/report.html".replace("testResult-", "")
                                } else if (options["failedConfigurations"].contains(it + "-" + quality)) {
                                    reportFiles += ",../${it}-${quality}_failures/report.html".replace("testResult-", "Test-")
                                }
                            }
                            catch(e) {
                                echo "Can't unstash ${it} ${quality}"
                                println(e.toString())
                                println(e.getMessage())
                            }
                        }
                    }
                }

                if (options.failedConfigurations.size() != 0) {
                    utils.publishReport(this, "${BUILD_URL}", "SummaryReport", "${reportFiles.replaceAll('^,', '')}", "HTML Failures", reportFiles.replaceAll('^,', '').replaceAll("\\.\\./", ""), options.storeOnNAS, ["jenkinsBuildUrl": BUILD_URL, "jenkinsBuildName": currentBuild.displayName])
                }
            } catch(e) {
                println(e.toString())
            }
        } else {
            try {
                String reportFiles = ""
                dir("SummaryReport") {
                    options["apiValues"].each() { apiValue ->
                        testResultList.each() {
                            if (apiValue == "d3d12" && testResultList.contains("Ubuntu")) {
                                // DX12 tests are supported only on Windows
                                return
                            }

                            try {
                                if (!options.storeOnNAS) {
                                    makeUnstash(name: "${it}_${apiValue}", storeOnNAS: options.storeOnNAS)
                                    reportFiles += ", ${it}-${apiValue}-Failures/report.html".replace("testResult-", "")
                                } else if (options["failedConfigurations"].contains(it + "-" + apiValue)) {
                                    reportFiles += ",../${it}_${apiValue}_Failures/report.html".replace("testResult-", "Test-")
                                    println(reportFiles)
                                }
                            } catch(e) {
                                println("[ERROR] Can't unstash ${it}")
                                println(e.toString())
                                println(e.getMessage())
                            }
                        }
                    }
                }

                if (options.failedConfigurations.size() != 0) {
                    utils.publishReport(this, "${BUILD_URL}", "SummaryReport", "${reportFiles.replaceAll('^,', '')}", "HTML Failures", reportFiles.replaceAll('^,', '').replaceAll("\\.\\./", ""), options.storeOnNAS, ["jenkinsBuildUrl": BUILD_URL, "jenkinsBuildName": currentBuild.displayName])
                }
            } catch(e) {
                println(e.toString())
            }

            if (options.scenarios && !options.updateRefsPerf) {
                checkoutScm(branchName: "master", repositoryUrl: "git@github.com:luxteam/HTMLReportsShared")

                dir("performanceReports") {
                    testResultList.each() {
                        try {
                            dir("${it}".replace("testResult-", "")) {
                                makeUnstash(name: "${it.replace('testResult-', 'testPerfResult-')}", storeOnNAS: options.storeOnNAS)
                            }
                        }
                        catch(e) {
                            echo "[ERROR] Can't unstash ${it.replace('testResult-', 'testPerfResult-')}"
                            println(e.toString());
                            println(e.getMessage());
                        }
                    }
                }

                python3("-m pip install --user -r requirements.txt")
                python3("hybrid_perf_report.py --json_files_path \"performanceReports\"")

                utils.publishReport(this, "${BUILD_URL}", "PerformanceReport", "performace_report.html", "Performance Tests Report", "Performance Tests Report", options.storeOnNAS, ["jenkinsBuildUrl": BUILD_URL, "jenkinsBuildName": currentBuild.displayName])
            }
        }
    }

    // set error statuses for PR, except if current build has been superseded by new execution
    if (env.CHANGE_ID && !currentBuild.nextBuild) {
        // if jobs was aborted or crushed remove pending status for unfinished stages
        GithubNotificator.closeUnfinishedSteps(options, "Build has been terminated unexpectedly")
        String status = currentBuild.result ?: "success"
        status = status.toLowerCase()
        String commentMessage = ""
        if (!options.successfulTests["unit"]) {
            commentMessage = "\\n Unit tests failures - ${env.BUILD_URL}/HTML_20Failures/"
        }
        if (!options.successfulTests["perf"]) {
            commentMessage += "\\n Perf tests report (problems detected) - ${env.BUILD_URL}/Performance_20Tests_20Report/"
        } else if (options.successfulTests["cliff_detected"] && options.successfulTests["unexpected_acceleration"]) {
            commentMessage += "\\n Perf tests report (cliff and unexpected acceleration detected) - ${env.BUILD_URL}/Performance_20Tests_20Report/"
        } else if (options.successfulTests["cliff_detected"]) {
            commentMessage += "\\n Perf tests report (cliff detected) - ${env.BUILD_URL}/Performance_20Tests_20Report/"
        } else if (options.successfulTests["unexpected_acceleration"]) {
            commentMessage += "\\n Perf tests report (unexpected acceleration detected) - ${env.BUILD_URL}/Performance_20Tests_20Report/"
        } else {
            commentMessage += "\\n Perf tests report (success) - ${env.BUILD_URL}/Performance_20Tests_20Report/"
        }
        String commitUrl = "${options.githubNotificator.repositoryUrl}/commit/${options.githubNotificator.commitSHA}"
        GithubNotificator.sendPullRequestComment("Jenkins build for ${commitUrl} finished as ${status} ${commentMessage}", options)
    }

    if (env.TAG_NAME) {
        hybrid_to_blender_workflow.createUSDBranch(options)
    }
}

def call(String projectBranch = "",
         String platforms = "Windows:NVIDIA_RTX3080TI,AMD_RX6800XT;Ubuntu20",
         String testsQuality = "none",
         String scenarios = "all",
         Boolean updateRefs = false,
         Boolean updateRefsPerf = false,
         Boolean enableNotifications = true,
         String cmakeKeys = "-DCMAKE_BUILD_TYPE=Release -DBAIKAL_ENABLE_RPR=ON -DBAIKAL_NEXT_EMBED_KERNELS=ON",
         String apiValues = "vulkan,d3d12") {

    if (env.CHANGE_URL && env.CHANGE_TARGET == "master") {
        while (jenkins.model.Jenkins.instance.getItem(env.JOB_NAME.split("/")[0]).getItem("master").lastBuild.result == null) {
            println("[INFO] Make a delay because there is a running build in master branch")
            sleep(300)
        }
    } else if (env.BRANCH_NAME && env.BRANCH_NAME == "master") {
        def buildNumber = env.BUILD_NUMBER as int
        if (buildNumber > 1) {
            milestone(buildNumber - 1)
        }
        milestone(buildNumber) 
    }

    Boolean isLegacyBranch = false

    if (testsQuality == "none") {
        println "[INFO] Convert none quality to empty string"
        testsQuality = ""
    }

    if ((env.BRANCH_NAME && env.BRANCH_NAME == "1.xx") || (env.CHANGE_TARGET && env.CHANGE_TARGET == "1.xx") || (projectBranch == "1.xx")) {
        testsQuality = "low,medium,high"
        scenarios = ""
        isLegacyBranch = true
    }

    List apiList = apiValues.split(",") as List

    println "Test quality: ${testsQuality}"
    println "[INFO] Performance tests which will be executed: ${scenarios}"
    println "[INFO] Testing APIs: ${apiList}"

    Map successfulTests = ["unit": true, "perf": true, "cliff_detected": false, "unexpected_acceleration": false]

    currentBuild.description = ""

    multiplatform_pipeline(platforms, this.&executePreBuild, this.&executeBuild, this.&executeTests, this.&executeDeploy,
                           [platforms:platforms,
                            projectBranch:projectBranch,
                            scenarios:scenarios,
                            updateRefs:updateRefs,
                            updateRefsPerf:updateRefsPerf,
                            testsQuality:testsQuality,
                            enableNotifications:enableNotifications,
                            PRJ_NAME:"RadeonProRender-Hybrid",
                            PRJ_ROOT:"rpr-core",
                            projectRepo:"git@github.com:Radeon-Pro/RPRHybrid.git",
                            BUILDER_TAG:'HybridBuilder',
                            TESTER_TAG:'HybridTester',
                            executeBuild:true,
                            executeTests:true,
                            slackChannel:"${SLACK_BAIKAL_CHANNEL}",
                            slackWorkspace:SlackUtils.SlackWorkspace.BAIKAL,
                            TEST_TIMEOUT:60,
                            cmakeKeys:cmakeKeys,
                            retriesForTestStage:1,
                            successfulTests:successfulTests,
                            isLegacyBranch:isLegacyBranch,
                            failedConfigurations: [],
                            storeOnNAS: true,
                            finishedBuildStages: new ConcurrentHashMap(),
                            apiValues: apiList])
}

import groovy.transform.Field
import groovy.json.JsonOutput
import utils
import net.sf.json.JSON
import net.sf.json.JSONSerializer
import net.sf.json.JsonConfig
import TestsExecutionType

@Field ProblemMessageManager problemMessageManager = new ProblemMessageManager(this, currentBuild)


def installHoudiniPlugin(String osName, Map options){
    
    switch(osName)
    {
        case 'Windows':

            unstash "appWindows"

            bat """
                %CIS_TOOLS%\\7-Zip\\7z.exe -aoa e hdRpr_${osName}.tar.gz
                %CIS_TOOLS%\\7-Zip\\7z.exe -aoa x tmpPackage.tar 
                cd ${options.win_build_name}
                echo y | activateHoudiniPlugin.exe >> \"..\\${options.stageName}_${options.currentTry}.install.log\"  2>&1
            """

            break;

        case "OSX":

            unstash "appOSX"

            sh """
                tar -xzf hdRpr_${osName}.tar.gz 
                cd ${options.osx_build_name}
                chmod +x activateHoudiniPlugin
                echo y | ./activateHoudiniPlugin >> \"../${options.stageName}_${options.currentTry}.install.log\" 2>&1
            """
            
            break;

        default:
            
            unstash "app${osName}"

            sh """
                tar -xzf hdRpr_${osName}.tar.gz
                cd ${options.unix_build_name}
                chmod +x activateHoudiniPlugin
                echo y | ./activateHoudiniPlugin \"../${options.stageName}_${options.currentTry}.install.log\" 2>&1
            """
            
    }

}


def buildRenderCache(String osName, Map options)
{
    dir("scripts") {
        switch(osName)
        {
            case 'Windows':
                bat """
                    build_rpr_cache.bat \"${options.win_tool_path}\\bin\\husk.exe\" >> \"..\\${options.stageName}_${options.currentTry}.cb.log\"  2>&1
                """
                break;
            case 'OSX':
                sh """
                    chmod +x build_rpr_cache.sh
                    ./build_rpr_cache.sh \"${options.osx_tool_path}/bin/husk\" >> \"../${options.stageName}_${options.currentTry}.cb.log\" 2>&1
                """  
                break;   
            default:
                sh """
                    chmod +x build_rpr_cache.sh
                    ./build_rpr_cache.sh \"/home/user/${options.unix_tool_path}/bin/husk\" >> \"../${options.stageName}_${options.currentTry}.cb.log\" 2>&1
                """     
        }
    }
}


def executeGenTestRefCommand(String osName, Map options, Boolean delete)
{
    dir('scripts')
    {
        switch(osName)
        {
            case 'Windows':
                bat """
                    make_results_baseline.bat ${delete}
                """
                break;
            default:
                sh """
                    chmod +x make_results_baseline.sh
                    ./make_results_baseline.sh ${delete}
                """
        }
    }
}

def executeTestCommand(String osName, Map options)
{
    dir('scripts') {
        switch(osName)
        {
            case 'Windows':
                bat """
                    run.bat ${options.testsPackage} \"${options.tests}\" ${options.width} ${options.height} ${options.updateRefs} \"${options.win_tool_path}\\bin\\husk.exe\" >> \"../${STAGE_NAME}_${options.currentTry}.log\" 2>&1
                """
                break;
            case 'OSX':
                sh """
                    chmod +x run.sh
                    ./run.sh ${options.testsPackage} \"${options.tests}\" ${options.width} ${options.height} ${options.updateRefs} \"${options.osx_tool_path}/bin/husk\" >> \"../${STAGE_NAME}_${options.currentTry}.log\" 2>&1
                """
                break;
            default:
                sh """
                    chmod +x run.sh
                    ./run.sh ${options.testsPackage} \"${options.tests}\" ${options.width} ${options.height} ${options.updateRefs} \"/home/user/${options.unix_tool_path}/bin/husk\" >> \"../${STAGE_NAME}_${options.currentTry}.log\" 2>&1
                """
        }
    }
}


def executeTests(String osName, String asicName, Map options)
{

     if (options.buildType == "Houdini") {
        try {
            timeout(time: "20", unit: 'MINUTES') {
                houdini_python3 = options.houdini_python3 ? "--python3" : ''
                withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'sidefxCredentials', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
                    print(python3("${CIS_TOOLS}/houdini_api.py --client_id \"$USERNAME\" --client_secret_key \"$PASSWORD\" --version \"${options.houdiniVersion}\" ${houdini_python3}"))
                }
            }
        } catch (e) {
            if (utils.isTimeoutExceeded(e)) {
                String errorMessage = "Failed to validate installed houdini or install houdini due to timeout."
                GithubNotificator.updateStatus("Build", osName, "failure", env, options, errorMessage)
                problemMessageManager.saveSpecificFailReason(errorMessage, "Build", osName)
            } else {
                String errorMessage = "Failed to validate installed houdini or install houdini."
                GithubNotificator.updateStatus("Build", osName, "failure", env, options, errorMessage)
                problemMessageManager.saveSpecificFailReason(errorMessage, "Build", osName)
            }
            throw e  
        }
    }

    // used for mark stash results or not. It needed for not stashing failed tasks which will be retried.
    Boolean stashResults = true

    try {
        try {
            timeout(time: "5", unit: 'MINUTES') {
                GithubNotificator.updateStatus("Test", options['stageName'], "pending", env, options, "Downloading tests repository.", "${BUILD_URL}")
                cleanWS(osName)
                checkOutBranchOrScm(options['testsBranch'], 'git@github.com:luxteam/jobs_test_houdini.git')
                println "[INFO] Preparing on ${env.NODE_NAME} successfully finished."
            }
        } catch (e) {
            if (utils.isTimeoutExceeded(e)) {
                throw new ExpectedExceptionWrapper("Failed to download tests repository due to timeout.", e)
            } else {
                throw new ExpectedExceptionWrapper("Failed to download tests repository.", e)
            }
        }

        try {
            GithubNotificator.updateStatus("Test", options['stageName'], "pending", env, options, "Downloading test scenes.", "${BUILD_URL}")
            downloadAssets("${options.PRJ_ROOT}/${options.PRJ_NAME}/HoudiniAssets/", 'HoudiniAssets')
        } catch (e) {
            throw new ExpectedExceptionWrapper("Failed to download test scenes.", e)
        }

        try {
            try {
                timeout(time: "10", unit: 'MINUTES') {
                    GithubNotificator.updateStatus("Test", options['stageName'], "pending", env, options, "Installing the plugin.", "${BUILD_URL}")
                    installHoudiniPlugin(osName, options)
                }
            } catch (e) {
                if (utils.isTimeoutExceeded(e)) {
                    throw new ExpectedExceptionWrapper("Failed to install the plugin due to timeout.", e)
                } else {
                    throw new ExpectedExceptionWrapper("Failed to install the plugin.", e)
                }
            }
        } catch(e) {
            println("[ERROR] Failed to install plugin on ${env.NODE_NAME}")
            println(e.toString())
            throw e
        }

        try {
            timeout(time: "5", unit: 'MINUTES') {
                GithubNotificator.updateStatus("Test", options['stageName'], "pending", env, options, "Building cache.", "${BUILD_URL}")
                buildRenderCache(osName, options)
                if(!fileExists("./Work/Results/Houdini/cache_building.jpg")){
                    println "[ERROR] Failed to build cache on ${env.NODE_NAME}. No output image found."
                    throw new ExpectedExceptionWrapper("No output image after cache building.", new Exception("No output image after cache building."))
                }
            }
        } catch (e) {
            if (utils.isTimeoutExceeded(e)) {
                throw new ExpectedExceptionWrapper("Failed to build cache due to timeout.", e)
            } else {
                throw new ExpectedExceptionWrapper("Failed to build cache.", e)
            }
        }

        String REF_PATH_PROFILE="${options.PRJ_ROOT}/${options.PRJ_NAME}/rpr_houdini_autotests_baselines/${asicName}-${osName}"
        
        outputEnvironmentInfo(osName, options.stageName, options.currentTry)

        try {
            if (options['updateRefs'].contains('Update')) {
                executeTestCommand(osName, options)
                executeGenTestRefCommand(osName, options, options['updateRefs'].contains('clean'))
                sendFiles('./Work/GeneratedBaselines/', REF_PATH_PROFILE)
                // delete generated baselines when they're sent 
                switch(osName) {
                    case 'Windows':
                        bat "if exist Work\\GeneratedBaselines rmdir /Q /S Work\\GeneratedBaselines"
                        break;
                    default:
                        sh "rm -rf ./Work/GeneratedBaselines"        
                }
            } else {
                // TODO: receivebaseline for json suite
                try {
                    String baseline_dir = isUnix() ? "${CIS_TOOLS}/../TestResources/rpr_houdini_autotests_baselines" : "/mnt/c/TestResources/rpr_houdini_autotests_baselines"
                    GithubNotificator.updateStatus("Test", options['stageName'], "pending", env, options, "Downloading reference images.", "${BUILD_URL}")
                    println "[INFO] Downloading reference images for ${options.testsPackage}"
                    options.tests.split(" ").each() {
                        try {
                            receiveFiles("${REF_PATH_PROFILE}/${it}", baseline_dir)
                        } catch (e) {
                            println("[WARNING] Problem when copying baselines. Group: ${it}. " + e.getMessage())
                        }
                    }
                } catch (e) {
                    println("[WARNING] Problem when copying baselines. " + e.getMessage())
                }
                GithubNotificator.updateStatus("Test", options['stageName'], "pending", env, options, "Executing tests.", "${BUILD_URL}")
                executeTestCommand(osName, options)
            }
            options.executeTestsFinished = true
        } catch (e) {
            if (utils.isTimeoutExceeded(e)) {
                throw new ExpectedExceptionWrapper("Failed to execute tests due to timeout.", e)
            } else {
                throw new ExpectedExceptionWrapper("An error occurred while executing tests. Please contact support.", e)
            }
        }

    } catch (e) {
        if (options.currentTry < options.nodeReallocateTries) {
            stashResults = false
        }
        println(e.toString())
        println(e.getMessage())
        if (e instanceof ExpectedExceptionWrapper) {
            GithubNotificator.updateStatus("Test", options['stageName'], "failure", env, options, e.getMessage(), "${BUILD_URL}")
            throw e
        } else {
            String errorMessage = "The reason is not automatically identified. Please contact support."
            GithubNotificator.updateStatus("Test", options['stageName'], "failure", env, options, errorMessage, "${BUILD_URL}")
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
                dir('Work')
                {
                    if (fileExists("Results/Houdini/session_report.json")) {

                        def sessionReport = null
                        sessionReport = readJSON file: 'Results/Houdini/session_report.json'

                        if (sessionReport.summary.error > 0) {
                            GithubNotificator.updateStatus("Test", options['stageName'], "failure", env, options, "Some tests were marked as error. Check the report for details.", "${BUILD_URL}")
                        } else if (sessionReport.summary.failed > 0) {
                            GithubNotificator.updateStatus("Test", options['stageName'], "failure", env, options, "Some tests were marked as failed. Check the report for details.", "${BUILD_URL}")
                        } else {
                            GithubNotificator.updateStatus("Test", options['stageName'], "success", env, options, "Tests completed successfully.", "${BUILD_URL}")
                        }

                        echo "Stashing test results to : ${options.testResultsName}"
                        stash includes: '**/*', name: "${options.testResultsName}", allowEmpty: true

                        echo "Total: ${sessionReport.summary.total}"
                        echo "Error: ${sessionReport.summary.error}"
                        echo "Skipped: ${sessionReport.summary.skipped}"
                        if (sessionReport.summary.total == sessionReport.summary.error + sessionReport.summary.skipped || sessionReport.summary.total == 0) {
                            if (sessionReport.summary.total != sessionReport.summary.skipped){
                                // collectCrashInfo(osName, options, options.currentTry)
                                String errorMessage
                                if (options.currentTry < options.nodeReallocateTries) {
                                    errorMessage = "All tests were marked as error. The test group will be restarted."
                                } else {
                                    errorMessage = "All tests were marked as error."
                                }
                                throw new ExpectedExceptionWrapper(errorMessage, new Exception(errorMessage))
                            }
                        }
                    }
                }
            } else {
                println "[INFO] Task ${options.tests} on ${options.nodeLabels} labels will be retried."
            }
        } catch (e) {
            // throw exception in finally block only if test stage was finished
            if (options.executeTestsFinished) {
                if (e instanceof ExpectedExceptionWrapper) {
                    GithubNotificator.updateStatus("Test", options['stageName'], "failure", env, options, e.getMessage(), "${BUILD_URL}")
                    throw e
                } else {
                    String errorMessage = "An error occurred while saving test results. Please contact support."
                    GithubNotificator.updateStatus("Test", options['stageName'], "failure", env, options, , "${BUILD_URL}")
                    throw new ExpectedExceptionWrapper(errorMessage, e)
                }
            }
        }
    }
}


def executeBuildWindows(String osName, Map options)
{
    clearBinariesWin()

    if (options.rebuildUSD){
        dir ("USD") {
            bat """
                set PATH=c:\\python36\\;c:\\python36\\scripts\\;%PATH%;
                call "C:\\Program Files (x86)\\Microsoft Visual Studio\\2017\\Community\\VC\\Auxiliary\\Build\\vcvarsall.bat" amd64 >> ${STAGE_NAME}_USD.log 2>&1
                
                if exist USDgen rmdir /s/q USDgen
                if exist USDinst rmdir /s/q USDinst

                python build_scripts\\build_usd.py -v --build USDgen/build --src USDgen/src USDinst >> ${STAGE_NAME}_USD.log 2>&1
            """
        }
    }
    
    dir ("RadeonProRenderUSD") {
        GithubNotificator.updateStatus("Build", osName, "pending", env, options, "Building the plugin.", "${BUILD_URL}/artifact/Build-Windows.log")

        if (options.buildType == "Houdini") {
            options.win_houdini_python3 = options.houdini_python3 ? " Python3" : ""
            options.win_tool_path = "C:\\Program Files\\Side Effects Software\\Houdini ${options.houdiniVersion}${options.win_houdini_python3}"
            bat """
                mkdir build
                set PATH=c:\\python35\\;c:\\python35\\scripts\\;%PATH%;
                set HFS=${options.win_tool_path}
                python pxr\\imaging\\plugin\\hdRpr\\package\\generatePackage.py -i "." -o "build" >> ..\\${STAGE_NAME}.log 2>&1
            """

        } else {
            bat """
                mkdir build
                set PATH=c:\\python35\\;c:\\python35\\scripts\\;%PATH%;
                python pxr\\imaging\\plugin\\hdRpr\\package\\generatePackage.py -i "." -o "build" --cmake_options " -Dpxr_DIR=../USD/USDinst" >> ..\\${STAGE_NAME}.log 2>&1
            """
        } 

        dir ("build") {

            if (options.buildType == "Houdini") {
                options.win_houdini_python3 = options.houdini_python3 ? "py3" : "py2.7"
                options.win_build_name = "hdRpr-${options.pluginVersion}-Houdini-${options.houdiniVersion}-${options.win_houdini_python3}-${osName}"
            } else if (options.buildType == "USD") {
                options.win_build_name = "hdRpr-${options.pluginVersion}-USD-${osName}"
            }

            archiveArtifacts "hdRpr*.tar.gz"
            String pluginUrl = "${BUILD_URL}/artifact/${options.win_build_name}.tar.gz"
            rtp nullAction: '1', parserName: 'HTML', stableText: """<h3><a href="${pluginUrl}">[BUILD: ${BUILD_ID}] ${options.win_build_name}.tar.gz</a></h3>"""

            bat """
                rename hdRpr* hdRpr_${osName}.tar.gz
            """

            stash includes: "hdRpr_${osName}.tar.gz", name: "app${osName}"

            GithubNotificator.updateStatus("Build", osName, "success", env, options, "The plugin was successfully built and published.", pluginUrl)
        }
    }
}


def executeBuildOSX(String osName, Map options) 
{
    clearBinariesUnix()

    if (options.rebuildUSD) {
        dir ("USD") {
            sh """
                if [ -d "./USDgen" ]; then
                    rm -fdr ./USDgen
                fi

                if [ -d "./USDinst" ]; then
                    rm -fdr ./USDinst
                fi

                export OS=Darwin
                python3 build_scripts/build_usd.py -vvv --build USDgen/build --src USDgen/src USDinst >> ${STAGE_NAME}_USD.log 2>&1
            """
        }
    }

    dir ("RadeonProRenderUSD") {
        GithubNotificator.updateStatus("Build", osName, "pending", env, options, "Building the plugin.", "${BUILD_URL}/artifact/Build-OSX.log")

        if (options.buildType == "Houdini") {
            options.osx_houdini_python3 = options.houdini_python3 ? "-py3" : "-py2"
            options.osx_tool_path = "/Applications/Houdini/Houdini${options.houdiniVersion}${options.osx_houdini_python3}/Frameworks/Houdini.framework/Versions/Current/Resources"
            sh """
                mkdir build
                export HFS=${options.osx_tool_path}
                python3 pxr/imaging/plugin/hdRpr/package/generatePackage.py -i "." -o "build" >> ../${STAGE_NAME}.log 2>&1
            """
        } else {
            sh """
                mkdir build
                python3 pxr/imaging/plugin/hdRpr/package/generatePackage.py -i "." -o "build" --cmake_options " -Dpxr_DIR=../USD/USDinst" >> ../${STAGE_NAME}.log 2>&1
            """
        }

        dir ("build") {

            if (options.buildType == "Houdini") {
                options.osx_houdini_python3 = options.houdini_python3 ? "py3" : "py2.7"
                options.osx_build_name = "hdRpr-${options.pluginVersion}-Houdini-${options.houdiniVersion}-${options.osx_houdini_python3}-macOS"
            } else if (options.buildType == "USD") {
                options.osx_build_name = "hdRpr-${options.pluginVersion}-USD-macOS"
            }

            archiveArtifacts "hdRpr*.tar.gz"
            String pluginUrl = "${BUILD_URL}/artifact/${options.osx_build_name}.tar.gz"
            rtp nullAction: '1', parserName: 'HTML', stableText: """<h3><a href="${pluginUrl}">[BUILD: ${BUILD_ID}] ${options.osx_build_name}.tar.gz</a></h3>"""

            sh """
                mv hdRpr*.tar.gz hdRpr_${osName}.tar.gz
            """

            stash includes: "hdRpr_${osName}.tar.gz", name: "app${osName}"

            GithubNotificator.updateStatus("Build", osName, "success", env, options, "The plugin was successfully built and published.", pluginUrl)
        }
    }
}


def executeBuildUnix(String osName, Map options) 
{
    clearBinariesUnix()

    if (options.rebuildUSD) {
        dir ("USD") {
            sh """
                if [ -d "./USDgen" ]; then
                    rm -fdr ./USDgen
                fi

                if [ -d "./USDinst" ]; then
                    rm -fdr ./USDinst
                fi

                export OS=
                python3 build_scripts/build_usd.py -v --build USDgen/build --src USDgen/src USDinst >> ${STAGE_NAME}_USD.log 2>&1
            """
        }
    }

    dir ("RadeonProRenderUSD") {
        GithubNotificator.updateStatus("Build", osName, "pending", env, options, "Building the plugin.", "${BUILD_URL}/artifact/Build-Ubuntu18.log")

        String installation_path
        if (env.HOUDINI_INSTALLATION_PATH) {
            installation_path = "${env.HOUDINI_INSTALLATION_PATH}"
        } else {
            installation_path = "/home/admin"
        }

        if (options.buildType == "Houdini") {
            options.unix_houdini_python3 = options.houdini_python3 ? "-py3" : "-py2"
            options.unix_tool_path = "Houdini/hfs${options.houdiniVersion}${options.unix_houdini_python3}"
            sh """
                mkdir build
                export HFS=${installation_path}/${options.unix_tool_path}
                python3 pxr/imaging/plugin/hdRpr/package/generatePackage.py -i "." -o "build" >> ../${STAGE_NAME}.log 2>&1
            """
        } else {
            sh """
                mkdir build
                python3 pxr/imaging/plugin/hdRpr/package/generatePackage.py -i "." -o "build" --cmake_options " -Dpxr_DIR=../USD/USDinst" >> ../${STAGE_NAME}.log 2>&1
            """
        }

        dir ("build") {

            if (options.buildType == "Houdini") {
                options.unix_houdini_python3 = options.houdini_python3 ? "py3" : "py2.7"
                if (osName == "Ubuntu18") {
                    options.unix_build_name = "hdRpr-${options.pluginVersion}-Houdini-${options.houdiniVersion}-${options.unix_houdini_python3}-ubuntu18.04"
                } else {
                    options.unix_build_name = "hdRpr-${options.pluginVersion}-Houdini-${options.houdiniVersion}-${options.unix_houdini_python3}-${osName}"
                }
            } else if (options.buildType == "USD") {
                if (osName == "Ubuntu18") {
                    options.unix_build_name = "hdRpr-${options.pluginVersion}-USD-ubuntu18.04"
                } else {
                    options.unix_build_name = "hdRpr-${options.pluginVersion}-USD-${osName}"
                }
            }

            archiveArtifacts "hdRpr*.tar.gz"
            String pluginUrl = "${BUILD_URL}/artifact/${options.unix_build_name}.tar.gz"
            rtp nullAction: '1', parserName: 'HTML', stableText: """<h3><a href="${pluginUrl}">[BUILD: ${BUILD_ID}] ${options.unix_build_name}.tar.gz</a></h3>"""

            sh """
                mv hdRpr*.tar.gz hdRpr_${osName}.tar.gz
            """

            stash includes: "hdRpr_${osName}.tar.gz", name: "app${osName}"

            GithubNotificator.updateStatus("Build", osName, "success", env, options, "The plugin was successfully built and published.", pluginUrl)
        }
    }
}


def executeBuild(String osName, Map options) {

    if (options.buildType == "Houdini") {
        try {
            timeout(time: "20", unit: 'MINUTES') {
                houdini_python3 = options.houdini_python3 ? "--python3" : ''
                withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'sidefxCredentials', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
                    print(python3("${CIS_TOOLS}/houdini_api.py --client_id \"$USERNAME\" --client_secret_key \"$PASSWORD\" --version \"${options.houdiniVersion}\" ${houdini_python3}"))
                }
            }
        } catch (e) {
            if (utils.isTimeoutExceeded(e)) {
                String errorMessage = "Failed to validate installed houdini or install houdini due to timeout."
                GithubNotificator.updateStatus("Build", osName, "failure", env, options, errorMessage)
                problemMessageManager.saveSpecificFailReason(errorMessage, "Build", osName)
            } else {
                String errorMessage = "Failed to validate installed houdini or install houdini."
                GithubNotificator.updateStatus("Build", osName, "failure", env, options, errorMessage)
                problemMessageManager.saveSpecificFailReason(errorMessage, "Build", osName)
            }
            throw e  
        }
    }

    try {

        try {
            GithubNotificator.updateStatus("Build", osName, "pending", env, options, "Downloading the plugin repository.")
            dir ("RadeonProRenderUSD") {
                checkOutBranchOrScm(options.projectBranch, options.projectRepo)
            }
        } catch (e) {
            String errorMessage
            if (e.getMessage() && e.getMessage().contains("Branch not suitable for integration")) {
                errorMessage = "Failed to merge branches."
            } else {
                errorMessage = "Failed to download plugin repository."
            }
            GithubNotificator.updateStatus("Build", osName, "failure", env, options, errorMessage)
            problemMessageManager.saveSpecificFailReason(errorMessage, "Build", osName)
            throw e
        }

        
        if (options.rebuildUSD) {
            try {
                GithubNotificator.updateStatus("Build", osName, "pending", env, options, "Downloading the USD repository.")
                dir('USD') {
                    checkOutBranchOrScm(options['usdBranch'], 'git@github.com:PixarAnimationStudios/USD.git')
                }
            } catch (e) {
                String errorMessage = "Failed to download the USD repository."
                GithubNotificator.updateStatus("Build", osName, "failure", env, options, errorMessage)
                problemMessageManager.saveSpecificFailReason(errorMessage, "Build", osName)
                throw e
            }
            
        }

        outputEnvironmentInfo(osName)

        try {
            switch(osName) {
                case 'Windows':
                    executeBuildWindows(osName, options);
                    break;
                case 'OSX':
                    executeBuildOSX(osName, options);
                    break;
                default:
                    executeBuildUnix(osName, options);
            }
        } catch (e) {
            String errorMessage = "Failed to build the plugin."
            GithubNotificator.updateStatus("Build", osName, "failure", env, options, errorMessage)
            problemMessageManager.saveSpecificFailReason(errorMessage, "Build", osName)
            throw e
        }
    } catch (e) {
        currentBuild.result = "FAILED"
        throw e
    } finally {
        archiveArtifacts "*.log"
        if (options.rebuildUSD){
            archiveArtifacts "USD/*.log"
        }
    }
}

def executePreBuild(Map options) {

    // manual job
    if (options.forceBuild) {
        options.executeBuild = true
        options.executeTests = true
    // auto job
    } else {
        if (env.CHANGE_URL) {
            println "[INFO] Branch was detected as Pull Request"
            options.executeBuild = true
            options.executeTests = true
            options.testsPackage = "Full.json"
            GithubNotificator githubNotificator = new GithubNotificator(this, pullRequest)
            options.githubNotificator = githubNotificator
            githubNotificator.initPreBuild("${BUILD_URL}")
        } else if (env.BRANCH_NAME == "master" || env.BRANCH_NAME == "develop") {
           println "[INFO] ${env.BRANCH_NAME} branch was detected"
           options.enableNotifications = true
           options.executeBuild = true
           options.executeTests = true
           options.testsPackage = "Full.json"
        } else {
            println "[INFO] ${env.BRANCH_NAME} branch was detected"
            options.testsPackage = "Full.json"
        }
    }

    dir('RadeonProRenderUSD')
    {
        try {
            checkOutBranchOrScm(options.projectBranch, options.projectRepo, true)
        } catch (e) {
            String errorMessage = "Failed to download plugin repository."
            GithubNotificator.updateStatus("PreBuild", "Version increment", "error", env, options, errorMessage)
            problemMessageManager.saveSpecificFailReason(errorMessage, "PreBuild")
            throw e
        }

        options.commitAuthor = bat (script: "git show -s --format=%%an HEAD ",returnStdout: true).split('\r\n')[2].trim()
        options.commitMessage = bat (script: "git log --format=%%B -n 1", returnStdout: true).split('\r\n')[2].trim()
        options.commitSHA = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()

        println "The last commit was written by ${options.commitAuthor}."
        println "Commit message: ${options.commitMessage}"
        println "Commit SHA: ${options.commitSHA}"

        if (options.projectBranch){
            currentBuild.description = "<b>Project branch:</b> ${options.projectBranch}<br/>"
        } else {
            currentBuild.description = "<b>Project branch:</b> ${env.BRANCH_NAME}<br/>"
        }

        try {

            options.majorVersion = version_read("${env.WORKSPACE}\\RadeonProRenderUSD\\cmake\\defaults\\Version.cmake", 'set(HD_RPR_MAJOR_VERSION "', '')
            options.minorVersion = version_read("${env.WORKSPACE}\\RadeonProRenderUSD\\cmake\\defaults\\Version.cmake", 'set(HD_RPR_MINOR_VERSION "', '')
            options.patchVersion = version_read("${env.WORKSPACE}\\RadeonProRenderUSD\\cmake\\defaults\\Version.cmake", 'set(HD_RPR_PATCH_VERSION "', '')
            options.pluginVersion = "${options.majorVersion}.${options.minorVersion}.${options.patchVersion}"

            currentBuild.description += "<b>Version:</b> ${options.majorVersion}.${options.minorVersion}.${options.patchVersion}<br/>"
            currentBuild.description += "<b>Commit author:</b> ${options.commitAuthor}<br/>"
            currentBuild.description += "<b>Commit message:</b> ${options.commitMessage}<br/>"

            if(options['incrementVersion'])
            {
                if(env.BRANCH_NAME == "develop" && options.commitAuthor != "radeonprorender")
                {
                    println "[INFO] Incrementing version of change made by ${options.commitAuthor}."
                    println "[INFO] Current build version: ${options.majorVersion}.${options.minorVersion}.${options.patchVersion}"

                    new_version = version_inc(options.patchVersion, 1, ' ')
                    println "[INFO] New build version: ${new_version}"

                    version_write("${env.WORKSPACE}\\RadeonProRenderUSD\\cmake\\defaults\\Version.cmake", 'set(HD_RPR_PATCH_VERSION "', new_version, '')
                    options.patchVersion = version_read("${env.WORKSPACE}\\RadeonProRenderUSD\\cmake\\defaults\\Version.cmake", 'set(HD_RPR_PATCH_VERSION "', '')
                    options.pluginVersion = "${options.majorVersion}.${options.minorVersion}.${options.patchVersion}"
                    println "[INFO] Updated build version: ${options.patchVersion}"

                    bat """
                        git add cmake/defaults/Version.cmake
                        git commit -m "buildmaster: version update to ${options.majorVersion}.${options.minorVersion}.${options.patchVersion}"
                        git push origin HEAD:develop
                    """

                    //get commit's sha which have to be build
                    options['projectBranch'] = bat ( script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
                    println "[INFO] Project branch hash: ${options.projectBranch}"
                }
            }
        } catch(e) {
            String errorMessage = "Failed to increment plugin version."
            GithubNotificator.updateStatus("PreBuild", "Version increment", "error", env, options, errorMessage)
            problemMessageManager.saveSpecificFailReason(errorMessage, "PreBuild")
            throw e
        }
    }

    if (env.BRANCH_NAME && (env.BRANCH_NAME == "master" || env.BRANCH_NAME == "develop")) {
        properties([[$class: 'BuildDiscarderProperty', strategy:
                         [$class: 'LogRotator', artifactDaysToKeepStr: '',
                          artifactNumToKeepStr: '', daysToKeepStr: '', numToKeepStr: '10']]]);
    } else if (env.BRANCH_NAME && env.BRANCH_NAME != "master" && env.BRANCH_NAME != "develop") {
        properties([[$class: 'BuildDiscarderProperty', strategy:
                         [$class: 'LogRotator', artifactDaysToKeepStr: '',
                          artifactNumToKeepStr: '', daysToKeepStr: '', numToKeepStr: '3']]]);
    } else {
        properties([[$class: 'BuildDiscarderProperty', strategy:
                         [$class: 'LogRotator', artifactDaysToKeepStr: '',
                          artifactNumToKeepStr: '', daysToKeepStr: '', numToKeepStr: '10']]]);
    }

    def tests = []
    options.groupsUMS = []

    try {
        dir('jobs_test_houdini')
        {
            checkOutBranchOrScm(options['testsBranch'], 'git@github.com:luxteam/jobs_test_houdini.git')
            dir ('jobs_launcher') {
                options['jobsLauncherBranch'] = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
            }
            options['testsBranch'] = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
            println "[INFO] Test branch hash: ${options['testsBranch']}"

            if(options.testsPackage != "none")
            {
                // json means custom test suite. Split doesn't supported
                def tempTests = readJSON file: "jobs/${options.testsPackage}"
                tempTests["groups"].each() {
                    // TODO: fix: duck tape - error with line ending
                    tests << it.key
                }
                options.tests = tests
                options.testsPackage = "none"
            }
            else {
                options.tests.split(" ").each()
                {
                    tests << "${it}"
                }
                options.tests = tests
            }

            options.testsList = ['']
            options.tests = tests.join(" ")
        }

    } catch (e) {
        String errorMessage = "Failed to configurate tests."
        GithubNotificator.updateStatus("PreBuild", "Version increment", "error", env, options, errorMessage)
        problemMessageManager.saveSpecificFailReason(errorMessage, "PreBuild")
        throw e
    }

    if (env.CHANGE_URL) {
        options.githubNotificator.initPR(options, "${BUILD_URL}")
    }

    GithubNotificator.updateStatus("PreBuild", "Version increment", "success", env, options, "PreBuild stage was successfully finished.")

}

def executeDeploy(Map options, List platformList, List testResultList)
{
    try {
        if(options['executeTests'] && testResultList)
        {
            try {
                GithubNotificator.updateStatus("Deploy", "Building test report", "pending", env, options, "Preparing tests results.", "${BUILD_URL}")
                checkOutBranchOrScm(options['testsBranch'], 'git@github.com:luxteam/jobs_test_houdini.git')
            } catch (e) {
                String errorMessage = "Failed to download tests repository."
                GithubNotificator.updateStatus("Deploy", "Building test report", "failure", env, options, errorMessage, "${BUILD_URL}")
                problemMessageManager.saveSpecificFailReason(errorMessage, "Deploy")
                throw e
            }

            List lostStashes = []

            dir("summaryTestResults") {
                unstashCrashInfo(options['nodeRetry'])
                testResultList.each() {
                    dir("$it".replace("testResult-", "")) {
                        try {
                            unstash "$it"
                        } catch(e) {
                            echo "Can't unstash ${it}"
                            lostStashes.add("'$it'".replace("testResult-", ""))
                            println(e.toString());
                            println(e.getMessage());
                        }
                    }
                }
            }

            try {
                dir("jobs_launcher") {
                    bat """
                        count_lost_tests.bat \"${lostStashes}\" .. ..\\summaryTestResults \"${options.splitTestsExecution}\" \"${options.testsPackage}\" \"${options.tests.toString()}\" \"\" \"{}\"
                    """
                }
            } catch (e) {
                println("[ERROR] Can't generate number of lost tests")
            }

            try {
                GithubNotificator.updateStatus("Deploy", "Building test report", "pending", env, options, "Building test report.", "${BUILD_URL}")
                withEnv(["JOB_STARTED_TIME=${options.JOB_STARTED_TIME}"]) {
                    dir("jobs_launcher") {

                        if(options.projectBranch != "") {
                            options.branchName = options.projectBranch
                        } else {
                            options.branchName = env.BRANCH_NAME
                        }
                        if(options.incrementVersion) {
                            options.branchName = "develop"
                        }

                        options.commitMessage = options.commitMessage.replace("'", "")
                        options.commitMessage = options.commitMessage.replace('"', '')

                        def retryInfo = JsonOutput.toJson(options.nodeRetry)
                        dir("..\\summaryTestResults") {
                            JSON jsonResponse = JSONSerializer.toJSON(retryInfo, new JsonConfig());
                            writeJSON file: 'retry_info.json', json: jsonResponse, pretty: 4
                        }                    

                        if (options.buildType == "Houdini") {
                            def python3 = options.houdini_python3 ? "py3" : "py2.7"
                            def tool = "Houdini ${options.houdiniVersion} ${python3}"
                            bat """
                                build_reports.bat ..\\summaryTestResults \"${escapeCharsByUnicode(tool)}\" ${options.commitSHA} ${options.branchName} \"${escapeCharsByUnicode(options.commitMessage)}\"
                            """
                        } else {
                            bat """
                                build_reports.bat ..\\summaryTestResults USD ${options.commitSHA} ${options.branchName} \"${escapeCharsByUnicode(options.commitMessage)}\"
                            """
                        }

                        bat "get_status.bat ..\\summaryTestResults"
                    }
                }
            } catch(e) {
                String errorMessage = utils.getReportFailReason(e.getMessage())
                GithubNotificator.updateStatus("Deploy", "Building test report", "failure", env, options, errorMessage, "${BUILD_URL}")
                if (utils.isReportFailCritical(e.getMessage())) {
                    problemMessageManager.saveSpecificFailReason(errorMessage, "Deploy")
                    println("[ERROR] Failed to build test report.")
                    println(e.toString())
                    println(e.getMessage())
                    if (!options.testDataSaved) {
                        try {
                            // Save test data for access it manually anyway
                            utils.publishReport(this, "${BUILD_URL}", "summaryTestResults", "summary_report.html, performance_report.html, compare_report.html", \
                                "Test Report", "Summary Report, Performance Report, Compare Report")
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
                    problemMessageManager.saveGlobalFailReason(errorMessage)
                }
            }

            try
            {
                dir("jobs_launcher") {
                    archiveArtifacts "launcher.engine.log"
                }
            }
            catch(e)
            {
                println("[ERROR] during archiving launcher.engine.log")
                println(e.toString())
                println(e.getMessage())
            }

            Map summaryTestResults = [:]
            try
            {
                def summaryReport = readJSON file: 'summaryTestResults/summary_status.json'
                summaryTestResults['passed'] = summaryReport.passed
                summaryTestResults['failed'] = summaryReport.failed
                summaryTestResults['error'] = summaryReport.error
                if (summaryReport.error > 0) {
                    println("[INFO] Some tests marked as error. Build result = FAILURE.")
                    currentBuild.result = "FAILURE"
                    problemMessageManager.saveGlobalFailReason("Some tests marked as error.")
                }
                else if (summaryReport.failed > 0) {
                    println("[INFO] Some tests marked as failed. Build result = UNSTABLE.")
                    currentBuild.result = "UNSTABLE"
                    problemMessageManager.saveUnstableReason("Some tests marked as failed.")
                }
            }
            catch(e)
            {
                println(e.toString())
                println(e.getMessage())
                println("[ERROR] CAN'T GET TESTS STATUS")
                problemMessageManager.saveUnstableReason("Can't get tests status")
                currentBuild.result = "UNSTABLE"
            }

            try
            {
                options.testsStatus = readFile("summaryTestResults/slack_status.json")
            }
            catch(e)
            {
                println(e.toString())
                println(e.getMessage())
                options.testsStatus = ""
            }

            try {
                GithubNotificator.updateStatus("Deploy", "Building test report", "pending", env, options, "Publishing test report.", "${BUILD_URL}")
                utils.publishReport(this, "${BUILD_URL}", "summaryTestResults", "summary_report.html, performance_report.html, compare_report.html", \
                    "Test Report", "Summary Report, Performance Report, Compare Report")
                if (summaryTestResults) {
                    // add in description of status check information about tests statuses
                    // Example: Report was published successfully (passed: 69, failed: 11, error: 0)
                    GithubNotificator.updateStatus("Deploy", "Building test report", "success", env, options, "Report was published successfully. Results: passed - ${summaryTestResults.passed}, failed - ${summaryTestResults.failed}, error - ${summaryTestResults.error}.", "${BUILD_URL}/Test_20Report")
                } else {
                    GithubNotificator.updateStatus("Deploy", "Building test report", "success", env, options, "Report was published successfully.", "${BUILD_URL}/Test_20Report")
                }
            } catch (e) {
                String errorMessage = "Failed to publish test report."
                GithubNotificator.updateStatus("Deploy", "Building test report", "failure", env, options, errorMessage, "${BUILD_URL}")
                problemMessageManager.saveSpecificFailReason(errorMessage, "Deploy")
                throw e
            }
        }
    }
    catch (e) {
        println(e.toString());
        println(e.getMessage());
        throw e
    }
    finally
    {}

}

def call(String projectRepo = "git@github.com:GPUOpen-LibrariesAndSDKs/RadeonProRenderUSD.git",
        String projectBranch = "",
        String usdBranch = "master",
        String testsBranch = "master",
        String platforms = 'Windows:AMD_RXVEGA,AMD_WX9100,AMD_WX7100,AMD_RadeonVII,AMD_RX5700XT,NVIDIA_GF1080TI,NVIDIA_RTX2080TI;OSX:AMD_RXVEGA;Ubuntu18:AMD_RadeonVII,NVIDIA_RTX2070',
        String buildType = "Houdini",
        Boolean rebuildUSD = false,
        String houdiniVersion = "18.5.351",
        Boolean houdini_python3 = false,
        String updateRefs = 'No',
        String testsPackage = "Full.json",
        String tests = "",
        String width = "0",
        String height = "0",
        String tester_tag = "Houdini",
        Boolean splitTestsExecution = false,
        Boolean incrementVersion = true,
        String parallelExecutionTypeString = "TakeOneNodePerGPU",
        Boolean enableNotifications = true,
        Boolean forceBuild = false
        )
{

    def nodeRetry = []
    Map options = [:]

    try
    {

        try {

            String PRJ_NAME="RadeonProRenderUSDPlugin"
            String PRJ_ROOT="rpr-plugins"

            gpusCount = 0
            platforms.split(';').each()
            { platform ->
                List tokens = platform.tokenize(':')
                if (tokens.size() > 1)
                {
                    gpuNames = tokens.get(1)
                    gpuNames.split(',').each()
                    {
                        gpusCount += 1
                    }
                }
            }

            def parallelExecutionType = TestsExecutionType.valueOf(parallelExecutionTypeString)

            options << [projectRepo:projectRepo,
                        projectBranch:projectBranch,
                        usdBranch:usdBranch,
                        testsBranch:testsBranch,
                        updateRefs:updateRefs,
                        enableNotifications:enableNotifications,
                        PRJ_NAME:PRJ_NAME,
                        PRJ_ROOT:PRJ_ROOT,
                        BUILDER_TAG:'BuilderHoudini',
                        TESTER_TAG:tester_tag,
                        incrementVersion:incrementVersion,
                        testsPackage:testsPackage,
                        tests:tests.replace(',', ' '),
                        forceBuild:forceBuild,
                        reportName:'Test_20Report',
                        splitTestsExecution:splitTestsExecution,
                        BUILD_TIMEOUT:30,
                        TEST_TIMEOUT:30,
                        buildType:buildType,
                        rebuildUSD:rebuildUSD,
                        houdiniVersion:houdiniVersion,
                        houdini_python3:houdini_python3,
                        width:width,
                        gpusCount:gpusCount,
                        height:height,
                        nodeRetry: nodeRetry,
                        problemMessageManager: problemMessageManager,
                        platforms:platforms,
                        parallelExecutionType:parallelExecutionType
                        ]

        } catch(e) {
            problemMessageManager.saveGeneralFailReason("Failed initialization.", "Init")
            throw e
        }
        
        multiplatform_pipeline(platforms, this.&executePreBuild, this.&executeBuild, this.&executeTests, this.&executeDeploy, options)
    } catch(e) {
        currentBuild.result = "FAILURE"
        println(e.toString());
        println(e.getMessage());
        throw e
    } finally {
        problemMessageManager.publishMessages()
    }
}

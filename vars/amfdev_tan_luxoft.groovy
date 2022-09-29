import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

def getTanTool(String osName, Map options) {
    switch(osName) {
        case 'Windows':

            if (!fileExists("${CIS_TOOLS}\\..\\PluginsBinaries\\${options.pluginWinSha}.zip")) {

                clearBinariesWin()

                println "[INFO] The plugin does not exist in the storage. Unstashing and copying..."
                makeUnstash(name: "TAN_Windows", unzip: false)
                
                bat """
                    IF NOT EXIST "${CIS_TOOLS}\\..\\PluginsBinaries" mkdir "${CIS_TOOLS}\\..\\PluginsBinaries"
                    copy binWindows.zip "${CIS_TOOLS}\\..\\PluginsBinaries\\${options.pluginWinSha}.zip"
                """

            } else {
                println "[INFO] The plugin ${options.pluginWinSha}.zip exists in the storage."
                bat """
                    copy "${CIS_TOOLS}\\..\\PluginsBinaries\\${options.pluginWinSha}.zip" binWindows.zip
                """
            }

            bat """
                    mkdir FilesToCheck
                """

            unzip zipFile: "binWindows.zip", dir: "FilesToCheck", quiet: true

            break

        case 'OSX':

            if (!fileExists("${CIS_TOOLS}/../PluginsBinaries/${options.pluginOSXSha}.tar.gz")) {

                clearBinariesUnix()

                println "[INFO] The plugin does not exist in the storage. Unstashing and copying..."
                makeUnstash(name: "TAN_OSX", unzip: false)
                
                sh """
                    mkdir -p "${CIS_TOOLS}/../PluginsBinaries"
                    cp binMacOS.tar.gz "${CIS_TOOLS}/../PluginsBinaries/${options.pluginOSXSha}.tar.gz"
                """ 

            } else {
                println "[INFO] The plugin ${options.pluginOSXSha}.tar.gz exists in the storage."
                sh """
                    cp "${CIS_TOOLS}/../PluginsBinaries/${options.pluginOSXSha}.tar.gz" binMacOS.tar.gz
                """
            }

            sh """
                tar -zxvf binMacOS.tar.gz
            """
            
            break

        default:
            
            if (!fileExists("${CIS_TOOLS}/../PluginsBinaries/${options.pluginUbuntuSha}.tar.gz")) {

                clearBinariesUnix()

                println "[INFO] The plugin does not exist in the storage. Unstashing and copying..."
                makeUnstash(name: "TAN_Ubuntu", unzip: false)
                
                sh """
                    mkdir -p "${CIS_TOOLS}/../PluginsBinaries"
                    cp binUbuntu.tar.gz "${CIS_TOOLS}/../PluginsBinaries/${options.pluginUbuntuSha}.tar.gz"
                """ 

            } else {

                println "[INFO] The plugin ${options.pluginUbuntuSha}.tar.gz exists in the storage."
                sh """
                    cp "${CIS_TOOLS}/../PluginsBinaries/${options.pluginUbuntuSha}.tar.gz" binUbuntu.tar.gz
                """
            }

            sh """
                tar -zxvf binUbuntu.tar.gz
            """
    }
}

// todo need to add switch between tests
// ./run.sh "Convolution/test_smoke_convolution.py" >> ../${STAGE_NAME}.log 2>&1
def executeTestCommand(String osName, Map options) {
    switch(osName) {
        case 'Windows':
            dir('Launcher') {
                bat """
                    run.bat "Correlation" >> ../${STAGE_NAME}.log 2>&1
                """
            }
            break
        case 'OSX':
            dir('Launcher') {
                sh """
                    ./run.sh "Correlation" >> ../${STAGE_NAME}.log 2>&1
                """
            }
            break
        default:
            dir('Launcher') {
                try {
                    sh """
                        ./run.sh "Correlation" >> ../${STAGE_NAME}.log 2>&1
                    """
                } catch (e) {
                    println("[ERROR] Run.sh script returned non-zero exit code")
                    println(e)
                }
            }
    }
}


def executeTests(String osName, String asicName, Map options) {
    
    // used for mark stash results or not. It needed for not stashing failed tasks which will be retried.
    Boolean stashResults = true

    try {
        timeout(time: "10", unit: 'MINUTES') {
            try {
                cleanWS(osName)
                checkoutScm(branchName: options.testsBranch, repositoryUrl: 'git@github.com:luxteam/jobs_test_tan.git')
                getTanTool(osName, options)
            } catch(e) {
                println("[ERROR] Failed to prepare test group on ${env.NODE_NAME}")
                println(e.toString())
                throw e
            }
        }

        String assetsDir = isUnix() ? "${CIS_TOOLS}/../TestResources/gpuopen_tan_autotests_assets" : "C:\\TestResources\\gpuopen_tan_autotests_assets"
        downloadFiles("/volume1/web/Assets/gpuopen_tan_autotests/", assetsDir)

        String REF_PATH_PROFILE="${options.REF_PATH}/${asicName}-${osName}"
        options.REF_PATH_PROFILE = REF_PATH_PROFILE

        outputEnvironmentInfo(osName)

        executeTestCommand(osName, options)

    } catch (e) {
        if (options.currentTry < options.nodeReallocateTries) {
            stashResults = false
        } 
        println(e.toString())
        println(e.getMessage())
        options.failureMessage = "Failed during testing: ${asicName}-${osName}"
        options.failureError = e.getMessage()
        throw e
    } finally {
        archiveArtifacts artifacts: "*.log", allowEmptyArchive: true
        if (stashResults) {
            dir('allure-results')
            {       
                println "Stashing test results to : ${options.testResultsName}"
                makeStash(includes: '**/*', name: "${options.testResultsName}", allowEmpty: true)
            }
        }
    }
}


def executeBuildWindows(Map options) {

    bat """
        @echo off
        mkdir thirdparty\\Qt\\Qt5.9.9\\5.9.9\\msvc2017_64
        echo Copying Qt to thirdparty\\Qt\\Qt5.9.9\\5.9.9\\msvc2017_64
        xcopy C:\\Qt\\Qt5.9.9\\5.9.9\\msvc2017_64 thirdparty\\Qt\\Qt5.9.9\\5.9.9\\msvc2017_64 /s/y/i >> nul 2>&1
    """

    options.buildConfiguration.each() { win_build_conf ->
        options.winTool.each() { win_tool ->
                            
            win_build_conf = win_build_conf.capitalize()

            println "Current build configuration: ${win_build_conf}."
            println "Current tool: ${win_tool}."

            win_build_name = "${win_build_conf}_${win_tool}"

            // download IPP for build
            if (!fileExists("${CIS_TOOLS}\\..\\PluginsBinaries\\ipp_installer\\bootstrapper.exe")) {
                downloadFiles("/volume1/CIS/bin-storage/IPP/w_ipp_oneapi_p_2021.1.1.47_offline.exe", "/mnt/c/JN//PluginsBinaries")
                dir ("${CIS_TOOLS}\\..\\PluginsBinaries") {
                    bat """
                        w_ipp_oneapi_p_2021.1.1.47_offline.exe -s -f ipp_installer -x
                    """
                }
            }

            timeout(time: "5", unit: 'MINUTES') {
                try {
                    ipp_installed = powershell(script: """Get-WmiObject -Class Win32_Product -Filter \"Name LIKE 'Intel%Integrated Performance Primitives'\"""", returnStdout: true).trim()
                    println "[INFO] PW script return about IPP: ${ipp_installed}"
                    dir ("${CIS_TOOLS}\\..\\PluginsBinaries\\ipp_installer") {
                        if (ipp_installed && options.IPP == "off") {
                            bat """
                                bootstrapper.exe --action remove --silent --log-dir logs --eula accept
                            """
                        } else if (!ipp_installed && options.IPP == "on") {
                            bat """
                                bootstrapper.exe --action install --silent --log-dir logs --eula accept
                            """
                        }
                    }
                } catch(e) {
                    println("[ERROR] Failed to install/remove IPP on ${env.NODE_NAME}")
                    println(e.toString())
                }
            }
            
            switch (options.winVisualStudioVersion) {
                case '2017':
                    options.visualStudio = "Visual Studio 15 2017"
                    options.msBuildPath = "C:\\Program Files (x86)\\Microsoft Visual Studio\\2017\\Community\\MSBuild\\15.0\\Bin\\MSBuild.exe"
                    break
                case '2019':
                    options.visualStudio = "Visual Studio 16 2019"
                    options.msBuildPath = "C:\\Program Files (x86)\\Microsoft Visual Studio\\2019\\Community\\MSBuild\\Current\\Bin\\MSBuild.exe"
            }

            dir('tan\\build\\cmake') {

                if (fileExists(win_build_name)){
                    bat """
                        rd /s /q ${win_build_name}
                        mkdir ${win_build_name}
                    """
                }

                opencl_flag = "-DOpenCL_INCLUDE_DIR=../../../thirdparty/OpenCL-Headers"
                portaudio_flag = "-DPortAudio_DIR=../../../thirdparty/portaudio"

                String fftw_flag =""
                if (options.FFTW_DIR == "on") {
                    fftw_flag = "-DFFTW_DIR=../../thirdparty/fftw "
                }

                String tan_no_opencl_flag ="-DTAN_NO_OPENCL=0"
                if (options.TAN_NO_OPENCL == "on") {
                    tan_no_opencl_flag = "-DTAN_NO_OPENCL=1"
                }

                String amf_core_static_flag ="-DAMF_CORE_STATIC=0"
                if (options.AMF_CORE_STATIC == "on") {
                    amf_core_static_flag = "-DAMF_CORE_STATIC=1"
                }

                try {
                    dir (win_build_name) {
                        bat """
                            SET CMAKE_PREFIX_PATH=../../../thirdparty/Qt/Qt5.9.9/5.9.9/msvc2017_64/lib/cmake/Qt5Widgets
                            cmake .. -G "${options.visualStudio}" -A x64 -DCMAKE_BUILD_TYPE=${win_build_conf} ${opencl_flag} ${portaudio_flag} ${fftw_flag} -DDEFINE_AMD_OPENCL_EXTENSION=1 -DAMF_OPEN_DIR=../../../amfOpen ${tan_no_opencl_flag} ${amf_core_static_flag} >> ../../../../${STAGE_NAME}_${win_build_name}.log 2>&1
                        """
                    }

                    if (win_tool == "msbuild") {
                        dir (win_build_name) {
                            bat """
                                set msbuild="${options.msBuildPath}"
                                %msbuild% TAN-CL.sln /target:build /maxcpucount /p:Configuration=${win_build_conf} /p:Platform=x64 >> ../../../../${STAGE_NAME}_${win_build_name}.log 2>&1
                            """
                        }
                    } else if (win_tool == "cmake") {
                        bat """
                            cmake --build ${win_build_name} --config ${win_build_conf} >> ../../../${STAGE_NAME}_${win_build_name}.log 2>&1
                        """
                    }

                    bat """
                        mkdir FilesToCheck
                        xcopy /s/y/i ..\\..\\..\\bin FilesToCheck\\bin
                        xcopy /s/y/i ..\\..\\..\\scenes FilesToCheck\\scenes
                    """   

                    zip archive: true, dir: "FilesToCheck", glob: '', zipFile: "Windows_${win_build_name}.zip"

                    bat """
                        rename Windows_${win_build_name}.zip binWindows.zip
                    """
                    makeStash(includes: "binWindows.zip", name: 'TAN_Windows', preZip: false)
                    options.pluginWinSha = sha1 "binWindows.zip"

                } catch (FlowInterruptedException error) {
                    println "[INFO] Job was aborted during build stage"
                    throw error
                } catch (e) {
                    println(e.toString())
                    println(e.getMessage())
                    currentBuild.result = "FAILED"
                    println "[ERROR] Failed to build TAN on Windows"
                }
            }
        }
    }
}

def executeBuildOSX(Map options) {

    options.buildConfiguration.each() { osx_build_conf ->
        options.osxTool.each() { osx_tool ->

            osx_build_conf = osx_build_conf.capitalize()

            println "Current build configuration: ${osx_build_conf}."
            println "Current tool: ${osx_tool}."

            osx_build_name = "${osx_build_conf}_${osx_tool}"

            // download IPP for build
            if (!fileExists("${CIS_TOOLS}/../PluginsBinaries/m_ipp_oneapi_p_2021.1.1.45_offline.dmg")) {
                downloadFiles("/volume1/CIS/bin-storage/IPP/m_ipp_oneapi_p_2021.1.1.45_offline.dmg", "${CIS_TOOLS}/../PluginsBinaries")
            }

            timeout(time: "5", unit: 'MINUTES') {
                try {
                    ipp_installed = fileExists("/opt/intel/oneapi")
                    println "[INFO] IPP installed: ${ipp_installed}"
                    if (ipp_installed && options.IPP == "off") {
                        sh """
                            sudo ${CIS_TOOLS}/ippInstaller.sh ${CIS_TOOLS}/../PluginsBinaries/m_ipp_oneapi_p_2021.1.1.45_offline.dmg remove
                        """
                    } else if (!ipp_installed && options.IPP == "on") {
                        sh """
                            sudo ${CIS_TOOLS}/ippInstaller.sh ${CIS_TOOLS}/../PluginsBinaries/m_ipp_oneapi_p_2021.1.1.45_offline.dmg install
                        """
                    }
                } catch (e) {
                    println("[ERROR] Failed to install/remove IPP on ${env.NODE_NAME}")
                    println(e.toString())
                }
            }

            if (options.OMP == "on") {
                sh """
                    export HOMEBREW_NO_AUTO_UPDATE=1
                    brew install libomp
                """
            } else {
                sh """
                    brew uninstall --ignore-dependencies libomp
                """
            }

            dir('tan\\build\\cmake') {

                sh """
                    rm -rf ./macos
                    mkdir macos
                """

                dir("macos") {
                    try {

                        cmake_flag = "-DCMAKE_PREFIX_PATH=/usr/local/Cellar/qt/5.13.1"
                        opencl_flag = "-DOpenCL_INCLUDE_DIR=../../../thirdparty/OpenCL-Headers"
                        portaudio_flag = "-DPortAudio_DIR=../../../thirdparty/portaudio"

                        String fftw_flag =""
                        if (options.FFTW_DIR == "on") {
                            fftw_flag = "-DFFTW_DIR=../../thirdparty/fftw "
                        }

                        String tan_no_opencl_flag ="-DTAN_NO_OPENCL=0"
                        if (options.TAN_NO_OPENCL == "on") {
                            tan_no_opencl_flag = "-DTAN_NO_OPENCL=1"
                        }

                        String amf_core_static_flag ="-DAMF_CORE_STATIC=0"
                        if (options.AMF_CORE_STATIC == "on") {
                            amf_core_static_flag = "-DAMF_CORE_STATIC=1"
                        }

                        if (osx_tool == "cmake") {
                            sh """
                                cmake .. -DCMAKE_BUILD_TYPE=${osx_build_conf} ${cmake_flag} ${opencl_flag} \
                                ${portaudio_flag} ${fftw_flag} ${tan_no_opencl_flag} ${amf_core_static_flag} \
                                -DAMF_OPEN_DIR=../../../amfOpen \
                                -DENABLE_METAL=1 >> ../../../../${STAGE_NAME}_${osx_build_name}.log 2>&1
                            """
                        } else if (osx_tool == "xcode") {
                            sh """
                                cmake  .. -G "Xcode" ${cmake_flag} ${opencl_flag} \
                                ${portaudio_flag} ${fftw_flag} ${tan_no_opencl_flag} ${amf_core_static_flag} \
                                -DAMF_OPEN_DIR=../../../amfOpen \
                                -DENABLE_METAL=1 >> ../../../../${STAGE_NAME}_${osx_build_name}.log 2>&1
                            """
                        }
                        
                        sh """
                            make VERBOSE=1 >> ../../../../${STAGE_NAME}_${osx_build_name}.log 2>&1
                        """

                        sh """
                            mkdir FilesToCheck
                            cp -rf ../../../../bin FilesToCheck/bin
                            cp -rf ../../../../scenes FilesToCheck/scenes
                        """

                        sh """
                            tar -czvf "MacOS_${osx_build_name}.tar.gz" ./FilesToCheck
                        """
                        
                        archiveArtifacts "MacOS_${osx_build_name}.tar.gz"

                        sh """
                            mv MacOS_${osx_build_name}.tar.gz binMacOS.tar.gz
                        """

                        makeStash(includes: "binMacOS.tar.gz", name: 'TAN_OSX', preZip: false)
                        options.pluginOSXSha = sha1 "binMacOS.tar.gz"

                    } catch (FlowInterruptedException error) {
                        println "[INFO] Job was aborted during build stage"
                        throw error
                    } catch (e) {
                        println(e.toString());
                        println(e.getMessage());
                        currentBuild.result = "FAILED"
                        println "[ERROR] Failed to build TAN on OSX"
                    } 
                }
            }
            sh """
                export HOMEBREW_NO_AUTO_UPDATE=1
                brew install libomp
            """
        }
    }
}

def executeBuildLinux(String osName, Map options) {

    options.buildConfiguration.each() { ub18_build_conf ->

        ub18_build_conf = ub18_build_conf.capitalize()

        println "Current build configuration: ${ub18_build_conf}."

        ub18_build_name = "${ub18_build_conf}"

        // download IPP for build
        if (!fileExists("${CIS_TOOLS}/../PluginsBinaries/l_ipp_oneapi_p_2021.1.1.47_offline.sh")) {
            downloadFiles("/volume1/CIS/bin-storage/IPP/l_ipp_oneapi_p_2021.1.1.47_offline.sh", "${CIS_TOOLS}/../PluginsBinaries")
            dir("${CIS_TOOLS}/../PluginsBinaries"){
                sh """
                    ./l_ipp_oneapi_p_2021.1.1.47_offline.sh -f ipp_installer -x
                """
            }
        }

        timeout(time: "5", unit: 'MINUTES') {
            try {
                ipp_installed = fileExists("/opt/intel/oneapi")
                println "[INFO] IPP installed: ${ipp_installed}"
                if (ipp_installed && options.IPP == "off") {
                    sh """
                        sudo ${CIS_TOOLS}/ippInstaller.sh ${CIS_TOOLS}/../PluginsBinaries/ipp_installer/l_ipp_oneapi_p_2021.1.1.47_offline remove
                    """
                } else if (!ipp_installed && options.IPP == "on") {
                    sh """
                        sudo ${CIS_TOOLS}/ippInstaller.sh ${CIS_TOOLS}/../PluginsBinaries/ipp_installer/l_ipp_oneapi_p_2021.1.1.47_offline install
                    """
                }
            } catch(e) {
                println("[ERROR] Failed to install/remove IPP on ${env.NODE_NAME}")
                println(e.toString())
            }
        }

        if (options.OMP == "on") {
            sh """
                echo y | sudo ${CIS_TOOLS}/ompInstaller.sh install
            """
        } else {
            sh """
                echo y | sudo ${CIS_TOOLS}/ompInstaller.sh remove
            """
        }

        dir('tan\\build\\cmake') {

            sh """
                rm -rf ./linux
                mkdir linux
            """

            dir ("linux") {
                try {

                    opencl_flag = "-DOpenCL_INCLUDE_DIR=../../../../thirdparty/OpenCL-Headers"
                    opencl_lib_flag = "-DOpenCL_LIBRARY=/usr/lib/x86_64-linux-gnu/libOpenCL.so"
                    portaudio_flag = "-DPortAudio_DIR=../../../../../thirdparty/portaudio"

                    String fftw_flag =""
                    if (options.FFTW_DIR == "on") {
                        fftw_flag = "-DFFTW_DIR=../../thirdparty/fftw "
                    }

                    String tan_no_opencl_flag = "-DTAN_NO_OPENCL=0"

                    if (options.TAN_NO_OPENCL == "on") {
                        tan_no_opencl_flag = "-DTAN_NO_OPENCL=1"
                    }

                    String amf_core_static_flag ="-DAMF_CORE_STATIC=0"
                    if (options.AMF_CORE_STATIC == "on") {
                        amf_core_static_flag = "-DAMF_CORE_STATIC=1"
                    }

                    opencl_headers = "../../../../thirdparty/OpenCL-Headers"
                    options.ub18_opencl_lib = "/usr/lib/x86_64-linux-gnu/libOpenCL.so"
                    options.ub18_portaudio = "../../../../../thirdparty/portaudio"

                    sh """
                        cmake .. -DCMAKE_BUILD_TYPE=${ub18_build_conf} -DCMAKE_PREFIX_PATH=/usr/bin/gcc \
                        ${opencl_flag} ${opencl_lib_flag} ${tan_no_opencl_flag} \
                        ${portaudio_flag} ${fftw_flag} \
                        ${amf_core_static_flag} -DAMF_OPEN_DIR="../../../amfOpen" >> ../../../../${STAGE_NAME}_${ub18_build_name}.log 2>&1
                    """

                    sh """
                        make VERBOSE=1 >> ../../../../${STAGE_NAME}_${ub18_build_name}.log 2>&1
                    """

                    // todo return tests after fix
                    // cp -rf cmake-TALibTestConvolution-bin binUbuntu/cmake-TALibTestConvolution-bin
                    // cp -rf cmake-TALibDopplerTest-bin binUbuntu/cmake-TALibDopplerTest-bin
                    // cp -rf cmake-RoomAcousticQT-bin binUbuntu/cmake-RoomAcousticQT-bin
                    sh """
                        mkdir FilesToCheck
                        cp -rf ../../../../bin FilesToCheck/bin
                        cp -rf ../../../../scenes FilesToCheck/scenes
                    """

                    sh """
                        tar -czvf "Ubuntu_${ub18_build_name}.tar.gz" ./FilesToCheck
                    """
                    
                    archiveArtifacts "Ubuntu_${ub18_build_name}.tar.gz"

                    sh """
                        mv Ubuntu_${ub18_build_name}.tar.gz binUbuntu.tar.gz
                    """

                    makeStash(includes: "binUbuntu.tar.gz", name: 'TAN_Ubuntu', preZip: false)
                    options.pluginUbuntuSha = sha1 "binUbuntu.tar.gz"

                } catch (FlowInterruptedException error) {
                    println "[INFO] Job was aborted during build stage"
                    throw error
                } catch (e) {
                    println(e.getMessage())
                    currentBuild.result = "FAILED"
                    println "[ERROR] Failed to build TAN on Ubuntu"
                } 
            }
        }
    }
}


def executeBuild(String osName, Map options) {
    try {

        cleanWS(osName)
        checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo, recursiveSubmodules: false)
        
        switch (osName) {
            case 'Windows':
                executeBuildWindows(options)
                break
            case 'OSX':
                withEnv(["PATH=$WORKSPACE:$PATH"]) {
                    executeBuildOSX(options)
                }
                break
            default:
                withEnv(["PATH=$PWD:$PATH"]) {
                    executeBuildLinux(osName, options)
                }
        }
    } catch (e) {
        options.failureMessage = "[ERROR] Failed to build plugin on ${osName}"
        options.failureError = e.getMessage()
        currentBuild.result = "FAILED"
        throw e
    } finally {
        archiveArtifacts artifacts: "*.log", allowEmptyArchive: true
    }
}

def executePreBuild(Map options) {

    // manual job
    if (options.forceBuild) {
        env.BRANCH_NAME = options['projectBranch']
        options.executeBuild = true
        options.executeTests = true
    // auto job
    } else {
        options.executeBuild = true
        options.executeTests = true
        if (env.CHANGE_URL) {
            println "[INFO] Branch was detected as Pull Request"
            options.testsPackage = "PR"
        } else if ("${env.BRANCH_NAME}" == "master") {
           println "[INFO] master branch was detected"
           options.testsPackage = "master"
        } else {
            println "[INFO] ${env.BRANCH_NAME} branch was detected"
            options.testsPackage = "smoke"
        }
    }

    if (!env.CHANGE_URL) {

        checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo, disableSubmodules: true)

        options.commitAuthor = bat (script: "git show -s --format=%%an HEAD ",returnStdout: true).split('\r\n')[2].trim()
        options.commitMessage = bat (script: "git log --format=%%B -n 1", returnStdout: true).split('\r\n')[2].trim()
        options.commitSHA = bat(script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()

        println "The last commit was written by ${options.commitAuthor}."
        println "Commit message: ${options.commitMessage}"
        println "Commit SHA: ${options.commitSHA}"

        try {
            def major = version_read("${env.WORKSPACE}\\tan\\tanlibrary\\include\\TrueAudioNext.h", '#define TAN_VERSION_MAJOR')
            def minor = version_read("${env.WORKSPACE}\\tan\\tanlibrary\\include\\TrueAudioNext.h", '#define TAN_VERSION_MINOR')
            def release = version_read("${env.WORKSPACE}\\tan\\tanlibrary\\include\\TrueAudioNext.h", '#define TAN_VERSION_RELEASE')
            def build = version_read("${env.WORKSPACE}\\tan\\tanlibrary\\include\\TrueAudioNext.h", '#define TAN_VERSION_BUILD')
            options.tanVersion = "${major}.${minor}.${release}.${build}"
        } catch (e) {
            println "[WARNING] Can't detect TAN version"
            options.tanVersion = "0"
        }

        currentBuild.description = "<b>Project branch:</b> ${env.BRANCH_NAME}<br/>"
        currentBuild.description += "<b>Version:</b> ${options.tanVersion}<br/>"
        currentBuild.description += "<b>Commit author:</b> ${options.commitAuthor}<br/>"
        currentBuild.description += "<b>Commit message:</b> ${options.commitMessage}<br/>"
        currentBuild.description += "<b>Commit SHA:</b> ${options.commitSHA}<br/>"
        
        if (options.incrementVersion) {
            if (options.commitAuthor != "radeonprorender") {
                
                println "[INFO] Incrementing version of change made by ${options.commitAuthor}."
                
                def current_version=version_read("${env.WORKSPACE}\\tan\\tanlibrary\\include\\TrueAudioNext.h", '#define TAN_VERSION_BUILD')
                println "[INFO] Current build version: ${current_version}"
                
                def new_version = version_inc(current_version, 1)
                println "[INFO] New build version: ${new_version}"

                version_write("${env.WORKSPACE}\\tan\\tanlibrary\\include\\TrueAudioNext.h", '#define TAN_VERSION_BUILD', new_version)
                def updated_version = version_read("${env.WORKSPACE}\\tan\\tanlibrary\\include\\TrueAudioNext.h", '#define TAN_VERSION_BUILD')
                println "[INFO] Updated build version: ${updated_version}"

                bat """
                    git add tan\\tanlibrary\\include\\TrueAudioNext.h
                    git commit -m "buildmaster: automatic build version update to ${updated_version}"
                    git push origin HEAD:${env.BRANCH_NAME}
                """

                //get commit's sha which have to be build
                options.projectBranch = bat (script: "git log --format=%%H -1", returnStdout: true).split('\r\n')[2].trim()
            } 
        }
    }
}


def executeDeploy(Map options, List platformList, List testResultList) {
    try {
        if (options['executeTests'] && testResultList) {
            checkoutScm(branchName: options.testsBranch, repositoryUrl: 'git@github.com:luxteam/jobs_test_tan.git')

            downloadFiles("/volume1/CIS/bin_storage/allure/", "allure")
    
            dir("allure-results") {
                testResultList.each() {
                    try {
                        makeUnstash(name: "$it")
                    } catch(e) {
                        println "[ERROR] Failed to unstash ${it}"
                        println(e.toString())
                        println(e.getMessage())
                    }
                }
            }

            String branchName = env.BRANCH_NAME ?: options.projectBranch
            try {
                dir("Launcher") {
                    bat """
                        generate_report.bat 
                    """
                }
            } catch(e) {
                println "[ERROR] Failed during build report stage on ${env.NODE_NAME} node"
                println(e.toString())
                println(e.getMessage())
            }

            allure([
                includeProperties: false,
                jdk: '',
                properties: [[key: 'allure.issues.tracker.pattern', value: 'http://tracker.company.com/%s']],
                reportBuildPolicy: 'ALWAYS',
                results: [[path: 'target/allure-results']]
            ])

            println "BUILD RESULT: ${currentBuild.result}"
            println "BUILD CURRENT RESULT: ${currentBuild.currentResult}"
        }
    } catch(e) {
        println(e.toString())
        println(e.getMessage())
        throw e
    }
}


def call(String projectBranch = "",
    String testsBranch = "RA_testing",
    String platforms = 'Windows:AMD_RXVEGA;Ubuntu20:AMD_RX580',
    String buildConfiguration = "release",
    String IPP = "off",
    String OMP = "off",
    String FFTW = "off",
    String TAN_NO_OPENCL = "off",
    String AMF_CORE_STATIC = "off",
    String winTool = "msbuild",
    String winVisualStudioVersion = "2017",
    String osxTool = "cmake",
    Boolean enableNotifications = true,
    Boolean incrementVersion = true,
    Boolean forceBuild = false,
    String tests = "") {

    try {

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

        buildConfiguration = buildConfiguration.split(',')
        winTool = winTool.split(',')
        osxTool = osxTool.split(',')

        println "Build configuration: ${buildConfiguration}"
        println "IPP: ${IPP}"
        println "OMP: ${OMP}"
        println "FFTW: ${FFTW}"
        println "TAN_NO_OPENCL: ${TAN_NO_OPENCL}"
        println "AMF_CORE_STATIC: ${AMF_CORE_STATIC}"
        println "Win visual studio version: ${winVisualStudioVersion}"
        println "Win tool: ${winTool}"
        println "OSX tool: ${osxTool}"

        multiplatform_pipeline(platforms, this.&executePreBuild, this.&executeBuild, this.&executeTests, this.&executeDeploy,
                               [projectBranch:projectBranch,
                                projectRepo:'git@github.com:imatyushin/TAN.git',
                                testsBranch:testsBranch,
                                enableNotifications:enableNotifications,
                                incrementVersion:incrementVersion,
                                forceBuild:forceBuild,
                                PRJ_NAME:"TAN",
                                PRJ_ROOT:"gpuopen",
                                buildConfiguration:buildConfiguration,
                                IPP:IPP,
                                OMP:OMP,
                                FFTW:FFTW,
                                TAN_NO_OPENCL:TAN_NO_OPENCL,
                                AMF_CORE_STATIC:AMF_CORE_STATIC,
                                winTool:winTool,
                                winVisualStudioVersion:winVisualStudioVersion,
                                osxTool:osxTool,
                                tests:tests,
                                gpusCount:gpusCount,
                                TEST_TIMEOUT:90,
                                DEPLOY_TIMEOUT:150,
                                BUILDER_TAG:"BuilderTAN"
                                ])
    } catch (e) {
        currentBuild.result = "FAILED"
        failureMessage = "INIT FAILED"
        failureError = e.getMessage()
        println(e.toString())
        println(e.getMessage())
        throw e
    }
}

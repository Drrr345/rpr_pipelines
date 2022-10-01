import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
import utils


def executeUnitTestsCommand(String osName, Map options) {

    switch (osName) {
        case 'Windows':
            bat """
                tests.exe --gtest_output=xml:${STAGE_NAME}.gtest.xml >> ${STAGE_NAME}.UnitTests.log 2>&1
            """
            break
        case 'OSX':
        case 'MacOS_ARM':
            sh """
                chmod +x tests
                export LD_LIBRARY_PATH=\$PWD:\$LD_LIBRARY_PATH
                ./tests --gtest_output=xml:${STAGE_NAME}.gtest.xml >> ${STAGE_NAME}.UnitTests.log 2>&1
            """
            break
        default:
            sh """
                chmod +x tests
                export LD_LIBRARY_PATH=\$PWD:\$LD_LIBRARY_PATH
                ./tests --gtest_output=xml:${STAGE_NAME}.gtest.xml >> ${STAGE_NAME}.UnitTests.log 2>&1
            """
    }
}

def executeFunctionalTestsCommand(String osName, String asicName, Map options) {

    String assetsDir = isUnix() ? "${CIS_TOOLS}/../TestResources/rpr_ml_autotests_assets" : "/mnt/c/TestResources/rpr_ml_autotests_assets"
    withNotifications(title: "${asicName}-${osName}-FT", options: options, configuration: NotificationConfiguration.DOWNLOAD_SCENES) {
        downloadFiles("/volume1/web/Assets/rpr_ml_assets/", assetsDir)
    }

    ws("WS/${options.PRJ_NAME}-FT") {

        withNotifications(title: "${asicName}-${osName}-FT", options: options, logUrl: BUILD_URL, configuration: NotificationConfiguration.DOWNLOAD_TESTS_REPO) {
            timeout(time: "5", unit: "MINUTES") {
                cleanWS(osName)
                checkoutScm(branchName: options.testsBranch, repositoryUrl: "${options.gitlabURL}/rml/ft_engine.git", credentialsId: "radeonprorender-gitlab")
            }
        }

        try {
            dir("rml_release") {
                makeUnstash(name: "app${osName}", storeOnNAS: options.storeOnNAS)
            }
            withNotifications(title: "${asicName}-${osName}-FT", options: options, configuration: NotificationConfiguration.EXECUTE_TESTS) {
                switch (osName) {
                    case 'Windows':
                        assetsDir = "C:\\TestResources\\rpr_ml_autotests_assets"

                        withEnv(["PATH=C:\\Python39;C:\\Python39\\Scripts;${PATH}"]) {
                            bat """
                                pip install --user -r requirements.txt >> ${STAGE_NAME}.ft.log 2>&1
                                python -V >> ${STAGE_NAME}.ft.log 2>&1
                                python run_tests.py -t ${assetsDir} -e rml_release/test_app.exe -i ${assetsDir} -o results -c true >> ${STAGE_NAME}.ft.log 2>&1
                                python execute_cases.py -t ${assetsDir} -e rml_release/test_app.exe -i ${assetsDir} -o results >> ${STAGE_NAME}.ft.log 2>&1
                                python process_cases.py -i ${assetsDir} -o results -c true >> ${STAGE_NAME}.ft.log 2>&1
                                rename ft.log ${STAGE_NAME}.execution.ft.log
                                rename ft-process.log ${STAGE_NAME}.process.ft.log
                            """
                        }
                        break

                    case 'MacOS_ARM':
                        sh """
                            export LD_LIBRARY_PATH=${assetsDir}:\$LD_LIBRARY_PATH
                            python3.9 execute_cases.py -t ${assetsDir} -e rml_release/test_app -i ${assetsDir} -o results >> ${STAGE_NAME}.ft.log 2>&1
                            python process_cases.py -i ${assetsDir} -o results -c true >> ${STAGE_NAME}.ft.log 2>&1
                            mv ft.log ${STAGE_NAME}.execute.ft.log
                            mv ft-process.log ${STAGE_NAME}.process.ft.log
                        """
                        break

                    default:
                        sh """
                            export LD_LIBRARY_PATH=${assetsDir}:\$LD_LIBRARY_PATH
                            python3.9 -V >> ${STAGE_NAME}.ft.log 2>&1
                            env >> ${STAGE_NAME}.ft.log 2>&1
                            python3.9 execute_cases.py -t ${assetsDir} -e rml_release/test_app -i ${assetsDir} -o results >> ${STAGE_NAME}.ft.log 2>&1
                            python3.9 process_cases.py -i ${assetsDir} -o results -c true >> ${STAGE_NAME}.ft.log 2>&1
                            mv ft.log ${STAGE_NAME}.execute.ft.log
                            mv ft-process.log ${STAGE_NAME}.process.ft.log
                        """
                }
            }
            GithubNotificator.updateStatus("Test", "${asicName}-${osName}-FT", "success", options, NotificationConfiguration.TEST_PASSED, "${BUILD_URL}/artifact/${STAGE_NAME}.ft.log")
        } catch(e) {
            println(e.toString())
            currentBuild.result = "UNSTABLE"
            options.problemMessageManager.saveUnstableReason(NotificationConfiguration.FAILED_FT_TESTS)
            GithubNotificator.updateStatus("Test", "${asicName}-${osName}-FT", "failure", options, NotificationConfiguration.TEST_FAILED, "${BUILD_URL}/artifact/${STAGE_NAME}.ft.log")
            throw e
        } finally {
            archiveArtifacts "*.log"
            utils.publishReport(this, BUILD_URL, "results", "report.html", "FT ${osName}-${asicName}", "FT ${osName}-${asicName}", options.storeOnNAS, \
                ["jenkinsBuildUrl": BUILD_URL, "jenkinsBuildName": currentBuild.displayName, "updatable": false])
        }
    }
}

def executeTests(String osName, String asicName, Map options) {

    cleanWS(osName)

    try {
        GithubNotificator.updateStatus("Test", "${asicName}-${osName}-Unit", "in_progress", options, NotificationConfiguration.EXECUTE_UNIT_TESTS, BUILD_URL)
        outputEnvironmentInfo(osName, "${STAGE_NAME}.UnitTests")
        makeUnstash(name: "app${osName}", storeOnNAS: options.storeOnNAS)
        executeUnitTestsCommand(osName, options)
        GithubNotificator.updateStatus("Test", "${asicName}-${osName}-Unit", "success", options, NotificationConfiguration.UNIT_TESTS_PASSED, "${BUILD_URL}/artifact/${STAGE_NAME}.UnitTests.log")
    } catch (FlowInterruptedException error) {
        println("[INFO] Job was aborted during executing tests.")
        throw error
    } catch (e) {
        println(e.toString())
        println(e.getMessage())
        currentBuild.result = "UNSTABLE"
        options.problemMessageManager.saveUnstableReason(NotificationConfiguration.FAILED_UNIT_TESTS)
        GithubNotificator.updateStatus("Test", "${asicName}-${osName}-Unit", "failure", options, NotificationConfiguration.UNIT_TESTS_FAILED, "${BUILD_URL}/artifact/${STAGE_NAME}.UnitTests.log")
    } finally {
        archiveArtifacts "*.log"
        junit "*gtest.xml"
    }

    cleanWS(osName)

    if (options.executeFT) {
        try {
            outputEnvironmentInfo(osName, "${STAGE_NAME}.ft")
            executeFunctionalTestsCommand(osName, asicName, options)
        } catch (e) {
            println(e.toString())
            println(e.getMessage())

        } finally {
            archiveArtifacts "*.log"
        }
    }
}


def executeWindowsBuildCommand(String osName, Map options, String buildType){

    bat """
        mkdir build-${buildType}
        cd build-${buildType}
        cmake ${options.cmakeKeysWin} -DRML_TENSORFLOW_DIR=${WORKSPACE}/third_party/tensorflow -DMIOpen_INCLUDE_DIR=${WORKSPACE}/third_party/miopen -DMIOpen_LIBRARY_DIR=${WORKSPACE}/third_party/miopen -DDirectML_INCLUDE_DIR=${WORKSPACE}/third_party/directml -DDirectML_LIBRARY_DIR=${WORKSPACE}/third_party/directml .. >> ..\\${STAGE_NAME}_${buildType}.log 2>&1
        set msbuild=\"C:\\Program Files (x86)\\Microsoft Visual Studio\\2017\\Community\\MSBuild\\15.0\\Bin\\MSBuild.exe\"
        %msbuild% RadeonML.sln -property:Configuration=${buildType} >> ..\\${STAGE_NAME}_${buildType}.log 2>&1
    """
    
    bat """
        cd build-${buildType}
        xcopy ..\\third_party\\miopen\\MIOpen.dll ${buildType}
        xcopy ..\\third_party\\tensorflow\\windows\\* ${buildType}
        xcopy ..\\third_party\\directml\\DirectML.dll ${buildType}
        mkdir ${buildType}\\rml
        mkdir ${buildType}\\rml_internal
        xcopy ..\\rml\\include\\rml\\*.h* ${buildType}\\rml
        xcopy ..\\rml\\include\\rml_internal\\*.h* ${buildType}\\rml_internal
    """

    if (env.TAG_NAME) {
        bat """
            if exist ${osName}\\${buildType} RMDIR /S/Q ${osName}\\${buildType}
            MD ${osName}\\${buildType}
            xcopy /s/y/i build-${buildType}\\${buildType}\\MIOpen.dll ${osName}\\${buildType}
            xcopy /s/y/i build-${buildType}\\${buildType}\\RadeonML*.dll ${osName}\\${buildType}
            xcopy /s/y/i build-${buildType}\\${buildType}\\RadeonML*.lib ${osName}\\${buildType}
            xcopy /s/y/i build-${buildType}\\${buildType}\\RadeonML_DirectML*.dll ${osName}\\${buildType}
            xcopy /s/y/i build-${buildType}\\${buildType}\\RadeonML_MIOpen*.dll ${osName}\\${buildType}
        """
        dir("${osName}/${buildType}") {
            makeStash(includes: "*", name: "deploy_${osName}_${buildType}")
        }
    }

    String ARTIFACT_NAME = "${osName}_${buildType}.zip"
    String artifactURL

    dir("build-${buildType}\\${buildType}") {
        bat(script: '%CIS_TOOLS%\\7-Zip\\7z.exe a' + " \"${ARTIFACT_NAME}\" .")
        artifactURL = makeArchiveArtifacts(name: ARTIFACT_NAME, storeOnNAS: options.storeOnNAS, createLink: false)
    }

    zip archive: true, dir: "build-${buildType}\\${buildType}", glob: "RadeonML*.lib, RadeonML*.dll, MIOpen.dll, DirectML.dll, libtensorflow*, test*.exe", zipFile: "build-${buildType}\\${osName}_${buildType}.zip"

    return artifactURL
}


def executeBuildWindows(String osName, Map options) {

    GithubNotificator.updateStatus("Build", osName, "in_progress", options, NotificationConfiguration.BUILD_SOURCE_CODE_START_MESSAGE, "${BUILD_URL}/artifact")

    bat """
        xcopy /s/y/i ..\\RML_thirdparty\\MIOpen third_party\\miopen
        xcopy /s/y/i ..\\RML_thirdparty\\tensorflow third_party\\tensorflow
        xcopy /s/y/i ..\\RML_thirdparty\\DirectML third_party\\directml
    """

    options.cmakeKeysWin ='-G "Visual Studio 15 2017 Win64" -DRML_DIRECTML=ON -DRML_TENSORFLOW_CPU=ON -DRML_TENSORFLOW_CUDA=OFF -DRML_MPS=OFF'

    String releaseLink = executeWindowsBuildCommand(osName, options, "Release")
    String debugLink = executeWindowsBuildCommand(osName, options, "Debug")

    rtp nullAction: '1', parserName: 'HTML', stableText: """<h4>${osName}: <a href="${releaseLink}">Release</a> / <a href="${debugLink}">Debug</a> </h4>"""

    GithubNotificator.updateStatus("Build", osName, "success", options, NotificationConfiguration.BUILD_SOURCE_CODE_END_MESSAGE, "${BUILD_URL}/artifact")
}


def executeOSXBuildCommand(String osName, Map options, String buildType) {

    sh """
        mkdir build-${buildType}
        cd build-${buildType}
        cmake -DCMAKE_OSX_SYSROOT=$MACOS_SDK -DCMAKE_buildType=${buildType} ${options.cmakeKeysOSX} .. >> ../${STAGE_NAME}_${buildType}.log 2>&1
        make -j 4 >> ../${STAGE_NAME}_${buildType}.log 2>&1
        mv bin ${buildType}
    """

    try {
        // PR-375 stores part of libs and binaries in bin directory
        sh """
            cd build-${buildType}/${buildType}
            mv bin/* .
            rmdir bin
        """
    } catch (e) {
        print(e)
    }

    sh """
        cd build-${buildType}
        rm -rf ${buildType}/*.a
        mkdir ./${buildType}/rml
        mkdir ./${buildType}/rml_internal
        cp ../rml/include/rml/*.h* ./${buildType}/rml
        cp ../rml/include/rml_internal/*.h* ./${buildType}/rml_internal

        # search for libs in local dir
        install_name_tool -add_rpath "@executable_path" ./${buildType}/tests
        install_name_tool -add_rpath "@executable_path" ./${buildType}/test_app

        tar cf ${osName}_${buildType}.tar ${buildType}
    """

    if (env.TAG_NAME) {
        sh """
            rm -rf ${osName}/${buildType}
            mkdir -p ${osName}/${buildType}
            cp -R build-${buildType}/${buildType}/libRadeonML.*dylib ${osName}/${buildType}
            cp -R build-${buildType}/${buildType}/libRadeonML_MPS.*dylib ${osName}/${buildType}

        """
        dir("${osName}/${buildType}") {
            makeStash(includes: "*", name: "deploy_${osName}_${buildType}", storeOnNAS: options.storeOnNAS)
        }
    }

    String artifactURL

    dir("build-${buildType}") {
        String ARTIFACT_NAME = "${osName}_${buildType}.tar"
        artifactURL = makeArchiveArtifacts(name: ARTIFACT_NAME, storeOnNAS: options.storeOnNAS, createLink: false)
        
        dir(buildType) {
            sh("zip ${osName}_${buildType}.zip libRadeonML*.dylib test*")
            archiveArtifacts "${osName}_${buildType}.zip"
        }
    }

    return artifactURL
}


def executeBuildOSX(String osName, Map options) {

    GithubNotificator.updateStatus("Build", osName, "in_progress", options, NotificationConfiguration.BUILD_SOURCE_CODE_START_MESSAGE, "${BUILD_URL}/artifact")

    sh """
        cp -r ../RML_thirdparty/MIOpen/* ./third_party/miopen
    """

    options.cmakeKeysOSX = "-DRML_DIRECTML=OFF -DRML_MIOPEN=OFF -DRML_TENSORFLOW_CPU=OFF -DRML_TENSORFLOW_CUDA=OFF -DRML_MPS=ON -DMIOpen_INCLUDE_DIR=${WORKSPACE}/third_party/miopen -DMIOpen_LIBRARY_DIR=${WORKSPACE}/third_party/miopen"

    String releaseLink = executeOSXBuildCommand(osName, options, "Release")
    String debugLink = executeOSXBuildCommand(osName, options, "Debug")

    rtp nullAction: '1', parserName: 'HTML', stableText: """<h4>${osName}: <a href="${releaseLink}">Release</a> / <a href="${debugLink}">Debug</a> </h4>"""

    GithubNotificator.updateStatus("Build", osName, "success", options, NotificationConfiguration.BUILD_SOURCE_CODE_END_MESSAGE, "${BUILD_URL}/artifact")

}


def executeLinuxBuildCommand(String osName, Map options, String buildType) {
    
    outputEnvironmentInfo(osName, "${STAGE_NAME}_${buildType}")

    try {
        sh """
            mkdir build-${buildType}
            cd build-${buildType}
            cmake -DCMAKE_buildType=${buildType} ${options.cmakeKeysLinux[osName]} -DRML_TENSORFLOW_DIR=${WORKSPACE}/third_party/tensorflow -DMIOpen_INCLUDE_DIR=${WORKSPACE}/third_party/miopen -DMIOpen_LIBRARY_DIR=${WORKSPACE}/third_party/miopen .. >> ../${STAGE_NAME}_${buildType}.log 2>&1
            make -j 8 >> ../${STAGE_NAME}_${buildType}.log 2>&1
        """
    } catch (e) {
        def exception = e

        try {
            String buildLogContent = readFile("${STAGE_NAME}_${buildType}.log")
            if (buildLogContent.contains("Segmentation fault")) {
                exception = new ExpectedExceptionWrapper(NotificationConfiguration.SEGMENTATION_FAULT, e)
                exception.retry = true

                utils.reboot(this, osName)
            }
        } catch (e1) {
            println("[WARNING] Could not analyze build log")
        }

        throw exception
    }

    sh """
        cd build-${buildType}
        mv bin ${buildType}
    """

    try {
        // PR-375 stores part of libs and binaries in bin directory
        sh """
            cd build-${buildType}/${buildType}
            mv bin/* .
            rmdir bin
        """
    } catch (e) {
        print(e)
    }

    sh """
        cd build-${buildType}
        rm -rf ${buildType}/*.a
        cp -R ../third_party/miopen/libMIOpen.so* ./${buildType}
        cp -R ../third_party/tensorflow/linux/* ./${buildType}
        mkdir ./${buildType}/rml
        mkdir ./${buildType}/rml_internal
        cp ../rml/include/rml/*.h* ./${buildType}/rml
        cp ../rml/include/rml_internal/*.h* ./${buildType}/rml_internal

        tar cf ${osName}_${buildType}.tar ${buildType}
    """

    if (env.TAG_NAME) {
        sh """
            rm -rf ${osName}/${buildType}
            mkdir -p ${osName}/${buildType}
            cp -R build-${buildType}/${buildType}/libMIOpen.so* ${osName}/${buildType}
            cp -R build-${buildType}/${buildType}/libRadeonML_MIOpen.so* ${osName}/${buildType}
            cp -R build-${buildType}/${buildType}/libRadeonML.so* ${osName}/${buildType}
        """
        dir("${osName}/${buildType}") {
            makeStash(includes: "*", name: "deploy_${osName}_${buildType}", storeOnNAS: options.storeOnNAS)
        }
    }

    String artifactURL

    dir("build-${buildType}") {
        String ARTIFACT_NAME = "${osName}_${buildType}.tar"
        artifactURL = makeArchiveArtifacts(name: ARTIFACT_NAME, storeOnNAS: options.storeOnNAS, createLink: false)
    }

    zip archive: true, dir: "build-${buildType}/${buildType}", glob: "libRadeonML*.so, libMIOpen*.so, libtensorflow*.so, test*", zipFile: "${osName}_${buildType}.zip"

    return artifactURL
}


def executeBuildLinux(String osName, Map options) {
    GithubNotificator.updateStatus("Build", osName, "in_progress", options, NotificationConfiguration.BUILD_SOURCE_CODE_START_MESSAGE, "${BUILD_URL}/artifact")

    sh """
        cp -r ../RML_thirdparty/MIOpen/* ./third_party/miopen
        cp -r ../RML_thirdparty/tensorflow/* ./third_party/tensorflow
    """

    options.cmakeKeysLinux = [
        'Ubuntu20': '-DRML_DIRECTML=OFF -DRML_MIOPEN=ON -DRML_TENSORFLOW_CPU=ON -DRML_TENSORFLOW_CUDA=ON -DRML_MPS=OFF',
        'CentOS7': '-DRML_DIRECTML=OFF -DRML_MIOPEN=ON -DRML_TENSORFLOW_CPU=ON -DRML_TENSORFLOW_CUDA=OFF -DRML_MPS=OFF'
    ]

    String releaseLink = executeLinuxBuildCommand(osName, options, "Release")
    String debugLink = executeLinuxBuildCommand(osName, options, "Debug")

    rtp nullAction: '1', parserName: 'HTML', stableText: """<h4>${osName}: <a href="${releaseLink}">Release</a> / <a href="${debugLink}">Debug</a> </h4>"""

    GithubNotificator.updateStatus("Build", osName, "success", options, NotificationConfiguration.BUILD_SOURCE_CODE_END_MESSAGE, "${BUILD_URL}/artifact")

}


def executeBuild(String osName, Map options) {
    try {

        withNotifications(title: osName, options: options, configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
            checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo)
        }
        
        downloadFiles("/volume1/CIS/rpr-ml/MIOpen/${osName}/release/", "../RML_thirdparty/MIOpen")
        downloadFiles("/volume1/CIS/rpr-ml/tensorflow/", "../RML_thirdparty/tensorflow")
        downloadFiles("/volume1/CIS/rpr-ml/DirectML/", "../RML_thirdparty/DirectML")

        utils.removeFile(this, osName, "${STAGE_NAME}_Release.log")
        utils.removeFile(this, osName, "${STAGE_NAME}_Debug.log")

        withNotifications(title: osName, options: options, configuration: NotificationConfiguration.BUILD_SOURCE_CODE) {
            switch (osName) {
                case 'Windows':
                    executeBuildWindows(osName, options)
                    break
                case 'OSX':
                case 'MacOS_ARM':
                    executeBuildOSX(osName, options)
                    break
                default:
                    executeBuildLinux(osName, options)
            }
        }

        if (!env.TAG_NAME) {
            dir('build-Release/Release') {
                makeStash(includes: '*', name: "app${osName}", storeOnNAS: options.storeOnNAS)
            } 
        }

    } catch (e) {
        throw e
    } finally {
        archiveArtifacts "*.log"
    }
}


def executePreBuild(Map options) {

    withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
        checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo, disableSubmodules: true)
    }

    options.commitAuthor = bat (script: "git show -s --format=%%an HEAD ",returnStdout: true).split('\r\n')[2].trim()
    options.commitMessage = bat (script: "git log --format=%%B -n 1", returnStdout: true).split('\r\n')[2].trim()
    options.commitSHA = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
    options.commitShortSHA = options.commitSHA[0..6]

    println "The last commit was written by ${options.commitAuthor}."
    println "Commit message: ${options.commitMessage}"
    println "Commit SHA: ${options.commitSHA}"
    println "Commit shortSHA: ${options.commitShortSHA}"

    if (env.BRANCH_NAME) {
        withNotifications(title: "Jenkins build configuration", printMessage: true, options: options, configuration: NotificationConfiguration.CREATE_GITHUB_NOTIFICATOR) {
            GithubNotificator githubNotificator = new GithubNotificator(this, options)
            githubNotificator.init(options)
            options.githubNotificator = githubNotificator
            githubNotificator.initPreBuild(BUILD_URL)
            options.projectBranchName = githubNotificator.branchName
        }
    } else {
        options.projectBranchName = options.projectBranch
    }

    currentBuild.description = "<b>Project branch:</b> ${options.projectBranchName}<br/>"
    currentBuild.description += "<b>Commit author:</b> ${options.commitAuthor}<br/>"
    currentBuild.description += "<b>Commit message:</b> ${options.commitMessage}<br/>"
    currentBuild.description += "<b>Commit SHA:</b> ${options.commitSHA}<br/>"

    withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.CONFIGURE_TESTS) {
        if (options.executeFT) {
            options.tests = ["Unit", "FT"]
        } else {
            options.tests = ["Unit"]
        }
    }

    if (env.BRANCH_NAME && options.githubNotificator) {
        options.githubNotificator.initChecks(options, BUILD_URL, true, true, false)
    }
}


def executeDeploy(Map options, List platformList, List testResultList) {

    try {
        dir("rml-deploy") {
           
            checkoutScm(branchName: "master", repositoryUrl: "${options.gitlabURLSSH}/servants/rml-deploy.git", credentialsId: "radeonprorender-gitlab")
             
            bat """
                git rm -r *
            """ 

            platformList.each() {
                dir(it) {
                    dir("Release"){
                        makeUnstash(name: "deploy_${it}_Release", storeOnNAS: options.storeOnNAS)
                    }
                    dir("Debug"){
                        makeUnstash(name: "deploy_${it}_Debug", storeOnNAS: options.storeOnNAS)
                    }
                }
            }
                
            bat """
                git add --all
                git commit -m "buildmaster: SDK release ${env.TAG_NAME}"
                git tag -a rml_sdk_${env.TAG_NAME} -m "rml_sdk_${env.TAG_NAME}"
                git push --tag origin HEAD:master
            """ 
        }
    } catch (e) {
        println("[ERROR] Failed to deploy RML binaries")
        println(e.toString())
        println(e.getMessage())
    }
}


def call(String projectBranch = "",
         String testsBranch = "master",
         String platforms = 'Windows:AMD_RadeonVII,NVIDIA_RTX3080TI,AMD_RX6800XT;Ubuntu20;OSX:AMD_RX5700XT;CentOS7;MacOS_ARM:AppleM1',
         String projectRepo='git@github.com:Radeon-Pro/RadeonML.git',
         Boolean enableNotifications = true,
         Boolean executeFT = true) {

    ProblemMessageManager problemMessageManager = new ProblemMessageManager(this, currentBuild)

    Map options = [:]
    options["stage"] = "Init"
    options["problemMessageManager"] = problemMessageManager

    def gitlabURL
    withCredentials([string(credentialsId: 'gitlabURL', variable: 'GITLAB_URL'), string(credentialsId: 'gitlabURLSSH', variable: 'GITLAB_URL_SSH')]) {
        gitlabURL = GITLAB_URL
        gitlabURLSSH = GITLAB_URL_SSH
    }

    try {

        def deployStage = env.TAG_NAME ? this.&executeDeploy : null
        platforms = env.TAG_NAME ? "Windows;Ubuntu20;OSX;CentOS7" : platforms

        options << [platforms:platforms,
                    projectRepo:projectRepo,
                    projectBranch:projectBranch,
                    testsBranch:testsBranch,
                    enableNotifications:enableNotifications,
                    PRJ_NAME:'RadeonML',
                    PRJ_ROOT:'rpr-ml',
                    BUILDER_TAG:'BuilderML',
                    BUILD_TIMEOUT:45,
                    TEST_TIMEOUT:45,
                    DEPLOY_TIMEOUT:45,
                    executeBuild:true,
                    executeTests:true,
                    executeFT:executeFT,
                    retriesForTestStage:1,
                    gitlabURL:gitlabURL,
                    gitlabURLSSH:gitlabURLSSH,
                    storeOnNAS:true,
                    flexibleUpdates: true
                    ]

        multiplatform_pipeline(platforms, this.&executePreBuild, this.&executeBuild, this.&executeTests, deployStage, options)

    } catch (e) {
        currentBuild.result = "FAILURE"
        println(e.toString())
        println(e.getMessage())
        throw e
    } finally {
        problemMessageManager.publishMessages()
    }
}

def executeTestCommand(String osName, String libType, Boolean testPerformance)
{
    switch(osName) {
        case 'Windows':
            dir("unittest") {
                bat "mkdir testSave"
                if (testPerformance) {
                    bat """
                    set RIF_AI_FP16_ENABLED=1
                    ..\\bin\\UnitTest.exe --mode p --gtest_filter=\"Performance.*\" --gtest_output=xml:../${STAGE_NAME}.${libType}.gtest.xml >> ..\\${STAGE_NAME}.${libType}.log  2>&1
                    """
                } else {
                    bat """
                    set RIF_AI_FP16_ENABLED=1
                    ..\\bin\\UnitTest.exe -t .\\testSave -r .\\referenceImages --models ..\\models --gtest_filter=\"*.*/1\" --gtest_output=xml:../${STAGE_NAME}.${libType}.gtest.xml >> ..\\${STAGE_NAME}.${libType}.log  2>&1
                    """
                }
            }
            break
        case 'OSX':
        case 'MacOS_ARM':
            dir("unittest") {
                sh "mkdir testSave"
                if (testPerformance) {
                    sh "RIF_AI_FP16_ENABLED=1 ../bin/UnitTest --mode p --gtest_filter=\"Performance.*\" --gtest_output=xml:../${STAGE_NAME}.${libType}.gtest.xml >> ../${STAGE_NAME}.${libType}.log  2>&1"
                } else {
                    sh "RIF_AI_FP16_ENABLED=1 ../bin/UnitTest  -t ./testSave -r ./referenceImages --models ../models --gtest_filter=\"*.*/1\" --gtest_output=xml:../${STAGE_NAME}.${libType}.gtest.xml >> ../${STAGE_NAME}.${libType}.log  2>&1"
                }
            }
            break
        default:
            dir("unittest") {
                sh "mkdir testSave"
                if (testPerformance) {
                    sh "RIF_AI_FP16_ENABLED=1 ../bin/UnitTest --mode p --gtest_filter=\"Performance.*\" --gtest_output=xml:../${STAGE_NAME}.${libType}.gtest.xml >> ../${STAGE_NAME}.${libType}.log  2>&1"
                } else {
                    sh "RIF_AI_FP16_ENABLED=1 ../bin/UnitTest  -t ./testSave -r ./referenceImages --models ../models --gtest_filter=\"*.*/1\" --gtest_output=xml:../${STAGE_NAME}.${libType}.gtest.xml >> ../${STAGE_NAME}.${libType}.log  2>&1"
                }
            }
    }
}


def executeTestsForCustomLib(String osName, String libType, Map options)
{
    try {
        checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo)
        outputEnvironmentInfo(osName, "${STAGE_NAME}.${libType}")
        makeUnstash(name: "app_${libType}_${osName}", storeOnNAS: false)
        executeTestCommand(osName, libType, options.testPerformance)
    } catch (e) {
        println(e.toString())
        println(e.getMessage())
        throw e
    } finally {
        archiveArtifacts "*.log"
        if (options.testPerformance) {
            switch(osName) {
                case 'Windows':
                    bat """
                        move unittest\\rif_performance_*.csv .
                        rename rif_performance_*.csv ${STAGE_NAME}.${libType}.csv
                    """
                    break
                case 'OSX':
                    sh """
                        mv unittest/rif_performance_*.csv ./${STAGE_NAME}.${libType}.csv
                    """
                    break
                default:
                    sh """
                        mv unittest/rif_performance_*.csv ./${STAGE_NAME}.${libType}.csv
                    """
                    break
            }
            makeStash(includes: "${STAGE_NAME}.${libType}.gtest.xml, ${STAGE_NAME}.${libType}.csv", name: "${options.testResultsName}.${libType}", allowEmpty: true, storeOnNAS: options.storeOnNAS)
        }
        junit "*.gtest.xml"
    }
}


def executeTests(String osName, String asicName, Map options)
{
    Boolean testsFailed = false

    try {
        executeTestsForCustomLib(osName, 'dynamic', options)
    } catch (e) {
        println("Error during testing dynamic lib")
        testsFailed = true
        println(e.toString())
        println(e.getMessage())
        println(e.getStackTrace()) 
    }

    if (testsFailed) {
        currentBuild.result = "FAILED"
        error "Error during testing"
    }

}


def executeBuildWindows(String cmakeKeys, String osName, Map options)
{
    bat """
        set msbuild="C:\\Program Files (x86)\\Microsoft Visual Studio\\2017\\Community\\MSBuild\\15.0\\Bin\\MSBuild.exe" >> ..\\${STAGE_NAME}.dynamic.log 2>&1
        mkdir build-${options.packageName}-${osName}-dynamic
        cd build-${options.packageName}-${osName}-dynamic
        cmake .. -DADL_PROFILING=ON -G "Visual Studio 15 2017 Win64" -DCMAKE_INSTALL_PREFIX=../${options.packageName}-${osName}-dynamic >> ..\\${STAGE_NAME}.dynamic.log 2>&1
        %msbuild% INSTALL.vcxproj -property:Configuration=Release >> ..\\${STAGE_NAME}.dynamic.log 2>&1
        cd ..

        mkdir build-${options.packageName}-${osName}-static-runtime
        cd build-${options.packageName}-${osName}-static-runtime
        cmake .. -DADL_PROFILING=ON -G "Visual Studio 15 2017 Win64" -DCMAKE_INSTALL_PREFIX=../${options.packageName}-${osName}-static-runtime -DRIF_STATIC_RUNTIME_LIB=ON >> ..\\${STAGE_NAME}.static-runtime.log 2>&1
        %msbuild% INSTALL.vcxproj -property:Configuration=Release >> ..\\${STAGE_NAME}.static-runtime.log 2>&1
        cd ..
    """

    // Stash for testing only
    dir("${options.packageName}-${osName}-dynamic") {
        makeStash(includes: "bin/*", name: "app_dynamic_${osName}", storeOnNAS: false)
    }

    bat """
        xcopy README.md ${options.packageName}-${osName}-dynamic\\README.md* /y
        xcopy README.md ${options.packageName}-${osName}-static-runtime\\README.md* /y

        cd ${options.packageName}-${osName}-dynamic
        del /S UnitTest*
        cd ..

        cd ${options.packageName}-${osName}-static-runtime
        del /S UnitTest*
        cd ..
    """

    // Stash for github repo
    dir("${options.packageName}-${osName}-dynamic/bin") {
        makeStash(includes: "*", excludes: '*.exp, *.pdb', name: "deploy-dynamic-${osName}", storeOnNAS: false)
    }

    dir("${options.packageName}-${osName}-static-runtime/bin") {
        makeStash(includes: "*", excludes: '*.exp, *.pdb', name: "deploy-static-runtime-${osName}", storeOnNAS: false)
    }

    makeStash(includes: "models/**/*", name: "models", storeOnNAS: options.storeOnNAS)
    makeStash(includes: "samples/**/*", name: "samples", storeOnNAS: options.storeOnNAS)
    makeStash(includes: "include/**/*", name: "include", storeOnNAS: options.storeOnNAS)

    dir ('src') {
        makeStash(includes: "License.txt", name: "txtFiles", storeOnNAS: options.storeOnNAS)
    }

    bat """
        mkdir RIF_Release
        mkdir RIF_Debug
        mkdir RIF_Samples
        mkdir RIF_Models

        xcopy ${options.packageName}-${osName}-dynamic RIF_Dynamic\\${options.packageName}-${osName}-dynamic /s/y/i
        xcopy ${options.packageName}-${osName}-static-runtime RIF_Static_Runtime\\${options.packageName}-${osName}-static-runtime /s/y/i
        xcopy samples RIF_Samples\\samples /s/y/i
        xcopy models RIF_Models\\models /s/y/i
    """

    String DYNAMIC_PACKAGE_NAME = "${options.packageName}-${osName}-dynamic.zip"
    bat(script: '%CIS_TOOLS%\\7-Zip\\7z.exe a' + " \"${DYNAMIC_PACKAGE_NAME}\" \"RIF_Dynamic\"")
    String dynamicPackageURL = makeArchiveArtifacts(name: DYNAMIC_PACKAGE_NAME, storeOnNAS: options.storeOnNAS, createLink: false)

    String STATIC_RUNTIME_PACKAGE_NAME = "${options.packageName}-${osName}-static-runtime.zip"
    bat(script: '%CIS_TOOLS%\\7-Zip\\7z.exe a' + " \"${STATIC_RUNTIME_PACKAGE_NAME}\" \"RIF_Static_Runtime\"")
    String statisRuntimePackageURL = makeArchiveArtifacts(name: STATIC_RUNTIME_PACKAGE_NAME, storeOnNAS: options.storeOnNAS, createLink: false)

    String SAMPLES_NAME = "${options.samplesName}.zip"
    bat(script: '%CIS_TOOLS%\\7-Zip\\7z.exe a' + " \"${SAMPLES_NAME}\" \"RIF_Samples\"")
    String samplesURL = makeArchiveArtifacts(name: DYNAMIC_PACKAGE_NAME, storeOnNAS: options.storeOnNAS, createLink: false)

    String MODELES_NAME = "${options.modelsName}.zip"
    bat(script: '%CIS_TOOLS%\\7-Zip\\7z.exe a' + " \"${MODELES_NAME}\" \"RIF_Models\"")
    String modelesURL = makeArchiveArtifacts(name: MODELES_NAME, storeOnNAS: options.storeOnNAS, createLink: false)

    rtp nullAction: "1", parserName: "HTML", stableText: """<h4>${osName}: <a href="${dynamicPackageURL}">dynamic</a> / <a href="${statisRuntimePackageURL}">static-runtime</a> </h4>"""
    rtp nullAction: "1", parserName: "HTML", stableText: """<h4>Samples: <a href="${samplesURL}">${SAMPLES_NAME}</a></h4>"""
    rtp nullAction: "1", parserName: "HTML", stableText: """<h4>Models: <a href="${modelesURL}">${MODELES_NAME}</a></h4>"""
}

def executeBuildUnix(String cmakeKeys, String osName, Map options, String compilerName="gcc")
{
    String EXPORT_CXX
    String SRC_BUILD

    if (compilerName == "clang-9.0") {
        EXPORT_CXX = "export CXX=clang-9"
        SRC_BUILD = "RadeonImageFilters"
    } else if (compilerName == "clang-5.0") {
        EXPORT_CXX = "export CXX=clang-5.0"
        SRC_BUILD = "RadeonImageFilters"
    } else {
        EXPORT_CXX = ""
        SRC_BUILD = "all"
    }

    sh """
        ${EXPORT_CXX}
        mkdir build-${options.packageName}-${osName}-dynamic
        cd build-${options.packageName}-${osName}-dynamic
        cmake .. -DADL_PROFILING=ON ${cmakeKeys} -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=../${options.packageName}-${osName}-dynamic >> ../${STAGE_NAME}.dynamic.log 2>&1
        make -j 8 ${SRC_BUILD} >> ../${STAGE_NAME}.dynamic.log 2>&1
        make install >> ../${STAGE_NAME}.dynamic.log 2>&1
        cd ..

        mkdir build-${options.packageName}-${osName}-static-runtime
        cd build-${options.packageName}-${osName}-static-runtime
        cmake .. -DADL_PROFILING=ON ${cmakeKeys} -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=../${options.packageName}-${osName}-static-runtime -DRIF_STATIC_RUNTIME_LIB=ON >> ../${STAGE_NAME}.static-runtime.log 2>&1
        make -j 8 ${SRC_BUILD} >> ../${STAGE_NAME}.static-runtime.log 2>&1
        make install >> ../${STAGE_NAME}.static-runtime.log 2>&1
        cd ..
    """
    
    if (compilerName == "clang-5.0" || compilerName == "clang-9.0") {
        sh """
            export CXX=clang++-9
            cd build-${options.packageName}-${osName}-dynamic
            rm -rd *
            cmake .. ${cmakeKeys} -DRIF_UNITTEST=ON -DRIF_UNITTEST_ONLY=ON -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=../${options.packageName}-${osName}-dynamic >> ../${STAGE_NAME}.dynamic.log 2>&1
            make -j 8 >> ../${STAGE_NAME}.dynamic.log 2>&1
            make install >> ../${STAGE_NAME}.dynamic.log 2>&1
            cd ..

        """
    }

    // Stash for testing
    dir("${options.packageName}-${osName}-dynamic") {
        makeStash(includes: "bin/*", name: "app_dynamic_${osName}", storeOnNAS: false)
    }

    sh """
        cp README.md ${options.packageName}-${osName}-dynamic
        cp README.md ${options.packageName}-${osName}-static-runtime
    """

    sh """
        rm ${options.packageName}-${osName}-dynamic/bin/UnitTest*
    """
    if (compilerName != "clang-5.0" && compilerName != "clang-9.0") {
        sh """
            rm ${options.packageName}-${osName}-static-runtime/bin/UnitTest*
        """
    }

    sh """
        tar cf ${options.packageName}-${osName}-dynamic.tar ${options.packageName}-${osName}-dynamic
        tar cf ${options.packageName}-${osName}-static-runtime.tar ${options.packageName}-${osName}-static-runtime
    """

    String DYNAMIC_PACKAGE_NAME = "${options.packageName}-${osName}-dynamic.tar"
    String dynamicPackageURL = makeArchiveArtifacts(name: DYNAMIC_PACKAGE_NAME, storeOnNAS: options.storeOnNAS, createLink: false)

    String STATIC_RUNTIME_PACKAGE_NAME = "${options.packageName}-${osName}-static-runtime.tar"
    String statisRuntimePackageURL = makeArchiveArtifacts(name: STATIC_RUNTIME_PACKAGE_NAME, storeOnNAS: options.storeOnNAS, createLink: false)

    rtp nullAction: "1", parserName: "HTML", stableText: """<h4>${osName}: <a href="${dynamicPackageURL}">dynamic</a> / <a href="${statisRuntimePackageURL}">static-runtime</a> </h4>"""

    dir("${options.packageName}-${osName}-dynamic/bin/") {
        makeStash(includes: "*", excludes: '*.exp, *.pdb', name: "deploy-dynamic-${osName}", storeOnNAS: false)
    }

    dir("${options.packageName}-${osName}-static-runtime/bin/") {
        makeStash(includes: "*", excludes: '*.exp, *.pdb', name: "deploy-static-runtime-${osName}", storeOnNAS: false)
    }
}


def getArtifactName(String name, String branch, String commit) {
    String return_name = name + (branch ? '-' + branch : '') + (commit ? '-' + commit : '')
    return return_name.replaceAll('[^a-zA-Z0-9-_.]+','')
}


def executePreBuild(Map options)
{
    checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo, disableSubmodules: true)

    options.commitAuthor = bat (script: "git show -s --format=%%an HEAD ",returnStdout: true).split('\r\n')[2].trim()
    options.commitMessage = bat (script: "git log --format=%%B -n 1", returnStdout: true)
    options.commitSHA = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
    options.commitShortSHA = options.commitSHA[0..6]
    println "The last commit was written by ${options.commitAuthor}."
    println "Commit message: ${options.commitMessage}"
    println "Commit SHA: ${options.commitSHA}"

    String branch = env.BRANCH_NAME ? env.BRANCH_NAME : env.Branch
    options.branch = branch.replace('origin/', '')

    options.packageName = getArtifactName('radeonimagefilters', options.branch, options.commitShortSHA)
    options.modelsName = getArtifactName('models', options.branch, options.commitShortSHA)
    options.samplesName = getArtifactName('samples', options.branch, options.commitShortSHA)
}


def executeBuild(String osName, Map options)
{
    try {
        checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo)
        outputEnvironmentInfo(osName, "${STAGE_NAME}.dynamic")
        outputEnvironmentInfo(osName, "${STAGE_NAME}.static-runtime")

        switch(osName) {
            case 'Windows':
                executeBuildWindows(options.cmakeKeys, osName, options)
                break
            case 'OSX':
            case 'MacOS_ARM':
                executeBuildUnix(options.cmakeKeys, osName, options, 'clang')
                break
            case 'Ubuntu18-Clang':
                executeBuildUnix("${options.cmakeKeys} -DRIF_UNITTEST=OFF -DRIF_ADL_INCLUDE=ON -DCMAKE_CXX_FLAGS=\"-D_GLIBCXX_USE_CXX11_ABI=0\"", osName, options, 'clang-5.0')
                break
            case 'Ubuntu20-Clang':
                executeBuildUnix("${options.cmakeKeys} -DRIF_UNITTEST=OFF -DRIF_ADL_INCLUDE=ON -DCMAKE_CXX_FLAGS=\"-D_GLIBCXX_USE_CXX11_ABI=0\"", osName, options, 'clang-9.0')
                break
            default:
                executeBuildUnix(options.cmakeKeys, osName, options)
        }
    } catch (e) {
        currentBuild.result = "FAILED"
        throw e
    } finally {
        archiveArtifacts "*.log"
    }
}

def executeDeploy(Map options, List platformList, List testResultList)
{
    cleanWS()

    if (options.testPerformance) {
        dir("testResults") {
            testResultList.each() {
                try {
                    makeUnstash(name: "${it}.dynamic", storeOnNAS: options.storeOnNAS)
                } catch(e) {
                    echo "[ERROR] Failed to unstash ${it}"
                    println(e.toString());
                    println(e.getMessage());
                }

            }
        }

        dir("rif-report") {
            checkoutScm(branchName: "master", repositoryUrl: "git@github.com:luxteam/rif_report.git")

            bat """
                set PATH=c:\\python39\\;c:\\python39\\scripts\\;%PATH%
                pip install --user -r requirements.txt >> ${STAGE_NAME}.requirements.log 2>&1
                python build_report.py --test_results ..\\testResults --output_dir ..\\results
            """
        }

        utils.publishReport(this, "${BUILD_URL}", "summaryTestResults", "summary_report.html", "Test Report", "Summary Report")

    } else {
        checkoutScm(branchName: "master", repositoryUrl: "git@github.com:Radeon-Pro/RadeonProImageProcessingSDK.git")

        bat """
            git rm -r *
        """

        platformList.each() {
            dir(it) {
                dir("Dynamic"){
                    makeUnstash(name: "deploy-dynamic-${it}", storeOnNAS: false)
                }
                dir("Static-Runtime"){
                    makeUnstash(name: "deploy-static-runtime-${it}", storeOnNAS: false)
                }
            }
        }

        makeUnstash(name: "models", storeOnNAS: options.storeOnNAS)
        makeUnstash(name: "samples", storeOnNAS: options.storeOnNAS)
        makeUnstash(name: "txtFiles", storeOnNAS: options.storeOnNAS)
        makeUnstash(name: "include", storeOnNAS: options.storeOnNAS)

        bat """
            git add --all
            git commit -m "buildmaster: SDK release v${env.TAG_NAME}"
            git tag -a rif_sdk_${env.TAG_NAME} -m "rif_sdk_${env.TAG_NAME}"
            git push --tag origin HEAD:master
        """
    }
}

def call(String projectBranch = "",
         String platforms = 'Windows:NVIDIA_RTX3080TI,AMD_RadeonVII,AMD_RX6800XT;Ubuntu20;OSX:AMD_RX5700XT;CentOS7;Ubuntu20-Clang;MacOS_ARM:AppleM1',
         Boolean updateRefs = false,
         Boolean enableNotifications = true,
         String cmakeKeys = '',
         Boolean testPerformance = false,
         String tester_tag = 'Tester') {

    println "TAG_NAME: ${env.TAG_NAME}"

    def deployStage = env.TAG_NAME || testPerformance ? this.&executeDeploy : null
    platforms = env.TAG_NAME ? "Windows;Ubuntu18-Clang;Ubuntu20-Clang;Ubuntu20;OSX;CentOS7;MacOS_ARM;" : platforms

    def nodeRetry = []

    multiplatform_pipeline(platforms, this.&executePreBuild, this.&executeBuild, this.&executeTests, deployStage,
                           [projectBranch:projectBranch,
                            projectRepo:'git@github.com:Radeon-Pro/RadeonProImageProcessing.git',
                            enableNotifications:enableNotifications,
                            TESTER_TAG:tester_tag,
                            BUILD_TIMEOUT:'40',
                            TEST_TIMEOUT:'45',
                            BUILDER_TAG:'BuilderRIF',
                            executeBuild:true,
                            executeTests:true,
                            PRJ_NAME:"RadeonProImageProcessor",
                            PRJ_ROOT:"rpr-core",
                            cmakeKeys:cmakeKeys,
                            testPerformance:testPerformance,
                            nodeRetry: nodeRetry,
                            retriesForTestStage:1,
                            storeOnNAS:true])
}

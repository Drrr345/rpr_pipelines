def executeTests(String osName, String asicName, Map options)
{}

def executeBuildWindows(Map options)
{
    withEnv(["PATH=c:\\python366\\;c:\\python366\\scripts\\;${PATH}", "WORKSPACE=${env.WORKSPACE.toString().replace('\\', '/')}"]) {
        outputEnvironmentInfo("Windows", "${STAGE_NAME}.EnvVariables")

        // vcvars64.bat sets VS/msbuild env
        bat """
            call "C:\\Program Files (x86)\\Microsoft Visual Studio\\2017\\Community\\VC\\Auxiliary\\Build\\vcvars64.bat" >> ${STAGE_NAME}.EnvVariables.log 2>&1
        

            :: VulkanWrappers

            cd RPRViewer\\deps\\VidWrappers
            cmake -G "Visual Studio 15 2017 Win64" -B build -DVW_ENABLE_RRNEXT=OFF >> ..\\..\\..\\${STAGE_NAME}.VulkanWrappers.log 2>&1
            cmake --build build --target VidWrappers --config Release >> ..\\..\\..\\${STAGE_NAME}.VulkanWrappers.log 2>&1
            cmake --build build --target SPVRemapper --config Release >> ..\\..\\..\\${STAGE_NAME}.VulkanWrappers.log 2>&1
            cd ..\\..\\..

            git apply usd_dev.patch  >> ${STAGE_NAME}.USDPixar.log 2>&1

            :: PySide

            cd RPRViewer\\deps\\PySide
            python setup.py install --ignore-git --parallel=%NUMBER_OF_PROCESSORS% >> ..\\..\\..\\${STAGE_NAME}.USDPixar.log 2>&1
            cd ..\\..\\..

            :: USD

            python USDPixar\\build_scripts\\build_usd.py --build RPRViewer/build --src RPRViewer/deps RPRViewer/inst >> ${STAGE_NAME}.USDPixar.log 2>&1
        
            :: HdRPRPlugin

            set PXR_DIR=%CD%\\USDPixar
            set INSTALL_PREFIX_DIR=%CD%\\RPRViewer\\inst

            cd HdRPRPlugin
            cmake -B build -G "Visual Studio 15 2017 Win64" -Dpxr_DIR=%PXR_DIR% -DCMAKE_INSTALL_PREFIX=%INSTALL_PREFIX_DIR% ^
                -DRPR_BUILD_AS_HOUDINI_PLUGIN=FALSE -DPXR_USE_PYTHON_3=ON >> ..\\${STAGE_NAME}.HdRPRPlugin.log 2>&1
            cmake --build build --config Release --target install >> ..\\${STAGE_NAME}.HdRPRPlugin.log 2>&1
        """

        // for testing
        //set PATH=${WORKSPACE}\\RPRViewer\\RPRViewer\\inst\\bin;${WORKSPACE}\\RPRViewer\\RPRViewer\\inst\\lib;%PATH%
        //set PYTHONPATH=${WORKSPACE}\\RPRViewer\\RPRViewer\\inst\\lib\\python;%PYTHONPATH%
        
        // TODO: filter files for archive
        zip archive: true, dir: "RPRViewer/inst", glob: '', zipFile: "RadeonProUSDViewer_Windows.zip"
        
    }
}

def executeBuild(String osName, Map options)
{

    checkOutBranchOrScm(options['projectBranch'], options['projectRepo'])

    try {
        switch (osName) {
            case 'Windows':
                executeBuildWindows(options);
                break;
            case 'OSX':
                println "OS isn't supported."
                break;
            default:
                println "OS isn't supported."
        }
    }
    catch (e) {
        currentBuild.result = "FAILED"
        throw e
    }
    finally {
        archiveArtifacts artifacts: "*.log", allowEmptyArchive: true
    }
}

def executePreBuild(Map options)
{
    checkOutBranchOrScm(options['projectBranch'], options['projectRepo'], null, null, true)

    options.commitAuthor = bat (script: "git show -s --format=%%an HEAD ",returnStdout: true).split('\r\n')[2].trim()
    options.commitMessage = bat (script: "git log --format=%%s -n 1", returnStdout: true).split('\r\n')[2].trim().replace('\n', '')
    options.commitSHA = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()

    println "The last commit was written by ${options.commitAuthor}."
    println "Commit message: ${options.commitMessage}"
    println "Commit SHA: ${options.commitSHA}"

    if (options.projectBranch) {
        currentBuild.description = "<b>Project branch:</b> ${options.projectBranch}<br/>"
    } else {
        currentBuild.description = "<b>Project branch:</b> ${env.BRANCH_NAME}<br/>"
    }
    currentBuild.description += "<b>Commit author:</b> ${options.commitAuthor}<br/>"
    currentBuild.description += "<b>Commit message:</b> ${options.commitMessage}<br/>"
    currentBuild.description += "<b>Commit SHA:</b> ${options.commitSHA}<br/>"

    if (env.CHANGE_URL) {
        echo "branch was detected as Pull Request"
        options['isPR'] = true
        options.testsPackage = "PR"
    }
    else if(env.BRANCH_NAME && env.BRANCH_NAME == "master") {
        options.testsPackage = "master"
    }
    else if(env.BRANCH_NAME) {
        options.testsPackage = "smoke"
    }
}

def executeDeploy(Map options, List platformList, List testResultList)
{}

def call(String projectBranch = "",
         String testsBranch = "master",
         String platforms = 'Windows',
         Boolean updateRefs = false,
         Boolean enableNotifications = true)
{
    def nodeRetry = []
    String PRJ_ROOT='rpr-core'
    String PRJ_NAME='USDViewer'
    String projectRepo='git@github.com:Radeon-Pro/RPRViewer.git'

    multiplatform_pipeline(platforms, this.&executePreBuild, this.&executeBuild, null, null,
            [projectBranch:projectBranch,
             testsBranch:testsBranch,
             updateRefs:updateRefs,
             enableNotifications:enableNotifications,
             PRJ_NAME:PRJ_NAME,
             PRJ_ROOT:PRJ_ROOT,
             projectRepo:projectRepo,
             BUILDER_TAG:'RPRUSDVIEWER',
             executeBuild:true,
             executeTests:false,
             BUILD_TIMEOUT:90,
             DEPLOY_TIMEOUT:45,
             nodeRetry: nodeRetry])
}

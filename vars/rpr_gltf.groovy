def executeTestCommand(String osName)
{
}

def executeTests(String osName, String asicName, Map options)
{
}

def executeBuildWindows()
{
    bat """
        set msbuild=\"C:\\Program Files (x86)\\MSBuild\\14.0\\Bin\\MSBuild.exe\"
        if not exist %msbuild% (
            set msbuild=\"C:\\Program Files (x86)\\Microsoft Visual Studio\\2017\\Community\\MSBuild\\15.0\\Bin\\MSBuild.exe\"
        )
        set target=build
        set maxcpucount=/maxcpucount
        set PATH=C:\\Python27\\;%PATH%
        .\\Tools\\premake\\win\\premake5 vs2015    >> ${STAGE_NAME}.log 2>&1
        set solution=Build\\ProRenderGLTF.sln
        %msbuild% /target:%target% %maxcpucount% /nodeReuse:false /property:Configuration=Release;Platform=x64 %parameters% %solution% >> ${STAGE_NAME}.log 2>&1
    """
}

def executeBuildOSX()
{
    sh """
        chmod +x Tools/premake/osx/premake5
        Tools/premake/osx/premake5 gmake   >> ${STAGE_NAME}.log 2>&1
        cd Build
        make config=release_x64 >> ../${STAGE_NAME}.log 2>&1
    """
}

def executeBuildLinux()
{
    sh """
        chmod +x Tools/premake/linux64/premake5
        Tools/premake/linux64/premake5 gmake   >> ${STAGE_NAME}.log 2>&1
        cd Build
        make config=release_x64 >> ../${STAGE_NAME}.log 2>&1
    """
}

def executePreBuild(Map options)
{
    checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo, disableSubmodules: true)

    options.commitAuthor = bat (script: "git show -s --format=%%an HEAD ",returnStdout: true).split('\r\n')[2].trim()
    commitMessage = bat (script: "git log --format=%%B -n 1", returnStdout: true)
    options.commitSHA = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
    println "The last commit was written by ${options.commitAuthor}."
    println "Commit message: ${commitMessage}"
    println "Commit SHA: ${options.commitSHA}"
}


def executeBuild(String osName, Map options)
{
    try {
        checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo)
        outputEnvironmentInfo(osName)

        switch(osName) {
            case 'Windows':
                executeBuildWindows()
                break
            case 'OSX':
                executeBuildOSX()
                break
            default:
                executeBuildLinux()
        }

    } catch (e) {
        currentBuild.result = "FAILED"
        throw e
    } finally {
        archiveArtifacts "*.log"
    }
}

def executeDeploy(Map options, List platformList, List testResultList) {}


def call(String projectBranch = "",
         String platforms = 'Windows;OSX',
         Boolean updateRefs = false, Boolean enableNotifications = true) {

    multiplatform_pipeline(platforms, this.&executePreBuild, this.&executeBuild, null, this.&executeDeploy,
                           [projectBranch:projectBranch,
                            projectRepo:'git@github.com:Radeon-Pro/RadeonProRender-GLTF.git',
                            enableNotifications:enableNotifications,
                            BUILD_TIMEOUT:'10',
                            executeBuild:true,
                            executeTests:false,
                            PRJ_NAME:"RadeonProRender-GLTF",
                            PRJ_ROOT:"rpr-core"])
}

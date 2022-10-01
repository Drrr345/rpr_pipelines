def executeTestCommand(String osName) {}

def executeTests(String osName, String asicName, Map options) {}

def executeBuildWindows() {
    withEnv(["PATH=c:\\python39\\;c:\\python39\\scripts\\;${PATH}"]) {
        bat """
            pushd MatLibPkg
            build_windows_installer.cmd >> ..\\${STAGE_NAME}.log 2>&1        
        """
        bat "xcopy MatLibPkg\\RadeonProMaterialLibrary.msi RadeonProRenderMaterialLibrary.msi*"
    }
}

def executeBuildOSX() {
    sh """
        pushd MatLibPkg
        ./build_osx_installer.sh >> ../${STAGE_NAME}.log 2>&1
        cp ./.installer_build/RadeonProRenderMaterialLibrary_2.0.0.dmg ../RadeonProRenderMaterialLibrary.dmg
        popd
    """
}

def executeBuildLinux() {
    sh """
        cd MatLibPkg
        ./build_linux_installer.sh >> ../${STAGE_NAME}.log 2>&1
        cp ./.installer_build/RadeonProRenderMaterialLibraryInstaller_2.0.run ../RadeonProRenderMaterialLibrary.run
        cd ..
    """
}

def executePreBuild(Map options) {
    checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo, disableSubmodules: true)

    options.commitAuthor = bat (script: "git show -s --format=%%an HEAD ",returnStdout: true).split('\r\n')[2].trim()
    options.commitMessage = bat (script: "git log --format=%%s -n 1", returnStdout: true).split('\r\n')[2].trim().replace('\n', '')
    options.commitSHA = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()

    println("The last commit was written by ${options.commitAuthor}.")
    println("Commit message: ${options.commitMessage}")
    println("Commit SHA: ${options.commitSHA}")

    if (options.projectBranch){
        currentBuild.description = "<b>Project branch:</b> ${options.projectBranch}<br/>"
    } else {
        currentBuild.description = "<b>Project branch:</b> ${env.BRANCH_NAME}<br/>"
    }

    currentBuild.description += "<b>Commit author:</b> ${options.commitAuthor}<br/>"
    currentBuild.description += "<b>Commit message:</b> ${options.commitMessage}<br/>"
    currentBuild.description += "<b>Commit SHA:</b> ${options.commitSHA}<br/>"
}

def executeBuild(String osName, Map options) {
    try {
        checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo)
        outputEnvironmentInfo(osName)

        String artifactName

        switch (osName) {
            case 'Windows': 
                executeBuildWindows()
                artifactName = "RadeonProRenderMaterialLibrary.msi"
                break
            case 'OSX':
                executeBuildOSX()
                artifactName = "RadeonProRenderMaterialLibrary.dmg"
                break
            default: 
                executeBuildLinux()
                artifactName = "RadeonProRenderMaterialLibrary.run"
        }

        makeArchiveArtifacts(name: artifactName, storeOnNAS: options.storeOnNAS)

    } catch (e) {
        currentBuild.result = "FAILED"
        throw e
    } finally {
        archiveArtifacts "*.log"
    }
}

def executeDeploy(Map options, List platformList, List testResultList) {}

def call(String projectBranch = "",
         String projectRepo = 'git@github.com:Radeon-Pro/RadeonProRenderPkgPlugin.git',
         String platforms = 'Windows;OSX;Ubuntu20',
         Boolean enableNotifications = true) {

    multiplatform_pipeline(platforms, this.&executePreBuild, this.&executeBuild, null, null,
                           [projectBranch:projectBranch,
                            enableNotifications:enableNotifications,
                            executeBuild:true,
                            executeTests:false,
                            PRJ_NAME:"RadeonProRenderMaterialLibrary",
                            PRJ_ROOT:"rpr-plugins",
                            BUILD_TIMEOUT:'25',
                            projectRepo:projectRepo,
                            storeOnNAS:true])
}

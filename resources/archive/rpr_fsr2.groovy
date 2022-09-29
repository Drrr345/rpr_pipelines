import groovy.transform.Field
import utils


@Field final String PROJECT_REPO = "git@github.com:Radeon-Pro/FSR2.0.git"
@Field final String HTTP_PROJET_REPO = "https://github.com/Radeon-Pro/FSR2.0"


def executeTests(String osName, String asicName, Map options) {
}


def uploadToDropbox(String osName, Map options) {
    println("[INFO] Uploading built project to dropbox")
    //TODO implement uploading to dropbox
}


def executeBuildWindows(Map options) {

    Map builtConfigurations = [:]

    options.winBuildConfiguration.each() { winBuildConf ->

        builtConfigurations[winBuildConf] = false

        withNotifications(title: "Windows_${winBuildConf}", options: options, configuration: NotificationConfiguration.BUILD_SOURCE_CODE_NO_THROW) {

            println "Current build configuration: ${winBuildConf}."

            String logName = "${STAGE_NAME}.${winBuildConf}.log"
            String winArtifactsDir = "${winBuildConf.substring(0, 1).toUpperCase() + winBuildConf.substring(1).toLowerCase()}"
            String msBuildPath = "C:\\Program Files (x86)\\Microsoft Visual Studio\\2019\\Professional\\MSBuild\\Current\\Bin\\MSBuild.exe"

            dir("FSR2.0") {
                outputEnvironmentInfo("Windows", "Build-Windows.${winBuildConf}")

                bat """
                    set msbuild="${msBuildPath}"
                    %msbuild% D3D12Demo.sln /target:build /maxcpucount /nodeReuse:false /property:Configuration=${winBuildConf};Platform=x64 >> ${logName} 2>&1
                """
            }

            String archiveUrl = ""
            String BUILD_NAME

            dir("FSR2.0") {
                BUILD_NAME = options.branchPostfix ? "FSR2.0_${winBuildConf}.(${options.branchPostfix}).7z" : "FSR2.0_${winBuildConf}.7z"

                bat """
                    package.cmd ${winBuildConf}
                """

                utils.renameFile(this, "Windows", "FSR2-*.7z", BUILD_NAME)

                if (options["githubApiProvider"]) {
                    options["githubApiProvider"].addAsset(HTTP_PROJET_REPO, options["release_id"], BUILD_NAME)
                }

                archiveArtifacts artifacts: BUILD_NAME, allowEmptyArchive: false
                uploadToDropbox("Windows", options)
            }

            archiveUrl = "${BUILD_URL}artifact/${BUILD_NAME}"
            rtp nullAction: "1", parserName: "HTML", stableText: """<h3><a href="${archiveUrl}">[BUILD: ${BUILD_ID}] ${BUILD_NAME}</a></h3>"""

            builtConfigurations[winBuildConf] = true
        }
    }

    builtConfigurations.each { key, value ->
        if (!value) {
            currentBuild.result = "FAILURE"
            options.problemMessageManager.saveGlobalFailReason(NotificationConfiguration.BUILD_SOURCE_CODE["exceptions"][0]["problemMessage"])
        }
    }
}


def executeBuild(String osName, Map options) {
    try {
        dir("FSR2.0") {
            withNotifications(title: osName, options: options, configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
                checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo, prBranchName: options.prBranchName, prRepoName: options.prRepoName)
            }
        }

        switch(osName) {
            case "Windows":
                executeBuildWindows(options)
                break
            case "OSX":
                println("Unsupported OS")
                break
            default:
                println("Unsupported OS")
        }

    } catch (e) {
        throw e
    } finally {
        dir("FSR2.0") {
            archiveArtifacts artifacts: "*.log", allowEmptyArchive: true
        }
    }
}


def getBuildStages(Map options) {
    def customBuildStages = []

    if (options.platforms.contains("Windows")) {
        options.winBuildConfiguration.each() { winBuildConf ->
            customBuildStages << "Windows_${winBuildConf}"
        }
    }

    return customBuildStages
}


def executePreBuild(Map options) {
    // manual job
    if (!env.BRANCH_NAME) {
        println "[INFO] Manual job launch detected"
        options["executeBuild"] = true
        options["executeTests"] = true
    // auto job
    } else {
        if (env.CHANGE_URL) {
            println "[INFO] Branch was detected as Pull Request"
            options["executeBuild"] = true
            options["executeTests"] = true
            options["testsPackage"] = "regression.json"
        } else if (env.BRANCH_NAME == "main") {
            println "[INFO] ${env.BRANCH_NAME} branch was detected"
            options["executeBuild"] = true
            options["executeTests"] = true
            options["testsPackage"] = "regression.json"
        } else {
            println "[INFO] ${env.BRANCH_NAME} branch was detected"
            options["executeBuild"] = true
            options["executeTests"] = true
            options['testsPackage'] = "regression.json"
        }
    }

    // branch postfix
    options["branchPostfix"] = ""

    if (env.TAG_NAME) {
        options["branchPostfix"] = env.TAG_NAME
    } else if (env.BRANCH_NAME) {
        options["branchPostfix"] = env.BRANCH_NAME.replace('/', '-')
    } else if (options.projectBranch) {
        options["branchPostfix"] = options.projectBranch.replace('/', '-')
    }

    dir("FSR2.0") {
        withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
            checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo, prBranchName: options.prBranchName, prRepoName: options.prRepoName, disableSubmodules: true)
        }

        options.commitAuthor = bat (script: "git show -s --format=%%an HEAD ",returnStdout: true).split('\r\n')[2].trim()
        options.commitMessage = bat (script: "git log --format=%%B -n 1", returnStdout: true).split('\r\n')[2].trim()
        options.commitSHA = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
        options.commitShortSHA = options.commitSHA[0..6]

        println(bat (script: "git log --format=%%s -n 1", returnStdout: true).split('\r\n')[2].trim());
        println "The last commit was written by ${options.commitAuthor}."
        println "Commit message: ${options.commitMessage}"
        println "Commit SHA: ${options.commitSHA}"
        println "Commit shortSHA: ${options.commitShortSHA}"

        if (options.projectBranch) {
            currentBuild.description = "<b>Project branch:</b> ${options.projectBranch}<br/>"
        } else {
            currentBuild.description = "<b>Project branch:</b> ${env.BRANCH_NAME}<br/>"
        }

        //currentBuild.description += "<b>Version:</b> ${options.pluginVersion}<br/>"
        currentBuild.description += "<b>Commit author:</b> ${options.commitAuthor}<br/>"
        currentBuild.description += "<b>Commit message:</b> ${options.commitMessage}<br/>"
        currentBuild.description += "<b>Commit SHA:</b> ${options.commitSHA}<br/>"
    }

    withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.CONFIGURE_TESTS) {
        if (env.BRANCH_NAME) {
            withNotifications(title: "Jenkins build configuration", printMessage: true, options: options, configuration: NotificationConfiguration.CREATE_GITHUB_NOTIFICATOR) {
                options.customBuildStages = getBuildStages(options)

                GithubNotificator githubNotificator = new GithubNotificator(this, options)
                githubNotificator.init(options)
                options["githubNotificator"] = githubNotificator
                githubNotificator.initPreBuild("${BUILD_URL}")
            }
        }

        //TODO add tests initialization

        if (env.BRANCH_NAME && options.githubNotificator) {
            options.githubNotificator.initChecks(options, "${BUILD_URL}", false, true, false)
        }

        // some tag is processing. Create release if it's required
        if (env.TAG_NAME) {
            options["githubApiProvider"] = new GithubApiProvider(this)

            def releases = options["githubApiProvider"].getReleases(HTTP_PROJET_REPO)

            Boolean releaseExists = false

            for (release in releases) {
                if (env.TAG_NAME == release["tag_name"]) {
                    releaseExists = true

                    options["release_id"] = "${release.id}"

                    // remove existing assets
                    def assets = options["githubApiProvider"].getAssets(HTTP_PROJET_REPO, "${release.id}")

                    for (asset in assets) {
                        options["githubApiProvider"].removeAsset(HTTP_PROJET_REPO, "${asset.id}")
                    }

                    break
                }
            }

            if (!releaseExists) {
                def release = options["githubApiProvider"].createRelease(HTTP_PROJET_REPO, env.TAG_NAME, "${env.TAG_NAME}")

                options["release_id"] = "${release.id}"
            }
        }
    }
}


def executeDeploy(Map options, List platformList, List testResultList, String engine) {
}


def call(String projectBranch = "",
    String platforms = "Windows",
    String winBuildConfiguration = "release,debug",
    String winBuildPlatform = "x64")
{
    ProblemMessageManager problemMessageManager = new ProblemMessageManager(this, currentBuild)
    Map options = [:]
    options["stage"] = "Init"
    options["problemMessageManager"] = problemMessageManager

    def nodeRetry = []

    try {
        withNotifications(options: options, configuration: NotificationConfiguration.INITIALIZATION) {
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
            """

            winBuildConfiguration = winBuildConfiguration.split(',')

            println """
                Win build configuration: ${winBuildConfiguration}"
            """

            options << [projectRepo: PROJECT_REPO,
                        projectBranch: projectBranch,
                        winBuildConfiguration: winBuildConfiguration,
                        winBuildPlatform: winBuildPlatform,
                        PRJ_NAME: "RadeonProRenderFSR2.0",
                        PRJ_ROOT: "rpr-plugins",
                        gpusCount: gpusCount,
                        nodeRetry: nodeRetry,
                        BUILDER_TAG: "BuilderFSR2.0",
                        platforms: platforms
                        ]
        }

        multiplatform_pipeline(platforms, this.&executePreBuild, this.&executeBuild, null, null, options)
    } catch(e) {
        currentBuild.result = "FAILURE"
        println(e.toString())
        println(e.getMessage())
        throw e
    } finally {
        String problemMessage = options.problemMessageManager.publishMessages()
    }
}

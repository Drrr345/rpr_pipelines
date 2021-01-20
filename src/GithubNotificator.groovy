import java.util.concurrent.atomic.AtomicBoolean
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper

/**
 * Class which manages status checks of PRs
 */
public class GithubNotificator {

    def context
    String repositoryUrl
    String commitSHA
    GithubApiProvider githubApiProvider
    List buildCases = []
    List testCases = []
    Boolean hasDeployStage
    // this variable is used for prevent multiple closing of status checks in multi thread logic of pipeline
    AtomicBoolean statusesClosed = new AtomicBoolean(false)

    /**
     * Main constructor
     *
     * @param context
     * @param repositoryUrl Url to github repository
     * @param commitSHA SHA of target commit
     */
    GithubNotificator(def context, String repositoryUrl, String commitSHA) {
        this.context = context
        // format as a http url
        this.repositoryUrl = repositoryUrl.replace("git@github.com:", "https://github.com/").replaceAll(".git\$", "")
        this.commitSHA = commitSHA
        githubApiProvider = new GithubApiProvider(context)
    }

    /**
     * Function for init status check of PreBuild stage
     *
     * @param url Build url
     */
    def initPreBuild(String url) {
        try {
            context.println("[INFO] Started initialization of notification for PreBuild stage")
            String statusTitle = "[PREBUILD] Version increment"
            githubApiProvider.createOrUpdateStatusCheck(
                repositoryUrl: repositoryUrl,
                status: "pending",
                name: statusTitle,
                head_sha: commitSHA,
                conclusion: "Status check for PreBuild stage initialized.",
                details_url: url
            )
            context.println("[INFO] Finished initialization of notification for PreBuild stage")
        } catch (e) {
            context.println("[ERROR] Failed to notification for PreBuild stage")
            context.println(e.toString())
            context.println(e.getMessage())
        }
    }

    /**
     * Function for init status checks of PR: create checks with pending status for all steps which will be executed
     *
     * @param options Options map
     * @param url Build url
     * @param hasDeployStage Specify that status for deploy stage should be created or not
     */
    def initPR(Map options, String url, Boolean hasDeployStage = true) {
        try {
            context.println("[INFO] Started initialization of PR notifications")
            this.hasDeployStage = hasDeployStage

            options.platforms.split(';').each() {
                if (it) {
                    List tokens = it.tokenize(':')
                    String osName = tokens.get(0)
                    buildCases << osName

                    String gpuNames = ""
                    if (tokens.size() > 1) {
                        gpuNames = tokens.get(1)
                    }

                    if(gpuNames) {
                        gpuNames.split(',').each() { asicName ->
                            if (options.splitTestsExecution) {
                                options.tests.each() { testName ->
                                    // check that group isn't fully skipped
                                    if (options.skippedTests && options.skippedTests.containsKey(testName) && options.skippedTests[testName].contains("${asicName}-${osName}")) {
                                        return
                                    }
                                    String parsedTestsName = testName
                                    if (testName.contains("~")) {
                                        String[] testsNameParts = parsedTestsName.split("~")
                                        String engine = ""
                                        if (testsNameParts.length == 2 && testsNameParts[1].contains("-")) {
                                            engine = testsNameParts[1].split("-")[1]
                                        }
                                        parsedTestsName = engine ? "${testsNameParts[0]}-${engine}" : "${testsNameParts[0]}"
                                        parsedTestsName = parsedTestsName.replace(".json", "")
                                    }
                                    testCases << "${asicName}-${osName}-${parsedTestsName}"
                                }
                            } else {
                                testCases << "${asicName}-${osName}"
                            }
                        }
                    }
                }
            }

            String statusTitle = ""
            for (buildCase in buildCases) {
                statusTitle = "[BUILD] ${buildCase}"
                pullRequest.createStatus("pending", statusTitle, "This stage will be executed later...", url)
            }

            for (testCase in testCases) {
                statusTitle = "[TEST] ${testCase}"
                pullRequest.createStatus("pending", statusTitle, "This stage will be executed later...", url)
            }
            if (hasDeployStage) {
                statusTitle = "[DEPLOY] Building test report"
                pullRequest.createStatus("pending", statusTitle, "This stage will be executed later...", url)
            }
            context.println("[INFO] Finished initialization of PR notifications")
        } catch (e) {
            context.println("[ERROR] Failed to initialize PR notifications")
            context.println(e.toString())
            context.println(e.getMessage())
        }

    }

    /**
     * Function for update existing status check
     * Error status check can be replaced only by other error status check
     *
     * @param stageName Name of stage
     * @param title Name of status check which should be updated
     * @param status New status of status check
     * @param options Options map
     * @param message Message of status check. If it's empty current message will be gotten
     * @param url Url of status check. If it's empty current url will be gotten
     */
    static def updateStatus(String stageName, String title, String status, Map options, String message = "", String url = "") {
        if (options.githubNotificator) {
            options.githubNotificator.updateStatusPr(stageName, title, status, options.commitSHA, message, url)
        }
    }

    private def updateStatusPr(String stageName, String title, String status, String commitSHA, String message = "", String url = "") {
        String statusTitle = "[${stageName.toUpperCase()}] ${title}"
        try {
            // prevent updating of checks by errors which are generated by aborted build
            if (commitSHA && isBuildWithSameSHA(commitSHA)) {
                context.println("[INFO] Found next build which has same SHA of target commit as this commit. Status check won't be updated")
                return
            }
            if (statusTitle.contains("~")) {
                String[] statusTitleParts = statusTitle.split("~")
                String engine = ""
                if (statusTitleParts.length == 2 && statusTitleParts[1].contains("-")) {
                    engine = statusTitleParts[1].split("-")[1]
                }
                statusTitle = engine ? "${statusTitleParts[0]}-${engine}" : "${statusTitleParts[0]}"
                statusTitle = statusTitle.replace(".json", "")
            }

            for (prStatus in pullRequest.statuses) {
                if (statusTitle == prStatus.context) {
                    if (!url) {
                        url = prStatus.targetUrl
                    }
                    if (!message) {
                        message = prStatus.description
                    }
                    break
                }
            }

            pullRequest.createStatus(status, statusTitle, message, url)
        } catch (e) {
            context.println("[ERROR] Failed to update status for ${statusTitle}")
            context.println(e.toString())
            context.println(e.getMessage())
        }
    }

    /**
     * Function for receive current status of existing status check
     *
     * @param stageName Name of stage
     * @param title Name of status check which should be updated
     * @param options Options map
     */
    static def getCurrentStatus(String stageName, String title, Map options) {
        if (options.githubNotificator) {
            options.githubNotificator.getCurrentStatusPr(stageName, title)
        }
    }

    private def getCurrentStatusPr(String stageName, String title) {
        String statusTitle = "[${stageName.toUpperCase()}] ${title}"
        try {
            for (prStatus in pullRequest.statuses) {
                if (statusTitle == prStatus.context) {
                    return prStatus.state
                }
            }
        } catch (e) {
            context.println("[ERROR] Failed to get status for ${statusTitle}")
            context.println(e.toString())
            context.println(e.getMessage())
        }
    }

    /**
     * Function for close unfinished stages (pending or failure status checks)
     * All pending and failure status checks will be marked as errored
     * It can be useful if build was aborted
     *
     * @param options Options map
     * @param message Message of status check. If it's empty current message will be gotten
     */
    static def closeUnfinishedSteps(Map options, String message = "") {
        if (options.githubNotificator) {
            options.githubNotificator.closeUnfinishedStepsPr(options.commitSHA, message)
        }
    }

    private def closeUnfinishedStepsPr(String commitSHA, String message = "") {
        try {
            if(statusesClosed.compareAndSet(false, true)) {
                if (isBuildWithSameSHA(commitSHA)) {
                    context.println("[INFO] Found next build which has same SHA of target commit as this commit. Status checks won't be closed")
                    return
                }
                //FIXME: get only first stages with each name (it's github API issue: check can't be deleted or updated)
                List stagesList = []
                stagesList << "[PREBUILD] Version increment"
                buildCases.each { stagesList << "[BUILD] " + it }
                testCases.each { stagesList << "[TEST] " + it }
                if (hasDeployStage) {
                    stagesList << "[DEPLOY] Building test report"
                }
                for (prStatus in pullRequest.statuses) {
                    if (stagesList.contains(prStatus.context)) {
                        if (prStatus.state == "pending" || prStatus.state == "failure") {
                            if (!message) {
                                message = prStatus.description
                            }
                            pullRequest.createStatus("error", prStatus.context, message, prStatus.url)
                        }
                        stagesList.remove(prStatus.context)
                    }
                    if (stagesList.size() == 0) {
                        break
                    }
                }
                context.println("[INFO] Unfinished steps were closed")
            }
        } catch (e) {
            context.println("[ERROR] Failed to close unfinished steps")
            context.println(e.toString())
            context.println(e.getMessage())
        }
    }

    private Boolean isBuildWithSameSHA(String commitSHA) {
        try {
            //check that some of next builds (if it exists) has different sha of target commit
            RunWrapper nextBuild = context.currentBuild.getNextBuild()
            while(nextBuild) {
                String nextBuildSHA = ""
                nextBuildSHA = getBuildCommit()
                //if it isn't possible to find commit SHA in description - it isn't initialized yet. Wait 1 minute
                if(!nextBuildSHA) {
                    context.sleep(60)
                }
                //if it still isn't possible to get SHA or SHAs are same - it isn't necessary to close status checks (next build will do it if it'll be necessary)
                if((nextBuild && !nextBuildSHA) || nextBuildSHA == commitSHA) {
                    return true
                }
                nextBuild = nextBuild.getNextBuild()

                return false
            }
        } catch (e) {
            context.println("[ERROR] Failed to find build with same SHA")
            context.println(e.toString())
            context.println(e.getMessage())
        }
        return false
    }

    private String getBuildCommit() {
        String commitSHA = ""
        def description = context.currentBuild.description
        String[] descriptionParts = description.split('<br/>')
        for (part in descriptionParts) {
            if (part.contains('Commit SHA')) {
                commitSHA = part.split(':')[1].replace('<b>', '').replace('</b>', '').trim()
                break
            }
        }
        return commitSHA
    }

    /**
     * Function for close status checks which represents test stages on some OS
     * It can be useful if building on some OS didn't finish successfully
     *
     * @param options Options map
     * @param osName OS name on which building failed
     */
    static def failPluginBuilding(Map options, String osName) {
        if (options.githubNotificator) {
            options.githubNotificator.failPluginBuildingPr(osName)
        }
    }

    private def failPluginBuildingPr(String osName) {
        try {
            for (prStatus in pullRequest.statuses) {
                if (prStatus.context.contains("[TEST]") && prStatus.context.contains(osName)) {
                    pullRequest.createStatus("error", prStatus.context, "Building stage was failed", prStatus.url)
                }
            }
            context.println("[INFO] Test steps were closed")
        } catch (e) {
            context.println("[ERROR] Failed to close test steps")
            context.println(e.toString())
            context.println(e.getMessage())
        }
    }

}

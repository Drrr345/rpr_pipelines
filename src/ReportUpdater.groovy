import java.util.concurrent.atomic.AtomicBoolean

/**
 * Class which provides flexible rebuilding of test reports
 */
public class ReportUpdater {

    def context
    def env
    def options

    // locks for each report (to prevent updating of report by two parallel branches of build)
    Map locks = [:]

    /**
     * Main constructor
     *
     * @param context
     * @param env env variable of the current pipeline
     * @param options map with options
     */
    ReportUpdater(context, env, options) {
        this.context = context
        this.env = env
        this.options = options
    }

    /**
     * Init function (it prepares necessary environment to rebuild reports in future)
     * 
     * @param buildArgsFunc fuction to get string with arguments for build script
     */
    def init(def buildArgsFunc) {
        String remotePath = "/volume1/web/${env.JOB_NAME}/${env.BUILD_NUMBER}/".replace(" ", "_")

        context.withCredentials([context.string(credentialsId: "nasURL", variable: "REMOTE_HOST"), context.string(credentialsId: "nasSSHPort", variable: "SSH_PORT")]) {
            context.bat('%CIS_TOOLS%\\clone_test_repo.bat' + ' %REMOTE_HOST% %SSH_PORT%' + " ${remotePath} ${options.testRepo} ${options.testsBranch} ")
        }

        String locations = ""
        String reportFiles = "summary_report.html"
        String reportFilesNames = "Overview Report"

        if (options.testProfiles) {
            options.testProfiles.each { profile ->
                String profileName

                if (options.containsKey("displayingTestProfiles")) {
                    profileName = options.displayingTestProfiles[profile]
                } else {
                    profileName = profile
                }

                String reportName = "Test Report ${profileName}"

                if (options.testProfiles.size() > 1) {
                    // publish, but do not create links to reports (they'll be accessible through overview report)
                    context.utils.publishReport(context, "${context.BUILD_URL}", "summaryTestResults", "summary_report.html, performance_report.html, compare_report.html", \
                        reportName, "Summary Report, Performance Report, Compare Report" , options.storeOnNAS, \
                        ["jenkinsBuildUrl": context.BUILD_URL, "jenkinsBuildName": context.currentBuild.displayName, "updatable": true])
                } else {
                    // pass links for builds with only one profile
                    context.utils.publishReport(context, "${context.BUILD_URL}", "summaryTestResults", "summary_report.html, performance_report.html, compare_report.html", \
                        reportName, "Summary Report, Performance Report, Compare Report" , options.storeOnNAS, \
                        ["jenkinsBuildUrl": context.BUILD_URL, "jenkinsBuildName": context.currentBuild.displayName])
                }

                String rebuiltScript = context.readFile("..\\..\\cis_tools\\update_report_template.sh")

                rebuiltScript = rebuiltScript.replace("<jobs_started_time>", options.JOB_STARTED_TIME).replace("<build_name>", options.baseBuildName) \
                    .replace("<report_name>", reportName.replace(" ", "_")).replace("<build_script_args>", buildArgsFunc(profileName, options)) \
                    .replace("<build_id>", env.BUILD_ID).replace("<job_name>", env.JOB_NAME).replace("<jenkins_url>", env.JENKINS_URL)

                // replace DOS EOF by Unix EOF
                rebuiltScript = rebuiltScript.replaceAll("\r\n", "\n")

                context.writeFile(file: "update_report_${profile}.sh", text: rebuiltScript)

                context.uploadFiles("update_report_${profile}.sh", "${remotePath}/jobs_test_repo/jobs_launcher")

                locks[profile] = new AtomicBoolean(false)

                updateReport(profile, false)

                String publishedReportName = reportName.replace(" ", "_")
                locations = locations ? "${locations}::${publishedReportName}" : "${publishedReportName}"

                reportFiles += ",../${reportName.replace(' ', '_')}/summary_report.html"
                reportFilesNames += ",${profileName} Report"
            }

            // do not build an overview report for builds with only one profile
            if (options.testProfiles.size() > 1) {
                context.println("[INFO] Publish overview report")

                // add overview report
                String reportName = "Test Report"

                context.utils.publishReport(context, "${context.BUILD_URL}", "summaryTestResults", reportFiles, \
                    reportName, reportFilesNames, options.storeOnNAS, \
                    ["jenkinsBuildUrl": context.BUILD_URL, "jenkinsBuildName": context.currentBuild.displayName])

                String rebuiltScript = context.readFile("..\\..\\cis_tools\\update_overview_report_template.sh")
                // take only first 4 arguments: tool name, commit sha, project branch name and commit message
                String buildScriptArgs = (buildArgsFunc("", options ).split() as List).subList(0, 4).join(" ")

                rebuiltScript = rebuiltScript.replace("<jobs_started_time>", options.JOB_STARTED_TIME).replace("<build_name>", options.baseBuildName) \
                    .replace("<report_name>", reportName.replace(" ", "_")).replace("<locations>", locations).replace("<build_script_args>", buildScriptArgs) \
                    .replace("<build_id>", env.BUILD_ID).replace("<job_name>", env.JOB_NAME).replace("<jenkins_url>", env.JENKINS_URL).replace("<credentials>", "none")

                // replace DOS EOF by Unix EOF
                rebuiltScript = rebuiltScript.replaceAll("\r\n", "\n")

                context.writeFile(file: "update_report.sh", text: rebuiltScript)

                context.uploadFiles("update_report.sh", "${remotePath}/jobs_test_repo/jobs_launcher")

                locks["default"] = new AtomicBoolean(false)

                updateReport()
            }
        } else {
            String reportName = "Test Report"

            context.utils.publishReport(context, "${context.BUILD_URL}", "summaryTestResults", "summary_report.html, performance_report.html, compare_report.html", \
                reportName, "Summary Report, Performance Report, Compare Report" , options.storeOnNAS, \
                ["jenkinsBuildUrl": context.BUILD_URL, "jenkinsBuildName": context.currentBuild.displayName])

            String rebuiltScript = context.readFile("..\\..\\cis_tools\\update_report_template.sh")

            rebuiltScript = rebuiltScript.replace("<jobs_started_time>", options.JOB_STARTED_TIME).replace("<build_name>", options.baseBuildName) \
                .replace("<report_name>", reportName.replace(" ", "_")).replace("<build_script_args>", buildArgsFunc(options)) \
                .replace("<build_id>", env.BUILD_ID).replace("<job_name>", env.JOB_NAME).replace("<jenkins_url>", env.JENKINS_URL)

            // replace DOS EOF by Unix EOF
            rebuiltScript = rebuiltScript.replaceAll("\r\n", "\n")

            context.writeFile(file: "update_report.sh", text: rebuiltScript)

            context.uploadFiles("update_report.sh", "${remotePath}/jobs_test_repo/jobs_launcher")

            locks["default"] = new AtomicBoolean(false)

            updateReport()
        }
    }

    /**
     * Function to update report
     * 
     * @param profile (optional) profile of the target report (if project supports splitting by profiles)
     * @param updateOverviewReport (optional) specify should overview report be updated or not (default - true). For builds with only one profile this param is ignored
     */
    def updateReport(String profile = "", Boolean updateOverviewReport = true) {
        String lockKey = profile ?: "default"
        String remotePath = "/volume1/web/${env.JOB_NAME}/${env.BUILD_NUMBER}/jobs_test_repo/jobs_launcher".replace(" ", "_")

        try {
            if (locks[lockKey].compareAndSet(false, true)) {
                String scriptName = profile ? "update_report_${profile}.sh" : "update_report.sh"

                context.withCredentials([context.string(credentialsId: "nasURL", variable: "REMOTE_HOST"), context.string(credentialsId: "nasSSHPort", variable: "SSH_PORT")]) {
                    if (context.isUnix()) {
                        context.sh(script: '$CIS_TOOLS/update_report.sh' + ' $REMOTE_HOST $SSH_PORT' + " ${remotePath} ${scriptName}")
                    } else {
                        context.bat(script: '%CIS_TOOLS%\\update_report.bat' + ' %REMOTE_HOST% %SSH_PORT%' + " ${remotePath} ${scriptName}")
                    }
                }

                locks[lockKey].getAndSet(false)
            } else {
                context.println("[INFO] Report update skipped")
            }
        } catch (e) {
            context.println("[ERROR] Can't update test report")
            context.println(e.toString())
            context.println(e.getMessage())

            locks[lockKey].getAndSet(false)
        }

        if (profile && options.testProfiles.size() > 1 && updateOverviewReport) {
            // update overview report
            updateReport()
        }
    }

}

import groovy.json.JsonOutput

class SlackUtils {

    private static final String AUTHOR_NAME = 'Jenkins'
    private static final String AUTHOR_ICON = 'https://cdn.icon-icons.com/icons2/2107/PNG/64/file_type_jenkins_icon_130515.png'

    // TODO: add other workspaces
    static enum SlackWorkspace {
        // FIXME: get rprlux.slack.com & baikal webhooks
        DEFAULT('rprlux.slack.com'), LUXCIS('cisAppSlackWebhook'), BAIKAL('baikal')

        private final String webhookCredential
  
        private SlackWorkspace(String webhookCredentialName) {
            webhookCredential = webhookCredentialName
        }
        
        String getCredential() {
            return webhookCredential
        }
    }

    static enum Color {
        RED('#fc0356'), GREEN('#2EFF2E'), LIGHT_YELLOW('#f4f4c8'), LIGHT_GREY('#d3d3d3'), ORANGE('#ff8833'), BLUE('#0000ff')

        private final String colorCode
  
        private Color(String code) {
            colorCode = code
        }
        
        String getCode() {
            return colorCode
        }
    }

    static def sendAttachments(def context, def attachments, SlackWorkspace workspace, String channel) {
        def slackMessage = [
            attachments: attachments,
            channel: channel
        ]
        
        context.withCredentials([context.string(credentialsId: workspace.getCredential(), variable: 'WEBHOOK_URL')]) {
            context.httpRequest(
                url: context.WEBHOOK_URL,
                httpMode: 'POST',
                requestBody: JsonOutput.toJson(slackMessage)
            )
        }
    }

    static def sendMessageToWorkspaceChannel(def context, String pretext, String text, Color color, SlackWorkspace workspace, String channel) {
        String messageTitle = "<${context.env.BUILD_URL}|${context.env.JOB_NAME} [${context.env.BUILD_NUMBER}]>"

        def attachments = [[
                "author_name": AUTHOR_NAME,
                "author_icon": AUTHOR_ICON,
                "color": color.getCode(),
                "text": "${pretext}\n${messageTitle}\n${text}"
            ]]

        try {
            sendAttachments(context, attachments, workspace, channel)
        } catch (e) {
            context.println("[WARNING] Could not send message to slack")
        }
    }

    static def sendBuildStatusToDebugChannel(def context, Map options) {
        if (context.currentBuild.result == "FAILURE") {

            String text 
            if (options["problemMessageManager"]) {
                text = options["problemMessageManager"].getMessages()
            } else {
                text = "Failed in:  ${options.FAILED_STAGES.join('\n')}"
            }

            try {
                if ((context.env.BRANCH_NAME && context.env.BRANCH_NAME == "master") || context.env.CHANGE_BRANCH || context.env.JOB_NAME.contains("Weekly")) {
                    sendMessageToWorkspaceChannel(context, context.currentBuild.result, text, Color.RED, SlackWorkspace.LUXCIS, "cis_failed_master")
                } else {
                    sendMessageToWorkspaceChannel(context, context.currentBuild.result, text, Color.RED, SlackWorkspace.LUXCIS, context.env.debagChannel)
                }
            } catch (e) {
                context.println("[WARNING] Error during slack notification to debug channel")
                context.println(e.toString())
            }
        }
    }

    static def sendBuildStatusNotification(def context, String buildStatus = 'STARTED', SlackWorkspace workspace, String channel, Map options) {
        context.println("Sending information about build status: ${buildStatus}")

        // build status of null means successful
        buildStatus =  buildStatus ?: 'SUCCESSFUL'
        buildStatus = options.CBR ?: buildStatus
        options.commitMessage = options.commitMessage ?: 'undefined'
        String BRANCH_NAME = context.env.BRANCH_NAME ?: options.projectBranch

        Color color

        switch(buildStatus) {
            case 'SUCCESSFUL':
                color = Color.GREEN
                break
            case 'ABORTED':
                color = Color.ORANGE
                break
            case 'SKIPPED':
                color = Color.BLUE
                break
            case 'UNSTABLE':
                color = Color.LIGHT_YELLOW
                break
            default:
                color = Color.RED
                break
        }

        // if env.CHANGE_BRANCH not empty display pull request link
        String INIT_BRANCH = context.env.CHANGE_BRANCH ? "\nSource branch: *${context.env.CHANGE_BRANCH}*" : ''
        // if reportName not empty display link to html report
        String HTML_REPORT_LINK = options.reportName ? "${context.env.BUILD_URL}${options.reportName}" : ''

        List reports = []
        if (options.containsKey("testProfiles")) {
            options.testProfiles.each { profile ->
                reports << "${profile}"
            }
        } else {
            if (options['testsStatus']) {
                reports << ""
            }
        }
        
        def attachments = [[
            "fallback": "${buildStatus} ${context.env.JOB_NAME}",
            "title": "${buildStatus}\nCIS: ${context.env.JOB_NAME} [${context.env.BUILD_NUMBER}]",
            "title_link": "${context.env.BUILD_URL}",
            "color": "${color.getCode()}",
            "text": ">>> Branch: *${BRANCH_NAME}*${INIT_BRANCH}\nAuthor: *${options.AUTHOR_NAME}*\nCommit message:\n```${context.utils.escapeCharsByUnicode(options.commitMessage)}```",
            "mrkdwn_in": ["text", "title"],
            "attachment_type": "default",
            "actions": [[
                "text": "PullRequest on GitHub",
                "type": "button",
                "url": "${context.env.CHANGE_URL}"
            ]]
        ]]

        for (int i = 0; i < reports.size(); i++) {
            String pretext

            if (reports[i]) {
                pretext = options.containsKey("displayingProfilesMapping") ? options["displayingProfilesMapping"][reports[i]] : ""
            } else {
                pretext = ""
            }

            String text = reports[i] ? options['testsStatus-' + reports[i]] : options['testsStatus']

            attachments << [
                "mrkdwn_in": ["text"],
                "title": "Brief info",
                "pretext": "AutoTests Results ${pretext}",
                "text": text.replace('"', '').replace('\\n', '\n'),
                "footer": "LUX CIS",
                "actions": [[
                    "text": "Report",
                    "type": "button",
                    "url": "${HTML_REPORT_LINK}"
                ]]
            ]
        }

        try {
            sendAttachments(context, attachments, workspace, channel)
        } catch (e) {
            context.println("[WARNING] Error during slack notification to project channel")
            context.println(e.toString())
        }
    }
}
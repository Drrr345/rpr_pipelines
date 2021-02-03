import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper
import hudson.model.Result
import groovy.json.JsonOutput

/**
 * self in methods params is a context of executable pipeline. Without it you can't call Jenkins methods.
 */
class utils {

    static int getTimeoutFromXML(Object self, String test, String keyword, Integer additional_xml_timeout) {
        try {
            String xml = self.readFile("jobs/Tests/${test}/test.job-manifest.xml")
            for (xml_string in xml.split("<")) {
                if (xml_string.contains("${keyword}") && xml_string.contains("timeout")) {
                    Integer xml_timeout = (Math.round((xml_string.findAll(/\d+/)[0] as Double).div(60)) + additional_xml_timeout)
                    return xml_timeout
                }
            }
        } catch (e) {
            self.println(e)
            return -1
        }
        return -1
    }

    static def setForciblyBuildResult(RunWrapper currentBuild, String buildResult) {
        currentBuild.build().@result = Result.fromString(buildResult)
    }

    static def isTimeoutExceeded(Exception e) {
        Boolean result = false
        String exceptionClassName = e.getClass().toString()
        if (exceptionClassName.contains("FlowInterruptedException")) {
            //sometimes FlowInterruptedException generated by 'timeout' block doesn't contain exception cause
            if (!e.getCause()) {
                result = true
            } else {
                for (cause in e.getCauses()) {
                    String causeClassName = cause.getClass().toString()
                    if (causeClassName.contains("ExceededTimeout") || causeClassName.contains("TimeoutStepExecution")) {
                        result = true
                        break
                    }
                }
            }
        }
        return result
    }

    static def markNodeOffline(Object self, String nodeName, String offlineMessage) {
        try {
            def nodes = jenkins.model.Jenkins.instance.getLabel(nodeName).getNodes()
            nodes[0].getComputer().doToggleOffline(offlineMessage)
            self.println("[INFO] Node '${nodeName}' was marked as failed")
        } catch (e) {
            self.println("[ERROR] Failed to mark node '${nodeName}' as offline")
            self.println(e)
            throw e
        }
    }

    static def sendExceptionToSlack(Object self, String jobName, String buildNumber, String buildUrl, String webhook, String channel, String message) {
        try {
            def slackMessage = [
                attachments: [[
                    "title": "${jobName} [${buildNumber}]",
                    "title_link": "${buildUrl}",
                    "color": "#720000",
                    "text": message
                ]],
                channel: channel
            ]
            self.httpRequest(
                url: webhook,
                httpMode: 'POST',
                requestBody: JsonOutput.toJson(slackMessage)
            )
            self.println("[INFO] Exception was sent to Slack")
        } catch (e) {
            self.println("[ERROR] Failed to send exception to Slack")
            self.println(e)
        }
    }

    static def publishReport(Object self, String buildUrl, String reportDir, String reportFiles, String reportName, String reportTitles = "") {
        Map params = [allowMissing: false,
                     alwaysLinkToLastBuild: false,
                     keepAll: true,
                     reportDir: reportDir,
                     reportFiles: reportFiles,
                     // TODO: custom reportName (issues with escaping)
                     reportName: reportName]
        if (reportTitles) {
            params['reportTitles'] = reportTitles
        }
        self.publishHTML(params)
        try {
            self.httpRequest(
                url: "${buildUrl}/${reportName.replace('_', '_5f').replace(' ', '_20')}/",
                authentication: 'jenkinsCredentials',
                httpMode: 'GET'
            )
            self.println("[INFO] Report exists.")
        } catch(e) {
            self.println("[ERROR] Can't access report")
            throw new Exception("Can't access report", e)
        }
    }

    static def deepcopyCollection(Object self, def collection) {
        def bos = new ByteArrayOutputStream()
        def oos = new ObjectOutputStream(bos)
        oos.writeObject(collection)
        oos.flush()
        def bin = new ByteArrayInputStream(bos.toByteArray())
        def ois = new ObjectInputStream(bin)
        return ois.readObject()
    }

    static def getReportFailReason(String exceptionMessage) {
        if (!exceptionMessage) {
            return "Failed to build report."
        }
        String[] messageParts = exceptionMessage.split(" ")
        Integer exitCode = messageParts[messageParts.length - 1].isInteger() ? messageParts[messageParts.length - 1].toInteger() : null

        if (exitCode && exitCode < 0) {
            switch(exitCode) {
                case -1:
                    return "Failed to build summary report."
                    break
                case -2:
                    return "Failed to build performance report."
                    break
                case -3:
                    return "Failed to build compare report."
                    break
                case -4:
                    return "Failed to build local reports."
                    break
                case -5:
                    return "Several plugin versions"
                    break
            }
        }
        return "Failed to build report."
    }

    static def getTestsFromCommitMessage(String commitMessage) {
        String[] messageParts = commitMessage.split("\n")
        for (part in messageParts) {
            if (part.contains("CIS TESTS:")) {
                String testsRow = part.replace("CIS TESTS:", "").trim()
                // split by ';', ',' or space characters
                String[] tests = testsRow.split("\\s*\\;\\s*|\\s*\\,\\s*|\\s+")
                return tests.join(" ")
            }
        }
        return ""
    }

    static def isReportFailCritical(String exceptionMessage) {
        if (!exceptionMessage) {
            return true
        }
        String[] messageParts = exceptionMessage.split(" ")
        Integer exitCode = messageParts[messageParts.length - 1].isInteger() ? messageParts[messageParts.length - 1].toInteger() : null

        // Unexpected fails
        return exitCode >= 0
    }

    static def renameFile(Object self, String osName, String oldName, String newName) {
        try {
            switch(osName) {
                case 'Windows':
                    self.bat """
                        rename \"${oldName}\" \"${newName}\"
                    """
                    break
                // OSX & Ubuntu18
                default:
                    self.sh """
                        mv ${oldName} ${newName}
                    """
            }
        } catch(Exception e) {
            self.println("[ERROR] Can't rename file")
            self.println(e.toString())
            self.println(e.getMessage())
        }
    }

    static def moveFiles(Object self, String osName, String source, String destination) {
        try {
            switch(osName) {
                case 'Windows':
                    source = source.replace('/', '\\\\')
                    destination = destination.replace('/', '\\\\')
                    self.bat """
                        move \"${source}\" \"${destination}\"
                    """
                    break
                // OSX & Ubuntu18
                default:
                    self.sh """
                        mv ${source} ${destination}
                    """
            }
        } catch(Exception e) {
            self.println("[ERROR] Can't move files")
            self.println(e.toString())
            self.println(e.getMessage())
        }
    }

    static def removeFile(Object self, String osName, String fileName) {
        try {
            switch(osName) {
                case 'Windows':
                    self.bat """
                        if exist \"${fileName}\" del \"${fileName}\"
                    """
                    break
                // OSX & Ubuntu18
                default:
                    self.sh """
                        rm -rf \"${fileName}\"
                    """
            }
        } catch(Exception e) {
            self.println("[ERROR] Can't remove file")
            self.println(e.toString())
            self.println(e.getMessage())
        }
    }

    @NonCPS
    static def parseJson(Object self, String jsonString) {
        try {
            def jsonSlurper = new groovy.json.JsonSlurperClassic()

            return jsonSlurper.parseText(jsonString)
        } catch(Exception e) {
            self.println("[ERROR] Can't parse JSON. Inputted value: " + jsonString)
            self.println(e.toString())
            self.println(e.getMessage())
        }
        return ""
    }

    /**
     * Download file using curl from given link
     * @param filelink - full url to file
     * @param outputDir - path to the directory where the file will be downloaded (default is current dir)
     * @param credentialsId - custom Jenkins credentials
     * @param extraParams - map with additional curl param, where keys and values are equivalent for curl
     * @return relative path to downloaded file
     */
    static String downloadFile(Object self, String filelink, String outputDir = "./", String credentialsId = "", Map extraParams = [:]) {
        String filename = filelink.split("/").last()
        String command = "curl -L -o ${outputDir}${filename} "
        if (credentialsId)
            self.withCredentials([self.usernamePassword(credentialsId: credentialsId, usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
                command += "-u ${self.USERNAME}:${self.PASSWORD} "
            }
        if (extraParams) {
            extraParams.each { command += "-${it.key} ${it.value} " }
        }
        command += "${filelink}"
        if (self.isUnix()) {
            self.sh command
        } else {
            self.bat command
        }
        return outputDir + filename
    }

    /**
     * Compares 2 images
     * @param img1 - path to first image
     * @param img2 - path to second image
     * @return percentage difference of images (0 - similar, 100 - different)
     */
    static Double compareImages(Object self, String img1, String img2) {
        return self.python3("./jobs_launcher/common/scripts/CompareMetrics.py --img1 ${img1} --img2 ${img2}").with {
            (self.isUnix() ? it : it.split(" ").last()) as Double
        }
    }
}
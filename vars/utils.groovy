import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper
import hudson.model.Result
import groovy.json.JsonOutput
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

/**
 * self in methods params is a context of executable pipeline. Without it you can't call Jenkins methods.
 */
class utils {
    
    static def updateDriver(options, osName, computer){
        timeout(time: "60", unit: "MINUTES") {
            try {
                DRIVER_PAGE_URL = "https://www.amd.com/en/support/graphics/amd-radeon-6000-series/amd-radeon-6800-series/amd-radeon-rx-6800-xt"
                OLDER_DRIVER_PAGE_URL = "https://www.amd.com/en/support/previous-drivers/graphics/amd-radeon-6000-series/amd-radeon-6800-series/amd-radeon-rx-6800-xt"

                cleanWS()
                def status, driver_path

                switch(osName) {
                    case "Windows":
                        driver_path = "C:\\AMD\\driver\\"
                        bat "${CIS_TOOLS}\\driver_detection\\amd_request.bat \"${DRIVER_PAGE_URL}\" ${env.WORKSPACE}\\page.html >> page_download_${computer}.log 2>&1 "
                        bat "${CIS_TOOLS}\\driver_detection\\amd_request.bat \"${OLDER_DRIVER_PAGE_URL}\" ${env.WORKSPACE}\\older_page.html >> older_page_download_${computer}.log 2>&1 "

                        withEnv(["PATH=c:\\python39\\;c:\\python39\\scripts\\;${PATH}"]) {
                            python3("-m pip install -r ${CIS_TOOLS}\\driver_detection\\requirements.txt >> parse_stage_${computer}.log 2>&1")
                            status = bat(returnStatus: true, script: "python ${CIS_TOOLS}\\driver_detection\\parse_driver.py --os win --html_path ${env.WORKSPACE}\\page.html \
                                --installer_dst ${env.WORKSPACE}\\driver.exe --win_driver_path ${driver_path} --driver_version ${options.driverVersion} --older_html_path ${env.WORKSPACE}\\older_page.html >> parse_stage_${computer}.log 2>&1")
                            if (status == 0) {
                                println("[INFO] ${options.driverVersion} driver was found. Trying to install...")
                                bat "${driver_path}\\Setup.exe -INSTALL -BOOT -LOG ${WORKSPACE}\\installation_result_${computer}.log"
                            }
                        }
                        break
                    case "Ubuntu20":
                        driver_path = "${env.WORKSPACE}/amdgpu-install.deb"
                        sh "${CIS_TOOLS}/driver_detection/amd_request.sh \"${DRIVER_PAGE_URL}\" ${env.WORKSPACE}/page.html >> page_download_${computer}.log 2>&1 "

                        python3("-m pip install -r ${CIS_TOOLS}/driver_detection/requirements.txt >> parse_stage_${computer}.log 2>&1")
                        status = sh(returnStatus: true, script: "python3.9 ${CIS_TOOLS}/driver_detection/parse_driver.py --os ubuntu20 --html_path ${env.WORKSPACE}/page.html --installer_dst ${driver_path} >> parse_stage_${computer}.log 2>&1")
                        if (status == 0) {
                            println("[INFO] Newer driver was found. Uninstalling previous driver...")
                            sh "sudo amdgpu-install -y --uninstall >> uninstallation_${computer}.log 2>&1"

                            println("[INFO] Driver uninstalled. Reboot ${computer}...")
                            utils.reboot(this, "Unix")
                            
                            sh "sudo apt-get purge -y amdgpu-install >> uninstallation_${computer}.log 2>&1"

                            println("[INFO] Trying to install new driver...")
                            sh """
                                sudo apt-get install -y ${driver_path} >> installation_${computer}.log 2>&1 && \
                                sudo amdgpu-install --usecase=workstation -y --vulkan=pro --opencl=rocr,legacy --accept-eula >> installation_${computer}.log 2>&1 \
                            """
                        }
                        break
                    default:
                        println "[WARNING] ${osName} is not supported"
                }
                switch(status) {
                    case 0:
                        println("[INFO] ${options.driverVersion} driver was installed")
                        newerDriverInstalled = true
                        utils.reboot(this, isUnix() ? "Unix" : "Windows")
                        break
                    case 1:
                        throw new Exception("Error during parsing stage")
                        break
                    case 404:
                        println("[INFO] ${options.driverVersion} driver not found")
                        break
                    default:
                        throw new Exception("Unknown exit code")
                }
            } catch(e) {
                println(e.toString());
                println(e.getMessage());
                currentBuild.result = "FAILURE";
            } finally {
                archiveArtifacts "*.log, *.LOG"
            }
        }
    }

    static int getTimeoutFromXML(Object self, String tests, String keyword, Integer additional_xml_timeout) {
        try {
            Integer xml_timeout = 0
            for (test in tests.split()) {
                String xml = self.readFile("jobs/Tests/${test}/test.job-manifest.xml")
                for (xml_string in xml.split("<")) {
                    if (xml_string.contains("${keyword}") && xml_string.contains("timeout")) {
                        xml_timeout += Math.round((xml_string.findAll(/\d+/)[0] as Double).div(60))
                    }
                }
            }
            
            return xml_timeout + additional_xml_timeout
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

    static def stashTestData(Object self, Map options, Boolean publishOnNAS = false, String excludes = "") {
        if (publishOnNAS) {
            String profile = ""
            String stashName = ""
            String reportName = ""
            List testsResultsParts = options.testResultsName.split("-") as List
            if (options.containsKey("testProfiles")) {
                profile = testsResultsParts[-1]
                // Remove "testResult" prefix and profile from stash name
                stashName = testsResultsParts.subList(1, testsResultsParts.size() - 1).join("-")
            } else {
                // Remove "testResult" prefix from stash name
                stashName = testsResultsParts.subList(1, testsResultsParts.size()).join("-")
            }

            if (options.containsKey("testProfiles")) {
                String profileName = options.containsKey("displayingTestProfiles") ? options.displayingTestProfiles[profile] : profile
                reportName = "Test_Report_${profileName}"
            } else {
                reportName = "Test_Report"
            }

            String path = "/volume1/web/${self.env.JOB_NAME}/${self.env.BUILD_NUMBER}/${reportName}/${stashName}/"
            self.makeStash(includes: '**/*', excludes: excludes, name: stashName, allowEmpty: true, customLocation: path, preZip: true, postUnzip: true, storeOnNAS: true)
            self.makeStash(includes: '*.json', excludes: '*/events/*.json', name: options.testResultsName, allowEmpty: true, storeOnNAS: true)
        } else {
            self.makeStash(includes: '**/*', excludes: excludes, name: options.testResultsName, allowEmpty: true)
        }
    }

    static String getPublishedReportName(Object self, String defaultReportName) {
        return defaultReportName.replace("_", "_5f").replace(" ", "_20")
    }

    static Integer getBuildPriority(Object self) {
        if (self.env.JOB_NAME.contains('Auto/') || self.env.JOB_NAME.contains('-Hybrid/')) {
            if (self.env.JOB_NAME.contains("USDViewer") || self.env.JOB_NAME.contains("InventorPluginInstaller")) {
                return 20
            } else if (self.env.JOB_NAME.contains("Core")) {
                return 9
            } else {
                return 30
            }
        } else if (self.env.JOB_NAME.contains("Weekly")) {
            return 30
        } else {
            return 40
        }
    }

    static def publishReport(Object self, String buildUrl, String reportDir, String reportFiles, String reportName, String reportTitles = "", Boolean publishOnNAS = false, Map nasReportInfo = [:]) {
        Map params

        String redirectReportName = "redirect_report.html"
        String wrapperReportName = "test_report.html"

        if (publishOnNAS) {
            String remotePath = "/volume1/web/${self.env.JOB_NAME}/${self.env.BUILD_NUMBER}/${reportName}/".replace(" ", "_")

            String reportLinkBase
            String authReportLinkBase

            self.withCredentials([self.string(credentialsId: "nasURL", variable: "REMOTE_HOST"),
                self.string(credentialsId: "nasURLFrontend", variable: "REMOTE_URL")]) {
                reportLinkBase = self.REMOTE_URL
            }

            authReportLinkBase = "${reportLinkBase}/${self.env.JOB_NAME}/${self.env.BUILD_NUMBER}/${reportName}/".replace(" ", "_")

            def links = []
            def linksTitles

            reportFiles.split(",").each { reportFile ->
                links << "${authReportLinkBase}${reportFile.trim()}"
            }

            links = links.join(",")

            if (reportTitles) {
                linksTitles = reportTitles
            } else {
                linksTitles = reportFiles
            }

            String jenkinsBuildUrl = ""
            String jenkinsBuildName = "Test report"

            if (nasReportInfo.containsKey("jenkinsBuildUrl")) {
                jenkinsBuildUrl = nasReportInfo["jenkinsBuildUrl"]
            }

            // TODO: jenkinsBuildName param is legacy and must be removed
            jenkinsBuildName = "#${self.env.BUILD_NUMBER} (Priority: ${getBuildPriority(self)})"

            self.dir(reportDir) {
                if (self.isUnix()) {
                    // copy the necessary font file
                    self.sh(script: 'cp $CIS_TOOLS/templates/Klavika-Regular.ttf Klavika-Regular.ttf')
                    self.sh(script: '$CIS_TOOLS/make_wrapper_page.sh ' + " \"${jenkinsBuildUrl}\" \"${jenkinsBuildName}\" \"${links}\" \"${linksTitles}\" \"${reportName}\" \".\" \"${wrapperReportName}\"")
                } else {
                    // copy the necessary font file
                    self.bat(script: 'copy %CIS_TOOLS%\\templates\\Klavika-Regular.ttf Klavika-Regular.ttf')
                    self.bat(script: '%CIS_TOOLS%\\make_wrapper_page.bat ' + " \"${jenkinsBuildUrl}\" \"${jenkinsBuildName}\" \"${links}\" \"${linksTitles}\" \"${reportName}\" \".\" \"${wrapperReportName}\"")
                }
            }

            self.dir(reportDir) {
                // upload report to NAS in archive and unzip it
                if (self.isUnix()) {
                    self.makeStash(includes: '*', name: "report", allowEmpty: true, customLocation: remotePath, preZip: true, postUnzip: true, storeOnNAS: true)
                } else {
                    self.makeStash(includes: '**/*', name: "report", allowEmpty: true, customLocation: remotePath, preZip: true, postUnzip: true, storeOnNAS: true)
                }
            }
            
            self.dir("redirect_links") {
                if (self.isUnix()) {
                    self.sh(script: '$CIS_TOOLS/make_redirect_page.sh ' + " \"${authReportLinkBase}${wrapperReportName}\" \".\" \"${redirectReportName}\"")
                } else {
                    self.bat(script: '%CIS_TOOLS%\\make_redirect_page.bat ' + " \"${authReportLinkBase}${wrapperReportName}\"  \".\" \"${redirectReportName}\"")
                }
            }
            
            def updateReportFiles = []
            reportFiles.split(",").each() { reportFile ->
                updateReportFiles << reportFile.trim().replace("/", "_")
            }
            
            updateReportFiles = updateReportFiles.join(", ")

            params = [allowMissing: false,
                          alwaysLinkToLastBuild: false,
                          keepAll: true,
                          reportDir: "redirect_links",
                          reportFiles: redirectReportName,
                          // TODO: custom reportName (issues with escaping)
                          reportName: reportName]
        } else {
            params = [allowMissing: false,
                          alwaysLinkToLastBuild: false,
                          keepAll: true,
                          reportDir: reportDir,
                          reportFiles: reportFiles,
                          // TODO: custom reportName (issues with escaping)
                          reportName: reportName]

            if (reportTitles) {
                params['reportTitles'] = reportTitles
            }
        }

        if (!nasReportInfo.containsKey("updatable") || !nasReportInfo["updatable"]) {
            self.publishHTML(params)
            /*try {
                self.httpRequest(
                    url: "${buildUrl}/${reportName.replace('_', '_5f').replace(' ', '_20')}/",
                    authentication: 'jenkinsCredentials',
                    httpMode: 'GET'
                )
                self.println("[INFO] Report exists.")
            } catch(e) {
                self.println("[ERROR] Can't access report")
                throw new Exception("Can't access report", e)
            }*/
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
                // OSX & Ubuntu
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
                // OSX & Ubuntu
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

    static def copyFile(Object self, String osName, String source, String destination) {
        try {
            switch(osName) {
                case 'Windows':
                    source = source.replace('/', '\\')
                    destination = destination.replace('/', '\\')
                    self.bat """
                        echo F | xcopy /s/y/i \"${source}\" \"${destination}\"
                    """
                    break
                // OSX & Ubuntu
                default:
                    self.sh """
                        cp ${source} ${destination}
                    """
            }
        } catch(Exception e) {
            self.println("[ERROR] Can't copy files")
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
                // OSX & Ubuntu
                default:
                    if (fileName.contains(" ")) {
                        self.sh """
                            rm -rf \"${fileName}\"
                        """
                    } else {
                        self.sh """
                            rm -rf ${fileName}
                        """
                    }
            }
        } catch(Exception e) {
            self.println("[ERROR] Can't remove file")
            self.println(e.toString())
            self.println(e.getMessage())
        }
    }

    //TODO unite with removeFile function
    static def removeDir(Object self, String osName, String dirName) {
        try {
            switch(osName) {
                case 'Windows':
                    self.bat """
                        if exist \"${dirName}\" rmdir /Q /S \"${dirName}\"
                    """
                    break
                // OSX & Ubuntu
                default:
                    self.sh """
                        rm -rf \"${dirName}\"
                    """
            }
        } catch(Exception e) {
            self.println("[ERROR] Can't remove directory")
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
        extraParams?.each { command += "-${it.key} ${it.value} " }
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

    static String escapeCharsByUnicode(String text) {
        def unsafeCharsRegex = /['"\\&$ <>|:\n\t\r]/

        return text.replaceAll(unsafeCharsRegex, {
            "\\u${Integer.toHexString(it.codePointAt(0)).padLeft(4, '0')}"
        })
    }

    static String incrementVersion(Map params) {
        Object self = params["self"]
        String currentVersion = params["currentVersion"]
        Integer index = params["index"] ?: 1
        String delimiter = params["delimiter"] ?: "\\."

        String[] indexes = currentVersion.split(delimiter)
        Integer targetIndex = (indexes[index - 1] as Integer) + 1
        indexes[index - 1] = targetIndex as String

        return indexes.join(delimiter.replace("\\", ""))
    }

    /**
     * Unite test suites to optimize execution of small suites
     * @param weightsFile - path to file with weight of each suite
     * @param suites - List of suites which will be executed during build
     * @param maxWeight - maximum summary weight of united suites
     * @param maxLength - maximum lenght of string with name of each united suite (it's necessary for prevent issues with to log path lengts on Deploy stage)
     * @return Return List of String objects (each string contains united suites in one run). Return suites argument if some exception appears
     */
    static List uniteSuites(Object self, String weightsFile, List suites, Integer maxWeight=3600, Integer maxLength=40) {
        List unitedSuites = []

        try {
            def weights = self.readJSON(file: weightsFile)
            List suitesLeft = suites.clone()
            weights["weights"].removeAll {
                !suites.contains(it["suite_name"])
            }
            while (weights["weights"]) {
                List buffer = []
                Integer currentLength = 0
                Integer currentWeight = 0
                for (suite in weights["weights"]) {
                    if (currentWeight == 0 || (currentWeight + suite["value"] <= maxWeight)) {
                        buffer << suite["suite_name"]
                        currentWeight += suite["value"]
                        currentLength += suite["suite_name"].length() + 1
                        if (currentLength > maxLength) {
                            break
                        }
                    }
                }
                weights["weights"].removeAll {
                    buffer.contains(it["suite_name"])
                }
                suitesLeft.removeAll {
                    buffer.contains(it)
                }
                unitedSuites << buffer.join(" ")
            }

            // add split suites which doesn't have information about their weight
            unitedSuites.addAll(suitesLeft)

            return unitedSuites
        } catch (e) {
            self.println("[ERROR] Can't unit test suites")
            self.println(e.toString())
            self.println(e.getMessage())
        }
        return suites
    }

    /**
     * @param command - executable command
     * @return clear bat stdout without original command
     */
    static String getBatOutput(Object self, String command) {
        return self.bat(script: "@${command}", returnStdout: true).trim()
    }

    /**
     * Reboot current node
     * @param osName - OS name of current node
     */
    static def reboot(Object self, String osName) {
        try {
            String nodeName = self.env.NODE_NAME

            switch(osName) {
                case "Windows":
                    self.bat """
                        shutdown /r /f /t 2
                    """
                    break
                case "OSX":
                    self.sh """
                        (sleep 2; sudo reboot) &
                    """
                // Ubuntu
                default:
                    self.sh """
                        (sleep 2; sudo reboot) &
                    """
            }

            while (true) {
                // some nodes can fail any action after reboot
                try {
                    self.sleep(15)
                    List nodesList = self.nodesByLabel(label: nodeName, offline: false)
                    while (nodesList.size() == 0) {
                        self.sleep(15)
                        nodesList = self.nodesByLabel(label: nodeName, offline: false)
                    }

                    self.println("[INFO] Node is available")

                    break
                } catch (FlowInterruptedException e) {
                    throw e
                } catch (Exception e) {
                    //do nothing
                }
            }
        } catch (FlowInterruptedException e) {
            throw e
        } catch (Exception e) {
            self.println("[ERROR] Failed to reboot machine")
            self.println(e.toString())
            self.println(e.getMessage())
        }
    }

    @NonCPS
    static Boolean isNodeIdle(String nodeName) {
        return jenkins.model.Jenkins.instance.getNode(nodeName).getComputer().countIdle() > 0
    }

    static def downloadMetrics(Object self, String localDir, String remoteDir) {
        try {
            self.dir(localDir) {
                self.downloadFiles("${remoteDir}", ".")
            }
        } catch (e) {
            self.println("[WARNING] Failed to download history of tracked metrics.")
            self.println(e.toString())
            self.println(e.getMessage())
        }
    }

    static def uploadMetrics(Object self, String localDir, String remoteDir) {
        try {
            self.dir(localDir) {
                self.uploadFiles(".", "${remoteDir}")
            }
        } catch (e) {
            self.println("[WARNING] Failed to update history of tracked metrics.")
            self.println(e.toString())
            self.println(e.getMessage())
        }
    }

    static def generateOverviewReport(Object self, def buildArgsFunc, Map options) {
        // do not build an overview report for builds with only one test profile
        if (options.containsKey("testProfiles") && options["testProfiles"].size() > 1) {
            self.withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'jenkinsCredentials', usernameVariable: 'JENKINS_USERNAME', passwordVariable: 'JENKINS_PASSWORD']]) {
                try {
                    String publishedReportName = getPublishedReportName("Test_Report")

                    // check that overview report isn't deployed yet
                    self.httpRequest(
                        url: "${self.BUILD_URL}/${publishedReportName}/",
                        authentication: 'jenkinsCredentials',
                        httpMode: 'GET'
                    )

                    self.println("[INFO] Overview report exists")
                } catch(e) {
                    self.println("[INFO] Overview report not found, publish it")

                    // take only first 4 arguments: tool name, commit sha, project branch name and commit message
                    String buildScriptArgs = (buildArgsFunc("", options).split() as List).subList(0, 4).join(" ")

                    String locations = ""

                    Boolean allReportsExists = true

                    for (profile in options["testProfiles"].reverse()) {
                        String publishedReportName = ""

                        if (options.containsKey("displayingTestProfiles")) {
                            String profileName = options.displayingTestProfiles[profile]
                            publishedReportName = getPublishedReportName(self, "Test Report ${profileName}")
                        } else {
                            publishedReportName = getPublishedReportName(self, "Test Report ${profile}")
                        }

                        try {
                            // check that all necessary reports are published
                            self.httpRequest(
                                url: "${self.BUILD_URL}/${publishedReportName}/",
                                authentication: 'jenkinsCredentials',
                                httpMode: 'GET'
                            )
                        } catch(e1) {
                            println("[INFO] Report '${publishedReportName}' not found")
                            allReportsExists = false
                            break
                        }

                        locations = locations ? "${locations}::${self.BUILD_URL}/${publishedReportName}" : "${self.BUILD_URL}/${publishedReportName}"
                    }

                    if (allReportsExists) {
                        self.dir("jobs_launcher") {
                            self.withEnv(["BUILD_NAME=${options.baseBuildName}"]) {
                                self.bat """
                                    build_overview_reports.bat ..\\OverviewReport ${locations} ${self.JENKINS_USERNAME}:${self.JENKINS_PASSWORD} ${buildScriptArgs}
                                """
                            }
                        }

                        publishReport(self, "${self.BUILD_URL}", "OverviewReport", "summary_report.html", "Test Report", "Summary Report (Overview)", false)
                    }
                }
            }
        }
    }

    // Function that compare current and neweset AMD gpu driver on Windows.
    static def compareDriverVersion(Object self, String logFilePath, String osName)
    {
        switch(osName) {
            case 'Windows':
                try{
                    def newestVersionNb, currentVersionNb = [0, 0, 0]
                    int newestMajor, newestMinor, newestPatch
                    int currentMajor, currentMinor, currentPatch

                    String fileWithDriverVer = self.readFile(logFilePath)
                    String[] lines = fileWithDriverVer.split('\n');
                    lines.each {
                        if(it.indexOf("newest_driver") != -1) {
                            newestVersionNb = it.findAll( /\d+/ ).collect{it.toInteger()}
                            newestMajor = newestVersionNb[0]
                            newestMinor = newestVersionNb[1]
                            newestPatch = newestVersionNb[2]
                        }
                        if(it.indexOf("driver_version") != -1) {
                            currentVersionNb = it.findAll( /\d+/ ).collect{it.toInteger()}
                            currentMajor = currentVersionNb[0]
                            currentMinor = currentVersionNb[1]
                            currentPatch = currentVersionNb[2]
                        }
                    }

                    self.println("\n[INFO] GPU current driver version " + currentVersionNb + "\n")
                    if(newestMajor - currentMajor >= 1 || newestMinor - currentMinor >= 2)
                    {
                        String newestDriverVerStr = "Newest version: " + "${newestVersionNb.join('.')}"
                        String currentDriverVerStr = "Current version: " + "${currentVersionNb.join('.')}"
                        String oldDriverMessage = "[WARNING] Driver version is outdated:\n" + currentDriverVerStr + "\n" + newestDriverVerStr + "\n"
                        self.println(oldDriverMessage)
                        self.node ("Windows") {
                            SlackUtils.sendMessageToWorkspaceChannel(self, '', oldDriverMessage, SlackUtils.Color.ORANGE, SlackUtils.SlackWorkspace.LUXCIS, 'zabbix_critical')
                        }
                    }
                } catch(e) {
                    self.println("\n[WARNING] Unable to determine GPU driver version\n")
                    self.println(e.toString())
                    self.println(e.getMessage())
                }
        }
    }
}
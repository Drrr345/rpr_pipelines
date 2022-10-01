import groovy.transform.Field
import net.sf.json.JSON
import net.sf.json.JSONSerializer
import net.sf.json.JsonConfig
import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic


@Field final Map BASELINE_DIR_MAPPING = [
    "blender": "rpr_blender_autotests",
    "maya": "rpr_maya_autotests",
    "max": "rpr_max_autotests",
    "core": "rpr_core_autotests",
    "blender_usd_hydra": "usd_blender_autotests",
    "inventor": "usd_inventor_autotests",
    "usd_viewer": "usd_rprviewer_autotests",
    "USD": "rpr_usdplugin_autotests",
    "maya_usd": "usd_maya_autotests",
    "anari": "rpr_anari_autotests",
    "render_studio": "render_studio_autotests",
    "hybrid_mtlx": "hybrid_mtlx_autotests"
]

@Field final Map PROJECT_MAPPING = [
    "blender": "Blender",
    "maya": "Maya",
    "max": "Max",
    "core": "Core",
    "blender_usd_hydra": "Blender USD Hydra",
    "inventor": "Inventor",
    "USD": "Houdini",
    "maya_usd": "Maya USD",
    "anari": "Anari",
    "render_studio": "Render Studio",
    "hybrid_mtlx": "Hybrid MTLX"
]

@Field final Map ENGINE_REPORT_MAPPING = [
    "full": "Tahoe",
    "full2": "Northstar",
    "hybridpro": "HybridPro",
    "hybrid": "Hybrid",
    "tahoe": "tahoe",
    "northstar": "Northstar",
    "hdrprplugin": "RPR",
    "hdstormrendererplugin": "GL",
    "rpr": "RPR",
    "gl": "GL",
    "hip": "HIP",
    "web": "Web",
    "desktop": "Desktop"
]

@Field final Map ENGINE_BASELINES_MAPPING = [
    "full": "",
    "full2": "NorthStar",
    "hybridpro": "HybridPro",
    "hybrid": "Hybrid",
    "tahoe": "",
    "northstar": "NorthStar",
    "hdrprplugin": "RPR",
    "hdstormrendererplugin": "GL",
    "gl": "GL",
    "rpr": "RPR",
    "hip": "HIP",
    "web": "Web",
    "desktop": "Desktop"
]


@NonCPS
def parseResponse(String response) {
    def jsonSlurper = new groovy.json.JsonSlurperClassic()
    return jsonSlurper.parseText(response)
}


def saveBaselines(String refPathProfile, String resultsDirectory = "results") {
    python3("${WORKSPACE}\\jobs_launcher\\common\\scripts\\generate_baselines.py --results_root ${resultsDirectory} --baseline_root baselines")
    uploadFiles("baselines/", refPathProfile)

    bat """
        if exist ${resultsDirectory} rmdir /Q /S ${resultsDirectory}
        if exist baselines rmdir /Q /S baselines
    """
}


def call(String jobName,
    String buildID,
    String resultPath,
    String caseName,
    String engine,
    String toolName,
    String updateType,
    Boolean allPlatforms=false) {

    // TODO: in future it can be required to replace engines by profiles

    stage("UpdateBaselines") {
        node("Windows && !NoBaselinesUpdate") {
            ws("WS/UpdateBaselines") {
                ProblemMessageManager problemMessageManager = new ProblemMessageManager(this, currentBuild)

                try {
                    cleanWS()

                    // duck tape for one autotests repository for two projects
                    if (toolName == "inventor" && jobName.contains("USD-Viewer")) {
                        toolName = "usd_viewer"
                    }

                    toolName = toolName.toLowerCase()
                    baselineDirName = BASELINE_DIR_MAPPING[toolName]

                    if (engine == "None" || engine == "\"") {
                        engine = ""
                    }

                    String groupName
                    String reportName = engine ? "Test_Report_${ENGINE_REPORT_MAPPING[engine.toLowerCase()]}" : "Test_Report"
                    String baselinesPath = "/Baselines/${baselineDirName}"
                    String reportComparePath

                    if (updateType == "Case" || updateType == "Group") {
                        groupName = resultPath.split("/")[-1]
                        reportComparePath = "results/${groupName}/report_compare.json"
                    }

                    if (updateType != "Build") {
                        def resultPathParts = resultPath.split("/")[0].split("-")

                        String platform = resultPathParts[0] + "-" + resultPathParts[1]

                        if (allPlatforms){
                            currentBuild.description = "<b>Configuration:</b> ${PROJECT_MAPPING[toolName]} (${engine ? ENGINE_REPORT_MAPPING[engine.toLowerCase()] : ''})<br/>"
                        } else {
                            currentBuild.description = "<b>Configuration:</b> ${PROJECT_MAPPING[toolName]} (${engine ? platform + '-' + ENGINE_REPORT_MAPPING[engine.toLowerCase()] : platform})<br/>"
                        }
                    } else {
                        currentBuild.description = "<b>Configuration:</b> ${PROJECT_MAPPING[toolName]} (${engine ? ENGINE_REPORT_MAPPING[engine.toLowerCase()] : ''})<br/>"
                    }

                    switch(updateType) {
                        case "Case":
                            currentBuild.description += "<b>Group:</b> ${groupName} / <b>Case:</b> ${caseName}<br/>"
                            break

                        case "Group":
                            currentBuild.description += "<b>Group:</b> ${groupName}<br/>"
                            break

                        case "Platform":
                            currentBuild.description += "Update all groups of platform<br/>"
                            break

                        case "Build":
                            currentBuild.description += "Update all baselines<br/>"

                            break

                        default:
                            throw new Exception("Unknown updateType ${updateType}")
                    }

                    dir("jobs_launcher") {
                        checkoutScm(branchName: 'master', repositoryUrl: 'git@github.com:luxteam/jobs_launcher.git')
                    }

                    switch(updateType) {
                        case "Case":
                        case "Group":
                            List directories

                            if (allPlatforms) {
                                // search all directories in the target report
                                withCredentials([string(credentialsId: "nasURL", variable: 'REMOTE_HOST'), string(credentialsId: "nasSSHPort", variable: "SSH_PORT")]) {
                                    directories = bat(returnStdout: true, script: '%CIS_TOOLS%\\' + "listFiles.bat \"/volume1/web/${jobName}/${buildID}/${reportName}\" " + '%REMOTE_HOST% %SSH_PORT%').split("\n") as List
                                }
                            } else {
                                directories = [resultPath.split("/")[0] + "/"]
                            }


                            directories.each() { directory ->
                                if (!directory.endsWith("/")) {
                                    // not a directory
                                    return
                                }
                                if (directory.split("-").length != 3) {
                                    println("[INFO] Directory ${directory} hasn't required structure. Skip it")
                                    return
                                }

                                String gpuName = directory.split("-")[0]
                                String osName = directory.split("-")[1]
                                List groups = directory.split("-")[2].replace("/", "").split() as List

                                if (!groups.contains(groupName)) {
                                    println("[INFO] Directory ${directory} doesn't contain ${groupName} test group. Skip it")
                                    return
                                }

                                String machineConfiguration

                                if (engine) {
                                    String engineBaselineName = ENGINE_BASELINES_MAPPING[engine.toLowerCase()]
                                    machineConfiguration = engineBaselineName ? "${gpuName}-${osName}-${engineBaselineName}" : "${gpuName}-${osName}"
                                } else {
                                    machineConfiguration = "${gpuName}-${osName}"
                                }

                                // replace platform directory by a new one to iterate through all necessary directories of all platforms
                                // it does nothing for builds with 'allPlatforms' equals to 'false'
                                List resultPathParts = resultPath.split("/") as List
                                String remoteResultPath = "/volume1/web/${jobName}/${buildID}/${reportName}/${directory + '/' + resultPathParts.subList(1, resultPathParts.size()).join('/')}"
                                String refPathProfile = "/volume1/${baselinesPath}/${machineConfiguration}" 

                                if (updateType == "Case") {
                                    downloadFiles(remoteResultPath + "/report_compare.json", "results/${groupName}")
                                    downloadFiles(remoteResultPath + "/Color/*${caseName}*", "results/${groupName}/Color")
                                    downloadFiles(remoteResultPath + "/*${caseName}*.json", "results/${groupName}")

                                    def testCases = readJSON(file: reportComparePath)

                                    for (testCase in testCases) {
                                        if (testCase["test_case"] == caseName) {
                                            JSON serializedJson = JSONSerializer.toJSON([testCase], new JsonConfig());
                                            writeJSON(file: reportComparePath, json: serializedJson, pretty: 4)
                                            break
                                        }
                                    }

                                    saveBaselines(refPathProfile)
                                } else {
                                    downloadFiles(remoteResultPath, "results")
                                    saveBaselines(refPathProfile)
                                }
                            }

                            break

                        case "Platform":
                            def resultPathParts = resultPath.split("/")[0].split("-")
                            String gpuName = resultPathParts[0]
                            String osName = resultPathParts[1]
                            String machineConfiguration

                            if (engine) {
                                String engineBaselineName = ENGINE_BASELINES_MAPPING[engine.toLowerCase()]
                                machineConfiguration = engineBaselineName ? "${gpuName}-${osName}-${engineBaselineName}" : "${gpuName}-${osName}"
                            } else {
                                machineConfiguration = "${gpuName}-${osName}"
                            }

                            String remoteResultPath = "/volume1/web/${jobName}/${buildID}/${reportName}/${resultPath}*"
                            String refPathProfile = "/volume1/${baselinesPath}/${machineConfiguration}" 
                            downloadFiles(remoteResultPath, "results")

                            dir("results") {
                                // one directory can contain test results of multiple groups
                                def grouppedDirs = findFiles()

                                for (currentDir in grouppedDirs) {
                                    if (currentDir.directory && currentDir.name.startsWith(resultPath)) {
                                        dir("${currentDir.name}/Results") {
                                            // skip empty directories
                                            if (findFiles().length == 0) {
                                                return
                                            }

                                            // find next dir name (e.g. Blender, Maya)
                                            String nextDirName = findFiles()[0].name
                                            saveBaselines(refPathProfile, nextDirName)
                                        }
                                    }
                                }
                            }

                            break

                        case "Build":
                            String remoteResultPath = "/volume1/web/${jobName}/${buildID}/${reportName}/"
                            downloadFiles(remoteResultPath, "results")

                            dir("results") {
                                // one directory can contain test results of multiple groups
                                def grouppedDirs = findFiles()

                                for (currentDir in grouppedDirs) {
                                    if (currentDir.directory && 
                                        (currentDir.name.startsWith("NVIDIA_") || currentDir.name.startsWith("AppleM1") || currentDir.name.startsWith("AMD_"))) {

                                        def resultPathParts = currentDir.name.split("-")
                                        String gpuName = resultPathParts[0]
                                        String osName = resultPathParts[1]

                                        if (engine) {
                                            String engineBaselineName = ENGINE_BASELINES_MAPPING[engine.toLowerCase()]
                                            machineConfiguration = engineBaselineName ? "${gpuName}-${osName}-${engineBaselineName}" : "${gpuName}-${osName}"
                                        } else {
                                            machineConfiguration = "${gpuName}-${osName}"
                                        }

                                        String refPathProfile = "/volume1/${baselinesPath}/${machineConfiguration}" 

                                        dir("${currentDir.name}/Results") {
                                            // skip empty directories
                                            if (findFiles().length == 0) {
                                                return
                                            }

                                            // find next dir name (e.g. Blender, Maya)
                                            String nextDirName = findFiles()[0].name
                                            saveBaselines(refPathProfile, nextDirName)
                                        }
                                    }
                                }
                            }

                            break

                        default:
                            throw new Exception("Unknown updateType ${updateType}")
                    }
                } catch (e) {
                    println("[ERROR] Failed to update baselines on NAS")
                    problemMessageManager.saveGlobalFailReason(NotificationConfiguration.FAILED_UPDATE_BASELINES_NAS)
                    currentBuild.result = "FAILURE"
                    throw e
                }

                problemMessageManager.publishMessages()
            }
        }
    }
}
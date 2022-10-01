import java.text.SimpleDateFormat
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
import hudson.plugins.git.GitException
import java.nio.channels.ClosedChannelException
import hudson.remoting.RequestAbortedException
import java.lang.IllegalArgumentException
import java.time.*
import java.time.format.DateTimeFormatter
import jenkins.model.Jenkins
import groovy.transform.Synchronized
import java.util.Iterator
import TestsExecutionType
import groovy.transform.Field


@Field List platformList = []
@Field Map testResultMap = [:]
@Field def executeDeploy


@NonCPS
@Synchronized
def getNextTest(Iterator iterator) {
    if (iterator.hasNext()) {
        return iterator.next()
    } else {
        return null
    }
}

@NonCPS
@Synchronized
def changeTestsCount(Map testsLeft, int count, String profile) {
    if (testsLeft && profile) {
        testsLeft[profile] += count
        println("Number of tests left for '${profile}' profile change by '${count}'. Tests left: ${testsLeft[profile]}")
    }
}


def initProfiles(Map options) {
    if (options.containsKey("configuration") && options["configuration"]["buildProfile"]) {
        // separate buildProfiles and buildsList keys for possible future difference in values of these keys
        options["buildProfiles"] = options["buildsList"].clone()
    }

    if (options.containsKey("configuration") && options["configuration"]["testProfile"]) {
        options["testProfiles"] = []

        for (test in options["testsList"]) {
            String profile = test.split("-")[-1]

            if (!options["testProfiles"].contains(profile)) {
                options["testProfiles"].add(profile)
            }
        }

        if (options["configuration"]["displayingProfilesMapping"]) {
            options["displayingTestProfiles"] = [:]

            for (value in options["testsList"]) {
                String profile = value.split("-")[-1]

                if (!options["displayingTestProfiles"].containsKey(profile)) {
                    List profileKeys = options["configuration"]["testProfile"].split("_") as List
                    List profileValues = profile.split("_") as List

                    List buffer = []

                    for (int i = 0; i < profileKeys.size(); i++) {
                        buffer.add(options["configuration"]["displayingProfilesMapping"][profileKeys[i]][profileValues[i]])
                    }

                    options["displayingTestProfiles"][profile] = buffer.join(" ")
                }
            }
        }
    }
}


boolean doesProfilesCorrespond(String buildProfile, String testProfile) {
    List buildProfileParts = buildProfile.split("_") as List
    List testProfileParts = testProfile.split("_") as List

    int lastIndex = -100

    for (part in buildProfileParts) {
        int currentIndex = testProfileParts.indexOf(part)

        if (currentIndex != -1 && currentIndex > lastIndex) {
            continue
        } else {
            return false
        }
    }

    return true
}


def executeTestsNode(String osName, String gpuNames, String buildProfile, def executeTests, Map options, Map testsLeft)
{
    if (gpuNames && options['executeTests']) {
        def testTasks = [:]
        gpuNames.split(',').each() {
            String asicName = it

            String taskName = "Test-${asicName}-${osName}"

            testTasks[taskName] = {
                stage(taskName) {
                    def testsList = options.testsList.clone() ?: ['']

                    def testerLabels
                    if (options.TESTER_TAG) {
                        if (options.TESTER_TAG.contains("PC-") || options.TESTER_TAG.contains("LC-")) {
                            // possibility to test some disabled tester machine
                            testerLabels = "${osName} && ${options.TESTER_TAG} && gpu${asicName}"
                        } else {
                            testerLabels = "${osName} && ${options.TESTER_TAG} && gpu${asicName} && !Disabled"
                        }
                    } else {
                        testerLabels = "${osName} && Tester && gpu${asicName} && !Disabled"
                    }

                    testsList.removeAll({buildProfile && !doesProfilesCorrespond(buildProfile, it.split("-")[-1])})

                    Iterator testsIterator = testsList.iterator()

                    Integer launchingGroupsNumber = 1
                    if (!options["parallelExecutionType"] || options["parallelExecutionType"] == TestsExecutionType.TAKE_ONE_NODE_PER_GPU) {
                        launchingGroupsNumber = 1
                    } else if (options["parallelExecutionType"] == TestsExecutionType.TAKE_ALL_NODES) {
                        List possibleNodes = nodesByLabel label: testerLabels, offline: true
                        launchingGroupsNumber = possibleNodes.size() ?: 1
                    }

                    Map testsExecutors = [:]

                    for (int i = 0; i < launchingGroupsNumber; i++) {
                        testsExecutors["Test-${asicName}-${osName}-${i}"] = {
                            String testName = getNextTest(testsIterator)
                            while (testName != null) {
                                String testProfile = null
                                String tests = null

                                if (options.containsKey("configuration") && options["configuration"]["testProfile"]) {
                                    testProfile = testName.split("-")[-1]
                                }

                                // if there number of errored groups in succession is 3 or more
                                if (options["errorsInSuccession"] && 
                                        ((testProfile && options["errorsInSuccession"]["${osName}-${asicName}-${testProfile}"] && options["errorsInSuccession"]["${osName}-${asicName}-${testProfile}"].intValue() >= 3)
                                        || (options["errorsInSuccession"]["${osName}-${asicName}"] && options["errorsInSuccession"]["${osName}-${asicName}"].intValue() >= 3))) {
                                    println("Test group ${testName} on ${asicName}-${osName} aborted due to exceeded number of errored groups in succession")
                                    testName = getNextTest(testsIterator)
                                    changeTestsCount(testsLeft, -1, testProfile)
                                    continue
                                }

                                if (options["abort${osName}"]) {
                                    println("Test group ${testName} on ${asicName}-${osName} aborted due to current context")
                                    testName = getNextTest(testsIterator)
                                    changeTestsCount(testsLeft, -1, testProfile)
                                    continue
                                }

                                // check that the current configuration shouldn't be skipped
                                if (options.containsKey("skipCallback")) {
                                    Boolean skip = false

                                    if (testProfile) {
                                        // remove profile name from testName
                                        List testNameParts = testName.split("-") as List
                                        String rawTestName = testNameParts.subList(0, testNameParts.size() - 1).join("-")

                                        if (options.splitTestsExecution) {
                                            skip = options["skipCallback"](options, asicName, osName, rawTestName, testProfile)
                                        } else {
                                            skip = options["skipCallback"](options, asicName, osName, testProfile)
                                        }
                                    } else {
                                        skip = options["skipCallback"](options, asicName, osName, testName)
                                    }

                                    if (skip) {
                                        println("Test group ${testName} on ${asicName}-${osName} is skipped on pipeline's layer")
                                        testName = getNextTest(testsIterator)
                                        changeTestsCount(testsLeft, -1, testProfile)
                                        continue
                                    }
                                }

                                println("Scheduling ${osName}:${asicName} ${testName}")

                                Map newOptions = options.clone()
                                newOptions["stage"] = "Test"
                                newOptions["asicName"] = asicName
                                newOptions["osName"] = osName
                                newOptions["taskName"] = taskName
                                newOptions['testResultsName'] = testName ? "testResult-${asicName}-${osName}-${testName}" : "testResult-${asicName}-${osName}"
                                newOptions['stageName'] = testName ? "${asicName}-${osName}-${testName}" : "${asicName}-${osName}"
                                newOptions['tests'] = options.splitTestsExecution ? testName.split("-")[0] : options.tests
                                newOptions["testProfile"] = testProfile

                                if (options.containsKey("configuration") && options["configuration"]["testProfile"]) {
                                    List profileKeys = options["configuration"]["testProfile"].split("_") as List
                                    List profileValues = testProfile.split("_") as List

                                    for (int j = 0; j < profileKeys.size(); j++) {
                                        newOptions[profileKeys[j]] = profileValues[j]
                                    }
                                }

                                def retringFunction = { nodesList, currentTry ->
                                    try {
                                        executeTests(osName, asicName, newOptions)
                                    } catch(Exception e) {
                                        // save expected exception message for add it in report
                                        String expectedExceptionMessage = ""
                                        if (e instanceof ExpectedExceptionWrapper) {
                                            if (e.abortCurrentOS) {
                                                options["abort${osName}"] = true
                                            }
                                            expectedExceptionMessage = e.getMessage()
                                            // check that cause isn't more specific expected exception
                                            if (e.getCause() instanceof ExpectedExceptionWrapper) {
                                                if (e.getCause().abortCurrentOS) {
                                                    options["abort${osName}"] = true
                                                }
                                                expectedExceptionMessage = e.getCause().getMessage()
                                            }
                                        }

                                        println "[ERROR] Failed during tests on ${env.NODE_NAME} node"
                                        println "Exception: ${e.toString()}"
                                        println "Exception message: ${e.getMessage()}"
                                        println "Exception cause: ${e.getCause()}"
                                        println "Exception stack trace: ${e.getStackTrace()}"

                                        String exceptionClassName = e.getClass().toString()
                                        if (exceptionClassName.contains("FlowInterruptedException")) {
                                            e.getCauses().each(){
                                                // UserInterruption aborting by user
                                                // ExceededTimeout aborting by timeout
                                                // CancelledCause for aborting by new commit
                                                String causeClassName = it.getClass().toString()
                                                println "Interruption cause: ${causeClassName}"
                                                if (causeClassName.contains("CancelledCause")) {
                                                    expectedExceptionMessage = "Build was aborted by new commit."
                                                } else if (causeClassName.contains("UserInterruption") || causeClassName.contains("ExceptionCause")) {
                                                    expectedExceptionMessage = "Build was aborted by user."
                                                } else if (utils.isTimeoutExceeded(e) && !expectedExceptionMessage.contains("timeout")) {
                                                    expectedExceptionMessage = "Timeout exceeded (pipelines layer)."
                                                }
                                            }
                                        } else if (exceptionClassName.contains("ClosedChannelException") || exceptionClassName.contains("RemotingSystemException") || exceptionClassName.contains("InterruptedException")) {
                                            expectedExceptionMessage = "Lost connection with machine."
                                        }

                                        // add info about retry to options
                                        boolean added = false;
                                        String testsOrTestPackage
                                        if (newOptions['splitTestsExecution']) {
                                            testsOrTestPackage = newOptions['tests']
                                        } else {
                                            //all non splitTestsExecution builds (e.g. any build of core)
                                            if (testName && !testName.startsWith("-")) {
                                                testsOrTestPackage = testName
                                            } else {
                                                testsOrTestPackage = 'DefaultExecution'
                                            }
                                        }

                                        if (!expectedExceptionMessage) {
                                            expectedExceptionMessage = "Unexpected exception."
                                        }

                                        if (options.containsKey('nodeRetry')) {
                                            // parse united suites
                                            testsOrTestPackageParts = testsOrTestPackage.split("-")
                                            for (failedSuite in testsOrTestPackageParts[0].split()) {
                                                String suiteName
                                                // check profile existence
                                                if (testsOrTestPackageParts.length > 1) {
                                                    suiteName = "${failedSuite}-${testsOrTestPackageParts[1]}"
                                                } else {
                                                    suiteName = "${failedSuite}"
                                                }
                                                Map tryInfo = [host:env.NODE_NAME, link:"${testsOrTestPackage}.${env.NODE_NAME}.retry_${currentTry}.crash.log", exception: expectedExceptionMessage, time: LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))]

                                                retryLoops: for (testers in options['nodeRetry']) {
                                                    if (testers['Testers'].equals(nodesList)){
                                                        for (suite in testers['Tries']) {
                                                            if (suite[suiteName]) {
                                                                suite[suiteName].add(tryInfo)
                                                                added = true
                                                                break retryLoops
                                                            }
                                                        }
                                                        // add list for test group if it doesn't exists
                                                        testers['Tries'].add([(suiteName): [tryInfo]])
                                                        added = true
                                                        break retryLoops
                                                    }
                                                }

                                                if (!added){
                                                    options['nodeRetry'].add([Testers: nodesList, gpuName: asicName, osName: osName, Tries: [[(suiteName): [tryInfo]]]])
                                                }
                                            }

                                            println options['nodeRetry'].inspect()
                                        }

                                        throw e
                                    }
                                }

                                try {
                                    Integer retries_count = options.retriesForTestStage ?: -1
                                    run_with_retries(testerLabels, options.TEST_TIMEOUT, retringFunction, true, "Test", newOptions, [], retries_count, osName)
                                } catch(FlowInterruptedException e) {
                                    options.buildWasAborted = true
                                    e.getCauses().each(){
                                        String causeClassName = it.getClass().toString()
                                        if (causeClassName.contains("CancelledCause") || causeClassName.contains("UserInterruption")) {
                                            throw e
                                        }
                                    }
                                } catch (e) {
                                    // Ignore other exceptions
                                }

                                testName = getNextTest(testsIterator)
                                changeTestsCount(testsLeft, -1, testProfile)
                            }
                        }                        
                    }

                    parallel testsExecutors
                }
            }
        }
        parallel testTasks
    } else {
        println "[WARNING] No tests found for ${osName}"
    }
}

def executePlatform(String osName, String gpuNames, String buildProfile, def executeBuild, def executeTests, Map options, Map testsLeft)
{
    def retNode = {
        try {

            try {
                if (options['executeBuild'] && executeBuild) {
                    String stageName = buildProfile ? "Build-${osName}-${buildProfile}" : "Build-${osName}"

                    stage(stageName) {
                        def builderLabels = "${osName} && ${options.BUILDER_TAG}"
                        def retringFunction = { nodesList, currentTry ->
                            executeBuild(osName, options)
                        }
                        run_with_retries(builderLabels, options.BUILD_TIMEOUT, retringFunction, false, "Build", options, ['FlowInterruptedException', 'IOException'], -1, osName, true)
                    }
                }
            } catch (e1) {
                if (options.testProfiles) {
                    options.testProfiles.each { testProfile ->
                        if (buildProfile) {
                            if (!doesProfilesCorrespond(buildProfile, testProfile)) {
                                return
                            }
                        }

                        changeTestsCount(testsLeft, -options.testsInfo["testsPer-${testProfile}-${osName}"], testProfile)
                    }
                }
                throw e1
            }

            if (options.containsKey('tests') && options.containsKey('testsPackage')){
                if (options['testsPackage'] != 'none' || options['tests'].size() == 0 || !(options['tests'].size() == 1 && options['tests'].get(0).length() == 0)){ // BUG: can throw exception if options['tests'] is string with length 1
                    executeTestsNode(osName, gpuNames, buildProfile, executeTests, options, testsLeft)
                }
            } else {
                executeTestsNode(osName, gpuNames, buildProfile, executeTests, options, testsLeft)
            }

        } catch (e) {
            println "[ERROR] executePlatform throw the exception"
            println "Exception: ${e.toString()}"
            println "Exception message: ${e.getMessage()}"
            println "Exception cause: ${e.getCause()}"
            println "Exception stack trace: ${e.getStackTrace()}"

            String exceptionClassName = e.getClass().toString()
            if (exceptionClassName.contains("FlowInterruptedException")) {
                options.buildWasAborted = true
            }

            currentBuild.result = "FAILURE"
            options.FAILED_STAGES.add(e.toString())
            throw e
        }
    }
    return retNode
}

def shouldExecuteDelpoyStage(Map options) {
    if (options['executeTests']) {
        if (options.containsKey('tests') && options.containsKey('testsPackage')){
            if (options['testsPackage'] == 'none' && options['tests'].size() == 1 && options['tests'].get(0).length() == 0){
                return false
            }
        }
    } else {
        return false
    }

    return true
}

def makeDeploy(Map options, String buildProfile = "", String testProfile = "") {
    Boolean executeDeployStage = shouldExecuteDelpoyStage(options)

    if (executeDeploy && executeDeployStage) {
        String stageName

        if (testProfile) {
            stageName = options.containsKey("displayingTestProfiles") ? "Deploy-${options.displayingTestProfiles[testProfile]}" : "Deploy-${testProfile}"
        } else {
            stageName = "Deploy"
        }

        stage(stageName) {
            def reportBuilderLabels = ""

            if (options.DEPLOY_TAG) {
                reportBuilderLabels = options.DEPLOY_TAG
            } else if (options.PRJ_NAME == "RadeonProImageProcessor" || options.PRJ_NAME == "RadeonML") {
                reportBuilderLabels = "Windows && GitPublisher && !NoDeploy"
            } else {
                reportBuilderLabels = "Windows && Tester && !NoDeploy"
            }

            options["stage"] = "Deploy"
            def retringFunction = { nodesList, currentTry ->
                List testResultList = buildProfile ? testResultMap[buildProfile] : testResultMap[""]

                if (testProfile) {
                    executeDeploy(options, platformList, testResultList, testProfile)
                } else {
                    executeDeploy(options, platformList, testResultList)
                }

                if (testProfile && options.testProfiles.size() > 1 && options.reportUpdater) {
                    options.reportUpdater.updateReport()
                }

                println("[INFO] Deploy stage finished without unexpected exception. Clean workspace")
                cleanWS("Windows")
            }
            run_with_retries(reportBuilderLabels, options.DEPLOY_TIMEOUT, retringFunction, false, "Deploy", options, [], 3)
        }
    }
}

// TODO: pass platforms only through options (it allows to modify it in PreBuild stage)
def call(String platforms, def executePreBuild, def executeBuild, def executeTests, def executeDeploy, Map options) {
    try {
        this.executeDeploy = executeDeploy

        try {
            setupBuildStoragePolicy()
        } catch (e) {
            println("[ERROR] Failed to setup build storage policty.")
            println(e.toString())
        }

        try {
            options.baseBuildName = currentBuild.displayName
            if (env.BuildPriority) {
                currentBuild.displayName = "${currentBuild.displayName} (Priority: ${env.BuildPriority})"
                println("[INFO] Priority was set by BuildPriority parameter")
            } else {
                def jenkins = Jenkins.getInstance();        
                def views = Jenkins.getInstance().getViews()
                String jobName = env.JOB_NAME.split('/')[0]

                def jobsViews = []
                for (view in views) {
                    if (view.contains(jenkins.getItem(jobName))) {
                        jobsViews.add(view.getDisplayName())
                    }
                }

                Integer priority = utils.getBuildPriority(this)
                currentBuild.displayName = "${currentBuild.displayName} (Priority: ${priority})"

                println("[INFO] Priority was set based on view of job")
            }
        } catch (e) {
            println("[ERROR] Failed to add priority into build name")
            println(e.toString())
        }

        if (env.CHANGE_URL) {
            def buildNumber = env.BUILD_NUMBER as int
            if (buildNumber > 1) milestone(buildNumber - 1)
            milestone(buildNumber) 
        } 

        def date = new Date()
        dateFormatter = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss")
        dateFormatter.setTimeZone(TimeZone.getTimeZone("GMT+3:00"))
        options.JOB_STARTED_TIME = dateFormatter.format(date)
        
        timestamps
        {
            String PRJ_PATH="${options.PRJ_ROOT}/${options.PRJ_NAME}"
            String REF_PATH="${PRJ_PATH}/ReferenceImages"
            String JOB_PATH="${PRJ_PATH}/${JOB_NAME}/Build-${BUILD_ID}".replace('%2F', '_')
            options['PRJ_PATH']="${PRJ_PATH}"
            options['REF_PATH']="${REF_PATH}"
            options['JOB_PATH']="${JOB_PATH}"

            if(options.get('BUILDER_TAG', '') == '')
                options['BUILDER_TAG'] = 'Builder'

            // if timeout doesn't set - use default (value in minutes)
            options['PREBUILD_TIMEOUT'] = options['PREBUILD_TIMEOUT'] ?: 20
            options['BUILD_TIMEOUT'] = options['BUILD_TIMEOUT'] ?: 40
            options['TEST_TIMEOUT'] = options['TEST_TIMEOUT'] ?: 20
            options['DEPLOY_TIMEOUT'] = options['DEPLOY_TIMEOUT'] ?: 20

            options['FAILED_STAGES'] = []

            Map testsLeft = [:]

            try {
                if (executePreBuild) {
                    node("Windows && PreBuild") {
                        ws("WS/${options.PRJ_NAME}_Build") {
                            stage("PreBuild") {
                                try {
                                    timeout(time: "${options.PREBUILD_TIMEOUT}", unit: 'MINUTES') {
                                        options["stage"] = "PreBuild"
                                        executePreBuild(options)
                                        if(!options['executeBuild']) {
                                            options.CBR = 'SKIPPED'
                                            echo "Build SKIPPED"
                                        }
                                    }
                                } catch (e) {
                                    println("[ERROR] Failed during prebuild stage on ${env.NODE_NAME}")
                                    println(e.toString())
                                    println(e.getMessage())
                                    String exceptionClassName = e.getClass().toString()
                                    if (exceptionClassName.contains("FlowInterruptedException")) {
                                        e.getCauses().each(){
                                            String causeClassName = it.getClass().toString()
                                            if (causeClassName.contains("ExceededTimeout")) {
                                                if (options.problemMessageManager) {
                                                    options.problemMessageManager.saveSpecificFailReason(NotificationConfiguration.TIMEOUT_EXCEEDED, "PreBuild")
                                                }
                                            }
                                        }
                                    }
                                    if (options.problemMessageManager) {
                                        options.problemMessageManager.saveGeneralFailReason(NotificationConfiguration.UNKNOWN_REASON, "PreBuild")
                                    }
                                    GithubNotificator.closeUnfinishedSteps(options, NotificationConfiguration.PRE_BUILD_STAGE_FAILED)
                                    throw e
                                }
                            }
                        }
                    }
                }

                options.testsInfo = [:]
                if (options.testProfiles) {
                    options['testsList'].each() { testName ->
                        String profile = testName.split("-")[-1]
                        if (!options.testsInfo.containsKey("testsPer-" + profile)) {
                            options.testsInfo["testsPer-${profile}"] = 0
                        }
                        options.testsInfo["testsPer-${profile}"]++
                    }
                }

                Map tasks = [:]

                if (options.platforms) {
                    platforms = options.platforms
                }

                platforms.split(';').each() {
                    if (it) {
                        List tokens = it.tokenize(':')
                        String osName = tokens.get(0)
                        String gpuNames = ""

                        if (tokens.size() > 1) {
                            gpuNames = tokens.get(1)
                        }

                        platformList << osName

                        List buildsList = options.containsKey("buildsList") ? options["buildsList"] : [""]

                        for (build in buildsList) {
                            Map newOptions = options.clone()
                            newOptions["stage"] = "Build"
                            newOptions["osName"] = osName
                            newOptions["buildProfile"] = build

                            if (options.containsKey("configuration") && options["configuration"]["buildProfile"]) {
                                List profileKeys = options["configuration"]["buildProfile"].split("_") as List
                                List profileValues = build.split("_") as List

                                for (int i = 0; i < profileKeys.size(); i++) {
                                    newOptions[profileKeys[i]] = profileValues[i]
                                }
                            }

                            if (gpuNames) {
                                gpuNames.split(',').each() {
                                    // if not split - testsList doesn't exists
                                    newOptions.testsList = newOptions.testsList ?: ['']
                                    newOptions['testsList'].each() { testName ->
                                        if (build) {
                                            String testProfile = testName.split("-")[-1]

                                            if (build && !doesProfilesCorrespond(build, testProfile)) {
                                                return
                                            }
                                        }

                                        String asicName = it
                                        String testResultItem = testName ? "testResult-${asicName}-${osName}-${testName}" : "testResult-${asicName}-${osName}"

                                        if (!testResultMap.containsKey(build)) {
                                            testResultMap[build] = []
                                        }

                                        testResultMap[build] << testResultItem
                                    }

                                    if (options.testProfiles) {
                                        options.testProfiles.each { testProfile ->
                                            if (build && !doesProfilesCorrespond(build, testProfile)) {
                                                return
                                            }

                                            if (!testsLeft.containsKey(testProfile)) {
                                                testsLeft[testProfile] = 0
                                            }
                                            testsLeft[testProfile] += (options.testsInfo["testsPer-${testProfile}"] ?: 0)

                                            if (!options.testsInfo.containsKey("testsPer-" + testProfile + "-" + osName)) {
                                                options.testsInfo["testsPer-${testProfile}-${osName}"] = 0
                                            }
                                            options.testsInfo["testsPer-${testProfile}-${osName}"] += (options.testsInfo["testsPer-${testProfile}"] ?: 0)
                                        }
                                    }
                                }
                            }

                            String taskName = build ? "${osName}-${build}" : osName

                            tasks[taskName]=executePlatform(osName, gpuNames, build, executeBuild, executeTests, newOptions, testsLeft)
                        }
                    }
                }

                println "Tests Info: ${options.testsInfo}"

                println "Tests Left: ${testsLeft}"

                if (options.testProfiles) {
                    options.testProfiles.each { testProfile ->
                        String stageName

                        if (options.containsKey("displayingTestProfiles")) {
                            stageName = "Deploy-${options.displayingTestProfiles[testProfile]}"
                        } else {
                            stageName = "Deploy-${testProfile}"
                        }

                        if (options.containsKey("buildProfiles")) {
                            options.buildProfiles.each { buildProfile ->
                                if (buildProfile && !doesProfilesCorrespond(buildProfile, testProfile)) {
                                    return
                                }

                                tasks[stageName] = {
                                    if (testsLeft[testProfile] != null) {
                                        waitUntil({testsLeft[testProfile] == 0}, quiet: true)
                                        makeDeploy(options, buildProfile, testProfile)
                                    }
                                }
                            }
                        } else {
                            tasks[stageName] = {
                                if (testsLeft[testProfile] != null) {
                                    waitUntil({testsLeft[testProfile] == 0}, quiet: true)
                                    makeDeploy(options, "", testProfile)
                                }
                            }
                        }
                    }
                }

                parallel tasks
            } catch (e) {
                println(e.toString())
                println(e.getMessage())
                currentBuild.result = "FAILURE"
                String exceptionClassName = e.getClass().toString()
                if (exceptionClassName.contains("FlowInterruptedException")) {
                    options.buildWasAborted = true
                    e.getCauses().each(){
                        // UserInterruption aborting by user
                        // ExceededTimeout aborting by timeout
                        // CancelledCause for aborting by new commit
                        String causeClassName = it.getClass().toString()
                        if (causeClassName.contains("CancelledCause")) {
                            executeDeploy = null
                        }
                    }
                }
            } finally {
                if (!(options.testProfiles)) {
                    makeDeploy(options)
                } else {
                    Map tasks = [:]

                    options.testProfiles.each {
                        if (testsLeft && testsLeft[it] != 0) {
                            // Build was aborted. Make reports from existing data
                            String stageName

                            if (options.containsKey("displayingTestProfiles")) {
                                stageName = "Deploy-${options.displayingTestProfiles[it]}"
                            } else {
                                stageName = "Deploy-${it}"
                            }

                            tasks[stageName] = {
                                makeDeploy(options, it)
                            }
                        }
                    }

                    if (tasks) {
                        parallel tasks
                    }
                }
            }
        }
    } catch (FlowInterruptedException e) {
        println(e.toString())
        println(e.getMessage())
        options.buildWasAborted = true
        println("Job was ABORTED by user. Job status: ${currentBuild.result}")
    } catch (e) {
        currentBuild.result = "FAILURE"
        throw e
    } finally {
        println("enableNotifications = ${options.enableNotifications}")
        if ("${options.enableNotifications}" == "true") {
            SlackUtils.sendBuildStatusNotification(this,
                                        currentBuild.result,
                                        options.get('slackWorkspace', SlackUtils.SlackWorkspace.DEFAULT),
                                        options.get('slackChannel', 'cis_stream'),
                                        options)
        }

        println("Send Slack message to debug channels")
        SlackUtils.sendBuildStatusToDebugChannel(this, options)

        println("[INFO] BUILD RESULT: ${currentBuild.result}")
        println("[INFO] BUILD CURRENT RESULT: ${currentBuild.currentResult}")
    }
}

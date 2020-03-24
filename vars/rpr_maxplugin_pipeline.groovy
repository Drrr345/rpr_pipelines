import RBSProduction
import RBSDevelopment
import hudson.plugins.git.GitException
import java.nio.channels.ClosedChannelException
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException


def executeGenTestRefCommand(String osName, Map options)
{
    executeTestCommand(osName, options)

    try
    {
        //for update existing manifest file
        receiveFiles("${options.REF_PATH_PROFILE}/baseline_manifest.json", './Work/Baseline/')
    }
    catch(e)
    {
        println("baseline_manifest.json not found")
    }

    dir('scripts')
    {
        switch(osName)
        {
            case 'Windows':
                bat """
                make_results_baseline.bat
                """
                break;
            case 'OSX':
                sh """
                echo 'sample image' > ./ReferenceImages/sample_image.txt
                """
                break;
            default:
                sh """
                echo 'sample image' > ./ReferenceImages/sample_image.txt
                """
        }
    }
}

def getPlugin(String osName, Map options)
{
    String customBuildLink = ""
    String extension = ""

    switch(osName)
    {
        case 'Windows':
            customBuildLink = options['customBuildLinkWindows']
            extension = "msi"
            break;
        case 'OSX':
            customBuildLink = options['customBuildLinkOSX']
            extension = "dmg"
            break;
        default:
            customBuildLink = options['customBuildLinkLinux']
            extension = "run"
    }

    if (options['isPreBuilt']) 
    {
        print "Use specified pre built plugin .${extension}"

        if (customBuildLink.startsWith("https://builds.rpr")) 
        {
            withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'builsRPRCredentials', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
                if (osName == "Windows")
                {
                    bat """
                    curl -L -o RadeonProRenderMax.${extension} -u %USERNAME%:%PASSWORD% "${customBuildLink}"
                    """
                }
                else
                {
                    sh """
                    curl -L -o RadeonProRenderMax.${extension} -u %USERNAME%:%PASSWORD% "${customBuildLink}"
                    """
                }
            }
        }
        else
        {
            if (osName == "Windows")
            {
                bat """
                curl -L -o RadeonProRenderMax.${extension} "${customBuildLink}"
                """
            }
            else
            {
                sh """
                curl -L -o RadeonProRenderMax.${extension} "${customBuildLink}"
                """
            }
        }

        def pluginSha = sha1 "RadeonProRenderMax.${extension}"

        switch(osName)
        {
            case 'Windows':
                options.pluginWinSha = pluginSha
                break;
            case 'OSX':
                options.pluginOSXSha = pluginSha
                break;
            default:
                options.pluginUbuntuSha = pluginSha
        }
    }
    else
    {
        print "Use plugin ${extension} which is built from source code"

        unstash "app${osName}"
    }
}

def executeTestCommand(String osName, Map options)
{
    if (!options['skipBuild']) {
        try {
            timeout(time: "30", unit: 'MINUTES') {
                getPlugin(osName, options)
                installRPRPlugin(osName, options, 'Max', options.stageName)
            }
        }
        catch(e) {
            println(e.toString())
            println("ERROR during plugin installation")
        }
    }

    dir('scripts')
    {
        bat"""
        run.bat ${options.renderDevice} ${options.testsPackage} \"${options.tests}\" ${options.toolVersion} ${options.resX} ${options.resY} ${options.SPU} ${options.iter} ${options.theshold} >> ../${options.stageName}.log  2>&1
        """
    }
}


def executeTests(String osName, String asicName, Map options)
{
    cleanWs(deleteDirs: true, disableDeferredWipeout: true)
    try {
        checkOutBranchOrScm(options['testsBranch'], 'git@github.com:luxteam/jobs_test_max.git')

        // setTester in rbs
        if (options.sendToRBS) {
            options.rbs_prod.setTester(options)
            options.rbs_dev.setTester(options)
        }

        downloadAssets("${options.PRJ_ROOT}/${options.PRJ_NAME}/MaxAssets/", 'MaxAssets')

        if (!options['skipBuild']) {
            try {
                timeout(time: "30", unit: 'MINUTES') {
                    unstash "app${osName}"
                    installRPRPlugin(osName, options, 'Max', options.stageName)
                }
            } catch(e) {
                println(e.toString())
                println("[ERROR] Failed to install plugin.")
                currentBuild.result = "FAILED"
                throw e
            }
        }

        String REF_PATH_PROFILE="${options.REF_PATH}/${asicName}-${osName}"
        String JOB_PATH_PROFILE="${options.JOB_PATH}/${asicName}-${osName}"

        options.REF_PATH_PROFILE = REF_PATH_PROFILE

        outputEnvironmentInfo(osName, options.stageName)

        if(options['updateRefs'])
        {
            executeGenTestRefCommand(osName, options)
            sendFiles('./Work/Baseline/', REF_PATH_PROFILE)
        }
        else
        {
            try {
                receiveFiles("${REF_PATH_PROFILE}/baseline_manifest.json", './Work/Baseline/')
                options.tests.split(" ").each() {
                    receiveFiles("${REF_PATH_PROFILE}/${it}", './Work/Baseline/')
                }
            } catch (e) {println("Baseline doesn't exist.")}
            executeTestCommand(osName, options)
        }
    }
    catch(GitException | ClosedChannelException e) {
        currentBuild.result = "FAILED"
        throw e
    }
    catch (e) {
        println(e.toString());
        println(e.getMessage());
        currentBuild.result = "FAILED"
        if (!options.splitTestsExecution) {
            throw e
        }
    }
    finally {
        archiveArtifacts artifacts: "*.log", allowEmptyArchive: true
        echo "Stashing test results to : ${options.testResultsName}"
        dir('Work')
        {
            def sessionReport = null
            stash includes: '**/*', name: "${options.testResultsName}", allowEmpty: true
            if (fileExists("Results/Max/session_report.json")) {
                sessionReport = readJSON file: 'Results/Max/session_report.json'
                // if none launched tests - mark build failed
                if (sessionReport.summary.total == 0)
                {
                    options.failureMessage = "Noone test was finished for: ${asicName}-${osName}"
                    currentBuild.result = "FAILED"
                }
            }

            if (options.sendToRBS)
            {
                options.rbs_prod.sendSuiteResult(sessionReport, options)
                options.rbs_dev.sendSuiteResult(sessionReport, options)
            }
        }
    }
}

def executeBuildWindows(Map options)
{
    dir("RadeonProRenderMaxPlugin/Package")
    {
        bat """
        build_windows_installer.cmd >> ../../${STAGE_NAME}.log  2>&1
        """

        String branch_postfix = ""
        if(env.BRANCH_NAME && BRANCH_NAME != "master")
        {
            branch_postfix = BRANCH_NAME.replace('/', '-')
        }
        if(env.Branch && Branch != "master")
        {
            branch_postfix = Branch.replace('/', '-')
        }
        if(branch_postfix)
        {
            bat """
            rename RadeonProRender*msi *.(${branch_postfix}).msi
            """
        }

        archiveArtifacts "RadeonProRender3dsMax*.msi"
        String BUILD_NAME = branch_postfix ? "RadeonProRender3dsMax_${options.pluginVersion}.(${branch_postfix}).msi" : "RadeonProRender3dsMax_${options.pluginVersion}.msi"
        rtp nullAction: '1', parserName: 'HTML', stableText: """<h3><a href="${BUILD_URL}/artifact/${BUILD_NAME}">[BUILD: ${BUILD_ID}] ${BUILD_NAME}</a></h3>"""

        bat '''
        for /r %%i in (RadeonProRender*.msi) do copy %%i RadeonProRenderMax.msi
        '''

        stash includes: 'RadeonProRenderMax.msi', name: 'appWindows'
        options.pluginWinSha = sha1 'RadeonProRenderMax.msi'
    }
}

def executeBuildOSX(Map options)
{

}

def executeBuildLinux(Map options)
{

}

def executeBuild(String osName, Map options)
{
    cleanWs(deleteDirs: true, disableDeferredWipeout: true)
    try {
        dir('RadeonProRenderMaxPlugin')
        {
            checkOutBranchOrScm(options['projectBranch'], 'git@github.com:Radeon-Pro/RadeonProRenderMaxPlugin.git')
        }

        outputEnvironmentInfo(osName)

        switch(osName)
        {
        case 'Windows':
            executeBuildWindows(options);
            break;
        case 'OSX':
            executeBuildOSX(options);
            break;
        default:
            executeBuildLinux(options);
        }

        //stash includes: 'Bin/**/*', name: "app${osName}"
    }
    catch (e) {
        currentBuild.result = "FAILED"
        if (options.sendToRBS)
        {
            try {
                options.rbs_dev.setFailureStatus()
                options.rbs_prod.setFailureStatus()
            } catch (err) {
                println(err)
            }
        }
        throw e
    }
    finally {
        archiveArtifacts artifacts: "*.log", allowEmptyArchive: true
    }
}

def executePreBuild(Map options)
{
    if (options['isPreBuilt'])
    {
        //plugin is pre built
        return;
    }

    currentBuild.description = ""
    ['projectBranch'].each
    {
        if(options[it] != 'master' && options[it] != "")
        {
            currentBuild.description += "<b>${it}:</b> ${options[it]}<br/>"
        }
    }
    
    dir('RadeonProRenderMaxPlugin')
    {
        checkOutBranchOrScm(options['projectBranch'], 'git@github.com:Radeon-Pro/RadeonProRenderMaxPlugin.git', true)

        AUTHOR_NAME = bat (
                script: "git show -s --format=%%an HEAD ",
                returnStdout: true
                ).split('\r\n')[2].trim()

        echo "The last commit was written by ${AUTHOR_NAME}."
        options.AUTHOR_NAME = AUTHOR_NAME

        commitMessage = bat ( script: "git log --format=%%B -n 1", returnStdout: true )
        echo "Commit message: ${commitMessage}"
        options.commitMessage = commitMessage.split('\r\n')[2].trim()
        options['commitSHA'] = bat(script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
        options.branchName = bat(script: "git branch --contains", returnStdout: true).split('\r\n')[2].trim()

        if(options['incrementVersion'])
        {
            if("${BRANCH_NAME}" == "master" && "${AUTHOR_NAME}" != "radeonprorender")
            {
                options.testsPackage = "master"
                echo "Incrementing version of change made by ${AUTHOR_NAME}."

                String currentversion=version_read("${env.WORKSPACE}\\RadeonProRenderMaxPlugin\\version.h", '#define VERSION_STR')
                echo "currentversion ${currentversion}"

                new_version=version_inc(currentversion, 3)
                echo "new_version ${new_version}"

                version_write("${env.WORKSPACE}\\RadeonProRenderMaxPlugin\\version.h", '#define VERSION_STR', new_version)

                String updatedversion=version_read("${env.WORKSPACE}\\RadeonProRenderMaxPlugin\\version.h", '#define VERSION_STR')
                echo "updatedversion ${updatedversion}"

                bat """
                    git add version.h
                    git commit -m "buildmaster: version update to ${updatedversion}"
                    git push origin HEAD:master
                   """

                //get commit's sha which have to be build
                options['projectBranch'] = bat ( script: "git log --format=%%H -1 ",
                                    returnStdout: true
                                    ).split('\r\n')[2].trim()

                options['executeBuild'] = true
                options['executeTests'] = true
            }
            else
            {
                options.testsPackage = "smoke"
                if(commitMessage.contains("CIS:BUILD"))
                {
                    options['executeBuild'] = true
                }

                if(commitMessage.contains("CIS:TESTS"))
                {
                    options['executeBuild'] = true
                    options['executeTests'] = true
                }

                if (env.CHANGE_URL)
                {
                    echo "branch was detected as Pull Request"
                    options['executeBuild'] = true
                    options['executeTests'] = true
                    options.testsPackage = "PR"
                }

                if("${BRANCH_NAME}" == "master")
                {
                   echo "rebuild master"
                   options['executeBuild'] = true
                   options['executeTests'] = true
                   options.testsPackage = "master"
                }
            }
        }
        options.pluginVersion = version_read("${env.WORKSPACE}\\RadeonProRenderMaxPlugin\\version.h", '#define VERSION_STR')
    }
    if(options['forceBuild'])
    {
        options['executeBuild'] = true
        options['executeTests'] = true
    }

    currentBuild.description += "<b>Version:</b> ${options.pluginVersion}<br/>"
    if(!env.CHANGE_URL)
    {
        currentBuild.description += "<b>Commit author:</b> ${options.AUTHOR_NAME}<br/>"
        currentBuild.description += "<b>Commit message:</b> ${options.commitMessage}<br/>"
    }

    if (env.BRANCH_NAME && env.BRANCH_NAME == "master") {
        properties([[$class: 'BuildDiscarderProperty', strategy:
                         [$class: 'LogRotator', artifactDaysToKeepStr: '',
                          artifactNumToKeepStr: '', daysToKeepStr: '', numToKeepStr: '10']]]);
    } else if (env.BRANCH_NAME && BRANCH_NAME != "master") {
        properties([[$class: 'BuildDiscarderProperty', strategy:
                         [$class: 'LogRotator', artifactDaysToKeepStr: '',
                          artifactNumToKeepStr: '', daysToKeepStr: '', numToKeepStr: '3']]]);
    } else if (env.JOB_NAME == "RadeonProRenderMaxPlugin-WeeklyFull") {
        properties([[$class: 'BuildDiscarderProperty', strategy:
                         [$class: 'LogRotator', artifactDaysToKeepStr: '',
                          artifactNumToKeepStr: '', daysToKeepStr: '', numToKeepStr: '50']]]);
    } else {
        properties([[$class: 'BuildDiscarderProperty', strategy:
                         [$class: 'LogRotator', artifactDaysToKeepStr: '',
                          artifactNumToKeepStr: '', daysToKeepStr: '', numToKeepStr: '10']]]);
    }

    def tests = []
    options.groupsRBS = []

    if(options.testsPackage != "none")
    {
        dir('jobs_test_max')
        {
            checkOutBranchOrScm(options['testsBranch'], 'git@github.com:luxteam/jobs_test_max.git')
            // json means custom test suite. Split doesn't supported
            if(options.testsPackage.endsWith('.json'))
            {
                def testsByJson = readJSON file: "jobs/${options.testsPackage}"
                testsByJson.each() {
                    options.groupsRBS << "${it.key}"
                }
                options.splitTestsExecution = false
            }
            else {
                String tempTests = readFile("jobs/${options.testsPackage}")
                tempTests.split("\n").each {
                    // TODO: fix: duck tape - error with line ending
                    tests << "${it.replaceAll("[^a-zA-Z0-9_]+","")}"
                }
                options.tests = tests
                options.testsPackage = "none"
                options.groupsRBS = tests
            }
        }
    }
    else
    {
        options.tests.split(" ").each()
        {
            tests << "${it}"
        }
        options.tests = tests
        options.groupsRBS = tests
    }

    println(options.groupsRBS)

    if(options.splitTestsExecution) {
        options.testsList = options.tests
    }
    else {
        options.testsList = ['']
        options.tests = tests.join(" ")
    }

    if (options.sendToRBS)
    {
        try
        {
            options.rbs_prod.startBuild(options)
            options.rbs_dev.startBuild(options)
        }
        catch (e)
        {
            println(e)
        }
    }

}

def executeDeploy(Map options, List platformList, List testResultList)
{
    try {
        if(options['executeTests'] && testResultList)
        {
            checkOutBranchOrScm(options['testsBranch'], 'git@github.com:luxteam/jobs_test_max.git')

            dir("summaryTestResults")
            {
                testResultList.each()
                {
                    try {
                        dir("$it".replace("testResult-", ""))
                        {
                            unstash "$it"
                        }
                    }catch(e) {
                        echo "Can't unstash ${it}"
                    }
                }
            }

            String branchName = env.BRANCH_NAME ?: options.projectBranch
            try
            {
                withEnv(["JOB_STARTED_TIME=${options.JOB_STARTED_TIME}"])
                {
                    dir("jobs_launcher") {
                        if (options['isPreBuilt'])
                        {
                            bat """
                            build_reports.bat ..\\summaryTestResults "${escapeCharsByUnicode('3ds Max')}" "PreBuilt" "PreBuilt" "PreBuilt"
                            """
                        }
                        else
                        {
                            bat """
                            build_reports.bat ..\\summaryTestResults "${escapeCharsByUnicode('3ds Max')}" ${options.commitSHA} ${branchName} \"${escapeCharsByUnicode(options.commitMessage)}\"
                            """
                        }
                    }
                }
            } catch(e) {
                println("ERROR during report building")
                println(e.toString())
                println(e.getMessage())
            }

            try
            {
                dir("jobs_launcher") {
                    bat "get_status.bat ..\\summaryTestResults"
                }
            }
            catch(e)
            {
                println("ERROR during slack status generation")
                println(e.toString())
                println(e.getMessage())
            }

            try
            {
                def summaryReport = readJSON file: 'summaryTestResults/summary_status.json'
                if (summaryReport.error > 0) {
                    println("Some tests crashed")
                    currentBuild.result="FAILED"
                }
                else if (summaryReport.failed > 0) {
                    println("Some tests failed")
                    currentBuild.result="UNSTABLE"
                }
            }
            catch(e)
            {
                println("CAN'T GET TESTS STATUS")
            }

            try
            {
                options.testsStatus = readFile("summaryTestResults/slack_status.json")
            }
            catch(e)
            {
                println(e.toString())
                println(e.getMessage())
                options.testsStatus = ""
            }

            publishHTML([allowMissing: false,
                         alwaysLinkToLastBuild: false,
                         keepAll: true,
                         reportDir: 'summaryTestResults',
                         reportFiles: 'summary_report.html, performance_report.html, compare_report.html',
                         reportName: 'Test Report',
                         reportTitles: 'Summary Report, Performance Report, Compare Report'])

            if (options.sendToRBS) {
                try {
                    String status = currentBuild.result ?: 'SUCCESSFUL'
                    options.rbs_dev.finishBuild(options, status)
                    options.rbs_prod.finishBuild(options, status)
                } catch (e) {
                    println(e.getMessage())
                }
            }
        }
    }
    catch (e) {
        currentBuild.result = "FAILED"
        println(e.toString());
        throw e
    }
    finally
    {}
}

def appendPlatform(String filteredPlatforms, String platform) {
    if (filteredPlatforms)
    {
        filteredPlatforms +=  ";" + platform
    } 
    else 
    {
        filteredPlatforms += platform
    }
    return filteredPlatforms
}

def call(String projectBranch = "",
        String testsBranch = "master",
        String platforms = 'Windows:AMD_RXVEGA,AMD_WX9100,AMD_WX7100,NVIDIA_GF1080TI',
        Boolean updateRefs = false,
        Boolean enableNotifications = true,
        Boolean incrementVersion = true,
        Boolean skipBuild = false,
        String renderDevice = "2",
        String testsPackage = "",
        String tests = "",
        String toolVersion = "2020",
        Boolean forceBuild = false,
        Boolean splitTestsExecution = false,
        Boolean sendToRBS = true,
        String resX = '0',
        String resY = '0',
        String SPU = '25',
        String iter = '50',
        String theshold = '0.05',
        String customBuildLinkWindows = "") 
{
    resX = (resX == 'Default') ? '0' : resX
    resY = (resY == 'Default') ? '0' : resY
    SPU = (SPU == 'Default') ? '60' : SPU
    iter = (iter == 'Default') ? '120' : iter
    theshold = (theshold == 'Default') ? '0.01' : theshold
    try
    {
        Boolean isPreBuilt = customBuildLinkWindows.length() > 0

        if (isPreBuilt)
        {
            //remove platforms for which pre built plugin is not specified
            String filteredPlatforms = ""

            platforms.split(';').each()
            { platform ->
                List tokens = platform.tokenize(':')
                String platformName = tokens.get(0)

                switch(platformName)
                {
                case 'Windows':
                    if (customBuildLinkWindows)
                    {
                        filteredPlatforms = appendPlatform(filteredPlatforms, platform)
                    }
                    break;
                }
            }

            platforms = filteredPlatforms
        }

        // if (tests == "" && testsPackage == "none") { currentBuild.setKeepLog(true) }

        String PRJ_NAME="RadeonProRenderMaxPlugin"
        String PRJ_ROOT="rpr-plugins"
        gpusCount = 0
        platforms.split(';').each()
        { platform ->
            List tokens = platform.tokenize(':')
            if (tokens.size() > 1)
            {
                gpuNames = tokens.get(1)
                gpuNames.split(',').each()
                {
                    gpusCount += 1
                }
            }
        }

        rbs_prod = new RBSProduction(this, "Max", env.JOB_NAME, env)
        rbs_dev = new RBSDevelopment(this, "Max", env.JOB_NAME, env)

        multiplatform_pipeline(platforms, this.&executePreBuild, this.&executeBuild, this.&executeTests, this.&executeDeploy,
                               [projectBranch:projectBranch,
                                testsBranch:testsBranch,
                                updateRefs:updateRefs,
                                enableNotifications:enableNotifications,
                                PRJ_NAME:PRJ_NAME,
                                PRJ_ROOT:PRJ_ROOT,
                                incrementVersion:incrementVersion,
                                skipBuild:skipBuild,
                                renderDevice:renderDevice,
                                testsPackage:testsPackage,
                                tests:tests,
                                toolVersion:toolVersion,
                                executeBuild:false,
                                executeTests:isPreBuilt,
                                isPreBuilt:isPreBuilt,
                                forceBuild:forceBuild,
                                reportName:'Test_20Report',
                                splitTestsExecution:splitTestsExecution,
                                sendToRBS: sendToRBS,
                                gpusCount:gpusCount,
                                TEST_TIMEOUT:720,
                                TESTER_TAG:'Max',
                                rbs_prod: rbs_prod,
                                rbs_dev: rbs_dev,
                                resX: resX,
                                resY: resY,
                                SPU: SPU,
                                iter: iter,
                                theshold: theshold,
                                customBuildLinkWindows: customBuildLinkWindows
                                ])
        }
        catch (e) {
            currentBuild.result = "FAILED"
            println(e.toString());
            println(e.getMessage());

            throw e
        }
}

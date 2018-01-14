def executePlatform(String osName, String gpuNames, Closure executeBuild, Closure executeTests, Closure executeDeploy, Map options)
{
    def retNode =  
    {
        try {
            node("${osName} && Builder")
            {
                stage("Build-${osName}")
                {
                    String JOB_NAME_FMT="${JOB_NAME}".replace('%2F', '_')
                    ws("WS/${JOB_NAME_FMT}") {
                        executeBuild(osName, options)
                    }
                }
            }

            if(gpuNames)
            {
                def testTasks = [:]
                gpuNames.split(',').each()
                {
                    String asicName = it
                    echo "Scheduling Test ${osName}:${asicName}"
                    testTasks["Test-${it}-${osName}"] = {
                        node("${osName} && Tester && OpenCL && gpu${asicName}")
                        {
                            stage("Test-${asicName}-${osName}")
                            {
                                executeTests(osName, asicName, options)
                            }
                        }
                    }
                }
                parallel testTasks
            }
            else
            {
                echo "No tests found for ${osName}"
            }
        }
        catch (e) {
            println(e.toString());
            println(e.getMessage());
            println(e.getStackTrace());        
            currentBuild.result = "FAILED"
            throw e
        }
    }
    return retNode
}

def call(String platforms, 
         Closure executeBuild, Closure executeTests, Closure executeDeploy, Map options) {
      
    try {
        timestamps {
            def tasks = [:]
            
            platforms.split(';').each()
            {
                def (osName, gpuNames) = it.tokenize(':')
                                
                tasks[osName]=executePlatform(osName, gpuNames, executeBuild, executeTests, executeDeploy, options)
            }
            parallel tasks
        }
    }
    catch (e) {
        currentBuild.result = "FAILED"
    }
    finally {
        if(options[EnableNotifications] == "true")
        {
            sendBuildStatusNotification(currentBuild.result)
        }
    }
}

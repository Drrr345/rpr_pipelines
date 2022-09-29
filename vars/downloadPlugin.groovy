def runCurl(String curlCommand, Integer tries=5, Integer oneTryTimeout=90) {
    Integer currentTry = 0
    while (currentTry++ < tries) {
        println("[INFO] Try to download plugin through curl (try #${currentTry})")
        try {
            timeout(time: oneTryTimeout, unit: "SECONDS") {
                if (isUnix()) {
                    sh """
                        ${curlCommand}
                    """
                } else {
                    bat """
                        ${curlCommand}
                    """
                }
            }
            break
        } catch (e) {
            println("[ERROR] Failed to download plugin during try #${currentTry}: ${e.getMessage()}")
            if (currentTry == tries) {
                throw e
            }
        }
    }
}


def call(String osName, Map options, String credentialsId = '', Integer oneTryTimeout = 90) {
    String customBuildLink = ""
    String extension = options["configuration"]["productExtensions"][osName]
    // the name of the artifact without OS name / version. It must be same for any OS / version
    String artifactNameBase = options["configuration"]["artifactNameBase"]

    switch(osName) {
        case 'Windows':
            customBuildLink = options['customBuildLinkWindows']
            break
        case 'OSX':
            customBuildLink = options['customBuildLinkOSX']
            break
        case 'MacOS':
            customBuildLink = options['customBuildLinkMacOS']
            break
        case 'MacOS_ARM':
            customBuildLink = options['customBuildLinkMacOSARM']
            break
        case 'Ubuntu':
            customBuildLink = options['customBuildLinkLinux']
            break
        case 'Ubuntu18':
            customBuildLink = options['customBuildLinkUbuntu18']
            break
        // Ubuntu20
        default:
            customBuildLink = options['customBuildLinkUbuntu20']
    }

    print "[INFO] Used specified pre built plugin."

    if (customBuildLink.startsWith("https://builds.rpr")) {
        withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'builsRPRCredentials', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
            runCurl("curl --insecure -L -o ${artifactNameBase}_${osName}.${extension} -u $USERNAME:$PASSWORD \"${customBuildLink}\"", 5, oneTryTimeout)
        }
    } else if (customBuildLink.startsWith("https://rpr.cis")) {
        withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'jenkinsCredentials', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
            runCurl("curl --insecure -L -o ${artifactNameBase}_${osName}.${extension} -u $USERNAME:$PASSWORD \"${customBuildLink}\"", 5, oneTryTimeout)
        }
    } else if (customBuildLink.startsWith("/CIS/")) {
        downloadFiles("/volume1${customBuildLink}", ".")
        if (isUnix()) {
            sh """
                mv ${customBuildLink.split("/")[-1]} ${artifactNameBase}_${osName}.${extension}
            """
        } else {
            bat """
                move ${customBuildLink.split("/")[-1]} ${artifactNameBase}_${osName}.${extension}
            """
        }
    } else if (customBuildLink.contains("cis.nas")) {
        runCurl("curl --insecure -L -o ${artifactNameBase}_${osName}.${extension} \"${customBuildLink}\"", 5, oneTryTimeout)
    } else {
        if (credentialsId) {
            withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: credentialsId, usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
                runCurl("curl -L -o ${artifactNameBase}_${osName}.${extension} -u $USERNAME:$PASSWORD \"${customBuildLink}\"", 5, oneTryTimeout)
            }
        } else {
            runCurl("curl --insecure -L -o ${artifactNameBase}_${osName}.${extension} \"${customBuildLink}\"", 5, oneTryTimeout)
        }
    }

    validatePlugin(osName, "${artifactNameBase}_${osName}.${extension}", options)

    // We haven't any branch so we use sha1 for identifying plugin build
    def pluginSha = sha1 "${artifactNameBase}_${osName}.${extension}"
    println "Downloaded plugin sha1: ${pluginSha}"

    options[getProduct.getIdentificatorKey(osName, options)] = pluginSha
}

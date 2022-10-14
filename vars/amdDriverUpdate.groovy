import groovy.transform.Field
import utils

@Field def newerDriverInstalled = false


def main(Map options) {
    timestamps {
        def updateTasks = [:]

        options.platforms.split(';').each() {
            if (it) {
                List tokens = it.tokenize(':')
                String osName = tokens.get(0)
                String gpuNames = ""

                Map newOptions = options.clone()
                newOptions["osName"] = osName

                if (tokens.size() > 1) {
                    gpuNames = tokens.get(1)
                }

                planUpdate(osName, gpuNames, newOptions, updateTasks)
            }
        }
        
        parallel updateTasks

        if (newerDriverInstalled) {
            withCredentials([string(credentialsId: "TAN_JOB_NAME", variable: "jobName"), string(credentialsId: "TAN_DEFAULT_BRANCH", variable: "defaultBranch")]) {
                build(
                    job: jobName + "/" + defaultBranch,
                    quietPeriod: 0,
                    wait: false
                )
            }
        }

        return 0
    }
}


def planUpdate(osName, gpuNames, options, updateTasks) {
    def gpuLabels = gpuNames.split(",").collect{"gpu${it}"}.join(" || ")
    def labels = "${osName} && (${gpuLabels})"

    if (options.tags) {
        labels = "${labels} && (${options.tags})"
    }
    nodes = nodesByLabel labels

    println("---SELECTED NODES (${osName}):")
    println(nodes)

    nodes.each() {
        updateTasks["${it}"] = {
            stage("Driver update ${it}") {
                node("${it}") {
                    utils.updateDriver(options, osName, "${it}")
                }
            }
        }
    }
}


def call(Boolean productionDriver = False,
        String platforms = "",
        String tags = "",
        String driverVersion = "")
{
    main([productionDriver:productionDriver,
        platforms:platforms,
        tags:tags,
        driverVersion:driverVersion])
}

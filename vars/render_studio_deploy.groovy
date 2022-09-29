def call(
    String deployEnvironment = 'dev'
) {
    node("RenderStudioServer") {
        dir("/usr/share/webusd/${deployEnvironment}") {
            sh """
                docker-compose -f ${deployEnvironment}.yml down
                docker-compose -f ${deployEnvironment}.yml pull
                docker-compose -f ${deployEnvironment}.yml up -d
            """
        }
    } 
}

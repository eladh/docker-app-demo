server = Artifactory.server "artifactory"
rtFullUrl = server.url
rtIpAddress = rtFullUrl - ~/^http?.:\/\// - ~/\/artifactory$/

buildInfo = Artifactory.newBuildInfo()

setNewProps();

podTemplate(label: 'jenkins-pipeline' , cloud: 'k8s' , containers: [
        containerTemplate(name: 'docker', image: 'docker', command: 'cat', ttyEnabled: true , privileged: true)],
        volumes: [hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock')]) {

    node('jenkins-pipeline') {

        stage('Cleanup') {
            cleanWs()
        }

        stage('Clone sources') {
            git url: 'https://github.com/eladh/docker-app-demo.git', credentialsId: 'github'
        }

        stage('Download Dependencies') {
            try {
                def pipelineUtils = load 'pipelineUtils.groovy'

                pipelineUtils.downloadArtifact(rtFullUrl, "gradle-local", "*demo-gradle/*", "jar", buildInfo, false)
                pipelineUtils.downloadArtifact(rtFullUrl, "npm-local", "*client-app*", "tgz", buildInfo, true)
            } catch (Exception e) {
                println "Caught Exception during resolution. Message ${e.message}"
                throw e as java.lang.Throwable
            }
        }

        stage('Docker build') {
            def rtDocker = Artifactory.docker server: server

            container('docker') {
                docker.withRegistry("https://docker.$rtIpAddress", 'artifactorypass') {
                    sh("chmod 777 /var/run/docker.sock")
                    def dockerImageTag = "docker.$rtIpAddress/docker-app:${env.BUILD_NUMBER}"
                    def dockerImageTagLatest = "docker.$rtIpAddress/docker-app:latest"

                    buildInfo.env.capture = true


                    docker.build(dockerImageTag, "--build-arg DOCKER_REGISTRY_URL=docker.$rtIpAddress .")
                    docker.build(dockerImageTagLatest, "--build-arg DOCKER_REGISTRY_URL=docker.$rtIpAddress .")


                    rtDocker.push(dockerImageTag, "docker-local", buildInfo)
                    rtDocker.push(dockerImageTagLatest, "docker-local", buildInfo)
                    server.publishBuildInfo buildInfo
                }
            }
        }
    }
}


podTemplate(label: 'dind-template' , cloud: 'k8s' , containers: [
        containerTemplate(name: 'dind', image: 'odavid/jenkins-jnlp-slave:latest',
                command: '/usr/local/bin/wrapdocker', ttyEnabled: true , privileged: true)]) {


    node('dind-template') {
        stage('Docker dind') {
            container('dind') {

                withCredentials([string(credentialsId: 'artipublickey', variable: 'CERT')]) {
                    sh "mkdir -p /etc/docker/certs.d/docker.$rtIpAddress"
                    sh "echo '${CERT}' |  base64 -d >> /etc/docker/certs.d/docker.$rtIpAddress/artifactory.crt"
                }

                docker.withRegistry("https://docker.$rtIpAddress", 'artifactorypass') {
                    sh("docker ps")
                    tag = "docker.$rtIpAddress/docker-app:${env.BUILD_NUMBER}"

                    docker.image(tag).withRun('-p 9191:81 -e “SPRING_PROFILES_ACTIVE=local” ') { c ->
                        sleep 10
                        def stdout = sh(script: 'curl http://localhost:9191/index.html', returnStdout: true)
                        println stdout
                        if (stdout.contains("client-app")) {
                            println "*** Passed Test: " + stdout
                            println "*** Passed Test"
                            return true
                        } else {
                            println "*** Failed Test: " + stdout
                            return false
                        }
                    }
                }
            }
        }
    }
}

podTemplate(label: 'helm-template' , cloud: 'k8s' , containers: [
        containerTemplate(name: 'jfrog-cli', image: 'docker.bintray.io/jfrog/jfrog-cli-go:latest', command: 'cat', ttyEnabled: true) ,
        containerTemplate(name: 'helm', image: 'alpine/helm:latest', command: 'cat', ttyEnabled: true) ]) {

    node('helm-template') {
        stage('Build Chart & push it to Artifactory') {

            git url: 'https://github.com/eladh/docker-app-demo.git', credentialsId: 'github'
            def pipelineUtils = load 'pipelineUtils.groovy'

            def aqlString = 'items.find ({"repo":"docker-local","type":"folder","$and":' +
                    '[{"path":{"$match":"docker-app*"}},{"path":{"$nmatch":"docker-app/latest"}}]' +
                    '}).include("path","created","name").sort({"$desc" : ["created"]}).limit(1)'


            def artifactInfo = pipelineUtils.executeAql(rtFullUrl, aqlString)

            println "docker === " +  artifactInfo ? artifactInfo.name : "latest"

            container('helm') {
                sh "helm init --client-only"
                sh "helm package helm-chart-docker-app"

            }
            container('jfrog-cli') {
                withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'artifactorypass', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
                    sh "jfrog rt c beta --user ${USERNAME} --password ${PASSWORD} --url ${rtFullUrl} < /dev/null"
                }
            }
        }
    }
}


podTemplate(label: 'promote-template' , cloud: 'k8s' , containers: []) {

    node('promote-template') {
        stage('Xray') {
            if (XRAY_SCAN == "YES") {
                java.util.LinkedHashMap<java.lang.String, java.lang.Boolean> xrayConfig = [
                        'buildName' : env.JOB_NAME,
                        'buildNumber' : env.BUILD_NUMBER,
                        'failBuild' : false
                ]
                def xrayResults = server.xrayScan xrayConfig

                if (xrayResults.isFoundVulnerable()) {
                    error('Stopping early… got Xray issues ')
                }
            } else {
                println "No Xray scan performed. To enable set XRAY_SCAN = YES"
            }
        }

        stage('Promote Docker image') {
            java.util.LinkedHashMap<java.lang.String, java.lang.Object> promotionConfig = [
                    'buildName'  : buildInfo.name,
                    'buildNumber': buildInfo.number,
                    'targetRepo' : "docker-prod-local",
                    'comment'    : 'This is a stable docker image',
                    'status'     : 'Released',
                    'sourceRepo' : 'docker-stage-local',
                    'copy'       : true,
                    'failFast'   : true
            ]
            server.promote promotionConfig
        }
    }
}

void setNewProps() {
    if  (params.XRAY_SCAN == null) {
        properties([parameters([string(name: 'XRAY_SCAN', defaultValue: 'NO')])])
        currentBuild.result = 'SUCCESS'
        error('Aborting the build to generate params')
    }
}



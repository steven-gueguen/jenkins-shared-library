def call(Map pipelineParams) {

    pipeline {
        agent any

        options {
            disableConcurrentBuilds()
            buildDiscarder(logRotator(numToKeepStr: '3'))
            timestamps()
        }

        environment {
            GPG_KEY_NAME = "${env.gpgKeyName}"
            NEXUS_HOST = "${env.nexusHost}"
            NEXUS_CREDS = credentials('nexus')
            OSSRH_CREDS = credentials('ossrh')
            GRADLE_CMD = './gradlew \
                -Psigning.gnupg.keyName=$GPG_KEY_NAME \
                -PossrhUsername=$OSSRH_CREDS_USR \
                -PossrhPassword=$OSSRH_CREDS_PSW \
                -PnexusHost=$NEXUS_HOST \
                -PnexusUsername=$NEXUS_CREDS_USR \
                -PnexusPassword=$NEXUS_CREDS_PSW'
        }

        stages {
            stage('Checkout') {
                steps {
                    script {
                        env.version = ""
                        notify.slack('STARTED')
                    }
                    git url: pipelineParams.scmUrl
                    script {
                        env.version = gitUtils.getVersion()
                    }
                }
            }

            stage('Build') {
                steps {
                    sh "$GRADLE_CMD clean build -x test"
                }
            }

            stage('Test') {
                steps {
                    sh "$GRADLE_CMD test"
                }
            }

            stage('Publish to: Local Nexus and Maven Central') {
                when {
                    beforeInput true
                    expression { gitUtils.isTag() }
                }
                options {
                    timeout(time: 4, unit: 'DAYS')
                }
                input {
                    message "Should we deploy module to 'Local Nexus' and 'Maven Central'?"
                }
                steps {
                    sh "$GRADLE_CMD publish closeAndReleaseStagingRepository"
                }
            }

            stage("Wait for module to be avaible on maven central") {
                steps {
                    sh "sleep 900" // Wait 15 minutes for the components to be available on Maven Central
                }
            }

            stage("Publish to WarpFleet") {
                steps {
                    nvm('version':'v16.18.0') {
                        sh "wf publish --gpg=${env.warpfleetGPG} --gpgArg='--batch' ${env.version} https://repo.maven.apache.org/maven2"
                        sh "wf publish --gpg=${env.warpfleetGPG} --gpgArg='--batch' ${env.version}-uberjar https://repo.maven.apache.org/maven2"
                    }
                }
            }
        }
        post {
            success {
                script {
                    notify.slack('SUCCESSFUL')
                }
            }
            failure {
                script {
                    notify.slack('FAILURE')
                }
            }
            aborted {
                script {
                    notify.slack('ABORTED')
                }
            }
            unstable {
                script {
                    notify.slack('UNSTABLE')
                }
            }
        }
    }
}
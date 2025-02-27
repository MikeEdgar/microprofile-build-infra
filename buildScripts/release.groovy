def modules = ['microprofile','microprofile-bom', 'microprofile-parent', 'microprofile-config',
    'microprofile-context-propagation','microprofile-fault-tolerance',
	'microprofile-health','microprofile-jwt-auth','microprofile-metrics',
	'microprofile-open-api','microprofile-opentracing','microprofile-rest-client',
	'microprofile-reactive-streams-operators', 'microprofile-reactive-messaging',
    'microprofile-lra', 'microprofile-graphql', 'microprofile-telemetry']
def moduleString = modules.join('\n')
pipeline {
    agent any
    tools {
        maven 'apache-maven-latest'
        jdk 'adoptopenjdk-hotspot-jdk8-latest'
    }
    parameters {
        string(description: 'The next snapshot version', name: 'snapshotVersion')
        string(description: 'The release version', name: 'releaseVersion')
        string(description: 'The SCM tag to apply', name: 'tag')
        choice(choices: 'Draft\nFinal', description: 'Revision Type', name: 'revremark')
        choice(choices: moduleString, description: 'Module', name: 'module')
        string(description: 'Branch to use', name: 'branch', defaultValue: 'master')
        string(description: 'A list of the staging repositories to be used for the build', name: 'stagingList', defaultValue: '')
    }

    stages {
        stage("Checkout") {
            steps {
                dir("${params.module}") {
                    git credentialsId: 'github-bot-ssh', url: "git@github.com:eclipse/${params.module}.git", branch: params.branch
                }
            }
        }
        stage("Execute Release") {
            steps {
                dir("${params.module}") {
                    withCredentials([file(credentialsId: 'secret-subkeys.asc', variable: 'KEYRING')]) {
                        sh 'gpg --batch --import "${KEYRING}"'
                        sh 'for fpr in $(gpg --list-keys --with-colons  | awk -F: \'/fpr:/ {print $10}\' | sort -u); do echo -e "5\ny\n" |  gpg --batch --command-fd 0 --expert --edit-key ${fpr} trust; done'
                    }
                    sshagent(['github-bot-ssh']) {
                        sh '''
                            git config --global user.email "microprofile-bot@eclipse.org"
                            git config --global user.name "Eclipse MicroProfile bot"
                        '''

                        script {
                            def settings = '-s /home/jenkins/.m2/settings.xml'

                            if (params.stagingList != '') {
                                sh "wget https://github.com/eclipse/microprofile-build-infra/raw/master/buildScripts/staging-augmenter-ubi8"
                                sh "chmod +x ./staging-augmenter-ubi8"
                                sh "./staging-augmenter-ubi8 -r ${params.stagingList} -o ../output-settings.xml /home/jenkins/.m2/settings.xml"

                                settings = '-s ../output-settings.xml -Pmp-staging'
                            }

                            if (params.module == 'microprofile-metrics' || params.module == 'microprofile-telemetry' || params.module == 'microprofile-parent' || params.module == 'microprofile-open-api') {
                                withEnv(["JAVA_HOME=${tool 'adoptopenjdk-hotspot-jdk11-latest'}", "PATH=${tool 'adoptopenjdk-hotspot-jdk11-latest'}/bin:${env.PATH}"]) {
                                    sh "mvn -q -N io.takari:maven:0.7.7:wrapper -Dmaven=3.9.0 && ./mvnw -v"
                                    sh "./mvnw ${settings} release:prepare release:perform -B -Dtag=${params.tag} -DdevelopmentVersion=${params.snapshotVersion} -DreleaseVersion=${params.releaseVersion} -Drevremark=${params.revremark} -Drelease.revision=${params.revremark} -DstagingProgressTimeoutMinutes=20"
                                }
                            } else {
                                // execute with JDK 8 defined in the tools section
                                sh "mvn ${settings} release:prepare release:perform -B -Dtag=${params.tag} -DdevelopmentVersion=${params.snapshotVersion} -DreleaseVersion=${params.releaseVersion} -Drevremark=${params.revremark} -Drelease.revision=${params.revremark} -DstagingProgressTimeoutMinutes=20"
                            }

                        }
                    }
                }
            }
        }
        stage("Copy Specs") {
            when {
                expression { params.module != "microprofile-parent" }
            }
            steps {
                dir("${params.module}") {
                    sshagent(['projects-storage.eclipse.org-bot-ssh']) {
                        
                        script {
                            if (fileExists('spec')) {
                                sh "ssh genie.microprofile@projects-storage.eclipse.org mkdir -p /home/data/httpd/download.eclipse.org/microprofile/staging/${params.module}-${params.releaseVersion}"
                                sh "scp -r spec/target/generated-docs/* genie.microprofile@projects-storage.eclipse.org:/home/data/httpd/download.eclipse.org/microprofile/staging/${params.module}-${params.releaseVersion}"
                            }
                            if (fileExists('api')) {
                                sh "scp api/target/*.jar genie.microprofile@projects-storage.eclipse.org:/home/data/httpd/download.eclipse.org/microprofile/staging/${params.module}-${params.releaseVersion}/"

                                sh "ssh genie.microprofile@projects-storage.eclipse.org mkdir -p /home/data/httpd/download.eclipse.org/microprofile/staging/${params.module}-${params.releaseVersion}/apidocs"
                                sh "scp -r api/target/apidocs/* genie.microprofile@projects-storage.eclipse.org:/home/data/httpd/download.eclipse.org/microprofile/staging/${params.module}-${params.releaseVersion}/apidocs"
                            }
                            if (fileExists('tck')) {
                                sh "find tck -name \"*.jar\" | xargs -I{} scp {} genie.microprofile@projects-storage.eclipse.org:/home/data/httpd/download.eclipse.org/microprofile/staging/${params.module}-${params.releaseVersion}/"
                            }
                            if (fileExists('tracing/spec')) {
                                sh "ssh genie.microprofile@projects-storage.eclipse.org mkdir -p /home/data/httpd/download.eclipse.org/microprofile/staging/${params.module}-${params.releaseVersion}/tracing"
                                sh "scp -r tracing/spec/target/generated-docs/* genie.microprofile@projects-storage.eclipse.org:/home/data/httpd/download.eclipse.org/microprofile/staging/${params.module}-${params.releaseVersion}/tracing"
                            }
                            if (fileExists('tracing/api')) {
                                sh "scp tracing/api/target/*.jar genie.microprofile@projects-storage.eclipse.org:/home/data/httpd/download.eclipse.org/microprofile/staging/${params.module}-${params.releaseVersion}/tracing"

                                sh "ssh genie.microprofile@projects-storage.eclipse.org mkdir -p /home/data/httpd/download.eclipse.org/microprofile/staging/${params.module}-${params.releaseVersion}/tracing/apidocs"
                                sh "scp -r tracing/api/target/apidocs/* genie.microprofile@projects-storage.eclipse.org:/home/data/httpd/download.eclipse.org/microprofile/staging/${params.module}-${params.releaseVersion}/tracing/apidocs"
                            }
                            if (fileExists('tracing/tck')) {
                                sh "find tck -name \"*.jar\" | xargs -I{} scp {} genie.microprofile@projects-storage.eclipse.org:/home/data/httpd/download.eclipse.org/microprofile/staging/${params.module}-${params.releaseVersion}/tracing/"
                            }
                        }
                    }
                }
            }
        }
    }
    post {
        always {
            script {
                if (params.module != "microprofile-parent") {
                    dir("${params.module}") {
                        archive 'spec/target/generated-docs/*'
                        sh "rm -f ../output-settings.xml"\
                    }
                }
            }
            deleteDir()
        }
    }
}

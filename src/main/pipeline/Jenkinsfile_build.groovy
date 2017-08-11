#!/usr/bin/env groovy
package src.pipeline

def PROJECT_OSS_INTERNAL="ssh://git@gitlab.internal:20022/home1-oss/oss-internal.git"

stage("Prepare") {
    node {
        timestamps {
            step([$class: 'WsCleanup'])
            def paramMap = parseParam(env)
            writeFile(file: 'data.zip', text: paramMap.inspect(), encoding: 'utf-8')
            sh "ls -la ${pwd()}"
            stash "data.zip"

            git credentialsId: 'jenkinsfile', branch: 'develop', url: "${paramMap.PROJECT_OSS_INTERNAL}"
            // stash resources for the moment
            def ID_RSA_FILE = "src/main/jenkins/"
            def SECURITY_FILE = "src/main/maven/settings-security.xml"
            dir("src/main/jenkins/") {
                if (paramMap.ENV != null && fileExists("${paramMap.ENV}")) {
                    dir("${paramMap.ENV}") {
                        stash name: "id_rsa", includes: "id_rsa"
                        ID_RSA_FILE += "${paramMap.ENV}/id_rsa"
                    }
                } else {
                    stash name: "id_rsa", includes: "id_rsa"
                    ID_RSA_FILE += "id_rsa"
                }
            }
            // maven's settings-security.xml
            dir("src/main/maven/") {
                stash name: "security", includes: "settings-security.xml"
            }
            initEnvDir(ID_RSA_FILE, SECURITY_FILE)

            // docker auth，ci.sh also will download this file now
//            dir("src/main/docker/") {
//                if (fileExists("config.json")) {
//                    sh "if [ ! -d ~/.docker/ ]; then mkdir -p ~/.docker/; fi"
//                    sh "cp config.json ~/.docker/"
//                }
//            }

            unstash "id_rsa"
            def workspace = pwd()
            sh "chmod 400 ${workspace}/id_rsa"
            sh "ls -la ${pwd()}"

            def projectName = "${paramMap.PROJECT}"  // Project Name
            // checkout source code
            withEnv(["GIT_SSH_COMMAND=ssh -i ${workspace}/id_rsa -F /dev/null"]) {
                sh "git clone -b ${paramMap.BRANCH} ${paramMap.GIT_REPO_URL} "

                dir(projectName) {
                    sh "git status"
                    sh 'ls -R | grep ":$" | sed -e "s/:$//" -e "s/[^-][^\\/]*\\//--/g" -e "s/^/   /" -e "s/-/|/"'
                }
                // Note: set useDefaultExcludes false will include all files, otherwise some files will not been included
                // http://ant.apache.org/manual/dirtasks.html#defaultexcludes
                stash name: projectName, includes: "${projectName}/**/*", useDefaultExcludes: false
            }
        }

    }
}

stage('Sonar') {
    node {
        timestamps {
            echo "---------start sonar analyze---------"
            step([$class: 'WsCleanup'])

            unstash "id_rsa"
            unstash "security"
            unstash "data.zip"
            sh "ls -lh ./"
            def data = readFile encoding: 'utf-8', file: 'data.zip'
            def paramMap = Eval.me(data)
            echo "param:" + paramMap.inspect()
            unstash "${paramMap.PROJECT}"

            withEnv(["CI_BUILD_REF_NAME=${paramMap.BRANCH}", "CI_PROJECT_URL=${paramMap.GIT_HTTP_URL}",
                     "MAVEN_SETTINGS_SECURITY_FILE=${pwd()}/settings-security.xml",
                     "BUILD_SITE=${paramMap.BUILD_SITE}", "DOCKER_REGISTRY=${paramMap.DOCKER_REGISTRY}"]) {
                dir("${paramMap.PROJECT}") {
                    sh 'bash ci.sh analysis'
                }
            }
            echo "---------end sonar analyze---------"
        }
    }
}

stage('Build') {
    node {
        timestamps {
            // Clean working directory before build
            echo "---------start test_and_build---------"
            step([$class: 'WsCleanup'])

            unstash "id_rsa"
            unstash "security"
            unstash "data.zip"
            sh "ls -lh ./"
            def data = readFile encoding: 'utf-8', file: 'data.zip'
            def paramMap = Eval.me(data)
            echo "param:" + paramMap.inspect()
            unstash "${paramMap.PROJECT}"

            withEnv(["CI_BUILD_REF_NAME=${paramMap.BRANCH}", "CI_PROJECT_URL=${paramMap.GIT_HTTP_URL}",
                     "MAVEN_SETTINGS_SECURITY_FILE=${pwd()}/settings-security.xml",
                     "BUILD_SITE=${paramMap.BUILD_SITE}", "DOCKER_REGISTRY=${paramMap.DOCKER_REGISTRY}"]) {
                // some block
                dir("${paramMap.PROJECT}") {
                    sh 'git status'
                    echo "starting build ${paramMap.PROJECT} version : ${version()}"
                    sh 'bash ci.sh test_and_build'
                    dir('target') {
                        echo "the artifact is:"
                        sh 'ls -R | grep ":$" | sed -e "s/:$//" -e "s/[^-][^\\/]*\\//--/g" -e "s/^/   /" -e "s/-/|/"'
                    }
                }
                stash name: "${paramMap.PROJECT}", includes: "${paramMap.PROJECT}/**/*", useDefaultExcludes: false
            }
            echo "---------end test_and_build---------"
        }
    }
}


stage('Publish') {
    node {
        timestamps {
            // Clean working directory before build
            echo "---------start publish_snapshot---------"
            step([$class: 'WsCleanup'])
            sh "ls -la ${pwd()}"

            unstash "id_rsa"
            unstash "security"
            unstash "data.zip"
            def data = readFile encoding: 'utf-8', file: 'data.zip'
            def paramMap = Eval.me(data)
            echo "param:" + paramMap.inspect()

            unstash "${paramMap.PROJECT}"
            sh 'ls -R | grep ":$" | sed -e "s/:$//" -e "s/[^-][^\\/]*\\//--/g" -e "s/^/   /" -e "s/-/|/"'

            withEnv(["CI_BUILD_REF_NAME=${paramMap.BRANCH}", "CI_PROJECT_URL=${paramMap.GIT_HTTP_URL}",
                     "MAVEN_SETTINGS_SECURITY_FILE=${pwd()}/settings-security.xml",
                     "BUILD_SITE=${paramMap.BUILD_SITE}", "DOCKER_REGISTRY=${paramMap.DOCKER_REGISTRY}"]) {
                dir("${paramMap.PROJECT}") {
                    echo "Building ${paramMap.PROJECT} version : ${version()}"

                    if ("${paramMap.BRANCH}" == "develop") {
                        sh 'bash ci.sh publish_snapshot'
                    } else if ("${paramMap.BRANCH}" == "master" || "${paramMap.BRANCH}".startsWith("release.")) {
                        // TODO online release
                        //sh 'bash ci.sh publish_release'
                        sh 'bash ci.sh publish_snapshot'
                    } else {
                        echo "will not trigger publish,as branch is : ${paramMap.BRANCH}"
                    }
                    dir('target') {
                        echo "the artifact is:"
                        sh 'ls -R | grep ":$" | sed -e "s/:$//" -e "s/[^-][^\\/]*\\//--/g" -e "s/^/   /" -e "s/-/|/"'
                    }
                }
            }
            echo "---------end publish_snapshot---------"
        }
    }
}

stage('Done') {
    echo "publish done !!!"
}

/**
 * Get input param
 *
 * @param project
 * @return
 */
def getInputParam() {
    return [
            [$class: 'ChoiceParameterDefinition', choices: 'gitlab.internal\ngit.internal', description: 'Gitlab domain', name: 'GIT_DOMAIN'],
            string(defaultValue: 'oss-eureka', description: 'Project', name: 'PROJECT'),
            string(defaultValue: 'develop', description: 'Branch', name: 'BRANCH'),
            [$class: 'ChoiceParameterDefinition', choices: 'registry.docker.internal\nmirror.docker.internal', description: 'Docker registry', name: 'DOCKER_REGISTRY'],
            [$class: 'ChoiceParameterDefinition', choices: 'true\nfalse', description: 'Build mvnsite', name: 'BUILD_SITE']
    ]
}

def version() {
    def matcher = readFile('pom.xml') =~ '<version>(.+)</version>'
    matcher ? matcher[0][1] : null
}

/**
 * Parse params
 *
 * @return
 */
def parseParam(env) {
    def paramMap = [:]
    def PROJECT_OSS_INTERNAL="git@gitlab.internal:home1-oss/oss-internal.git"
    if (env.gitlabSourceRepoURL == null || env.gitlabSourceRepoURL == "") {
        def GIT_HTTP_URL = "http://gitlab.internal:10080/home1-oss/#PROJECT#"
        def GIT_REPO_URL = "ssh://git@gitlab.internal:20022/home1-oss/#PROJECT#.git"
        timeout(30) { // timeout 30 minutes
            paramMap = input id: 'GenerateBuildParam', message: 'Pick up your parameter', ok: 'Submit', parameters: getInputParam()
        }

        if("${paramMap.GIT_DOMAIN}" != "gitlab.internal" ){
            GIT_REPO_URL = GIT_REPO_URL.replace("gitlab.internal", paramMap.GIT_DOMAIN)
            GIT_HTTP_URL = GIT_HTTP_URL.replace("gitlab.internal", paramMap.GIT_DOMAIN)
            PROJECT_OSS_INTERNAL = PROJECT_OSS_INTERNAL.replace("gitlab.internal", paramMap.GIT_DOMAIN)
        }

        paramMap["GIT_REPO_URL"] = GIT_REPO_URL.replace("#PROJECT#", paramMap.PROJECT)
        paramMap["GIT_HTTP_URL"] = GIT_HTTP_URL.replace("#PROJECT#", paramMap.PROJECT)
    } else {
        echo "projectInfo:${env.gitlabSourceRepoURL} ${env.gitlabSourceRepoName} ${env.gitlabBranch} ${env.gitlabSourceRepoHomepage}"
        echo "buildInfo:${env.gitlabUserName} ${env.gitlabActionType} ${env.gitlabUserEmail}"

        paramMap["PROJECT"] = "${env.gitlabSourceRepoName}" as String
        paramMap["BRANCH"] = "${env.gitlabBranch}" as String
        paramMap["GIT_REPO_URL"] = "${env.gitlabSourceRepoURL}" as String
        paramMap["GIT_HTTP_URL"] = "${env.gitlabSourceRepoHomepage}" as String
        paramMap["BUILD_SITE"] = "true" as String
        paramMap["DOCKER_REGISTRY"] = ""
    }
    paramMap["PROJECT_OSS_INTERNAL"] = PROJECT_OSS_INTERNAL
    println("param is :" + paramMap.inspect())
    return paramMap
}

/**
 * Init directories
 */
def initEnvDir(fileIdRSA, fileSecurity) {
    sh "if [ ! -d ${HOME}/.m2/ ]; then mkdir -p ${HOME}/.m2; fi"
    sh "if [ ! -f ${HOME}/.m2/settings-security.xml ]; then cp -R ${fileSecurity} ${HOME}/" + ".ssh/settings-security.xml; fi"

    sh "if [ ! -d ${HOME}/.ssh/ ]; then mkdir -p ${HOME}/.ssh; fi"
    sh "if [ ! -f ${HOME}/.ssh/mvnsite.internal ]; then cp -R ${fileIdRSA} ${HOME}/.ssh/mvnsite.internal; chmod 400 ${HOME}/.ssh/mvnsite.internal; fi"

    sh "ls -lh ${HOME}/.m2/"
    sh "ls -lh ${HOME}/.ssh/"
}
#!/usr/bin/env groovy

import groovy.json.JsonSlurper
import groovy.json.JsonBuilder

// internal private project for info security
def PROJECT_OSS_INTERNAL = "ssh://git@gitlab.internal:20022/home1-oss/oss-internal.git"
def PROJECT_TO_BE_DEPLOY = "ssh://git@gitlab.internal:20022/home1-oss/oss-jenkins-pipeline.git"

def project
stage("InitSpace") {
    node {
        timestamps {
            // clear space
            step([$class: 'WsCleanup'])
            sh "ls -la ${pwd()}"
            // add jenkinsfile authtication in jenkins console
            git credentialsId: 'jenkinsfile', url: "${PROJECT_TO_BE_DEPLOY}"
            workspace = pwd()
            project = getProjectList("${workspace}")
            echo "project is : ${project}"
        }

    }
}

stage "CollectParameters"
def paramMap = [:]
timeout(30) { // timeout 30 minutes
    timestamps {
        paramMap = input id: 'Environment_id', message: 'Custome your parameters', ok: 'Submit', parameters: getInputParam(project)
        println("param is :" + paramMap.inspect())
    }
}

stage("PrepareResource") {
    node {
        timestamps {
            if (fileExists("${paramMap.PROJECT}")) {
                stash name: "${paramMap.PROJECT}", includes: "${paramMap.PROJECT}/**" ,excludes: "${paramMap.PROJECT}/**/environment.json"

                def envJson = readFile file: "${workspace}/${paramMap.PROJECT}/environments/environment.json"
                paramMap = generateParam(paramMap, envJson)

                git credentialsId: 'jenkinsfile', branch: 'develop', url: "${PROJECT_OSS_INTERNAL}"
                // stash resources for the moment
                dir("src/main/jenkins/") {
                    stash name: "id_rsa", includes: "id_rsa"
                    if (paramMap.ENV != null && fileExists("${paramMap.ENV}")) {
                        dir("${paramMap.ENV}") {
                            if (fileExists("id_rsa")) {
                                stash name: "id_rsa", includes: "id_rsa"
                            }
                        }
                    }
                    // k8s
                    if(fileExists("k8s/${paramMap.ENV}")) {
                        dir("k8s/${paramMap.ENV}") {
                            stash name: "kubectl",includes: "**"
                        }
                    }
                }
                // maven's settings-security.xml
                dir("src/main/maven/") {
                    stash name: "security", includes: "settings-security.xml"
                }
                // docker auth，ci.sh also will download this file now
//                dir("src/main/docker/") {
//                    if (fileExists("config.json")) {
//                        sh "if [ ! -d ~/.docker ]; then mkdir ~/.docker ;fi"
//                        sh "cp config.json ~/.docker/"
//                    }
//                }

                writeFile(file: 'data.zip', text: paramMap.inspect(), encoding: 'utf-8')
                stash name: "data.zip", includes: 'data.zip'
            } else {
                print("project: ${paramMap.PROJECT} is not exist")
            }

            sh "ls -la ${pwd()}"
        }
    }
}

stage("deploy") {
    node {
        timestamps {
            step([$class: 'WsCleanup'])
            unstash "${paramMap.PROJECT}"
            unstash "data.zip"
            unstash "kubectl"
            def data = readFile encoding: 'utf-8', file: 'data.zip'
            echo "data:" + data
            paramMap = Eval.me(data)

            sh "ls -la"
            if(paramMap.PROJECT_TYPE == "k8s"){
                deployToK8s(paramMap)
            }else if( paramMap.PROJECT_TYPE == "docker-compose"){
                deployByDockerCompose(paramMap)
            }
        }
    }
}

timestamps {
    if (paramMap.PROJECT_TYPE == "docker-compose" && paramMap.NODES.size() > 0) {
        timeout(20) { // timeout 20 minutes
            input message: "Please check node ${paramMap["PRE_NODES"]}, click continue if service successfully deployed", ok: 'continue deploy'
        }
    }
}

stage("DeployAll") {
    node {
        timestamps {
            if (paramMap.PROJECT_TYPE == "docker-compose" && paramMap.NODES.size() > 0) { //
                def parallelBranches = [:]
                echo "--------------Deploy start--------------- "
                for (int i = 0; i < paramMap["NODES"].size(); i++) {
                    def ipNode = paramMap["NODES"][i]
                    parallelBranches[paramMap.PROJECT + "@" + ipNode] = generateBranch(paramMap, ipNode)
                }
                echo "waiting deploy projects are : ${parallelBranches}"
                parallel parallelBranches
            }
        }
    }
}

stage("Done") {
    echo "--------------Deploy finished--------------- "
}

/**
 * Deploy docker image
 *
 * @param paramMap
 * @param ip
 * @param workspace
 * @param sshKeyParam
 * @return
 */
def deploy(paramMap, ip, workspace, sshKeyParam) {
    def COMMON_ENV = "${paramMap.PROJECT}/environments/${paramMap.ENV}/common.yaml"
    def CONFIG_FILE = "${paramMap.PROJECT}/environments/${paramMap.ENV}/${ip}.yaml"
    def DOCKER_FILE = "${paramMap.PROJECT}/${paramMap.COMPOSE_FILE_NAME}"

    def REMOTE_USER = "root"
    println("*. Prepare deploy service ${paramMap.PROJECT} on ${REMOTE_USER}@${ip}")

    echo '=================Generate script:===================='
    sh "echo 'set -e' > ${workspace}/${ip}.sh"

    if (fileExists("${workspace}/${COMMON_ENV}")) {
        echo '*. Start copy common config '
        sh "cat ${workspace}/${COMMON_ENV} >> ${workspace}/${ip}.sh"
    }
    sh "cat ${workspace}/${CONFIG_FILE} >> ${workspace}/${ip}.sh"
    sh "echo \" \" >> ${workspace}/${ip}.sh"
    sh "echo \"export PROJECT_VERSION=${paramMap.VERSION} \" >> ${workspace}/${ip}.sh"

    sh "echo \"docker-compose -f /tmp/$DOCKER_FILE down --remove-orphans \" >> ${workspace}/${ip}.sh"
    sh "echo \"docker-compose -f /tmp/$DOCKER_FILE pull\" >> ${workspace}/${ip}.sh"
    sh "echo \"docker-compose -f /tmp/$DOCKER_FILE up -d\" >> ${workspace}/${ip}.sh"

    echo "=================Check script:===================="
    sh "cat ${workspace}/${ip}.sh"

    echo "=================Copy script:===================="
    sh "ssh ${sshKeyParam} -o StrictHostKeyChecking=no ${REMOTE_USER}@${ip} " +
            "\"if ! test -d /tmp/${paramMap.PROJECT}; " +
            "then mkdir /tmp/${paramMap.PROJECT}; else rm -rf /tmp/${paramMap.PROJECT}/*; fi;\""
    sh "scp ${sshKeyParam} -o StrictHostKeyChecking=no " +
            "~/.docker/config.json ${REMOTE_USER}@${ip}:~/.docker/config.json"
    sh "scp ${sshKeyParam} -o StrictHostKeyChecking=no " +
            "-r ${workspace}/${DOCKER_FILE} ${REMOTE_USER}@${ip}:/tmp/${paramMap.PROJECT}/"
    sh "scp ${sshKeyParam} -o StrictHostKeyChecking=no " +
            "-r ${workspace}/${ip}.sh ${REMOTE_USER}@${ip}:/tmp/${paramMap.PROJECT}/${ip}.sh"

    echo "=================Execute script:===================="
    sh "ssh ${sshKeyParam} -o StrictHostKeyChecking=no ${REMOTE_USER}@${ip} " +
            "\"cat /tmp/${paramMap.PROJECT}/${ip}.sh && sh /tmp/${paramMap.PROJECT}/${ip}.sh\""
    def containerId = sh script: "ssh ${sshKeyParam} ${REMOTE_USER}@${ip} \"docker ps | grep ${paramMap.PROJECT}:${paramMap.VERSION}|awk '{print \\\$1}'\"", returnStdout: true

    // need config health api in environment.json
    if (paramMap.HEALTH_API != null && paramMap.HEALTH_API != "") {
        echo "=================Service validate:===================="
        checkWaitforit()
        HEALTH_URL = "${paramMap.HEALTH_API}".replace("#host#", ip)
        sh "\$HOME/waitforit -full-connection=${HEALTH_URL} -timeout=300"
    }
    println("*. Deployed service ${paramMap.PROJECT} on ${REMOTE_USER}@${ip}，as containerId is ${containerId} \n\n\n")
}

/**
 * Deploy by docker-compose
 */
def deployByDockerCompose(paramMap)  {
    def preBranches = [:]
    echo "--------------PreDeploy start--------------- "
    for (int i = 0; i < paramMap["PRE_NODES"].size(); i++) {
        def ipNode = paramMap["PRE_NODES"][i]
        preBranches[paramMap.PROJECT + "@" + ipNode] = generateBranch(paramMap, ipNode)
    }
    echo "waiting deploy projects are : ${preBranches}"
    parallel preBranches
}


/**
 * Deploy by kubernetes
 *
 * @param paramMap
 * @return
 */
def deployToK8s(paramMap) {

    sh "sed -i \"s/\\#image_version#/${paramMap.VERSION}/g\" ${paramMap.PROJECT}/environments/*.yaml"
    echo "--------------*. k8s deploy start--------------- "

    sh "ls -la"
    sh "chmod +x kubectl"

    if(fileExists("${paramMap.PROJECT}/k8s_deploy.sh")){
        sh "chmod +x ${paramMap.PROJECT}/k8s_deploy.sh"
        sh "./${paramMap.PROJECT}/k8s_deploy.sh"
    }else {
        sh "./kubectl --kubeconfig .kube/config apply -f ${paramMap.PROJECT}/environments/${paramMap.ENV}"
        sh "./kubectl --kubeconfig .kube/config apply -f ${paramMap.PROJECT}/environments"
    }

    println("*. k8s deploy finished")
}

/**
 * Generate branch for parallel deploy
 *
 * @param paramMap
 * @param ipNode
 * @return
 */
def generateBranch(paramMap, ipNode) {
    return {
        node {
            step([$class: 'WsCleanup'])
            def workspace = pwd()
            unstash "${paramMap.PROJECT}"
            sh "ls -la ${workspace}"

            dir("${paramMap.PROJECT}") {
                deployToK8s(paramMap, ipNode, workspace)
            }
        }
    }
}

/**
 * Generate global deploy param
 *
 * @param paramMap
 * @param fileContent
 * @return
 */
def generateParam(paramMap, fileContent) {
    println("generateParam : paramMap=${paramMap},fileContent = ${fileContent}")
    paramMap.PROJECT_TYPE = parse(fileContent)["PROJECT_TYPE"]
    if(paramMap.PROJECT_TYPE != "k8s"){

        // if not given nodes ,will read from config file
        if (paramMap.NODES == null || paramMap.NODES == "") {
            paramMap.NODES = parse(fileContent)["REMOTE_HOSTS"]["${paramMap.ENV}"]
        } else {
            paramMap.NODES = paramMap.NODES.split(";").toList()
        }
        // generate preNodes
        if (paramMap.PRE_NODES == null || paramMap.PRE_NODES == "") {
            paramMap.PRE_NODES = paramMap.NODES[0..0]
            paramMap.NODES = paramMap.NODES - paramMap.PRE_NODES
        } else {
            paramMap.PRE_NODES = paramMap.PRE_NODES.split(";").toList()
            paramMap.NODES = paramMap.NODES - paramMap.PRE_NODES

        }
        paramMap.COMPOSE_FILE_NAME = parse(fileContent)["COMPOSE_FILE_NAME"]
    }
    paramMap.HEALTH_API = parse(fileContent)["HEALTH_API"]

    println("after generate:" + paramMap.inspect())
    return paramMap
}

/**
 * Get input param
 *
 * @param project
 * @return
 */
def getInputParam(String project) {
    def projectListStr = project.replace(" ", "\n")
    return [
            [$class: 'ChoiceParameterDefinition', choices: projectListStr, description: 'Project', name: 'PROJECT'],
            [$class: 'ChoiceParameterDefinition', choices: 'staging\nproduction', description: 'Environment', name: 'ENV'],
            string(defaultValue: 'latest', description: 'Version', name: 'VERSION')
    ]
}

/**
 * Get project list
 *
 * @param dirName
 * @return
 */
@NonCPS
def getProjectList(dirName) {
    // Add a["name"]=1 into awk expression to exclude more project name pattern
    def scriptStr = "ls -l " + dirName + " |awk 'BEGIN{a[\"src\"]=1;a[\"hooks\"]=1;} /^d/ {if(a[\$NF]!=1){printf \$NF\" \"}}'"
    def result = sh returnStdout: true, script: scriptStr
    return result
}

@NonCPS
def from(param) {
    builder = new JsonBuilder()
    builder(param)
    return builder.toString()
}

@NonCPS
def parse(text) {
    return new JsonSlurper().parseText(text)
}

def checkWaitforit() {
    sh '''
        set -e
        if [ ! -f $HOME/waitforit ]; then
            echo "Downloading waitforit from http://fileserver.internal/maxcnunes/waitforit/releases/download/v1.4.0/waitforit-linux_amd64, please wait patiently! "
            url="http://fileserver.internal/maxcnunes/waitforit/releases/download/v1.4.0/waitforit-linux_amd64"
            curl -L -o $HOME/waitforit "${url}"
            chmod 755 $HOME/waitforit
        fi
    '''
}
#!/usr/bin/env groovy
package src.pipeline

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

            def workspace = pwd()
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
                stash name: "${paramMap.PROJECT}", includes: "${paramMap.PROJECT}/**/*"
                echo "stash project: ${paramMap.PROJECT}"
                dir("src/main/resources/"){
                  sh 'mv ./waitforit-linux_amd64 ${HOME}/waitforit && chmod +x ${HOME}/waitforit'    
                }
                def envJson = readFile file: "${workspace}/${paramMap.PROJECT}/environments/environment.json"
                paramMap = generateParam(paramMap, envJson)

                git credentialsId: 'jenkinsfile', branch: 'develop', url: "${PROJECT_OSS_INTERNAL}"
                // stash resources for the moment
                dir("src/main/jenkins/") {
                    if (paramMap.ENV != null && fileExists("${paramMap.ENV}")) {
                        dir("${paramMap.ENV}") {
                            stash name: "id_rsa", includes: "id_rsa"
                        }
                    } else {
                        stash name: "id_rsa", includes: "id_rsa"
                    }
                }
                // security-setting
                dir("src/main/maven/") {
                    stash name: "security", includes: "settings-security.xml"
                }
                // docker auth
                dir("src/main/docker/") {
                    if (fileExists("config.json")) {
                        sh "cp config.json ~/.docker/"
                    }
                }

                writeFile(file: 'data.zip', text: paramMap.inspect(), encoding: 'utf-8')
                stash "data.zip"
            } else {
                print("project: ${paramMap.PROJECT} is not exist")
            }
            sh "ls -la ${pwd()}"
        }
    }
}

stage("PreDeploy") {
    node {
        timestamps {

            step([$class: 'WsCleanup'])
            unstash "id_rsa"
            sh "chmod +r id_rsa"

            unstash "data.zip"

            def data = readFile encoding: 'utf-8', file: 'data.zip'
            echo "data:" + data
            paramMap = Eval.me(data)

            def preBranches = [:]
            echo "--------------PreDeploy start--------------- "
            for (int i = 0; i < paramMap["PRE_NODES"].size(); i++) {
                def ipNode = paramMap["PRE_NODES"][i]
                preBranches[paramMap.PROJECT + "@" + ipNode] = generateBranch(paramMap, ipNode)
            }
            echo "waiting deploy projects are : ${preBranches}"
            parallel preBranches
        }
    }
}

timestamps {
    if (paramMap.NODES.size() > 0) {
        timeout(20) { // timeout 20 minutes
            input message: "Please check node ${paramMap["PRE_NODES"]}, click continue if service successfully deployed", ok: 'continue deploy'
        }
    }
}

stage("DeployAll") {
    node {
        timestamps {
            if (paramMap.NODES.size() > 0) {
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
    def COMMON_ENV = "${paramMap.PROJECT}/environments/${paramMap.ENV}/common_env"
    def CONFIG_FILE = "${paramMap.PROJECT}/environments/${paramMap.ENV}/${ip}"
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
    sh "echo \"export DOCKER_REGISTRY=${paramMap.DOCKER_REGISTRY} \" >> ${workspace}/${ip}.sh"
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
            unstash "id_rsa"
            sh "chmod 400 id_rsa"
            def workspace = pwd()
            unstash "${paramMap.PROJECT}"
            sh "ls -la ${workspace}"

            dir("${paramMap.PROJECT}") {
                deploy(paramMap, ipNode, workspace, "-i ${workspace}/id_rsa")
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
    paramMap.COMPOSE_FILE_NAME = parse(fileContent)["COMPOSE_FILE_NAME"]
    paramMap.COMPOSE_FILE_URL = parse(fileContent)["COMPOSE_FILE_URL"]
    paramMap.HEALTH_API = parse(fileContent)["HEALTH_API"]

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
            [$class: 'ChoiceParameterDefinition', choices: 'registry.docker.internal\nhome1oss', description: 'Docker registry', name: 'DOCKER_REGISTRY'],
            string(defaultValue: 'latest', description: 'Version', name: 'VERSION'),
            string(defaultValue: '', description: 'Preposition deploy, verify then deploy to other nodes, split by ";"', name: 'PRE_NODES'),
            string(defaultValue: '', description: 'All nodes，parallel deploy, split by ";"', name: 'NODES')
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
    def scriptStr = "ls -l " + dirName + " |awk 'BEGIN{a[\"src\"]=1;a[\"hooks\"]=1} /^d/ {if(a[\$NF]!=1){printf \$NF\" \"}}'"
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

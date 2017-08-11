# oss-jenkins-pipeline
Jenkins pipeline that build and deploy oss services and user's applications/services

该项目用来部署YRD-OSS相关的线上环境，使用了Pipeline的脚本方式来实现。YRD-OSS项目目前使用Pipeline的方式进行构建发布。使用Pipeline有如下几个优点：

- 所有的项目部署、测试、发布、部署，均可以脱离繁琐的配置，使用Pipeline脚本大幅度提升项目的构建维护成本；
- 部署支持并发的部署多台机器，提高部署的效率。
- 构建或者部署的触发机制支持gitlab自动触发，或者参数化的手工触发。
- 部署支持分批次部署，即先行部署少量节点，验证通过后，再部署其他节点
- 部署完毕后支持服务校验，检查服务真正启动后再继续后面的过程

## 环境依赖

- 安装Jenkins2.0
- 确保Jenkins上安装了Pipeline相关的插件
- 配置Jenkins的slave节点，确保slave的环境ready，安装清单如下

        java 1.8
        maven 3.3
        gradle 2.14

## 用到的Pipeline插件

    pipeline-input-step:2.5
    pipeline-milestone-step:1.2
    pipeline-build-step:2.4
    pipeline-rest-api:2.4
    pipeline-graph-analysis:1.3
    pipeline-stage-step:2.2
    pipeline-stage-view:2.4
其他相关插件参照 `docker-jenkins/jenkins/docker/plugins.txt`

## 项目说明
![](media/14839320867763/14850728366426.jpg)

> environment.json文件是oss-admin模块的环境描述信息，每个环境对应的节点机器ip，以及使用的docker-compose文件，以及其他相关部署的信息，都可以在这里维护。这里我们支持了部署后的服务校验，部署脚本会一直等待服务部署OK后才继续执行，只需在描述信息的配置文件内增加如下格式的校验接口信息即可，如：
> `"HEALTH_API":"http://#host#:8700"`


## 最佳实践
1. 尽可能地使用parallel来使得任务并行地执行

        def preBranches = [:]
        echo "--------------PreDeploy start--------------- "
        for (int i = 0; i < paramMap["PRE_NODES"].size(); i++) {
            def ipNode = paramMap["PRE_NODES"][i]
            preBranches[paramMap.PROJECT + "@" + ipNode]=generateBranch(paramMap, ipNode)
        }
        echo "waiting deploy projects are : ${preBranches}"
        parallel preBranches

2. 所有资源消耗的操作都应该放到node上执行
3. 使用stage组织任务的流程
4. 借助Snippet Generator生成Pipeline脚本，但不要依赖，可能有bug

5. 不要在node里使用input
> input 能够暂停pipeline的执行等待用户的approve（自动化或手动），通常地approve需要一些时间等待用户相应。 如果在node里使用input将使得node本身和workspace被lock，不能够被别的job使用。所以一般在node外面使用input。
   
        stage 'deployment'
        input 'Do you approve deployment?'
        node{
            //deploy the things
        } 
6. inputs应该封装在timeout中
> pipeline可以很容易地使用timeout来对step设定timeout时间。对于input我们也最好使用timeout。

        timeout(time:5, unit:'DAYS') {
            input message:'Approve deployment?', submitter: 'it-ops'
        }
7. 应该使用withEnv来修改环境变量

    > 不建议使用env来修改全局的环境变量，这样后面的groovy脚本也将被影响。一般使用withEnv来修改环境变量，变量的修改只在withEnv的块内起作用。

        withEnv(["PATH+MAVEN=${tool 'm3'}/bin"]) {
            sh "mvn clean verify"
        }
8. 尽量使用stash来实现stage/node间共享文件，不要使用archive
    
    > 在stash被引入pipeline DSL前，一般使用archive来实现node或stage间文件的共享。 在stash引入后，最好使用stash/unstash来实现node/stage间文件的共享。例如在不同的node/stage间共享源代码。archive用来实现更长时间的文件存储。

        stash excludes: 'target/', name: 'source'
        unstash 'source'

9. 执行stash操作时需要注意，在当前目录下的文件才可以进行stash，否则会报错。

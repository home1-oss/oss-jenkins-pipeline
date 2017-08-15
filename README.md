# oss-jenkins-pipeline
Jenkins pipeline that build and deploy oss services and user's applications/services

该项目用来部署OSS相关的线上环境，使用了Pipeline的脚本方式来实现。OSS项目目前使用Pipeline的方式进行构建发布。使用Pipeline有如下几个优点：

- 所有的项目部署、测试、发布、部署，均可以脱离繁琐的配置，使用Pipeline脚本大幅度提升项目的构建维护成本；
- 部署支持并发的部署多台机器，提高部署的效率。
- 构建或者部署的触发机制支持gitlab自动触发，或者参数化的手工触发。
- 部署支持分批次部署，即先行部署少量节点，验证通过后，再部署其他节点
- 部署完毕后支持服务校验，检查服务真正启动后再继续后面的过程

> environment.json文件是模块的环境描述信息，每个环境对应的节点机器ip，以及使用的docker-compose文件，以及其他相关部署的信息，都可以在这里维护。
> 这里我们支持了部署后的服务校验，部署脚本会一直等待服务部署OK后才继续执行，只需在描述信息的配置文件内增加如下格式的校验接口信息即可，
> 如：`"HEALTH_API":"http://#host#:8700"`

## Jenkins2.0
### 概述
自从去年9月底Jenkins的创始人Kohsuke Kawaguchi提出Jenkins 2.0（后称2.0）的愿景和草案之后，整个Jenkins社区为之欢欣鼓舞，不管是官方博客还是Google论坛，大家都在热烈讨论和期盼2.0的到来。4月20日，历经Alpha(2/29)，Beta(3/24)，RC(4/7)3个版本的迭代，2.0终于正式发布。这也是Jenkins面世11年以来（算上前身Hudson）的首次大版本升级。那么，这次升级具体包含了哪些内容呢？

- 外部特点
    - Pipeline as Code
    - 全新的开箱体验
    - 1.x兼容性。
- 内部特性
    - 升级Servlet版本到3.1，获取Web Sockets支持
    - 升级内嵌的Groovy版本到2.4.6
    - 提供一个简化的JS类库给Plugin开发者使用

## Pipeline 

### 基本概念
- Stage：一个Pipeline可以划分为若干个Stage，每个Stage代表一组操作。注意，Stage是一个逻辑分组的概念，可以跨多个Node。
- Node：一个Node就是一个Jenkins节点，或者是Master，或者是Agent，是执行Step的具体运行期环境。
- Step：Step是最基本的操作单元，小到创建一个目录，大到构建一个Docker镜像，由各类Jenkins Plugin提供。
  
### 功能清单
  
| 命令 | 功能 | 示例  |
| --- | --- | --- |
| input | 弹出和用户交互的输入表单 |input id: 'Environment_id', message: 'Custome your parameters', ok: '提交', parameters: getInputParam(project)  |
| readFile | 读取文件 | readFile encoding: 'utf-8', file: 'data.zip' |
| git | git操作 | git credentialsId: 'jenkinsfile', url: 'git@gitlab.internal:home1-oss/oss-jenkins-pipeline.git' |
| stash | 暂存文件 | stash name: "id_rsa", includes: "id_rsa" |
| unstash | 获取暂存文件 | unstash "id_rsa" |
| writeFile | 写入文件 | writeFile(file: 'data.zip', text: paramMap.inspect(), encoding: 'utf-8')  |
| parallel | 并行运行 |   |
[更多参见](http://jenkins.internal:18083/job/oss-environment-build/pipeline-syntax/html)

### 原理
- PIPELINE工作流程图
![](image/pipeline.jpg)

## Pipeline plugins depends on

    pipeline-input-step:2.5
    pipeline-milestone-step:1.2
    pipeline-build-step:2.4
    pipeline-rest-api:2.4
    pipeline-graph-analysis:1.3
    pipeline-stage-step:2.2
    pipeline-stage-view:2.4

see `docker-jenkins/jenkins/docker/plugins.txt`

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

### gitlab插件环境变量

在pipeline的构建脚本中，如果是gitlab的webhook触发，可以获取如下的构建参数：

propertyName|value|description|
---|---|---
gitlabBranch | master | 分支
gitlabSourceBranch | master| 
gitlabActionType | PUSH| 触发事件
gitlabUserName | 梁建| 用户名
gitlabUserEmail | jianliang9@yirendai.com| 用户邮箱
gitlabSourceRepoHomepage | http://gitlab.internal/jianliang9/my-pipeline-test|http地址
gitlabSourceRepoName | my-pipeline-test|项目名
gitlabSourceNamespace | jianliang9|
gitlabSourceRepoURL | git@gitlab.internal:jianliang9/my-pipeline-test.git|
gitlabSourceRepoSshUrl | git@gitlab.internal:jianliang9/my-pipeline-test.git|
gitlabSourceRepoHttpUrl | http://gitlab.internal/jianliang9/my-pipeline-test.git|
gitlabMergeRequestTitle | null|
gitlabMergeRequestDescription | null|
gitlabMergeRequestId | null|
gitlabMergeRequestIid | null|
gitlabMergeRequestLastCommit | 2c4467453d9814f67cf82faba32c9320544fe1c5|
gitlabTargetBranch | master|
gitlabTargetRepoName | null|
gitlabTargetNamespace | null|
gitlabTargetRepoSshUrl | null|
gitlabTargetRepoHttpUrl | null|
gitlabBefore | 9361e4aab88cb85d326ff8bbd9b97f0383010133|
gitlabAfter | 2c4467453d9814f67cf82faba32c9320544fe1c5|
gitlabTriggerPhrase | null|

### Notes
- 首次构建，可能会遇到groovy脚本执行权限受阻的情形，形如：
![](image/scriptsecurity_rejected.png)
需要进入系统管理->In-process Script Approval将对应的方法加入白名单
![](image/in-process_script_approval.png)
![](image/signatures_already_approved.png)

### 参考资料
  [Pipeline-plugin](https://github.com/jenkinsci/pipeline-plugin)
  [pipeline-examples](https://github.com/jenkinsci/pipeline-examples)
  [Pipeline+Stage+View+Plugin](https://wiki.jenkins-ci.org/display/JENKINS/Pipeline+Stage+View+Plugin)

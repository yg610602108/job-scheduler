# job-scheduler
Study notes based on the xxl-job framework with version of 2.2.0

## Introduction
XXL-JOB is a distributed task scheduling framework. 
It's core design goal is to develop quickly and learn simple, lightweight, and easy to expand. 
Now, it's already open source, and many companies use it in production environments, real "out-of-the-box".

XXL-JOB是一个分布式任务调度平台，其核心设计目标是开发迅速、学习简单、轻量级、易扩展。现已开放源代码并接入多家公司线上产品线，开箱即用。

本项目是基于该开源项目2.2.0版本的代码加了学习笔记，并自己写了一个SpringBootStarter，使用非常简单：

- 1、对项目 job-scheduler-spring-boot-autoconfigure 执行打包命令
- 2、对项目 job-scheduler-spring-boot-starter 执行打包命令
- 3、将依赖添加到项目中
```
<dependency>
    <groupId>com.ambition.spring.boot</groupId>
    <artifactId>job-scheduler-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```
- 4、配置调度中心参数
```yaml
job:
  scheduler:
    # 调度中心部署跟地址，如调度中心集群部署存在多个地址则用逗号分隔
    # 执行器将会使用该地址进行"执行器心跳注册"和"任务结果回调"
    registry-addresses: http://127.0.0.1:8080/xxl-job-admin/
    access-token:
    # 执行器配置
    # 名称是执行器心跳注册分组依据
    # 地址信息用于"调度中心请求并触发任务"和"执行器注册"
    # 执行器默认端口为9999，执行器IP默认为空表示自动获取IP，多网卡时可手动设置指定IP，该IP不会绑定Host仅作为通讯实用
    # 单机部署多个执行器时，注意要配置不同执行器端口
    executor:
      name: ${spring.application.name}
      address:
      ip:
      port: 8848
      # 执行器运行日志文件存储的磁盘位置，需要对该路径拥有读写权限
      log-path: /app/log/xxl-job/jobhandler
      # 执行器Log文件定期清理功能，指定日志保存天数，日志文件过期自动删除
      # 限制至少保持3天，否则功能不生效
      log-retention-days: 30
```
- 5、启动项目

## 运行流程
### 服务端
做完一系列的初始化操作后，多个服务端会去竞争数据库表锁，由其中一个节点去处理调度任务，主要是超前5秒查询下次要调度的任务，如果远远未到调度时间则直接更新下次调度的时间；即将到调度时间的任务，会直接调度，然后更新下次调度时间，此时这个任务可能下次调度时间和当前时间距离非常短，则根据下次调度时间提前将任务保存到键为【0，59】，值为任务编号的时间轮中，然后更新下次调度时间；已经超过调度时间的任务，也会根据下次调度时间保存到时间轮中，并更新下次调度时间。
当任务到达调度时间时，会将任务提交到初始化的快慢线程池中进行调度，默认使用快速调度线程池，如果这个任务一分钟内调度超时超过10次，则会使用慢速调度线程池，使用线程隔离的方式避免相互影响。
调度逻辑会获取调度任务数据和策略数据，获取到执行器地址后，基于HTTP请求向目标服务Socket发送调度信息。

### 客户端
整合框架时需要手动往Spring容器中注入XxlJobSpringExecutor，它实现了SmartInitializingSingleton，当容器中所有的单例Bean加载完成后，会回调它的afterSingletonsInstantiated方法，这个方法将容器中所有加了XxlJob注解的方法找出来，将其注册为MethodJobHandler对象存储在服务器缓存中。
获取配置数据，将自己作为服务端建立Socket连接，异步将自己注册到注册中心，主要就是调用服务端暴露的API接口。

### 调度流程
客户端通过Socket连接接收到调度请求后会从缓存中获取任务对应的MethodJobHandler对象，并将其封装为JobThread对象，一个调度任务就会有一个JobThread对象，该对象监听了一个阻塞队列，任务需要被调度的时候只需要将其添加到阻塞队列，使用MethodJobHandler对象通过反射即可调用需要被调度的方法。

## Documentation
- [开发文档](https://www.xuxueli.com/xxl-job/)

## Features
- 1、简单：支持通过Web页面对任务进行CRUD操作，操作简单，一分钟上手；
- 2、动态：支持动态修改任务状态、启动/停止任务，以及终止运行中任务，即时生效；
- 3、调度中心HA（中心式）：调度采用中心式设计，“调度中心”自研调度组件并支持集群部署，可保证调度中心HA；
- 4、执行器HA（分布式）：任务分布式执行，任务"执行器"支持集群部署，可保证任务执行HA；
- 5、注册中心: 执行器会周期性自动注册任务, 调度中心将会自动发现注册的任务并触发执行。同时，也支持手动录入执行器地址；
- 6、弹性扩容缩容：一旦有新执行器机器上线或者下线，下次调度时将会重新分配任务；
- 7、路由策略：执行器集群部署时提供丰富的路由策略，包括：第一个、最后一个、轮询、随机、一致性HASH、最不经常使用、最近最久未使用、故障转移、忙碌转移等；
- 8、故障转移：任务路由策略选择"故障转移"情况下，如果执行器集群中某一台机器故障，将会自动Failover切换到一台正常的执行器发送调度请求。
- 9、阻塞处理策略：调度过于密集执行器来不及处理时的处理策略，策略包括：单机串行（默认）、丢弃后续调度、覆盖之前调度；
- 10、任务超时控制：支持自定义任务超时时间，任务运行超时将会主动中断任务；
- 11、任务失败重试：支持自定义任务失败重试次数，当任务失败时将会按照预设的失败重试次数主动进行重试；其中分片任务支持分片粒度的失败重试；
- 12、任务失败告警；默认提供邮件方式失败告警，同时预留扩展接口，可方便的扩展短信、钉钉等告警方式；
- 13、分片广播任务：执行器集群部署时，任务路由策略选择"分片广播"情况下，一次任务调度将会广播触发集群中所有执行器执行一次任务，可根据分片参数开发分片任务；
- 14、动态分片：分片广播任务以执行器为维度进行分片，支持动态扩容执行器集群从而动态增加分片数量，协同进行业务处理；在进行大数据量业务操作时可显著提升任务处理能力和速度。
- 15、事件触发：除了"Cron方式"和"任务依赖方式"触发任务执行之外，支持基于事件的触发任务方式。调度中心提供触发任务单次执行的API服务，可根据业务事件灵活触发。
- 16、任务进度监控：支持实时监控任务进度；
- 17、Rolling实时日志：支持在线查看调度结果，并且支持以Rolling方式实时查看执行器输出的完整的执行日志；
- 18、GLUE：提供Web IDE，支持在线开发任务逻辑代码，动态发布，实时编译生效，省略部署上线的过程。支持30个版本的历史版本回溯。
- 19、脚本任务：支持以GLUE模式开发和运行脚本任务，包括Shell、Python、NodeJS、PHP、PowerShell等类型脚本;
- 20、命令行任务：原生提供通用命令行任务Handler（Bean任务，"CommandJobHandler"）；业务方只需要提供命令行即可；
- 21、任务依赖：支持配置子任务依赖，当父任务执行结束且执行成功后将会主动触发一次子任务的执行, 多个子任务用逗号分隔；
- 22、一致性：“调度中心”通过DB锁保证集群分布式调度的一致性, 一次任务调度只会触发一次执行；
- 23、自定义任务参数：支持在线配置调度任务入参，即时生效；
- 24、调度线程池：调度系统多线程触发调度运行，确保调度精确执行，不被堵塞；
- 25、数据加密：调度中心和执行器之间的通讯进行数据加密，提升调度信息安全性；
- 26、邮件报警：任务失败时支持邮件报警，支持配置多邮件地址群发报警邮件；
- 27、推送maven中央仓库: 将会把最新稳定版推送到maven中央仓库, 方便用户接入和使用;
- 28、运行报表：支持实时查看运行数据，如任务数量、调度次数、执行器数量等；以及调度报表，如调度日期分布图，调度成功分布图等；
- 29、全异步：任务调度流程全异步化设计实现，如异步调度、异步运行、异步回调等，有效对密集调度进行流量削峰，理论上支持任意时长任务的运行；
- 30、跨语言：调度中心与执行器提供语言无关的 RESTful API 服务，第三方任意语言可据此对接调度中心或者实现执行器。除此之外，还提供了 “多任务模式”和“httpJobHandler”等其他跨语言方案；
- 31、国际化：调度中心支持国际化设置，提供中文、英文两种可选语言，默认为中文；
- 32、容器化：提供官方docker镜像，并实时更新推送dockerhub，进一步实现产品开箱即用；
- 33、线程池隔离：调度线程池进行隔离拆分，慢任务自动降级进入"Slow"线程池，避免耗尽调度线程，提高系统稳定性；
- 34、用户管理：支持在线管理系统用户，存在管理员、普通用户两种角色；
- 35、权限控制：执行器维度进行权限控制，管理员拥有全量权限，普通用户需要分配执行器权限后才允许相关操作；
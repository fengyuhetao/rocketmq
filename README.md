## 整体介绍
* 丰富API，各种方式的消息投递
* 采用Netty NIO框架
* NameServer代替Zookeeper
* 强调集群无单点，可扩展，任意一点高可用，水平可扩展
* 消息失败重试（rabbitmq需要自己实现），消息可查询进度
* 活跃

## 概念模型
Producer： 消息生产者，负责产生消息，一般由业务系统负责产生消息
Consumer: 消息消费者，负责消费消息，一般是后台系统负责异步消费
Push Consumer: Consumer的一种，需要向Consumer对象注册监听
Pull Consumer: Consumer的一种，需要主动请求Broker拉取消息
Producer Group: 生产者集合，一般用于发送一类消息, 一个jvm只能启动一个
Consumer Group: 消费者集合，一般用于接受一类消息进行消费
Broker: MQ消息服务（中转角色，用于消息存储与生产消费转发） 

## 源码
* rocketmq-broker: 主要的业务逻辑，消息收发，主从同步，pagecache
* rocketmq-client: 客户端接口
* rocketmq-common: 公用数据结构
* rocketmq-distribution: 编译模块，编译输出
* rocketmq-filter: Broker过滤不感兴趣的消息传输，减小带宽压力
* rocketmq-logappender，rocketmq-logging: 日志相关
* rocketmq-namesrv: 用于服务协调
* rocketmq-openmessing: 对外提供服务
* rocketmq-remoting: 远程调用接口，基于Netty
* rocketmq-srvutil: 提供一些公用的工具方法，比如解析命令行参数
* rocketmq-store: 消息存储
* rocketmq-tools管理工具，比如mqadmin

## 命令行
nohup /usr/local/apache-rocketmq/bin/mqnamesrv &
nohup /usr/local/apache-rocketmq/bin/mqbroker -c /usr/local/apache-rocketmq/conf/2m-2s-async/broker-a.properties &
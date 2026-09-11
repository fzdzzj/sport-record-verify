# 规范：运动记录真实性校验系统（sport-record-verify）

> 首个能力域规范基线，由变更提案 `spec/changes/add-microservice-skeleton/` 落地生成。
> 校验引擎、好友、点赞、排行榜等业务需求在后续变更中分别以 ADDED 需求补充。

## ADDED Requirements

### Requirement: 多模块工程结构

WHEN 工程被构建,
系统 SHALL 产出父工程与 7 个可编译模块（common、api、gateway-service、user-service、record-service、verify-service），并 SHALL 通过 `mvn clean install`。

#### Scenario: 全量构建成功

GIVEN 本地已安装 JDK 21 与 Maven
WHEN 执行 `mvn clean install`
THEN 所有模块编译打包成功
AND 无快照依赖缺失报错

#### Scenario: 版本不匹配被拦截

GIVEN 本地 JDK 低于 21
WHEN 执行构建
THEN 编译失败
AND 错误信息明确提示 JDK 版本要求

### Requirement: 依赖版本集中锁定

WHERE 任一子模块声明依赖,
系统 SHALL 由父工程 dependencyManagement 统一管理版本，子模块 SHALL 不重复声明版本号。

#### Scenario: 子模块引用统一版本

GIVEN 子模块声明 mybatis-plus 依赖且未写版本号
WHEN 构建解析依赖
THEN 版本继承父工程锁定值
AND 与官方兼容矩阵一致

#### Scenario: 越界版本被拒绝

GIVEN 子模块引入与锁定矩阵不兼容的 Boot 3.3
WHEN 构建解析依赖
THEN 依赖树出现与 SCA 2023 分支不兼容冲突
AND 由版本矩阵约束阻止升级

### Requirement: 统一响应与错误码

WHEN 任一服务返回结果,
系统 SHALL 使用统一 `Result<T>` 包装，失败时 SHALL 返回结构化错误码与信息。

#### Scenario: 成功响应包装

GIVEN 服务处理成功
WHEN 返回结果
THEN 响应体为 `{code:0, message:"ok", data:<payload>}`

#### Scenario: 业务异常

GIVEN 服务抛出业务异常
WHEN 全局异常处理器拦截
THEN 返回对应错误码与可读信息
AND 不泄漏堆栈细节

### Requirement: 服务间 Feign 契约

WHEN 服务间发生调用,
系统 SHALL 通过 api 模块定义的 Feign 接口与 DTO 完成，接口定义与实现 SHALL 分离。

#### Scenario: 跨服务探活

GIVEN verify-service 需要调用 record-service 的健康端点
WHEN verify-service 发起 Feign 调用
THEN 请求经 api 模块接口定义路由
AND 返回结果与 DTO 契约一致

#### Scenario: 契约漂移

GIVEN api 模块 DTO 字段与实现不一致
WHEN 编译或运行时反序列化
THEN 编译失败或反序列化报错
AND 提示契约不一致

### Requirement: 服务注册与配置中心

WHEN 任一服务启动,
系统 SHALL 注册到 Nacos 注册中心并从 Nacos 配置中心拉取配置。

#### Scenario: 正常注册

GIVEN Nacos 已启动
WHEN 服务启动完成
THEN Nacos 控制台可见该服务实例
AND 服务成功拉取远程配置

#### Scenario: 注册中心不可用

GIVEN Nacos 未启动
WHEN 服务启动
THEN 启动失败或持续重试注册
AND 日志明确指向 Nacos 连接失败

### Requirement: 中间件一键编排

WHEN 开发者执行 `docker compose up`,
系统 SHALL 一键启动 Nacos、MySQL×3、Redis、RocketMQ，并保证启动依赖顺序与健康就绪。

#### Scenario: 一键起全部中间件

GIVEN 已安装 Docker
WHEN 执行 `docker compose up`
THEN 各中间件按依赖顺序启动
AND 健康检查全部就绪

#### Scenario: 依赖未就绪被等待

GIVEN MySQL 尚未健康
WHEN 依赖 MySQL 的容器启动
THEN 容器等待 MySQL 健康检查通过后再启动

### Requirement: 数据库初始化

WHEN 首次启动各服务,
系统 SHALL 执行审批版 §6 建表脚本，初始化 user_db / record_db / verify_db 中的全部表。

#### Scenario: 首次建表成功

GIVEN 三个库为空
WHEN 执行建表脚本
THEN 全部表创建成功
AND 字段与审批版 §6 定义一致

#### Scenario: 幂等重放

GIVEN 表已存在
WHEN 再次执行建表脚本
THEN 脚本幂等，不报错且不破坏既有结构

### Requirement: 网关统一入口

WHEN 客户端发起请求,
系统 SHALL 经 gateway-service 路由到目标服务，不直接暴露服务实例地址。

#### Scenario: 路由成功

GIVEN gateway 与目标服务均已启动
WHEN 客户端请求网关路径 `/record/**`
THEN 请求被转发到 record-service
AND 返回目标服务响应

#### Scenario: 目标不可达

GIVEN 目标服务未启动
WHEN 客户端请求对应网关路径
THEN 网关返回 502/503
AND 错误不泄漏内部拓扑

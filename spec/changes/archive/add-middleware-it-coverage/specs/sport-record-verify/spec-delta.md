# 规范差异：sport-record-verify

本文件包含对 `spec/specs/sport-record-verify/spec.md` 的规范变更（真中间件路径的覆盖缺口：Redis L2 真序列化往返、RocketMQ 真 broker、缺 env 跳过按未覆盖记账；`--it` 扩展为多 IT 清单）。

## ADDED Requirements

### Requirement: 真中间件路径有自动覆盖

WHEN 系统中存在依赖真实中间件的能力（Redis 二级缓存、RocketMQ 事件链路）,
系统 SHALL 提供指向真实中间件的自动覆盖，使序列化与消息路由在真实引擎上得到验证，而非仅在内存假件或 mock 上断言同为真；WHEN 该覆盖的执行前提（所需环境变量所指向的中间件）不齐备而被跳过时, 系统 SHALL 把该部分记为未覆盖, SHALL NOT 让缺席被表述为通过。

#### Scenario: Redis L2 在真 Redis 上往返

GIVEN 指向真实 Redis 实例的环境变量齐备
WHEN 定向执行真 Redis 集成测试
THEN 榜单条目经 JDK 序列化写入真实引擎
AND 另一全新实例能原样读回且数值精度（BigDecimal scale）存活
AND 写入的 TTL 真实落盘且与配置相符

#### Scenario: 连错 Redis 实例不被误判

GIVEN 本机存在多个 Redis 监听点（如原生进程与容器实例同占 6379）
WHEN 集成测试建立连接
THEN 测试记录所连实例的标识性信息（run_id/tcp_port）
AND 该标识与脚本侧通过同实例连接取到的标识一致
AND 空容器不再被误判为业务数据丢失

#### Scenario: RocketMQ 生产消费走真实 broker

GIVEN 指向真实 RocketMQ namesrv 的环境变量齐备
WHEN 定向执行真 broker 集成测试
THEN 生产端在真实 broker 上发送并取得消息 ID
AND 消费端按订阅的 Tag 收到同一消息且消息体字节一致
AND 反序列化后的事件字段与原发一致

#### Scenario: 前提缺失时按未覆盖处理

GIVEN 真中间件集成测试所需任一环境变量缺失
WHEN 定向执行端到端测试
THEN 该测试被跳过而非失败
AND 验收记录标注该部分未覆盖
AND 不据此声称真中间件路径已验证

### Requirement: 定向端到端覆盖可通过清单一键执行

WHEN 需要运行全部真中间件端到端覆盖,
系统 SHALL 通过同一被文档指路的定向入口按清单批量执行，使新增覆盖不需新增第二套命令拼写；SHALL NOT 使该扩展改变常规构建路径的用例行为或退出码语义。

#### Scenario: 多 IT 清单一次定向执行

GIVEN 存在多条以 `IT` 结尾且默认不被常规构建收集的真中间件测试
WHEN 通过定向入口的端到端分支执行
THEN 按声明清单批量收集并执行这些测试
AND 常规构建的用例数与退出码语义保持不变

## MODIFIED Requirements

### Requirement: 真库端到端测试有确定路径

WHEN 存在需要真实数据库或其他真实中间件的端到端测试,
系统 SHALL 提供一条被文档指路的定向执行入口，并 SHALL 声明其运行前提（所需环境变量与准备步骤）；该入口 SHALL 能按清单覆盖多条此类测试。当执行前提缺失而被跳过时, 系统 SHALL 把该测试记为未覆盖, SHALL NOT 让缺席被表述为通过。

#### Scenario: 前提齐备时真中间件执行

GIVEN 真实中间件已就绪且所需环境变量齐备
WHEN 通过统一入口的端到端分支执行
THEN 注解 SQL / 序列化 / 消息路由在真实引擎上被执行
AND 断言通过

#### Scenario: 前提缺失时按跳过处理

GIVEN 缺少任一所需环境变量
WHEN 执行该端到端分支
THEN 测试被跳过而非失败
AND 验收记录标注该测试未覆盖
AND 不据此声称真中间件路径已验证

---

## 备注

- 真中间件覆盖不引入 Testcontainers / Flyway / Liquibase / failsafe / 新 Maven 插件，仍以 `-Dtest=` 显式定向。
- MySQL 一律 scratch 库；Redis 只连与容器 `sport-verify-redis` 核对过 run_id 的实例，签名不留本地口令明文。
- RocketMQ 集成测试用独立 topic 与 consumer group，不触碰生产消费组位点。
- 全栈 `/daily` 端到端（宿主六服务 + 登录身份 + 规则版本机器判据）本期因宿主全栈运行门槛未具备，显式记为未覆盖并分期，不交付未经红绿自证的判别式。
# 规范差异：sport-record-verify

本文件包含对 `spec/specs/sport-record-verify/spec.md` 的规范变更（Feign 统一容错 + 内部接口凭证硬化）。

## ADDED Requirements

### Requirement: Feign 统一默认超时

WHEN 服务经 OpenFeign 发起跨服务调用,
系统 SHALL 使用全局默认连接超时 1000ms 与读超时 3000ms，且允许按客户端名覆盖；SHALL NOT 依赖 Feign 默认 10s/60s 作为未配置服务的超时。

#### Scenario: 默认超时生效

GIVEN 消费方未为某 Feign 客户端单独配置超时
WHEN 发起该客户端调用
THEN 使用 default connect-timeout=1000 与 read-timeout=3000

#### Scenario: 可按服务覆盖

GIVEN 已为某客户端配置更短或更长超时
WHEN 发起该客户端调用
THEN 以该客户端覆盖值为准

### Requirement: Feign 降级决策显式化

WHEN 被依赖服务不可用（连接拒绝 / 超时 / 熔断 OPEN）,
系统 SHALL 按契约语义给出明确业务错误或约定降级结果，且 SHALL NOT 以未处理异常对外返回 500 堆栈；对不可软降级的契约 SHALL 抛出业务异常以驱动重试或 DLQ，禁止假装成功。

#### Scenario: 好友榜 user-service 不可用返回空榜

GIVEN leaderboard 查询 type=friend 且 user-service 不可用
WHEN 拉取好友列表失败
THEN 返回空榜（不把总榜非好友当作好友展示）
AND 记录降级 warn 日志

#### Scenario: verify 拉轨迹 record-service 不可用

GIVEN verify 判定需要 RecordApi.getRecord/listPoints
WHEN record-service 不可用
THEN 抛出 RECORD_SERVICE_UNAVAILABLE（4007）
AND 不写入成功判定
AND 消息消费可重试或进入 DLQ

#### Scenario: AuthApi 不可软降级

GIVEN 经 AuthApi 注册/登录/刷新
WHEN user-service 不可用
THEN 抛出 USER_SERVICE_UNAVAILABLE（4006）
AND 不返回伪造 token 或伪造成功

### Requirement: 内部接口共享密钥校验

WHEN 请求命中服务本地 `/internal/**` 路径,
系统 SHALL 校验请求头 `X-Internal-Token` 与配置密钥一致；密钥经环境变量注入，本地可有演示默认值。不一致时 SHALL 拒绝（403/1002），不得仅依赖网络拓扑防护。

#### Scenario: 无密钥直连 grant-admin 被拒

GIVEN user-service 监听 8081
WHEN 不带正确 `X-Internal-Token` 调用 `POST /internal/auth/grant-admin`
THEN 请求被拒绝
AND 不授予 ADMIN

#### Scenario: Feign 内部调用自动携带密钥

GIVEN 服务间经 Feign 调用 `/internal/**`
WHEN 请求发出
THEN 自动携带与服务端一致的 `X-Internal-Token`
AND 校验通过后正常处理

### Requirement: 网关白名单不含无路由的 internal

WHEN 配置网关鉴权白名单,
系统 SHALL NOT 包含无对应路由的 `/internal/**` 死配置，避免未来误加 internal 路由时安全边界塌陷为可自提权。

#### Scenario: 白名单无 /internal/**

GIVEN 网关应用配置已加载
WHEN 读取 app.auth.whitelist
THEN 列表不含 `/internal/**`
AND 仍包含发 token 与探针所需前缀（如 `/api/auth/**`、`/actuator/**`）

## MODIFIED Requirements

### Requirement: 网内信任边界（鉴权）

原「服务间 `/internal/**` 仅靠网内拓扑信任、网关白名单放行」调整为：服务间 `/internal/**` 仍不对公网经网关路由暴露，但服务本地须校验共享密钥头；网关白名单不再放行无路由的 `/internal/**`。Actuator 指标端点本地演示可经白名单暴露；生产 profile SHALL 收敛暴露面（收窄 include，或管理端口/内网抓取隔离）。

#### Scenario: 生产 actuator 收敛口径已文档化

GIVEN 运维阅读 README 或 ADR-0007/0003
WHEN 部署生产 profile
THEN 文档要求收敛 actuator 暴露（收窄 include 或管理端口隔离）
AND 不将本地演示的 prometheus/metrics 公网可读配置直接用于生产

---

## 备注

- UserApi 好友榜产品口径：**空榜**，反对「不过滤」。
- RecordApi：**不可软降级**；fallback 只负责把故障转为明确业务码。
- 内部密钥默认值仅本地演示，生产必须注入 `INTERNAL_API_TOKEN`。

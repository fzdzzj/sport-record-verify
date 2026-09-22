# spec-delta：narrow-actuator-exposure

## MODIFIED Requirements

### Requirement: 网关统一鉴权

WHEN 请求进入网关,
系统 SHALL 校验 Authorization 头中的 token，失败 SHALL 返回 401（1001），成功 SHALL 解析 userId 并注入请求头透传下游。

#### Scenario: 有效 token 放行

GIVEN 请求携带有效 Bearer token
WHEN 网关过滤器处理
THEN 解析出 userId
AND 注入 X-User-Id 头透传下游

#### Scenario: 无效 token 拒绝

GIVEN 请求无 token 或 token 无效/过期
WHEN 网关过滤器处理
THEN 返回 401（1001）
AND 不放行至下游

#### Scenario: 白名单放行

GIVEN 请求路径属白名单（/api/auth/** 与健康探针 /actuator/health）
WHEN 网关过滤器处理
THEN 跳过鉴权直接放行

#### Scenario: actuator 非 health 端点走鉴权

GIVEN 请求路径为 /actuator/health 以外的 actuator 子路径（如 /actuator/metrics、/actuator/env、/actuator/prometheus）
WHEN 网关过滤器处理且请求无有效 token
THEN 返回 401（1001）
AND 不放行至下游

---

### Requirement: 白名单收紧

WHEN 网关过滤请求,
系统 SHALL 不为管理端接口提供匿名放行；内部接口（`/internal/**`）SHALL 不对公网经网关路由暴露，且网关白名单 SHALL NOT 包含无对应路由的 `/internal/**` 死配置（避免未来误加 internal 路由时安全边界塌陷为可自提权）。网关白名单对 actuator SHALL 只放健康探针 `/actuator/health`（精确匹配，SHALL NOT 含 `/actuator/**` 整段通配）；actuator 其余端点（指标/环境等）经网关访问 MUST 走鉴权分支，监控抓取按 ADR-0007 走内网直连不经网关。

#### Scenario: 管理端不匿名放行

GIVEN 请求路径为 /admin/**
WHEN 网关过滤
THEN 进入鉴权校验（不跳过）
AND 依角色判定放行或拒绝

#### Scenario: 内部接口维持网内

GIVEN 请求路径为 /internal/**
WHEN 网关过滤
THEN 维持网内信任（不对公网暴露）
AND 服务本地仍须通过共享密钥校验（见「内部接口共享密钥校验」）

#### Scenario: 白名单无 /internal/**

GIVEN 网关应用配置已加载
WHEN 读取 app.auth.whitelist
THEN 列表不含 `/internal/**`
AND 仍包含发 token 所需前缀（`/api/auth/**`）与健康探针精确路径（`/actuator/health`）

#### Scenario: 白名单无 actuator 整段通配

GIVEN 网关应用配置已加载
WHEN 读取 app.auth.whitelist
THEN 列表不含 `/actuator/**`
AND actuator 指标/环境端点经网关访问须携带有效 token

#### Scenario: health 探针不泄组件明细

GIVEN 任一服务（含网关）应用配置已加载
WHEN 读取 management.endpoint.health.show-details
THEN 值为 never
AND /actuator/health 响应仅含整体 status，不含数据源/Redis/磁盘等组件明细
AND health 端点本体与 include 列表（prometheus/metrics）保留（监控栈内网直连不受影响）

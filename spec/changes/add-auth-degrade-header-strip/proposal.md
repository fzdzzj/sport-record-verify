# 提案：鉴权降级路径与白名单路径剥离外部身份头

## Why

网关是系统唯一的外部入口，「身份由网关认定」这条边界目前**只在鉴权开启时成立**。

`AuthGlobalFilter.filter` 的第一个分支是：

```java
if (!authEnabled || isWhitelisted(path)) {
    return chain.filter(exchange);   // 原样透传，不动任何头
}
```

而这个分支在默认配置下**就是主路径**：

| 事实 | 位置 | 后果 |
| --- | --- | --- |
| `app.auth.enabled` 默认 `false` | `gateway-service/src/main/resources/application.yml:106` | 默认走降级分支 |
| 只有网关 8080 对宿主机全接口发布，5 个后端服务绑 `127.0.0.1` | `docker-compose.services.yml` 注释 | 网关是外部唯一入口，也是唯一可能的洗头点 |
| 下游按「网关注入的 `X-User-Id` 是唯一可信来源」取值 | `RecordController` 等 | 网关照抄外部头，等于把身份认定权交给调用方 |
| `/api/auth/**`、`/actuator/**` 属白名单 | `application.yml:114` | 白名单分支同样裸透传 |

即：`enabled=false` 且外部请求自带 `X-User-Id: 1`，下游就会按 userId=1 处理——**连 token 都不需要**。
`AuthGlobalFilter` 类注释写的「外部伪造的同名头被冲掉」，描述的是 `headers.set(...)` 那条
（鉴权开启）路径；降级分支与白名单分支并没有这条保证，注释覆盖范围大于实现。

**当前状态**：注释承诺「下游只信任网关注入值」，实现只兑现在三路分支中的一路。
**期望状态**：无论是鉴权注入路径还是透传路径，网关放行的请求都不带外部身份头——
降级只关校验，不放开身份头。

## What Changes

- `AuthGlobalFilter` 的透传分支（`!authEnabled || isWhitelisted(path)`）在 `chain.filter` 之前
  mutate 掉 `X-User-Id` 与 `X-Role`；两条透传路径（降级开关、白名单）共用同一段剥离逻辑。
- 鉴权开启路径**不动**：仍用 `headers.set(...)` 覆盖式注入 token 解析出的身份。
- 类注释同步：把「覆盖同名头」的适用范围写清，并补一句透传分支的剥离语义。
- 补单测（`AuthGlobalFilterTest`，沿用既有 MockServerWebExchange + 内联链风格）：
  - 降级分支带伪造 `X-User-Id`/`X-Role` → 下游收到的请求不含两头；
  - 白名单分支带伪造 `X-User-Id`/`X-Role` → 同上；
  - 鉴权分支带伪造头 + 有效 token → 下游只认 token 解析值（覆盖式注入未受影响）。

### 明确不做

- **不改 `app.auth.enabled` 默认值**：关闭态是本地演示与压测脚本的既有口径，改默认值是行为破坏性变更，
  要靠 profile / 环境变量强制开启另立变更再议。
- 不动 `JwtTokenParser`、白名单前缀语义、`adminPaths` 与角色校验逻辑。
- 不改 `application.yml` 里的注释（措辞同步与默认值同批处理，避免与「不改默认值」混在一次改动里）。
- 不引入新依赖 / 新 Maven 插件；不动其它模块。
- 不把服务侧「直连端口」问题一并修（那是网络暴露面，不在网关职责内）。

## Impact

### 受影响的规范
- `spec/specs/sport-record-verify/spec.md` — ADDED「网关降级路径剥离身份头」（含三个 Scenario）。
  差异见 `specs/sport-record-verify/spec-delta.md`；**本次不归档，并入主规格另派**。

### 受影响的代码
- `gateway-service`：`AuthGlobalFilter`（透传分支剥离两头 + 注释）；`AuthGlobalFilterTest` 新增用例。

### 受影响的既有行为
- 降级态下**只有自带 `X-User-Id`/`X-Role` 的调用会受影响**：这类调用本就在冒充身份，属修复对象。
- 已核对：仓库内**无任何脚本/前端**经网关发送这两个头——
  `web/src/api/client.ts` 明确写明「NEVER set 'X-User-Id', 'X-Role'」；
  `scripts/perf/LoadTest.java` 只用请求体占位符传 `{userId}`；
  冒烟脚本 `smoke-b.sh`/`smoke-cd.sh` 的 userId 在 JSON body 里；其余脚本直连 80xx 不经网关。
  故本地演示与压测口径不受影响。

### 风险与判别式
- 风险：剥离逻辑误伤鉴权路径（把注入值一起删掉）。判别式 = 「带有效 token + 伪造头 → 下游拿到的是
  token 解析值」这条用例；它在实现前就应为绿，实现后仍须为绿。
- 红证若取不到（改前测试也绿）说明断言没打在下游请求上，须重做取证，不得改口径。

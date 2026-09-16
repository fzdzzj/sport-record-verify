# 提案：统一 Feign 容错标准与内部接口凭证硬化

## Why

跨服务 Feign 超时与降级口径不一致：7 个 `@FeignClient` 仅 MapMatchApi / VerifyApi 配了 `fallbackFactory`；超时只配了 record→verify 与 verify→mapmatch，其余走 Feign 默认 connect 10s / read 60s，慢依赖可拖死整条链路。另：网关白名单含无路由的 `/internal/**`（死配置），user-service 的 `grant-admin` 等内部接口仅靠「网内信任」保护，直连 8081 即可调用。

**当前状态**：超时/降级参差不齐；`/internal/**` 无凭证；白名单留死配置；actuator 指标经白名单公网可读（本地演示可接受，生产口径未写清）。

**期望状态**：全局 Feign 默认超时 1s/3s；每个 Feign 契约有显式降级或「不可软降级」决策文档；依赖故障时调用方返回明确业务错误或约定降级结果（非 500+堆栈）；`/internal/**` 校验共享密钥头；网关白名单删除无路由 `/internal/**`；生产 actuator 收敛口径写入 ADR/README。

## What Changes

- 全局 Feign `default` 超时 connect=1000 / read=3000（record 用 properties，其余 yml）；按需保留服务级覆盖。
- 补齐 AuthApi / UserApi / RecordApi / LeaderboardApi 的 fallbackFactory 或不可降级注释；新增依赖不可用错误码 4006-4008。
- **降级决策（可追问）**：
  - **UserApi（好友榜过滤）→ 返回空榜**（不选「不过滤」）：不过滤会把总榜非好友泄露进好友榜，隐私与产品语义错误；空榜牺牲短暂可用性，可接受。LeaderboardService 捕获后返回空列表；昵称拉取失败降级占位符。
  - **RecordApi（verify 拉轨迹/回调）→ 不可软降级**：无轨迹无法判定，fallback 仅抛 `RECORD_SERVICE_UNAVAILABLE(4007)` 驱动 MQ 重试/DLQ，禁止假装成功写出错误判定。
  - **AuthApi → 不可软降级**：不可伪造 token / 注册成功，统一抛 `USER_SERVICE_UNAVAILABLE(4006)`。
  - **LeaderboardApi → 显式失败（4008）**：当前无 Feign 消费方；若未来接入读路径，调用方自行决定空榜或错误，契约层不假装成功。
  - **VerifyApi / MapMatchApi**：保持既有 4001 转人工 / 4005 R5 不命中。
- `breaker-test.sh` 增加停 user-service 的好友榜降级场景。
- `/internal/**` 增加 `X-Internal-Token` 校验（环境变量 `INTERNAL_API_TOKEN`，默认仅本地演示）；Feign 拦截器自动注入；更新 ADR-0007。
- 网关白名单删除 `/internal/**`；README/ADR 明确生产 actuator 应收敛（收窄 include 或管理端口隔离）。

## Impact

### 受影响的规范
- `spec/specs/sport-record-verify/spec.md` — 新增 Feign 统一超时与降级决策、内部接口凭证校验（本变更 spec-delta）。

### 受影响的代码
- `api/**` Feign 契约与 Fallback；`common/**` 内部凭证过滤器与错误码；各服务 Feign/超时配置；`gateway-service` 白名单；`user-service` InternalAuth 注释；`scripts/perf/breaker-test.sh`；`docs/adr/0007`、README。

### 用户影响
- 依赖故障时更快失败或按约定降级；直连内部接口无密钥被拒；经网关误配 `/internal` 路由时不再裸放行。

### API 变更
- 行为变更：`/internal/**` 要求 `X-Internal-Token`；Feign 调用自动带该头。
- 无对外 REST 路径变更。

### 需要迁移
- [ ] 数据库迁移
- [ ] API 版本提升
- [x] 运维：生产必须注入 `INTERNAL_API_TOKEN`，勿用默认演示值
- [x] 文档更新（ADR-0007 / README / 错误码表）

## 时间线评估

中：约 1 天（配置 + fallback + 过滤器 + 文档 + 验证）。

## 风险

- **密钥默认值误用于生产** → 缓解：ADR/README 明示演示默认；生产强制环境变量。
- **RecordApi 软降级误用** → 缓解：fallback 只抛业务异常，proposal/注释双写「不可软降级」。
- **好友榜空榜被误认为无好友** → 缓解：warn 日志区分；可后续加降级标记字段（本变更不做）。

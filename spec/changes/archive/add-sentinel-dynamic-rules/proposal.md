# 提案：网关流控规则切 Nacos 动态数据源

## Why

审批版 §8.3 / 压测变更 spec「限流与熔断验证」要求网关以 **5k QPS** 对齐限流基线。此前 `SentinelGatewayRuleConfig` 把流控规则以**代码加载**，阈值改一次就要**重启网关**才能生效——调整阈值、复测拦截链路都要停机重启，迭代成本高、不利于压测调参与线上灰度调参。当前只能「改代码 → 重启 → 生效」，无法做到「改阈值不重启即生效」。

**当前状态**：规则在 `SentinelGatewayRuleConfig` 硬编码加载，改 `count`（阈值）必须重启网关进程。

**期望状态**：规则从 Nacos 动态数据源加载（dataId=`gateway-flow-rules`，归属 DEFAULT_GROUP），改 Nacos 里的 count 即经长轮询推送 → `GatewayRuleManager` 就地更新，**不重启即生效**；Nacos 无规则/解析失败时回退代码默认 5000 QPS，限流不缺省。

## What Changes

- **pom.xml**：gateway-service 新增 `sentinel-datasource-nacos` 与 `sentinel-datasource-extension` 依赖（版本锁 1.8.6，与 SCA 2023.0.1.0 自带 sentinel-core 同版本，避免匹配冲突）。
- **SentinelGatewayRuleConfig**：启动时先 `GatewayRuleManager.loadRules(defaultRules())` 兜底默认；再以 `NacosDataSource` + `register2Property` 注册动态规则源，converter 解析 JSON 数组（元素为 GatewayFlowRule），解析失败返回 null 保持上一版规则不抖动。
- **application.yml**：新增 `app.sentinel.nacos.*`（server-addr/group-id/data-id），defaults 与配置项一致（127.0.0.1:8848 / DEFAULT_GROUP / gateway-flow-rules）。
- **scripts/perf/ratelimit-test.sh**：改为「动态生效复测」——同一网关进程不重启，先后向 Nacos 发布低/高阈值两档规则，验证 429 比例由高变 0 的「改阈值不重启即生效」实证。

## Impact

### 受影响的规范
- `spec/specs/sport-record-verify/spec.md` - 「限流与熔断验证」下追加动态规则源能力（ADDED）：Nacos 动态加载、改值不重启生效、兜底默认。

### 受影响的代码
- `gateway-service`：`pom.xml`、`SentinelGatewayRuleConfig`、`application.yml`
- `scripts/perf/ratelimit-test.sh`

### 用户影响
- 限流阈值调整无需重启网关，压测/运维可在线调参；最终用户无感知，限定行为不变（超限仍 429 + 提示）。

### API 变更
- 无对外 API 变更；新增 Nacos 数据源绑定（配置面）。

### 需要迁移
- [ ] 数据库迁移（无）
- [ ] API 版本提升（无）
- [ ] 用户沟通（无）
- [x] 文档更新（fast web 手册限流行、ADR）

## 时间线评估

小：约 1 天。规则结构与兜底逻辑已在现有 config 中，主要工作是引入数据源依赖并注册属性源。

## 风险

- **Sentinel 版本匹配冲突** → 缓解：datasource 锁 1.8.6，与 SCA 2023.0.1.0 自带 sentinel-core 同版本，且已实测编译通过（gateway 模块 verify SUCCESS）。
- **Nacos 无规则时限流缺省** → 缓解：启动先 `loadRules(defaultRules())` 兜底 5000 QPS，Nacos 首传/空源不影响限流基线。
- **坏配置抖动** → 缓解：converter 解析失败返回 null，`GatewayRuleManager` 保持上一版规则，不因瞬时坏 JSON 抖断限流。
# 规范差异：sport-record-verify

本文件包含对 `spec/specs/sport-record-verify/spec.md` 的规范变更（网关流控规则动态数据源，「限流与熔断验证」域内新增）。

## ADDED Requirements

### Requirement: 网关流控规则动态数据源

WHEN 网关加载流控规则,
系统 SHALL 从 Nacos 动态数据源加载网关流控规则（dataId=`gateway-flow-rules`，DEFAULT_GROUP），规则改动经 Nacos 推送就地生效而无需重启，且 SHALL 在无规则/解析失败时回退代码默认 5000 QPS 基线保证限流不缺省。

#### Scenario: 规则动态拉取并生效

GIVEN 网关已启动并注册 Nacos 动态规则源
WHEN 在 Nacos 中修改 `gateway-flow-rules` 的 count
THEN 网关不重启即收到推送并就地更新规则
AND 后续请求按新阈值判定

#### Scenario: 无规则时兜底默认

GIVEN Nacos 中尚无 `gateway-flow-rules` 或该 dataId 为空
WHEN 网关启动加载规则
THEN 使用代码默认 5000 QPS 基线
AND 限流行为不缺省

#### Scenario: 解析失败保持上版

GIVEN Nacos 推送了一份无法解析的规则 JSON
WHEN 网关处理该推送
THEN 保留上一版有效规则执行
AND 不因瞬时坏配置抖断限流

#### Scenario: 超限仍按 429 拦截

GIVEN 动态规则阈值已生效
WHEN 请求超过当前阈值
THEN 超限请求被限流拦截
AND 返回限流提示（HTTP 429）

---

## 备注

- 本变更把「代码加载、重启生效」升级为「Nacos 动态加载、改阈值不重启即生效」，落地审批版 §8.3「Sentinel 限流 5k QPS」基线，并保留代码兜底默认，是动态调参能力的实锤。
- 规则对象为 `GatewayFlowRule`（resource/count/intervalSec/grade），与 `SentinelGatewayRuleConfig` 中的路由 ID 粒度一致。
- 兜底与解析失败策略见 `SentinelGatewayRuleConfig` 实现注释。
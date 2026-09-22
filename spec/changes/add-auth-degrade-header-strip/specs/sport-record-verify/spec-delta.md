# 规范差异：sport-record-verify

本文件包含对 `spec/specs/sport-record-verify/spec.md` 的规范变更（「网关鉴权」域内新增需求）。
本次**不归档**：需求并入主规格由后续变更统一处理，本目录保留提案三件套与差异原文。

## ADDED Requirements

### Requirement: 网关降级路径剥离身份头

WHEN 请求未经过网关鉴权注入（鉴权开关关闭或路径命中白名单）,
系统 SHALL 在透传前剥离外部携带的 `X-User-Id` 与 `X-Role` 头。

#### Scenario: 降级开关下的伪造头清洗

GIVEN `app.auth.enabled=false` 且外部请求携带 `X-User-Id` / `X-Role`
WHEN 网关转发该请求
THEN 下游收到的请求不含这两个外部头

#### Scenario: 白名单路径下的伪造头清洗

GIVEN 请求路径命中白名单（`/api/auth/**` 或 `/actuator/**`）且外部请求携带 `X-User-Id` / `X-Role`
WHEN 网关转发该请求
THEN 下游收到的请求不含这两个外部头

#### Scenario: 鉴权路径的覆盖式注入不受影响

GIVEN `app.auth.enabled=true` 且请求携带有效 token 与外部伪造的 `X-User-Id` / `X-Role`
WHEN 网关过滤器处理
THEN 下游收到的是 token 解析出的 `X-User-Id` / `X-Role`
AND 剥离逻辑不作用于该分支

---

## 备注

- 需求边界是**头**，不是开关：`app.auth.enabled` 默认值不变（关闭态仍是本地演示与压测的既有口径），
  本需求只保证关闭态与白名单态不再成为身份伪造的通道。
- 「白名单」与「降级开关」共用同一段剥离逻辑，避免两条透传路径各自维护一份清洗行为。
- 与既有「网关统一鉴权」「鉴权降级开关」两条需求的关系：后两者描述校验与降级，本需求补齐
  「不校验时的边界」——三条合起来才是完整的身份认定口径。
- 服务直连端口（`127.0.0.1:8081-8085`）的暴露面不在本需求范围，属网络层职责。

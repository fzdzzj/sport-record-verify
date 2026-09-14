# 规范差异：sport-record-verify

本文件包含对 `spec/specs/sport-record-verify/spec.md` 的规范变更（道路拓扑匹配能力域）。

## ADDED Requirements

### Requirement: 独立路网匹配服务
WHEN 校验需要空间真实性判定,
系统 SHALL 提供独立的 `mapmatch-service` 承载路网数据与匹配算法，暴露匹配接口，与 verify-service 的规则链 SHALL 解耦。

#### Scenario: 服务注册
GIVEN mapmatch-service 已启动
WHEN 查看 Nacos 服务列表
THEN 可见 mapmatch-service 独立实例
AND 独立端口（默认 8085）

#### Scenario: 匹配接口返回
GIVEN 路网数据已加载
WHEN 调用 POST /mapmatch/match 提交轨迹点
THEN 返回匹配结果（matchedRatio / avgOffRoadDistance / offRoadRatio 等）

### Requirement: 真实路网数据
WHEN 路网服务初始化,
系统 SHALL 加载真实 OSM 路网数据（而非手工假数据），并提供幂等可重跑的导入流程。

#### Scenario: 路网导入成功
GIVEN 已下载指定城市 OSM 路网
WHEN 执行导入脚本
THEN 路网表写入空间库
AND 导入脚本幂等可重跑

#### Scenario: 数据可查询
GIVEN 路网已导入
WHEN 匹配算法查询最近道路
THEN 基于空间索引返回候选道路
AND 查询命中实际路网

### Requirement: R5 离路规则
WHEN 校验引擎执行规则链,
系统 SHALL 新增 R5 离路规则，通过路网匹配计算轨迹偏离真实道路的比例，偏离超阈值 SHALL 产生 SOFT/HARD 证据。

#### Scenario: 悬浮轨迹命中 R5
GIVEN 轨迹整体不在任何真实道路上（如海面/楼顶）
WHEN R5 执行路网匹配
THEN offRoadRatio 超过阈值
AND 命中 R5_OFFROAD 证据

#### Scenario: 真实轨迹不命中
GIVEN 真实骑行/跑步轨迹沿道路
WHEN R5 执行路网匹配
THEN offRoadRatio 低于阈值
AND 不产生 R5 命中

### Requirement: 判定聚合兼容
WHEN R5 产生证据,
系统 SHALL 将 R5 命中并入既有 hits 列表，走现有「HARD 即拒 / SOFT 计分」聚合，SHALL 不改变 R1-R4 既有行为。

#### Scenario: R5 软证据参与评分
GIVEN R5 命中 SOFT
WHEN 判定聚合
THEN score 计入 10×SOFT 项
AND 仅 SOFT 时遵循 soft-only-reject 策略

#### Scenario: R5 硬证据即拒
GIVEN R5 命中 HARD（极端偏离）
WHEN 判定聚合
THEN verdict=REJECTED

### Requirement: 匹配降级
WHEN mapmatch-service 不可用,
系统 SHALL 使 R5 降级为「不命中」，不阻断校验主链路，并 SHALL 记录告警。

#### Scenario: 服务不可用降级
GIVEN mapmatch-service 停机
WHEN R5 调用匹配接口失败
THEN 熔断降级为不命中
AND 校验主链路正常完成
AND 记录 warn 日志

---

## MODIFIED Requirements

### Requirement: 服务划分
**系统 SHALL 由 6 个服务构成：** gateway-service、user-service、record-service、verify-service、leaderboard-service、mapmatch-service；WHEN 系统部署, 道路拓扑匹配 SHALL 由 mapmatch-service 独立承载，离路判定 R5 SHALL 内聚于 verify-service 并远程调用匹配服务。

#### Scenario: 服务数
GIVEN 系统完整部署
WHEN 查看服务实例
THEN 可见 6 个服务各自注册
AND 空间匹配职责在 mapmatch-service 不在 verify-service

---

## 备注

- 本变更实现「规则链 + 空间真实性」双保险，把道路拓扑匹配从「讲设计」升级为实锤（执行计划 P2 天花板项）。
- R5 作为首个依赖外部服务的规则，验证了规则链的可扩展性（Rule 接口 + 依赖注入，新规则零改动接入聚合框架）。
- 数据用真实 OSM 单城市切片；匹配算法先落最近边投影，HMM 为进阶优化；R5 默认 SOFT 可灰度观察（复用 rule_version 机制）。
# 规范差异：sport-record-verify

本文件包含对 `spec/specs/sport-record-verify/spec.md` 的规范变更（规则灰度发布能力域，全部为新增）。

## ADDED Requirements

### Requirement: 规则版本化
WHEN 管理员创建规则版本,
系统 SHALL 将当前规则与阈值序列化为 `rules_json` 快照存入 rule_version，并 SHALL 维护版本状态（GRAY/ACTIVE/RETIRED）与 `gray_ratio`。

#### Scenario: 创建灰度版本
GIVEN 当前基线规则阈值
WHEN 管理员创建新版本并设 gray_ratio=10
THEN 快照 rules_json 落库
AND 版本状态 GRAY
AND 灰度比例 10

#### Scenario: 版本状态约束
GIVEN 已存在一个 ACTIVE 基线版本
WHEN 新版本被标记为 ACTIVE
THEN 旧版本置 RETIRED
AND 同一时刻至多一个 ACTIVE 版本

### Requirement: 灰度采样路由
WHEN 校验引擎执行,
系统 SHALL 按 `userId % 100 < gray_ratio` 决定使用灰度规则快照或基线规则，且 SHALL 保证同一用户始终同一分支。

#### Scenario: 命中灰度
GIVEN gray_ratio=10
AND 用户 userId%100=5（<10）
WHEN 该用户提交记录触发校验
THEN 使用灰度规则快照执行

#### Scenario: 未命中灰度
GIVEN gray_ratio=10
AND 用户 userId%100=50（≥10）
WHEN 该用户提交记录触发校验
THEN 使用基线规则执行

#### Scenario: 采样稳定性
GIVEN 同一用户重复提交
WHEN 多次触发校验
THEN 每次均命中同一分支（灰/基线）
AND 不因请求时序抖动切换分支

### Requirement: 规则快照隔离
WHEN 灰度观察期执行规则,
系统 SHALL 使用库内 `rules_json` 快照而非 Nacos 实时配置，避免灰度期间配置变更导致规则漂移。

#### Scenario: 灰度用快照
GIVEN 灰度版本观察中
AND Nacos 实时阈值此时被修改
WHEN 校验引擎取规则
THEN 灰度分支仍用版本快照执行
AND 不受 Nacos 瞬时变更影响

### Requirement: 秒级回滚
WHEN 灰度版本判定异常,
系统 SHALL 支持将 `gray_ratio` 置 0 秒级回滚，新版本 SHALL 立即不再被采样，基线 SHALL 不受影响。

#### Scenario: 回滚生效
GIVEN 灰度版本 gray_ratio=10 且出现异常
WHEN 管理员将 gray_ratio 置 0
THEN 短 TTL 缓存失效后（≤60s）新版本不再采样
AND 全部请求回归基线规则

#### Scenario: 基线性不受影响
GIVEN 灰度版本运行中
WHEN 回滚 gray_ratio=0
THEN 基线规则持续正常运行
AND 无请求中断

### Requirement: 全量发布
WHEN 灰度版本稳定,
系统 SHALL 支持将 gray_ratio 置 100 全量发布，并 SHALL 将旧版本置 RETIRED。

#### Scenario: 全量生效
GIVEN 灰度版本稳定运行满观察期
WHEN 管理员执行全量发布
THEN gray_ratio=100
AND 全部用户使用新版本规则

#### Scenario: 旧版本退役
GIVEN 新版本已全量
WHEN 发布完成
THEN 旧 ACTIVE 版本置 RETIRED
AND 不再被采样

### Requirement: 版本管理接口
WHEN 管理员管理规则,
系统 SHALL 提供版本管理端点：创建版本、调整灰度比例、全量发布。

#### Scenario: 创建与调灰度
GIVEN 管理员调用管理端点
WHEN 创建版本并调整 gray_ratio
THEN 返回版本信息与最新灰度比例

#### Scenario: 全量发布成功
GIVEN 存在 GRAY 版本
WHEN 调用全量发布端点
THEN 版本置 ACTIVE
AND 旧版本 RETIRED

---

## 备注

- 本变更把「阈值可配」升级为「版本化灰度发布」，落地审批版 §7.4 全部流程，是灰度发布能力的实锤。
- rule_version 表已建（sql/03-verify-db.sql），本变更不迁移表结构。
- 采样键 userId%100 与审批版 §7.4 一致；灰度观察期用库内快照避免与 Nacos 动态刷新竞态。
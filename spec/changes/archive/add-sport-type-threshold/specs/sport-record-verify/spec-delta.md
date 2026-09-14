# 规范差异：sport-record-verify

本文件包含对 `spec/specs/sport-record-verify/spec.md` 的规范变更（运动类型阈值能力域，全部为新增）。

## ADDED Requirements

### Requirement: 记录携带运动类型
WHEN 用户提交运动记录,
系统 SHALL 接收 sportType 字段，缺省 SHALL 回退为 RUNNING（向后兼容），未知类型 SHALL 拒绝。

#### Scenario: 提交带类型
GIVEN 用户提交记录并指定 sportType=CYCLING
WHEN 记录入库
THEN sportType 字段正确保存

#### Scenario: 缺省回退
GIVEN 提交记录未指定 sportType
WHEN 记录入库
THEN sportType 回退为 RUNNING
AND 校验行为与现有 RUNNING 一致

#### Scenario: 未知类型拒绝
GIVEN 提交记录 sportType 为枚举外取值
WHEN 记录提交接口处理
THEN 返回非法参数错误

### Requirement: 阈值按类型分维度
WHEN 系统配置规则阈值,
系统 SHALL 按运动类型分维度（每类型一套 R1-R4 阈值），而非全类型共用一套。

#### Scenario: 类型独立阈值
GIVEN 规则快照含 RUNNING 与 CYCLING 两套阈值
WHEN 读取阈值
THEN RUNNING 与 CYCLING 各自独立
AND 互不影响

### Requirement: 按类型判定
WHEN 校验引擎判定记录,
系统 SHALL 依据记录的 sportType 取对应阈值集执行 R1-R4，未知类型 SHALL 保守处理（回退默认或保守拒绝，可配）。

#### Scenario: 骑行不误杀
GIVEN 一条真实骑行轨迹 sportType=CYCLING
WHEN 引擎判定
THEN 取 CYCLING 阈值（速度上限高于跑步）
AND 不触发 R1 误判

#### Scenario: 跑步沿用原阈值
GIVEN 一条跑步轨迹 sportType=RUNNING
WHEN 引擎判定
THEN 取 RUNNING 阈值（沿用现有 5.5）
AND 行为与历史一致

### Requirement: 灰度与类型维度正交
WHEN 灰度路由与类型阈值叠加,
系统 SHALL 保持两维度正交：userId%100 决定使用哪个规则版本，sportType 决定版本内用哪套阈值，SHALL 不改动现有灰度路由逻辑。

#### Scenario: 正交叠加
GIVEN 某版本快照含多类型阈值
AND 用户命中灰度
WHEN 判定
THEN 先按灰度取版本，再按 sportType 取阈值
AND 灰度路由逻辑不变

---

## 备注

- 本变更消除 GenSamples 的已知局限（骑行 6~8m/s 超 R1 阈值被迫只测跑步），引擎走向多运动类型。
- rules_json 结构升级需向后兼容：旧结构缺类型维度时回退单套阈值。
- R5（空间匹配）与运动类型弱相关，暂维持通用，不纳入本变更。
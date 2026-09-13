# 提案：规则阈值分运动类型（校验精度质变）

## Why

当前校验引擎 R1-R4 阈值**全运动类型共用一套**：`GenSamples` 注释明确「骑行速度（6~8 m/s）超 R1 阈值，正样本集只取跑步/快走口径（引擎阈值暂不分运动类型）」。这暴露一个真实缺陷——**骑行的正常速度会被 R1（>5.5m/s）误判为作弊**。校验引擎目前只能正确校验「跑步/快走」，对其他运动类型要么误杀、要么放水。

这是校验精度的实质缺口，也是面试追问「阈值怎么定」「不同运动怎么处理」时的软肋。

**背景**：
- R1 速度阈值固定 `verify.rules.r1.speed=5.5`，对跑步合理、对骑行过低。
- records 提交时是否带 `sportType` 需确认（审批版运动记录应有类型字段）。
- 规则快照已支持 `rules_json`（rule_version 灰度），天然可扩展为「按 sportType 的阈值表」。

**当前状态**：阈值单一；提交轨迹无运动类型维度或未参与判定；压测样本被迫限定跑步口径。

**期望状态**：记录提交携带 `sportType`；规则快照按运动类型分维度（每类型一套 R1-R4 阈值）；判定时按记录的 sportType 取对应阈值；阈值表可配可灰度（复用 rule_version 机制）。

## What Changes

- **记录携带类型**：`SportRecordDTO` +提交接口增加 `sportType` 字段（RUNNING/CYCLING/WALKING 等枚举）。
- **阈值分维度**：`VerifyProperties.Rules` 或规则快照 JSON 改成 `Map<sportType, RuleThreshold>`，每类型独立 R1-R4 阈值。
- **判定按类型取阈值**：`VerifyEngine.verify` 根据 `sportType` 选对应阈值集；未知类型兜底默认（保守拒绝或宽松豁免可配）。
- **灰度兼容**：`rule_version.rules_json` 扩展为按类型嵌套，灰度路由仍按 `userId%100`，但快照内含类型维度。
- **迁移兼容**：旧数据/未传类型默认 RUNNING（保持现有行为），避免破坏已有压测与演示。

## Impact

### 受影响的规范
- `spec/specs/sport-record-verify/spec.md` - 追加运动类型阈值能力域（ADDED）：类型维度阈值、按类型判定。

### 受影响的代码
- `record-service`：SportRecord 实体/DTO +sportType、提交接口
- `verify-service`：VerifyProperties/规则快照改类型维度、VerifyEngine 按类型取阈值、R5 不受影响（R5 是空间匹配，与运动类型弱相关，暂维持通用）
- `api`：SportRecordDTO +sportType、新增 SportType 枚举
- `rule_version` 表 rules_json 结构升级

### 用户影响
- 骑行/步行等类型不再被误判；真实骑行轨迹可正常通过。

### API 变更
- 提交记录接口新增 sportType 字段（可选，默认 RUNNING，向后兼容）。

### 需要迁移
- [x] 数据库迁移（sport_record +sportType、rule_version rules_json 结构）
- [ ] API 版本提升（新增可选字段，向后兼容）
- [ ] 用户沟通
- [x] 文档更新（README/速览/ADR-0004 规则阈值补类型维度）

## 时间线评估

中等：约 1 周（涉及记录模型 + 阈值结构 + 引擎 + 灰度快照四处联动）。

## 风险

- **阈值分类型后每个类型如何定值** → 缓解：RUNNING 沿用现有 5.5；CYCLING 按骑行合理速度（如 15m/s）；各类型阈值写入配置说明依据，面试可讲「per-sport-type 标定」。
- **规则快照 JSON 结构升级的向后兼容**（灰度既有数据）→ 缓解：rules_json 做版本化解析，旧结构缺类型维度时回退单套阈值。
- **未知类型滥用（传奇怪类型逃过校验）** → 缓解：sportType 白名单枚举，未知值拒收或保守拒绝。
- **灰度与类型维度叠加复杂度** → 缓解：两维度正交（userId%100 决定用哪个版本，sportType 决定版本内用哪套阈值），不改动现有灰度路由逻辑。

## 备注

- 这是校验引擎「从单一运动类型走向多运动类型」的精度闭环，直接消除 GenSamples 的已知局限。
- 面试弹药：为什么 per-sport-type 阈值、阈值标定来源（真实数据分位数）、未知类型怎么保守处理。
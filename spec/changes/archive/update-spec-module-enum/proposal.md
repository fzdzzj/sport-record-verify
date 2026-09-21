# 提案：规格模块枚举补正（update-spec-module-enum）

## Why

主规格 `spec/specs/sport-record-verify/spec.md`「多模块工程结构」需求（L32-42）写
"8 个可编译模块"，但枚举括号内只列了 7 个（common、api、gateway-service、user-service、
record-service、verify-service、leaderboard-service）——缺 `mapmatch-service`（概览坑 9）。
父 pom 实际声明 8 个模块（common、api、gateway-service、user-service、record-service、
verify-service、leaderboard-service、mapmatch-service），文档与实现不一致。同文档
「服务划分」需求（L1096）已正确含 6 服务与 mapmatch-service，笔误仅此一处。

该需求此前已被 `add-controlled-verify-entrypoint` 以 MODIFIED 处理过，按既有规则本次
改动走新变更而非直接编辑主规格；验证通过后本变更自行归档并入主规格——这正是它存在的
意义：并入后主规格文本才真正修正。

## What Changes

- 新增变更 `spec/changes/update-spec-module-enum/` 三件套，spec-delta 以 EARS 格式
  MODIFIED 表达对「多模块工程结构」的修正：保留原需求结构与全部场景，仅枚举括号内补
  `mapmatch-service`，使"8 个"与列表一致。
- 验证通过后归档并入主规格：
  - 把 spec-delta 的 MODIFIED 应用到主规格 L35（枚举括号内补 `mapmatch-service`，不改其他行）；
  - 主规格头部「本规范已归档以下提案」列表末尾追加 `- update-spec-module-enum（规格模块枚举补正）`；
  - `git mv spec/changes/update-spec-module-enum spec/changes/archive/`。

**明确不做**：

- 不触碰 `spec/changes/` 下其余 14 个存量未归档变更（web 系列、perf 系列、gateway-browser-cors 等），
  整体归档另行派发；只归档本变更自己。
- 不改「服务划分」等其他需求（其已正确含 6 服务与 mapmatch-service）；不引入任何代码/脚本改动。

## Impact

### 受影响的规范
- `spec/specs/sport-record-verify/spec.md` — MODIFIED「多模块工程结构」（枚举补 `mapmatch-service`）。

### 受影响的代码
- 无（纯规范文本修正）。

### 用户影响
- 无运行时影响；仅修正规范文本与父工程模块声明的一致性。

### API 变更
- 无。

### 需要迁移
- [x] 文档更新

## 时间线评估

短：纯文档变更（变更三件套 + 红绿取证 + 归档 + 台账 + 回归确认），不涉及代码。

## 风险

- **误改其他需求行** → 缓解：只改白名单 7 文件，主规格仅动 L35 枚举与头部归档列表两处；
  红绿判据锁死「枚举 8 项 = 父 pom 8 模块逐名一致」。
- **改动集污染其余未归档变更** → 缓解：git mv 后核对 `git diff --cached --name-only`
  仅含本变更 3 文件；`git diff --name-only HEAD` + untracked 与白名单完全一致。

## 备注

- 红绿判据为文档级（判据脚本落盘为 .sh 执行，命令行不带中文）：
  - 红（修正前）：从「多模块工程结构」标题到下一个 `### Requirement:` 之间，节内
    `mapmatch-service` 计数 = 0，需求写"8 个"，父 pom `grep -c "<module>" pom.xml` = 8，
    枚举列表 7 项 → 7≠8 矛盾成立；
  - 绿（修正后）：节内 `mapmatch-service` 计数 = 1，节内模块枚举计数 = 8 = 父 pom 模块计数，
    逐名一致。
- 回归仅确认纯 spec 改动不扰动构建：`mvn-verify.sh --mode=offline test` → BUILD SUCCESS / 283。
- 不 push、不建 PR；未达外部门槛时在台账显式标注。

# TASK-121 台账：leaderboard-service 无用 import 清理（TASK-018 未覆盖项①）

## 背景与目标

TASK-018 接入静态检查三件套时发现 3 处真实无用 import，因该任务白名单无 Java 源改动权未修，
在 handoff 建议单开最小变更。本任务即该最小变更：**逐处核实后才删，只删 import 行**。

## 核实判据（删前必查，任一不成立则该处不删）

1. import 的类型在本文件内除 import 行外**零引用**（含代码、注释、javadoc）；
2. 无同名简单名冲突（删后不会引入歧义/改绑）；
3. 非 javadoc `{@link}` / `@see` 引用（javadoc 引用依赖 import 解析，删了会破坏 javadoc 解析）。

## 三处逐一核实结论（开工基线 `095fd98`，Grep 全文件计数留档）

| # | 位置 | 类型 | 引用计数（除 import 行外） | 结论 |
| --- | --- | --- | --- | --- |
| 1 | `InternalLeaderboardController.java:3` | `LeaderboardApi` | **1**（第 16 行 javadoc `{@link LeaderboardApi}`） | **核实不成立 → 不删** |
| 2 | `LeaderboardService.java:3` | `RecordVerifyEvents` | 0 | 成立 → 删 |
| 3 | `LeaderboardService.java:21` | `EnableCaching` | 0 | 成立 → 删 |

- 第 1 处：TASK-018 handoff 记录的行号与类型无误，但该类型是 javadoc `{@link}` 引用，命中判据 3 的排除项。
  **判据以本任务的核实流程为准，不沿用 TASK-018 的结论**——此即任务包定义的"未决"情形。
- 第 2/3 处：Grep 计数输出（模式 = 类型简单名，全文件）仅命中 import 行本身；文件内无同名冲突。

## 取证口径

- 本任务无"先红"判别式（删除类变更红不适用），改为**反向取证 + 删后绿**：
  - 反向取证：每处 import 的文件内引用计数（Grep 输出留档于本台账与 handoff）；
  - 删后绿：`bash scripts/verify/mvn-verify.sh --mode=offline test` 全绿、用例数与基线逐模块一致
    （删 import 不改行为，287→287 零扰动）。
- 契约：在途跑 `--baseline=095fd98`；收口提交后无参数复跑退出 0。

## 停止边界

- 只删核实成立的 import 行，不做任何其他顺手清理；不动 checkstyle/pmd 配置；不 push。
- 第 1 处不删，进 handoff「未决」。

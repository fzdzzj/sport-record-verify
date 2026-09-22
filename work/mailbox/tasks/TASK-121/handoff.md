# TASK-121 回传：leaderboard-service 无用 import 清理（TASK-018 未覆盖项①）

## 只改清单

- leaderboard-service/src/main/java/com/sportverify/leaderboard/service/LeaderboardService.java
- work/mailbox/tasks/TASK-121/spec.md
- work/mailbox/tasks/TASK-121/handoff.md
- work/mailbox/PLAN.md

## 核实与反向取证（引用计数，Grep 全文件，开工基线 `095fd98`）

| # | 位置 | 类型 | 除 import 行外引用计数 | 判定 |
| --- | --- | --- | --- | --- |
| 1 | `InternalLeaderboardController.java:3` | `LeaderboardApi` | **1**（第 16 行 javadoc `{@link LeaderboardApi}`） | 核实不成立 → **未删** |
| 2 | `LeaderboardService.java:3` | `RecordVerifyEvents` | 0 | 成立 → 已删 |
| 3 | `LeaderboardService.java:21` | `EnableCaching` | 0 | 成立 → 已删 |

- 第 1 处命中判据 3（javadoc `{@link}` 引用依赖 import 解析），按任务包规则不删，进「未决」。
  TASK-018 handoff 把它列为"真实无用 import"是就 checkstyle `UnusedImports` 而言的
  （checkstyle 默认不解析 javadoc 引用），本任务的判据更严，以本任务为准。
- 第 2/3 处：计数模式 = 类型简单名，全文件仅命中 import 行本身；文件内无同名冲突。
  第 3 处另核对本文件注解：只有 `@Cacheable`，无 `@EnableCaching`。
- 实际改动 diff 自证：`git diff 095fd98 -- LeaderboardService.java` 仅删除上述 2 行 import，
  其余零变化（删除行数 2，新增行数 0）。

## 删后绿（offline 全量）

- 命令：`bash scripts/verify/mvn-verify.sh --mode=offline test`（删后、收口前工作树实跑；
  台账两件套 + PLAN 记录为文档文件，不影响编译与用例）
- 结果：`BUILD SUCCESS`，逐模块 17 / 22 / 31 / 80 / 81 / 50 / 6 = **287** 全绿零跳过，
  用例数与开工基线逐模块一致（零扰动）；退出码 **0**。日志：`.trae/tmp/task121-offline.log`。
- **任务包口径订正**：任务包写"284 全绿"，为 TASK-018 时点的旧锚点；TASK-119 网关 +3 用例后
  总数已是 287（gateway 19→22）。本回传以实测 287 为准。
- 词面自检（CI 同款正则，`LC_ALL=C`）：本任务新增/改动的文本载体 0 命中；收口后全仓
  `git grep` 同款复跑 0 命中（命令行不含该正则，输出留档于本节描述）。

## 契约

- 在途：`bash scripts/verify/mailbox-contract.sh --baseline=095fd98` → 退出码 **0**，
  TASK-121 判据 A 两件套齐 + 判据 B 清单一致（4/4 文件逐一对上）。
- 收口后：无参数复跑 → 退出码 **0**（工作树无迹，不重审）。

## 未决

1. **`InternalLeaderboardController.java:3` 的 `LeaderboardApi` import 未删**（核实不成立：
   javadoc `{@link LeaderboardApi}` 引用，删了会破坏 javadoc 解析）。若后续想让
   checkstyle `UnusedImports` / PMD `UnnecessaryImport` 对该处归零，正路是改 javadoc 表述
   （如 `{@link com.sportverify.api.leaderboard.LeaderboardApi}` 全限定名后删 import），
   属另一最小变更，本任务边界内不动。
2. 任务包"284"数字已过时（现为 287），建议后续派发以最新锚点为准（本次已实测订正，无遗留动作）。

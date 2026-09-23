# TASK-129 核实 VerifyService 同 recordId 并发重入后果链（承 TASK-123 裁定哲学）

## 目标

先核实后文档化，默认不引入锁。findings F22 记录「verify 同一 recordId 并发重入无互斥，
双判定重复回调依赖 3003 冲突重试收敛；改法可配 Redisson 锁或文档化接受」。
TASK-123 先例：无正确性缺陷实证的并发窗口，裁定维持权衡并文档化，不做无实证优化。

核实问题清单（后果链四环节，逐环节给文件:行号证据）：

1. 并发双判定入口与窗口（无互斥实证）；
2. 结果落库冲突路径（3003 语义：定义点、触发点、幂等跳过分支）；
3. 上游重试方与收敛性（消费端失败处理 → 重投 → 重入 verify 的收敛路径）；
4. leaderboard 消费是否幂等（重复事件有无双份加分的可复现路径）。

判定标准：冲突拒绝式收敛 + 消费幂等兜住 → 文档化接受（javadoc 补权衡说明 +
F22 标已裁定 + 重开条件）；存在可复现错态（如双份贡献）→ 停手原文回传待裁定。

## 只改清单

- verify-service/src/main/java/com/sportverify/verify/service/VerifyService.java
- work/mailbox/findings-summary.md
- work/mailbox/tasks/TASK-129/spec.md
- work/mailbox/tasks/TASK-129/handoff.md
- work/mailbox/PLAN.md

## 停止边界

- 只改清单外零改动；VerifyService.java 仅 javadoc，主链路逻辑零变更
- 不动 outbox / 消费侧代码
- 未经主 agent 裁定不得引入 Redisson 锁或任何新互斥（与 TASK-123 同一裁定哲学）
- 不 push

## 复跑口径

- offline 全量零扰动（仅注释与台账；任务包所写 299 为过期锚点，TASK-127 起基线为 300）
- 契约 rc=0（在途 --baseline + 收口后无参数）
- 词面双 locale 自检

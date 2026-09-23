# TASK-128 核实 verify→leaderboard 事件丢失风险是否已被 outbox 闭环

## 目标

纯核实 + 台账更新，**无代码改动**。findings-summary F03 记录「判定 PASSED 但事件发送失败
即永久丢失，verify→leaderboard 方向没有补偿」（VerifyEventProducer.java:57-60 catch 后仅 log）；
派发背景称 outbox 已由 TASK-108（提交 a048745）落地。需逐链路核实台账结论与代码现状孰是孰非，
并同法核实 F09（事件可靠性第二条）当前状态。

## 核实问题清单（F03）

1. 判定事务内是否同事务写 outbox 行？
2. relay 是否定时重发直至成功？
3. catch-log 路径是否已被 outbox 覆盖（还是仍有旁路直发）？

## 只改文件

- work/mailbox/findings-summary.md
- work/mailbox/tasks/TASK-128/spec.md
- work/mailbox/tasks/TASK-128/handoff.md
- work/mailbox/PLAN.md

## 停止边界

- 核实类任务禁止顺手修代码
- 不 push
- 若发现部分闭环/仍在：不改代码，原文差距回传待主 agent 裁定立项

## 复跑口径

- offline 全量零扰动（无代码改动）
- 契约收口 rc=0
- 词面双 locale 自检

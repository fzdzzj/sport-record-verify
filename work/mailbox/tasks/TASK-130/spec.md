# TASK-130 性能热点 + 治理面现状侦察（立项前情报，不改任何代码）

## 目标

findings 积压一批规模性热点与治理面缺口，直接立项的风险是方案凭旧台账拍脑袋。
本任务纯侦察：逐项核实现状、给出影响面量化假设、修复方案草案与分级、红绿判别式可行性、
是否需要 scratch 库或全栈环境验证，产出下一轮立项建议供主 agent 写任务包。

侦察清单：

1. F05 mapmatch 逐点一次 SQL（MapMatchService.java:110-126）
2. F06 好友榜全量拉取（findings 原文行号需先核实）
3. F15~F17 点赞三条（含「是否已被 TASK-108 系列顺带解决」）
4. 治理面：服务侧不校验角色 —— 盘点 common InternalApiAuthFilter 覆盖面与 ADR-0007
   现行「网关内网信任边界」约定，给出「维持 ADR 边界 vs 服务侧校验角色」两个方向的论据

## 只改清单

- work/mailbox/findings-summary.md
- work/mailbox/tasks/TASK-130/spec.md
- work/mailbox/tasks/TASK-130/handoff.md
- work/mailbox/PLAN.md

## 停止边界

- 侦察类任务禁止改任何代码/配置/规格：不碰 `*/src/**`、不碰 pom/yml/properties/sql
- 数据库核实只用 scratch 库只读查询（本次无可用 scratch 实例，见 handoff「环境可行性」节）
- 不 push

## 复跑口径

- offline 全量零扰动（本任务只改 `work/` 下 4 个 md，用例数应与收口锚点逐位一致）
- 契约 rc=0（在途 `--baseline` + 收口后无参数）
- 词面自检双 locale

## 完成定义

- handoff 内侦察报告：逐项「现状证据[文件:行号] / 影响面量化假设 / 修复方案草案与分级 /
  红绿判别式可行性 / 是否需 scratch 库或全栈环境验证」
- 立项建议：每项标「建议立项 / 建议关闭 / 需用户拍板」
- findings-summary 相应行加核实批注

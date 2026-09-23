# TASK-127：归档三个 spec 变更并收敛两处 /actuator/** 漂移

## 任务来源

派发任务包 TASK-127（2026-09-23）。背景：`spec/changes/` 现有 3 个未归档变更
（add-auth-degrade-header-strip、narrow-actuator-exposure、add-strict-secret-fail-fast），
三条验收记录均已闭环（外部门槛 run 35802403723 两 job 全绿）。归档前有两处登记在案的
漂移必须先收敛：

1. **漂移①**：add-auth-degrade-header-strip 的 spec-delta 中 GIVEN 仍写整段 actuator 通配
   （TASK-125 未决②）——归档并入主规格前必须同步为 `/actuator/health`，否则把过期口径写进主规格。
2. **漂移②**：gateway `AuthGlobalFilter.java` 的 `@Value` 默认值与 javadoc 仍写整段 actuator 通配
   （TASK-125 未决①，yml 常在故运行时漂移不生效，但默认值与 javadoc 属文档面过期）。

## 硬约束（逐条遵守）

- 唯一验收入口：`bash scripts/verify/mvn-verify.sh --mode=offline test`；不 push；不引入新依赖。
- 先红后绿：判别式测试在改默认值前必须取到红（附失败断言原文与行号）；变异验证（TASK-106/125 手法）
  用 cp + sha256 留底还原，全程禁用 `git stash`。
- 归档搬移不得改写需求原文语义（除登记在案的漂移①修正）；不动 archived 变更的既成措辞。
- 收口后 `spec/changes/` 下应为 0 个在途变更（契约脚本以此为准）。
- 命令行禁中文；全仓用例数只增不减（gateway 29→30，其余模块零扰动）。

## 只改范围

- `gateway-service/src/main/java/com/sportverify/gateway/auth/AuthGlobalFilter.java`（@Value 默认值 + javadoc）
- `gateway-service/src/test/java/com/sportverify/gateway/auth/ActuatorWhitelistNarrowTest.java`（判别式，29→30）
- `spec/specs/sport-record-verify/spec.md`（归档并入 + 头部提案清单 + 变更历史）
- `spec/changes/{add-auth-degrade-header-strip,narrow-actuator-exposure,add-strict-secret-fail-fast}/`
  （①漂移修正、tasks.json 补归档步骤、整目录移入 `spec/changes/archive/`）
- `work/mailbox/tasks/TASK-127/{spec.md,handoff.md}`（本文件与回传）
- `work/mailbox/PLAN.md`（追加验收记录）

## 执行计划

1. 台账两件套（本文件 + handoff.md）。
2. 漂移②先红：ApplicationContextRunner 不注入 whitelist 属性实例化过滤器，断言生效白名单等于
   新默认值 `/api/auth/**,/actuator/health`——当前默认值仍为旧口径，判别式应红。
3. 漂移②后绿：@Value 默认值改新口径、javadoc 同步；定向复跑回绿；变异验证（临时还原旧默认值
   复现红，字节级还原 cmp 零差异）。
4. 漂移①：delta 文本 GIVEN 修正（纯规格文本，并档前完成）。
5. 归档：三个 delta 按 ADDED/MODIFIED 并入主规格「鉴权」域对应需求节（EARS/Scenario 原文逐字搬移）；
   头部提案清单与变更历史补三条；tasks.json 各补归档步骤；`git mv` 整目录入 archive/。
6. 全量 offline（预期 20/30/33/80/81/50/6 = 300）+ 词面双 locale + 契约在途/收口后无参数。
7. 分批 conventional commits + PLAN.md 验收记录。

## 判据与停止边界

- 红绿判据：判别式测试在改前红（断言清单内容不等于新默认值）、改后绿；变异轮复红。
- 全量：offline 全绿，gateway 30、其余六模块与基线 299 逐位一致（各 +0）。
- 词面：CI 同款正则、`LC_ALL=C` 全仓零命中；默认 locale 残留按既有伪影登记。
- 契约：在途 `--baseline=415d36d` 判据 A/B 通过；收口提交后无参数退出 0。
- 停止边界：不 push；不动 archived 变更措辞；超出只改范围即停。

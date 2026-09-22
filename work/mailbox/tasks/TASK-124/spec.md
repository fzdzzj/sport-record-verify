# TASK-124 · Sentinel 网关兜底路由补全

## 背景与目标

`gateway-service` 的 `SentinelGatewayRuleConfig.ROUTE_IDS`（限流兜底默认，Nacos 无规则时生效）
只登记了 record/user/verify 三条路由，而 `application.yml` 路由表实有 7 条：

```
route-auth-service / route-user-service / route-record-service / route-leaderboard-service
route-verify-service / route-admin-service / route-mapmatch-service
```

缺失的 4 条路由在 Nacos 无规则时没有限流兜底；javadoc 声称「与 application.yml 路由表一致」
但无机械校验，属文档口径与实现漂移。

目标：`ROUTE_IDS` 补全为与路由表一致的全部 7 条；并加一条判别式测试——从 classpath 的
`application.yml` 读真实路由 ID 与 `defaultRules()` 产物比对（承
`LeaderboardDailyAdminOnlyTest`「yml 灌入、防硬编码漂移」先例），今后 yml 加路由而兜底漏配直接红。

## 红绿取证计划

- 红（改前）：新测试按 yml 提取全部路由 ID，断言 `defaultRules()` 覆盖全部 → 当前只有 3 条 → 红
  （断言消息附缺失清单原文）。
- 绿（改后）：同断言通过。
- 变异验证：从 `ROUTE_IDS` 临时删一条 → 复现同样的红 → 还原后 `sha256sum -c` OK + `cmp` 零差异。

## 规格判定

不建 spec 三件套：本任务是既有限流基线（审批版 §8.3「Sentinel 限流 5k QPS」兜底默认）的
实现覆盖补全，无新需求、无行为语义变化（补的是既有声明的完整性），handoff 注明。

## 只改清单

- gateway-service/src/main/java/com/sportverify/gateway/config/SentinelGatewayRuleConfig.java
- gateway-service/src/test/java/com/sportverify/gateway/config/SentinelRouteCoverageTest.java（新建）
- work/mailbox/tasks/TASK-124/spec.md、work/mailbox/tasks/TASK-124/handoff.md（新建）
- work/mailbox/PLAN.md（追加验收记录）

## 停止边界

不动 Nacos 数据源注册逻辑、不动阈值/窗口默认值、不动 fallback 配置；不 push。

## 复跑命令

- 唯一入口：`bash scripts/verify/mvn-verify.sh --mode=offline test`（全仓，≥291）
- 定向留档：`bash scripts/verify/mvn-verify.sh --pl gateway-service test`
- 红绿/变异定向：`mvn -pl gateway-service -am test -Dtest=SentinelRouteCoverageTest`（承 TASK-125 先例，仅取证用）

## 基线锚点（实跑口径）

任务包原文写「gateway 22→23+，全仓 287→288+」为 TASK-125 增量前旧锚点；开工实况
（HEAD `e6c2643`）全仓 **290 = 17/25/31/80/81/50/6**（gateway 25 含 TASK-125 的 +3）。
本任务完成后应为 gateway 25→27（新测试类含 2 条用例）、全仓 290→292，以实跑为准并登记 handoff。

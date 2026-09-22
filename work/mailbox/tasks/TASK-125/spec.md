# TASK-125 后端研发工程

## 目标
actuator 白名单与详情暴露收窄（findings F08）：网关白名单 `/actuator/**` 收窄为 `/actuator/health`
（精确匹配），六个服务 `show-details: always` → `never`——health 仍返回 UP/status，
不再经网关与探针泄组件明细；监控栈按 ADR-0007 走内网直连（prometheus.yml 抓
host.docker.internal:8080-8084，不经网关），网关只应放健康探针。

## 前置核实（停止条件）
- `scripts/perf/*.sh`：全部只打 `/actuator/health`（healthcheck.sh:5、ratelimit-test.sh:36/40、
  recover-verify.sh:12、run-perf.sh:100/117/131），无非 health actuator 依赖。
- `prometheus/prometheus.yml`：metrics_path=/actuator/prometheus，但 targets 全部为
  host.docker.internal 直连服务端口（8080-8085），不经网关。
- `docker-compose*.yml`：无经网关的 actuator 依赖。
- **结论：无冲突，停止条件不触发。**

## 规格判定依据
Grep 主规格 `spec/specs/sport-record-verify/spec.md`：`/actuator/**` 写进了两处需求文本——
1. 「网关统一鉴权」Scenario「白名单放行」GIVEN（spec.md:1988）；
2. 「白名单收紧」正文（spec.md:2083「Actuator 指标端点本地演示可经白名单暴露」）
   与 Scenario「白名单无 /internal/**」AND 子句（spec.md:2104）。
⇒ 按规格驱动流程建 `spec/changes/narrow-actuator-exposure/` 三件套（MODIFIED + EARS）。

## 只改文件
- gateway-service/src/main/resources/application.yml（whitelist 一行 + 相邻注释同步）
- user-service/src/main/resources/application.yml（show-details 一行）
- record-service/src/main/resources/application.properties（show-details 一行，properties 格式）
- mapmatch-service/src/main/resources/application.yml（show-details 一行）
- leaderboard-service/src/main/resources/application.yml（show-details 一行）
- verify-service/src/main/resources/application.yml（show-details 一行）
- gateway-service/src/test/java/com/sportverify/gateway/auth/ActuatorWhitelistNarrowTest.java（新建）
- spec/changes/narrow-actuator-exposure/（三件套：proposal.md、tasks.json、spec-delta.md）
- work/mailbox/tasks/TASK-125/spec.md、handoff.md（本两件套，新建）
- work/mailbox/PLAN.md（追加验收记录）

## 下一个标题（隔断判据 B 解析）
- AuthGlobalFilter.java 的 `@Value` 默认值与 javadoc（/actuator/**）不在本任务改动清单内，
  属停止边界；fallback 语义漂移登记 handoff 未决。

## 验收命令
```bash
bash scripts/verify/mvn-verify.sh --mode=offline test
bash scripts/verify/mailbox-contract.sh
```

## 完成定义
- 红绿取证：新测试从 classpath application.yml 读真实 whitelist 灌进过滤器
  （承 LeaderboardDailyAdminOnlyTest 先例），断言 /actuator/metrics、/actuator/env 走鉴权分支
  → 改前在白名单裸放行（红，附失败原文）→ 改后 /actuator/health 放行 + metrics/env 走鉴权（绿）。
- 变异验证：whitelist 临时改回 /actuator/** 复现红 → 还原 cmp 零差异 + sha256 一致。
- 全仓 offline 用例数：gateway 22→25（+3），总数 287→290，其余模块零扰动。
- 契约脚本收口后无参数退出 0；不 push。

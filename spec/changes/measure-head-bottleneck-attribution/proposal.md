# measure-head-bottleneck-attribution

## Why

当前 HEAD 上已经落地批量插轨迹、规则二级缓存、路网分块预筛、好友榜窗口扫描、总榜真实入口缓存、点赞对账去 N+1、outbox 和原生重试。旧压测报告的 137 QPS / 1.6s P95 来自更早的环境，不能当作本 HEAD 的瓶颈。

没有同一负载下的延迟、吞吐、资源占用和调用次数归因之前，任何新的索引、缓存、连接池或 JVM 改动都无法验收。本变更只补这张归因表，不改业务代码。

## What Changes

- 在当前 HEAD 上固定环境快照，并用现有 `scripts/perf/run-perf.sh` 复跑**一档**提交负载：100 并发 x 2000 请求。
- 用代码路径盘点一次「提交」和一次「校验（含 R5）」的 SQL、Redis、Feign/HTTP、MQ 次数。
- 若校验栈可起，再跑现有 `quality` 验收集，记录拦截率、通过率、校验 P95；起不来则记未覆盖，不得用旧报告数字冒充。
- 把主要耗时归入且只归入一类：业务规则、数据库、远程调用、锁、CPU、GC。
- 产出 `docs/perf/归因-HEAD.md` 与小型 `docs/perf/data/attr-head-summary.json`。
- 把「优化前必须归因、一次只改一类、调用次数未降不得先调 JVM、不得改权限/幂等/治理语义」写入压测规范。
- 不实现被选中的那类优化，不调 JVM/GC/堆，不加索引，不改连接池，不启用 innodb 刷盘 overlay，不跑 500/1000 并发。

## Impact

- 受影响规范：`spec/specs/sport-record-verify/spec.md` 的「压测」域（ADDED）。
- 受影响文档：`docs/perf/归因-HEAD.md`、`docs/perf/data/attr-head-summary.json`、本变更三件套、`work/mailbox/tasks/TASK-138/`、`work/mailbox/PLAN.md`。
- 不受影响：全部业务 Java、SQL、服务 yml/properties、网关路由、治理凭证、好友/点赞/榜单实现。
- 明确删除并取代未完成的 `spec/changes/add-bottleneck-first-optimization/`（约束空转，没有可执行度量）。
- 不触碰 `spec/changes/add-verify-degrade-status-index/`（否决记录）以及已暂存的 outbox/native-retry 归档 rename。
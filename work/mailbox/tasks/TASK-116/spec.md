# TASK-116 全栈 `/daily` 端到端冒烟（闭环 TASK-110 的 C 项分期）

## 目标

TASK-110 的 C 项（`GET /api/leaderboard/daily` 全栈端到端）因宿主六服务拉起门槛当时不具备而显式记未覆盖并分期。
本期在环境就绪后补上该判据：交付独立 `scripts/smoke/smoke-daily.sh`，可运行、可红绿的机器判据，闭环 C 项。

链路：登录取 token（user-service）→ 携带 `Authorization: Bearer` 经网关 → leaderboard `/daily`
→ 断言响应结构 + 抽取「当日快照 top 名次距离」机器信号与期望比对。

## 交付

1. 新建 `scripts/smoke/smoke-daily.sh`：
   - 0.预置隔离岛：`docker exec` 到 MySQL，DELETE + INSERT 到 `record_db.leaderboard_daily_summary`（判定日期下 top-1 唯一，`SEED_TOP` 固定 `88.05`）。
   - 1.登录取 token：`POST /api/auth/login` → `sed` 抽 `accessToken`；连接失败/502/503 → `exit 3`「未就绪」。
   - 2.携带身份头：`GET /leaderboard/api/leaderboard/daily?date=...&size=10`（`Authorization: Bearer`）。
   - 3.断言 HTTP 200 + body `"code":0` + `"data":[{`。
   - 4.抽 rank=1 的 `distance` 与 `EXPECT_TOP`（默认 `88.05`）比对；不等 → `exit 1`「断言失败」。
   - 通过 → `exit 0`。
   - 不依赖 jq，仅 curl/sed/grep/date（Git Bash 自带）。
2. `scripts/smoke/README.md` 执行顺序表追加步骤 6 + env 变量行 + 「步骤 6」小节（三维退出码表 + rule version 机器信号语义 + 隔离岛说明）+ 预期输出条目。

## 判据语义（rule version 机器信号）

`/daily` 返回 `leaderboard_daily_summary` 的当日快照行，**无字面 `ruleVersion` 字段**
（`LeaderboardController` 返回 `Result<List<LeaderboardDTO>>`，DTO 仅 `rank/userId/nickname/distance`）。
该沉淀行由 verify→leaderboard 结算管线（`settleAndReconcile` → `upsertFromActiveContributions`，只写 `CURDATE()`）
写出，top 距离唯一取自规则校验通过的里程。故以**隔离岛日期下 top-1 距离**作可断言、可红绿的机器信号。

三维退出码：0=通过 / 1=断言失败 / 3=服务未就绪。

## 红绿取证（先红后绿，实跑）

- 红：`EXPECT_TOP=99.99`（≠ seed 88.05）→ `断言失败: top-1 距离 88.05 ≠ 期望 99.99`，exit 1。
- 绿：还原默认 `EXPECT_TOP=88.05` → `断言通过: top-1 距离 88.05 == 期望 88.05`，exit 0。
- 未就绪：`BASE_URL` 指向 dead 端口 → curl 连接失败，`未就绪`，exit 3。
- 红绿关键：seed 用**固定** `SEED_TOP`（不随 `EXPECT_TOP` 变化），把期望值改错必然红、还原即绿。

## 环境前置自检（第一停止边界，已完成）

- 六中间件 `docker compose ps` 均 `healthy`（nacos/mysql/redis/rocketmq-namesrv/rocketmq-broker/postgis）。
- 六服务可在宿主拉起 8080-8085（含 mapmatch/PostGIS/sharding）。
- 内存管理：停止 exam 系列容器 + Docker VM 内存提至 12GB（`.wslconfig`）。

## 停止边界

- 不改 `scripts/smoke/smoke-evidence.sh`、不改服务代码（`src/main`）、不动 `scripts/verify/mvn-verify.sh`。
- 不 commit 之外的人工操作；不 push、不建 PR。
- 只改清单：`scripts/smoke/smoke-daily.sh`、`scripts/smoke/README.md`、
  `work/mailbox/tasks/TASK-116/{spec.md,handoff.md}`、`work/mailbox/PLAN.md`。

## 验收命令（唯一口径）

```bash
bash scripts/smoke/smoke-daily.sh                              # 绿：exit 0
SMOKE_DAILY_EXPECT_TOP=99.99 bash scripts/smoke/smoke-daily.sh # 红：exit 1
BASE_URL=http://127.0.0.1:9999 bash scripts/smoke/smoke-daily.sh # 未就绪：exit 3
bash scripts/verify/mailbox-contract.sh --open=TASK-018,TASK-106  # 契约自证，收口后退出 0
```

## 完成定义

`handoff.md` 写入：绑定修订 / 只改清单与 `git diff --name-only` 一致（契约判据 B）/ 环境前置自检结果 /
红绿取证原文（三维退出码）/ 隔离岛设计说明 / 未达外部门槛声明 / 台账位置（PLAN.md TASK-116 验收记录，
注明 TASK-110 C 项就此闭环）/ 临时 ADMIN 账号与 seed 数据说明。正文 ≤300 字，关键输出贴原文。
全绿后按 `test(smoke): 全栈 /daily 端到端冒烟脚本` 自行 commit（不 push）。
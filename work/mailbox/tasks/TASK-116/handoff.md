# TASK-116 全栈 `/daily` 端到端冒烟 —— handoff

## 绑定修订

开工基线 `main@{之前收口提交}`（TASK-115 收口后）；本条记录所在的收口提交由其所在 commit 承载
（未 push，见「是否到达外部门槛」）。

## 只改清单（契约判据 B：与 `git diff --name-only` + untracked 一致）

1. `scripts/smoke/smoke-daily.sh`（新建，实跑取证通过）
2. `scripts/smoke/README.md`（执行顺序表 + env 变量 + 步骤 6 小节 + 预期输出）
3. `work/mailbox/tasks/TASK-116/spec.md`（本任务）
4. `work/mailbox/tasks/TASK-116/handoff.md`（本任务）
5. `work/mailbox/PLAN.md`（追加本任务验收记录，注明 TASK-110 C 项就此闭环）

`.trae/` 为基线允许排除项。未动：`smoke-evidence.sh`、服务代码、`mvn-verify.sh`。

## 环境前置自检（第一停止边界，实测通过）

- 六中间件 `docker compose ps` 均 `healthy`：nacos、mysql、redis、rocketmq-namesrv、rocketmq-broker、postgis。
- 六服务宿主拉起 8080-8085：gateway 8080 / user 8081 / record 8082 / verify 8083 / leaderboard 8084 / mapmatch 8085。
- 内存治理：停止 exam 系列容器释放 + Docker VM 内存提至 12GB（`C:\Users\fzdzzj\.wslconfig`，PowerShell `Set-Content` 写入）。
- 中途修正：`.env` 密码与容器卷实际不一致（root/root），统一 `MYSQL_ROOT_PASSWORD=root`、`POSTGRES_PASSWORD=postgres` 重启后 6 服务全部 DB 可达；迁移 SQL 因无 `USE` 子句改用 `docker exec` 直接应用到 record_db。

## 隔离岛设计（关键，避免假绿）

`/daily` 的沉淀行由结算管线写 `CURDATE()`，若判定日期取今天会被实时结算数据盖过。故：
- 判定日期默认取「3 天前的过去日期」（`date -d '3 days ago'`），该日快照行仅由脚本预置（先清后插）、结果确定。
- seed 用**固定** `SEED_TOP=88.05`（不随 `EXPECT_TOP` 变化），保证红绿可独立翻转。
- 登录号须 ADMIN（`/leaderboard/api/leaderboard/daily` 在网关 `app.auth.admin.paths`，非 ADMIN 被 403）。本 SESSION 临时注册并 `POST /internal/auth/grant-admin` 升 ADMIN 的账号 `13900009999 / smoke-daily-116`（user id=8）作为默认登录账号（脚本可经 `SMOKE_DAILY_PHONE/PASSWORD` 覆盖）。

## 红绿取证原文（三维退出码，实跑）

```
绿   : 断言通过: top-1 距离 88.05 == 期望 88.05 / == smoke-daily 通过 == / exit 0
红   : EXPECT_TOP=99.99 → 断言失败: top-1 距离 88.05 ≠ 期望 99.99 / exit 1
未就绪: BASE_URL=http://127.0.0.1:9999 → 未就绪: 无法连接（gateway/user-service 未起） / exit 3
```

## 门槛来源与本机口径

- 冒烟脚本为真机核验（非仅 `bash -n`）：红/绿/未就绪三维真实跑出，分别 exit 1/0/3。
- `mvn-verify.sh --mode=offline test` → BUILD SUCCESS，全 6 模块 `test` 通过（本轮无源码改动，纯新增冒烟脚本 + 文档，零扰动）。

## 是否到达外部门槛

**未达外部门槛**：修订未推送，结论仅来自本地；无 CI run 编号可绑。

## 未覆盖

无新未覆盖；TASK-110 C 项自此闭环（A/B 已于 TASK-110 经 `--it` 覆盖，C 于本任务交付可运行机器判据）。

## 台账与归档

- 更新 `work/mailbox/PLAN.md`：追加本任务验收记录，并在 TASK-110 验收记录旁注明「C 全栈 `/daily` 端到端于 TASK-116 闭环」。
- 本任务不自行归档；不建 `spec/changes/` 三件套（冒烟脚本不算 spec 需求变更，与 TASK-113 纯评估口径一致）。

## 未决

- SESSION 临时 ADMIN 账号（`13900009999/smoke-daily-116`）与隔离岛 seed 为测试数据；脚本每次 DELETE+INSERT 自洽，不依赖残留。
- 冒烟脚本依赖宿主「六中间件 + 六服务」全拉起，非 CI 常规链路；其放置于 `scripts/smoke/`（人工/集体冒烟），不并入 `mvn-verify.sh`（停止边界）。
# TASK-139：缩短提交路径 DB 足迹（只改数据库一类）

## 目标

在 TASK-138 归因结论（top_class = 数据库，T_tx 约 80ms，池 10 排队）上，**只缩短提交事务的 DB 足迹**，并用同一负载验收。

规范来源：`spec/changes/shorten-submit-db-footprint/`（proposal.md / tasks.json / spec-delta.md）。先读三件套再动手。本任务是一次完整作业：插桩拆分、先红后绿、改代码、复测、台账、提交。不要把 git mv、单次 commit 或单次 Maven 拆成多轮回传。

## 开工基线

- HEAD 应为 `f5696fb8b70547e49b0ac6e2a5489e8fde8e10be`（`docs(perf): 记录当前 HEAD 瓶颈归因`）。若不一致，回传实际 HEAD 并停手。
- 对照数字（不得覆盖这些文件）：`docs/perf/data/raw/head-c100-summary.json` → QPS 120.90 / P50 777.55ms / P95 1188.55ms。
- 旧报告 136.8 / 137 QPS、P95 1.6s、541ms、28~63ms 一律标「旧环境」，不得写成这次结果。

## 允许改动（仅此）

1. 默认关闭的分段计时：`record.submit.tx-timing-enabled=false`。true 时按段记录 select / insert 主表 / 轨迹写入 / updateStatus（若还在）/ commit，周期性打可解析 snapshot。不新增依赖、不新增业务 URL、不引入新 Maven 插件。
2. `SportRecordService.submit`：INSERT 直接 `VERIFYING` + `version=0`，删除同一事务内 `updateStatus(SUBMITTED -> VERIFYING)`。响应仍为 VERIFYING；afterCommit 仍发 SUBMITTED；轨迹失败仍不发事件；幂等不改。
3. 主 `record-service/src/main/resources/sharding.yaml`：`sql-show: ${SS_SQL_SHOW:false}`。
4. 对应单测与主 yaml 默认值测试。
5. `docs/perf/data/attr-submit-tx-split.json`、`docs/perf/复测-submit-tx.md`。
6. 三件套勾选、`work/mailbox/tasks/TASK-139/{spec,handoff}.md`、`work/mailbox/PLAN.md`。

## 禁止改动（违反即停手回传）

- 不改 `MYSQL_POOL_SIZE` 默认 10，不注入 30，不叠加 `docker-compose.perf.yml`。
- 不改 JVM / `-Xms` / `-Xmx` / `MaxGCPauseMillis`。
- 不加任何索引，不碰 `spec/changes/add-verify-degrade-status-index/`。
- 不启用 innodb 刷盘 overlay，不改 `innodb_flush_log_at_trx_commit`。
- 不开跨请求提交聚合。
- 不改校验消费线程数、outbox relay 批大小、好友榜、点赞、治理凭证、网关路由。
- 不碰 `.trae/`、`.codex/`。
- 不碰工作树里未跟踪的 `spec/changes/archive/wire-verify-outbox`、`spec/changes/archive/adopt-native-mq-retry`（若 index 里又被 staged，先 `git restore --staged` 那些路径）。
- 不 `git add -A`。不 push、不建 PR。禁用 git stash。
- 命令行禁止中文；提交用 `git commit -F` UTF-8 文件。
- 文本不得含 CI 禁用词；台账不复制禁用词正则原文。

## 负载与环境

- Shell：`D:\git\Git\bin\bash.exe`。禁止 `C:\Windows\System32\bash.exe`。
- MySQL 演示容器端口：**3307**。容器名 `sport-verify-mysql`。
- 起栈：`bash scripts/perf/run-perf.sh start-services`。
- 拆分跑（计时开、行为未改）：`bash scripts/perf/run-perf.sh load 100 2000 split`
- 验收跑（计时关、行为已改）：`bash scripts/perf/run-perf.sh load 100 2000 dbfoot`
- 禁止再跑 label=`head`（会覆盖 TASK-138 原始文件）。不跑 500/1000。
- 起栈或压测失败：完整尝试一次，记录命令与退出码，该项标未覆盖。禁止编造 QPS/P95。
- quality 不是本任务门槛（TASK-138 已因 mapmatch 缺失记未覆盖）。

## 判别式

1. **行为红**：改 `submit` / 主 yaml 之前，新测试必须失败。`mvn-verify.sh --mode=offline --pl record-service test` 退出码 1，失败用例必须是：
   - submit 仍调用 `updateStatus`，或 insert 的 status 不是 VERIFYING；
   - 以及主 yaml 仍是 `sql-show: true`。
   禁止用「删类导致编译失败」冒充行为红。红测后把临时测试夹具之外的生产代码保持到你真正开始阶段 3 为止。
2. **行为绿**：同一 Maven 入口退出码 0；submit 路径 `updateStatus` 调用 0 次；insert status=VERIFYING；回调 SUBMITTED 到 VERIFYING 仍通过；record-service 用例只增不减。
3. **复测**：`dbfoot` 的 QPS/P50/P95/错误率必须是这次命令的输出。与 120.90 / 777.55 对比。没有改善就停止叠加，不得接着改池。
4. 只改清单与 `git diff --name-only` + untracked 逐字一致。handoff「实际改动清单」用该固定标题，清单后空一行再写说明。
5. 收口提交后 `bash scripts/verify/mailbox-contract.sh` 无参数须 rc=0。若中文文件名触发工具提取盲区，git 层逐字比对仍须 一致，并在 handoff 写明（TASK-138 先例）。

Maven 验收唯一入口 `bash scripts/verify/mvn-verify.sh`，禁止手拼 mvn。

## 提交

一次作业内完成本地提交。建议：

- 业务+测试+复测文档：`perf(record): 缩短提交事务 DB 足迹`
- 若台账必须绑定业务哈希，可再一笔 `docs(mailbox): TASK-139 提交绑定与验收记录`

只 stage 清单内路径。未 push。

## 回传必须包含

- 实际 HEAD 与提交哈希
- split JSON 各段 P50（若压测未覆盖则写未覆盖原因与退出码）
- dbfoot 的 QPS/P50/P95/错误率（或未覆盖）
- 红绿 Maven 退出码与 record-service 用例数
- 契约退出码
- 只改清单
- 是否改善；若未改善，剩余假设一句话
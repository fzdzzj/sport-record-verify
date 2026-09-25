# TASK-138：当前 HEAD 同一负载瓶颈归因（只度量，不改业务代码）

## 目标

在开工基线 HEAD 上产出一张可复现的归因表，回答：提交路径和校验路径的时间主要耗在哪一类。本任务**不实施**任何优化。

规范来源：`spec/changes/measure-head-bottleneck-attribution/`（proposal.md / tasks.json / spec-delta.md）。先读三件套再动手。

## 负载与环境（必须同一套，禁止改档）

- Shell：`D:\git\Git\bin\bash.exe`。禁止用 `C:\Windows\System32\bash.exe`。
- MySQL 演示容器端口：**3307**（不是 3306）。容器名 `sport-verify-mysql`。
- 提交负载唯一命令：
  `bash scripts/perf/run-perf.sh load 100 2000 head`
- 校验验收集（仅当 verify + record + MQ 可用；mapmatch 缺失则本项未覆盖）：
  `bash scripts/perf/run-perf.sh quality head`
- 起栈只用现有 `bash scripts/perf/run-perf.sh start-services`。禁止改 JVM、禁止 `-Xms/-Xmx`、禁止 `MaxGCPauseMillis`、禁止叠加 `docker-compose.perf.yml`。
- 不跑 500/1000 并发。不启用 innodb 刷盘 overlay。
- 起栈或压测失败：完整尝试一次，记录命令与退出码，该项标未覆盖。禁止编造 QPS/P95。

## 必须交付的归因内容

1. 环境快照：`git rev-parse HEAD`、JDK、MYSQL_PORT、容器版本、服务是否起来。
2. 静态调用表（即使压测未覆盖也必须有），按代码路径点数，写清文件与方法：
   - 提交：`SportRecordService.submit`（含轨迹批量插入、afterCommit MQ）。
   - 校验：`VerifyEventConsumer` -> `VerifyService`/`VerifyEngine`/`R5OffRoadRule` -> `MapMatchService.fetchCandidates` -> outbox/relay。
   - 列：SQL 次数、Redis 次数、Feign/HTTP 次数、MQ 发送、事务范围。
3. 运行时（能跑才写数字）：c100 的 QPS/P50/P95/P99/错误率；quality 的拦截率/通过率/校验 P95；若 `logs/gc-record.log` 存在可摘 GC 次数，不存在则未覆盖。
4. 六类中**只选一类**作为当前占比最高因素：业务规则 / 数据库 / 远程调用 / 锁 / CPU / GC。
5. 明确下一步假设（一句话），并写「本任务不实施」。
6. 旧报告（`docs/perf/压测报告.md` 的 36->137 QPS、P95 1.6s、校验 28~63ms）只能标「旧环境」，不得写成当前 HEAD 实测。

产出文件：

- `docs/perf/归因-HEAD.md`
- `docs/perf/data/attr-head-summary.json`（小型 JSON：head、runtime_covered、load、quality、calls、top_class）
- 三件套已在工作树，收口时一并提交
- `work/mailbox/tasks/TASK-138/handoff.md`
- `work/mailbox/PLAN.md` 追加验收记录

`docs/perf/data/raw/` 与 `logs/` 不入库。

## 停止边界（违反即停手回传）

- 不改任何业务 Java、SQL、服务 yml/properties、Mapper、过滤器。
- 不改 `scripts/perf/run-perf.sh`、不改 JVM、不加索引、不改连接池、不加依赖、不加 Maven 插件。
- 不实施被选中的优化，不把「优化器使用了某索引」写成已变快。
- 不碰 `spec/changes/add-verify-degrade-status-index/`。
- 不碰已暂存的 `spec/changes/archive/wire-verify-outbox`、`spec/changes/archive/adopt-native-mq-retry`。
- 不碰 `.trae/`、`.codex/`。
- 不 `git add -A`。不 push、不建 PR。禁用 git stash。
- 命令行禁止中文；提交用 `git commit -F` UTF-8 文件。
- 文本不得含 CI 禁用词；台账不复制禁用词正则原文。

## 判别式（本任务无业务红绿，用交付物判别）

必须同时成立：

1. `git diff --name-only` 与 untracked 中，**没有** `*.java`、`sql/**`、各服务 `application.yml`/`application.properties` 的业务改动。
2. `docs/perf/归因-HEAD.md` 含环境快照、静态调用表、运行时或未覆盖声明、且 `top_class` 只有一个。
3. 文中若出现 136.8、137 QPS、1.6s、541ms，必须写明来自旧报告。
4. `tasks.json` 六个任务的 `completed`/`passes` 按实跑填写。
5. 只改清单与实际改动集逐字一致。

Maven 不是本任务门槛。不要为了凑绿去跑全仓测试，也不要因为没跑 Maven 而假装压测通过。

## 提交

单一本地提交，建议标题：`docs(perf): 记录当前 HEAD 瓶颈归因`

只 stage 清单内文件。若 index 里已有 archive rename，先 `git restore --staged` 那些归档路径，再只 add 本任务文件。

handoff 的「实际改动清单」用该固定标题，清单后空一行再写说明。
# TASK-138 回传：当前 HEAD 瓶颈归因（只度量，不改业务代码）

## 回传概要

- **结论**：top_class 六类中只选一类 = **数据库**。提交路径的同步阻塞 100% 在 DB 段（事务内 4 条 SQL + 提交刷盘 + 池 10 排队），成功分支无同步远程调用；GC 占墙钟 ≈0.5%、DB 行锁 0ms、业务规则纯内存，均被排除为第一因素。校验路径 15s 级延迟为消费吞吐排队（≈60 条/s < 到达 121 条/s），其每条消息同步链中每跳仍以 SQL 为底座，列为第二因素。
- **HEAD**：`58cd1041bac14c24cc801c2d3e383b606d8cc91b`（开工基线，main）。
- **runtime_covered**：true（提交负载全量实跑；校验 quality 未覆盖，见下）。
- **load**（`bash scripts/perf/run-perf.sh load 100 2000 head`，一次成功）：QPS **120.90** / P50 **777.55ms** / P95 **1188.55ms** / P99 **1428.57ms** / MAX 1987.60ms；2000/2000 成功、0 限流、0 错误，wall 16.542s。
- **quality**：**未覆盖**——start-services 栈不含 mapmatch 服务、PostGIS 未启动，R5 将全程熔断降级，拦截率/通过率失真；按任务书「mapmatch 缺失则本项未覆盖」处理，未执行、未编造。替代运行时证据（同一 run 派生）：2010 条（含 10 预热）全部到达终态、0 滞留；「提交→判定落库」P50 15.0s / P95 17.0s / MAX 18.0s（n=2010，排队形态）；outbox 快照 PENDING 1012 / SENT 998（relay 上限 20 行/s，只影响榜单侧事件到达）。
- **提交**：单一本地提交（哈希由回传消息与计划台账承载），未 push、未建 PR。
- **下一步假设（一句话，本任务不实施）**：先对提交事务插桩量出 4 条 SQL 各自耗时、ShardingSphere 多值 INSERT 解析份额与提交刷盘份额，再只在数据库一类内缩短单请求 DB 足迹（事务长度/连接占用）。

## 环境与运行时要点（全量见归因报告）

- JDK 21.0.9（`D:/develop1/jdk21`，四服务宿主进程）；MYSQL_PORT=3307；MySQL 8.0.46 / Nacos v2.3.2 / Redis 7.2-alpine / RocketMQ 5.2.0（brokerIP1=127.0.0.1）。
- 构建：仓库口径（`.mvn-settings.xml`）离线 package → BUILD SUCCESS，跳过测试；仅为起栈产物，Maven 非本任务门槛，未跑全仓测试、未把没跑的写成通过。
- 环境对齐（非代码改动）：演示库 verify_db 缺 `verify_event_outbox`（代码已落地、演示库 schema 未同步），按仓库基础建库脚本内同名表的既有 DDL 在演示库补建；仓库内无任何文件变更。
- record 侧连接池默认 10（`sharding.yaml` 的 `${MYSQL_POOL_SIZE:10}`，本次未注入 30 档）；GC（logs/gc-record.log）全程 221 次暂停合计 960.5ms、单次 1.7~2.3ms；MySQL `Innodb_row_lock_time`=0；R5 熔断降级 2010 次（仅 9 次真实 connect 超时）。
- 中间件与四服务均一次拉起成功（start-services 退出码 0），负载一次成功（LOAD_RC=0），无失败重试。

## 交付物判别式自证

1. **业务零改动**：本任务工作树足迹仅限下方清单节所列 8 项；无业务 Java、无 SQL 目录文件、无各服务配置文件改动；未调 JVM、未加索引、未改连接池、未改压测驱动脚本。原始压测数据（raw 目录）与运行日志均在 gitignore 内，不入库。
2. **归因报告要件齐备**：环境快照 / 静态调用表（提交：4 SQL · 0 Redis · 0 HTTP · 1 MQ 异步，单事务；校验：verify 侧 ≤5 SQL + record 侧 5 SQL · 1~2 Redis · 3 HTTP + R5 降级 · 事件经 relay，无本地长事务）/ 运行时数字与未覆盖声明俱全；top_class 全文只选中一个类。
3. **旧数字标注**：文中 136.8（即 137 QPS 口径）/ 1.6s / 541ms / 28~63ms 一律显式标注「旧环境」（2026-09-12 报告），并注明旧环境含池 30 + 组合索引 + 1g 堆三项未在本次快照启用的优化、不可与本次直接比较。
4. **任务勾选按实跑**：任务 4 第 1 步（quality 实跑）保持未勾（mapmatch 缺失），第 2 步与各任务 passes 如实勾选（任务书允许「否则静态盘点即可」分支）。
5. **清单一致（git 层逐字）**：`git diff --name-only` + untracked 与下方清单节 8=8 逐字一致；工具提取层对中文文件名有已知盲区，见契约核验节。

## 契约核验（实测，均为 D:\git\Git\bin\bash.exe 现跑）

- **提交前无参数口径**（脏树、基线默认 HEAD）：整体 rc=1——成因与本仓历史任务一致：本机在途/残留（`.codex/`、`add-verify-degrade-status-index/`、归档移名 unstage 后处于未跟踪态的 archive 文件）进入 ACTUAL，且共享文件 PLAN.md 触发与全部历史 handoff 的交叠强校验；TASK-138 段按在途口径评审，见下行。
- **提交前 `--diff-file` 口径**（8 项逐行）：TASK-138 段 7 项逐字一致；**1 项工具提取盲区**——归因报告正文文件名含非 ASCII 字节，契约提取正则只收 ASCII 路径字符，该行被提取为词元「-HEAD.md」，与真实路径对不上（同类先例：TASK-018 的点开头配置文件提取盲区，TASK-114 以扩白名单收口；本任务无契约脚本改动权，不代为修工具）。真实改动集一致性以 git 层逐字比对为准（判别式 5 已单独证明）。
- **收口提交后无参数口径**（硬门槛）：**rc=0**，TASK-138 足迹不在工作树、视为已收口（实测输出见回传消息）。

## 实际改动清单

docs/perf/归因-HEAD.md
docs/perf/data/attr-head-summary.json
spec/changes/measure-head-bottleneck-attribution/proposal.md
spec/changes/measure-head-bottleneck-attribution/tasks.json
spec/changes/measure-head-bottleneck-attribution/specs/sport-record-verify/spec-delta.md
work/mailbox/tasks/TASK-138/spec.md
work/mailbox/tasks/TASK-138/handoff.md
work/mailbox/PLAN.md

前两项为本任务交付物（归因报告正文与机器可读摘要）；第三至五项为规范来源三件套（任务勾选回填是其中唯一实质修改）；第六、七项为本任务台账两件套（规格侧开工即下发、回传侧本次新增）；第八项为计划台账追加本任务验收记录。清单之外无任何文件进入提交。

## 未覆盖与边界

- quality 验收集未覆盖（原因见概要）；500/1000 档按任务书禁止未跑；MQ 故障注入/熔断转人工演练非本任务范围。
- CPU 份额未单独观测（无插桩手段，任务禁止加观测代码）：归因报告将其列为下一步假设的量测点而非结论，top_class 选择不依赖该项。
- 归因报告中的「校验链路 P50 15s」为同一 load run 的派生实测（判定落库时戳差），不是 quality 口径的端到端 P95，报告中已注明口径差异。
- 本机命令行禁中文：含中文文件名的交付物一律经文件工具写入、提交经 UTF-8 信息文件承载。

## 提交

- 单一提交：`docs(perf): 记录当前 HEAD 瓶颈归因`，经 `git commit -F` UTF-8 文件；只 add 清单内路径（含中文文件名的目录以整目录加入并经 `git status` 核对，该目录下未跟踪且未忽略的恰为本任务两份交付物）。
- 未 push、未建 PR；已按任务书将索引中既有的归档移名记录退回暂存区之外（索引回 HEAD、工作树文件原位不动），该批文件不进入本提交；未用 `git stash`。

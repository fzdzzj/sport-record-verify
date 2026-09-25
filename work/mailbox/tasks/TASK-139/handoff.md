# TASK-139 回传：缩短提交路径 DB 足迹（只改数据库一类）

## 回传概要

- **开工 HEAD**：`f5696fb8b70547e49b0ac6e2a5489e8fde8e10be`（与任务书基线逐字一致；head-c100 原始文件未覆盖，对照 120.90 / 777.55ms 只读引用）。
- **提交**：两笔本地提交——`bd34f81e9add7dea81436dbfb9f8fcefc43b8470`（`perf(record): 缩短提交事务 DB 足迹`，业务+测试+复测文档+规范三件套，11 文件）；台账收口提交（`docs(mailbox): TASK-139 提交绑定与验收记录`，台账两件套+PLAN，3 文件，即本文件所在提交；哈希由任务回传承载，台账无法承载自身提交哈希）。未 push、未建 PR、未用 git stash。
- **split**（计时开、行为未改，`bash scripts/perf/run-perf.sh load 100 2000 split`，一次成功）：QPS 119.94 / P50 752.68ms（与基线同噪声带）。各段 P50：**select 681.6ms**（其中约 92% 为池 10 的排队等待）/ **insertMain 2.9ms** / **trackWrite 22.6ms** / **updateStatus 2.1ms** / **commit 28.9ms**（fsync 地板）→ `docs/perf/data/attr-submit-tx-split.json`。
- **dbfoot**（计时关、行为已改，`bash scripts/perf/run-perf.sh load 100 2000 dbfoot`，一次成功）：**QPS 123.23 / P50 711.45ms / P95 1332.35ms / 错误率 0.00%**（2000/2000、0 限流、0 错误）。
- **红绿 Maven**：唯一入口 `bash scripts/verify/mvn-verify.sh --mode=offline --pl record-service test`——红 **rc=1**（Tests run 86，Failures 3，均为新判别式；既有 83 条全绿）；绿 **rc=0**（86/0/0/0，+3 只增不减）。
- **契约**：收口提交后无参数 `bash scripts/verify/mailbox-contract.sh` **rc=0**（实测输出见契约节）。
- **是否改善**：**是**——P50 −8.5%、QPS +1.9%，与插桩预测（删 2.1ms/56.5ms 的事务内工作 + 关 sql-show 削打印份额）方向一致；P95 +12% 在同环境噪声带内（行为未改的 split 跑 P95 即 1410ms）。剩余假设一句话：剩余 P50 ≈711ms 仍由池 10 的排队倍数支配，事务内地板是 commit 刷盘（P50 28.9ms）与轨迹多值 INSERT（P50 22.6ms），再降需另立变更。

## 实际改动清单

record-service/src/main/java/com/sportverify/record/service/SportRecordService.java
record-service/src/main/java/com/sportverify/record/service/SubmitTxTiming.java
record-service/src/main/resources/application.properties
record-service/src/main/resources/sharding.yaml
record-service/src/test/java/com/sportverify/record/service/SportRecordServiceTest.java
record-service/src/test/java/com/sportverify/record/config/MainShardingYamlDefaultsTest.java
docs/perf/data/attr-submit-tx-split.json
docs/perf/复测-submit-tx.md
spec/changes/shorten-submit-db-footprint/proposal.md
spec/changes/shorten-submit-db-footprint/tasks.json
spec/changes/shorten-submit-db-footprint/specs/sport-record-verify/spec-delta.md
work/mailbox/tasks/TASK-139/spec.md
work/mailbox/tasks/TASK-139/handoff.md
work/mailbox/PLAN.md

## 清单说明

前两项为提交服务与插桩辅助（SubmitTxTiming 为纯新增类，无第三方依赖、无业务 URL）；第三、四项为配置（计时开关默认 false、sql-show 默认关）；第五、六项为测试（1 条服务判别式 + 2 条主 yaml 默认值判别式，均为新增，无删除）；第七、八项为本任务交付物（分段计时摘要与复测报告）；第九至十一项为规范来源三件套（tasks.json 勾选回填是其中唯一实质修改）；第十二、十三项为本任务台账两件套；第十四项为计划台账追加本任务验收记录。清单之外无任何文件进入提交；归档移名（wire-verify-outbox / adopt-native-mq-retry 的删除与未跟踪态）、`.codex/`、`.trae/`、`spec/changes/add-verify-degrade-status-index/` 均未触碰、未提交。

git 层逐字比对：`git diff --name-only --diff-filter=ACMR f5696fb8b70547e49b0ac6e2a5489e8fde8e10be` 与上列 14 项排序后逐字一致（14=14，留档比对输出见契约节）。契约工具提取层对第八项（复测报告文件名含非 ASCII 字节）存在已知盲区——提取正则只收 ASCII 路径字符，该行被提取为词元 `-submit-tx.md` 与真实路径对不上（TASK-138 的归因报告同名文件先例）；真实改动集一致性以 git 层比对为准。

## 判别式与实跑记录

1. **插桩（阶段 1，行为不变）**：`record.submit.tx-timing-enabled` 默认 false（application.properties 显式声明 + @Value 兜底）；true 时 SubmitTxTiming 按段收集、每 100 次提交在日志打一行 `SUBMIT_TX_TIMING {JSON}`（各段 n/P50/P95/max 微秒）。关闭态共享 no-op 收集器，零分配零写入；样本仅提交成功收口（afterCommit）并入，回滚丢弃。不新增依赖、不新增 Maven 插件、不新增业务 URL。
2. **split**（计时开 = 命令行参数 `--record.submit.tx-timing-enabled=true` 重启 record，行为未改、jar 为插桩版）：snapshot 至 samples=2000（2010 次提交含 10 预热），分段数据见概要。直接证实 TASK-138 的推断：select 段 P50 681.6ms 中的池等待约占观测 P50 的 9 成，事务内真实工作 P50 ≈56.5ms；updateStatus 仅占事务内工作 ≈4%，commit（28.9ms）与 trackWrite（22.6ms）才是事务内地板。
3. **行为红（阶段 2，先红）**：新增 3 条判别式（`submit_insertsVerifyingDirectly_andNeverCallsUpdateStatus`、`sqlShow_defaultsToFalseViaPlaceholder`、`sqlShow_literalTrueForbidden`），生产代码保持基线+插桩态。实跑过程如实登记：第一次 rc=1 为 clean 删不掉运行中 record 进程锁住的 jar（环境红，作废未计入，停进程后重跑）；第二次 rc=1 落在 BizException「提交后状态迁移失败」（弱红——未打桩的 updateStatus 返回 0 行中止了流程，按受控红绿惯例判为偏靶），给判别式补 updateStatus 打桩让旧实现走完全程后重跑；**第三次 rc=1 为目标行为红**：`submit_insertsVerifyingDirectly_andNeverCallsUpdateStatus:169 expected: <0> but was: <1>`（INSERT 实参 status=SUBMITTED≠VERIFYING）+ 主 yaml 两条 `sql-show: true` 判别（`MainShardingYamlDefaultsTest:36/:45`），Tests run 86 / Failures 3 / Errors 0，既有 83 条全绿。无编译红冒充。
4. **行为改动（阶段 3）**：submit 直接 INSERT `status=VERIFYING, version=0`，删除同一事务内 SUBMITTED→VERIFYING 的 updateStatus 与内存回填；响应仍 VERIFYING；afterCommit 仍发 SUBMITTED；轨迹失败仍不发事件（判别式覆盖）；幂等与 DuplicateKeyException 分支未动；`updateStatus` 方法本体与回调（statusCallback）/申诉/补偿路径的乐观锁迁移全部保留。
5. **转绿（阶段 4）**：同一 Maven 入口 rc=0、BUILD SUCCESS，record-service **86/0/0/0**（基线 83 + 3 新增）；既有 4 条 submit 路径测试去掉 updateStatus stub/verify 并补 INSERT 参数捕获断言；`statusCallback_fromSubmittedToVerifying` 等回调测试原样保留通过。随后 `--pl record-service package` 复跑同数 86/0/0/0（起栈产物）。
6. **dbfoot（阶段 5）**：stop-services → 新 jar start-services（四服务健康 200；tx-timing 未注入、MYSQL_POOL_SIZE 未注入、未叠加 compose overlay、未改 JVM），跑 load 100 2000 dbfoot 一次成功，数字见概要；`logs/record.log` 中 SUBMIT_TX_TIMING 行数为 0，确认插桩未污染对比。提交事务 SQL 4 条 → 3 条。
7. **词面自检**：CI 同款 `git grep -n -I -iE -f <词表文件> --untracked`（LC_ALL=C，模式经 UTF-8 文件承载、命令行无中文；排除 archive/docs 内部目录/CI 工作流三个 pathspec）对本任务全部交付物所在路径扫描 **0 命中**；台账不引用禁用词正则原文。
8. **旧环境标注**：复测报告中 136.8（137 QPS 口径）/ P95 1.60s / 541ms / 28~63ms 一律标注「旧环境」（2026-09-12 报告，含池 30 + 组合索引 + 1g 堆三项本快照未启用的优化），未与本次结果比较。

## 环境与操作注记（如实登记，均无仓库改动）

- 四服务为宿主机进程；中间件容器（mysql:3307 / nacos / redis / rocketmq）随既有栈在位。
- split 前手动重启 record 时首次漏带 `MYSQL_PORT=3307`，服务连到宿主 3306 的另一实例启动失败（Access denied）——环境错误，带正确环境变量重启即恢复，无任何仓库文件改动。
- `mvn-verify.sh` 离线依赖来源可判定（localRepository 在位），未触发退出码 3；两次全绿实跑（test / package）均在插桩/最终工作树上完成。

## 契约核验（实测，D:\git\Git\bin\bash.exe 现跑）

- **收口提交后无参数口径（硬门槛）**：`bash scripts/verify/mailbox-contract.sh` → **rc=0**，输出含判据 A 两件套齐全与判据 B 清单一致（TASK-139 足迹不在工作树、视为已收口）。
- **git 层逐字比对**：`git diff --name-only --diff-filter=ACMR f5696fb` 输出与「实际改动清单」14 项排序后 `diff` 为空（14=14）。
- 工具提取层盲区：清单第八项文件名含非 ASCII 字节，契约提取正则只收 ASCII 路径字符（TASK-138 先例、TASK-114 曾以扩白名单收口）；本任务无契约脚本改动权，不代为修工具。

## 未覆盖与边界

- quality 验收集非本任务门槛，未跑（TASK-138 已因 mapmatch 缺席记未覆盖）；500/1000 档按任务书禁止未跑；MQ 故障注入非本任务范围。
- updateStatus 分段仅存在于行为改动前的 split 数据中；dbfoot（改动后）提交路径该段为 0 次，无对应数据（属预期，不是缺失）。
- `SS_SQL_SHOW=true` 的显式打开分支未建单测：`loadShardingYaml` 走 `System.getenv`，本机已知 Mockito 无法 mock `java.lang.System`（TASK-111 同款限制）；默认关闭由两条主 yaml 判别式守住，占位符替换逻辑另有 `ShardingDataSourceConfigTest` 既有覆盖。
- P95 单项变差按噪声带解读（split 同行为跑 P95 1410ms 为佐证），未作反向结论；若需更严格的尾部延迟结论需多轮重复取分布，超出本任务口径。

## 提交

- `bd34f81e9add7dea81436dbfb9f8fcefc43b8470` `perf(record): 缩短提交事务 DB 足迹`（11 文件：服务 + 插桩 + 配置 + 测试 ×2 + 交付物 ×2 + 三件套 ×3 + 中文文件名交付物经 `git add docs/perf` 整目录加入并以 `git status` 核对）。
- 台账收口提交 `docs(mailbox): TASK-139 提交绑定与验收记录`（3 文件，`git commit -F` UTF-8 文件；哈希由任务回传承载——台账无法承载自身提交哈希）。
- 只 stage 清单内路径（未用 `git add -A`）；不 push、不建 PR；未用 git stash；开工前既有脏项（归档移名 / .codex / .trae / add-verify-degrade-status-index）保持原状。

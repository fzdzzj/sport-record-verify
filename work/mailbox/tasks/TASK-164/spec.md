# TASK-164 spec：批内并发 × 连接池 对 outbox relay 排空斜率的判别（条件式落地）

## 1. 唯一问题
在 TASK-163 已落地的 verify.outbox.relay-interval-ms=500 之上，把批内并发 relay-send-concurrency 由 1 提到 4、并把 verify-service 的 Hikari maximum-pool-size 由默认 10 显式提到 20，负载停止后的排空斜率 slope（行/s）相对落地态基线提升是否 ≥1.5？三支裁决：落地支 / 未定支 / 反证支（定义见 §7）。只判别这一项组合，不叠加 batch-size、max-retry、interval、SQL、索引、MQ、JVM 任何其它参数。

## 2. 三臂定义（唯一差异＝注入参数；三臂都必须带同一诊断开关，避免它成为混淆因子）
- A（落地态基线）：--verify.outbox.relay-diagnostics-enabled=true （仅此一个额外参数；N=1，池默认 10）
- B（只加池，分离池效应）：--verify.outbox.relay-diagnostics-enabled=true --spring.datasource.hikari.maximum-pool-size=20
- C（池＋并发）：--verify.outbox.relay-diagnostics-enabled=true --spring.datasource.hikari.maximum-pool-size=20 --verify.outbox.relay-send-concurrency=4
每臂切换后必须重启 verify-service（同一 jar，A/B/C 全程不得换 jar），启动前 export MYSQL_PORT=3307，显式指定 JAVA_BIN，unset 代理并设 no_proxy="*"。

## 3. 轮次编排与预算
- 交错次序：A1 → B1 → C1 → A2 → B2 → C2（6 个计数轮）。
- 预热轮 ≤4，全部丢弃但留档：起栈首轮 W0（A，100x2000）＋每次切臂首轮（100x200）。
- 计数轮负载统一 100x2000（run-perf.sh load 100 2000 task164-<label>），要求 ok=2000 / errors=0 / limited429=0。
- 替换轮 ≤2，且仅当某轮 M4 或 M6 红时可用同臂同参数替换；失败轮照占预算，不许为凑结论加跑。预算耗尽 ⇒ 未定支。

## 4. 指标与估计量（沿用 TASK-163 已验证口径，不得更换）
- 负载停止后每 ~2s 采一次可投递 PENDING（retry_count < max-retry）写 docs/perf/data/raw/task164-<label>-drain.csv，采到 0 为止。
- P_peak = drain.csv 中 PENDING 最大值（要求 ≥100）；t_peak = 首次达到 P_peak 的时间戳；t_zero = 首次达到 0 的时间戳；slope = P_peak / ((t_zero - t_peak)/1000) 行/s。
- 线性度：t_mid = 最接近 (t_peak+t_zero)/2 的采样点，slope_half = (P_peak - P_at_mid)/((t_mid - t_peak)/1000)；|slope_half - slope|/slope > 30% 必须披露。
- 每轮同时归档：批次诊断行（selectMs/sendMs/markMs/incrRetryMs/lockProcessingMs/lockHoldMs/residualMs/emptyRounds/lockSkips/sendConcurrency）、Com_select 与 Com_update 前后值、hikaricp_connections_timeout_total / _pending / _active、宿主共变量（非 sports 的 java.exe 进程数 + CPU 忙闲三采样）、docker ps、四服务 health。
- 提交 QPS 漂移不作废轮理由（TASK-163 已证斜率与 QPS 解耦：QPS +48.56% 而斜率仅 +5.68%），但必须逐轮披露 QPS 与共变量。

## 5. 门（预注册，不得放宽；任一不符按 §7 归支）
- M0 环境门（起栈前一次）：Docker Desktop 需先启动（当前 daemon 是关的），5 个演示容器 healthy；SHOW VARIABLES LIKE 'max_connections' 与四服务起栈后的 SHOW GLOBAL STATUS LIKE 'Threads_connected' 留档；要求余量 max_connections - Threads_connected ≥ 30，不足即停手回报，不得开跑。
- M1 机制门（逐轮）：A/B 臂 verify.log 不得出现「已创建批内并发发送线程池」，且 >1s 空档数 ≤5、中位 ≤2000ms（即确为 500ms 档形态）；C 臂必须出现恰一条 sendConcurrency=4 的建池日志，且批次诊断行 sendConcurrency=4（A/B 臂为 1）。C 臂 residualMs 可为负（并发下为线程时间聚合），照 javadoc 口径披露，不得读作未归因墙钟。
- M2 效应门：slope(C1)/max(slope(A1),slope(B1)) ≥ 1.5 且 slope(C2)/max(slope(A2),slope(B2)) ≥ 1.5。结构预测区间 [1.7, 2.5]（推导：cycle=0.5+T_batch/N_eff；TASK-163 反解 T_batch=1.2517~1.7189s；取 TASK-161 的 S_prod(4)@J=0=3.1538 作并行效率代理 ⇒ slope 95.7~111.5；基线 45.07~57.09）。该区间不是门，落在门外但 ≥1.5 仍判过，须披露；该推导依赖 TASK-161 明写「未被回答」的可迁移性假设，不得当成已证事实。
- M3 排序控制门：|slope(A2)-slope(A1)|/mean(slope(A1),slope(A2)) ≤ 20%（硬门，A 是对照臂）。B、C 两臂的同式偏差照实记录；若 C 臂偏差 > 30% ⇒ 重复性不足，不得判落地支，归未定支。
- M4 健康与语义门（逐轮）：ok=2000/errors=0/limited429=0；该轮 outbox 行数 = markSent 行数 = SENT 增量，排空末 PENDING=0；retry_count>0 行数=0；耗尽行增量=0；uk_event_id 零重复；零 RECONSUME_LATER；relay 失败/耗尽日志零行；零锁异常行。
- M5 代价门：Com_select 的 C/A 增量比 ≤1.5（预期 ≈1.0，tick 数未变）；Com_update 增量必须精确等于该轮 outbox 行数（不等即红）。
- M6 并发专属资源门（逐轮，硬门）：hikaricp_connections_timeout_total 增量 = 0（三臂全部）；_pending 与 _active 峰值照实记录（无阈值，必须披露）；消费侧不得饿死（以 M4 的 SENT=cohort 与 PENDING=0 为准）。

## 6. 条件式落地（仅落地支）与确认轮 D
- 落地动作＝application.yml 纯新增两个键（不得改任何既有行）：在既有 hikari: 块（现 L39-40，只有 initialization-fail-timeout: -1）下加 maximum-pool-size: 20；在既有 outbox: 块（现 L122-123）下加 relay-send-concurrency: 4。允许同时新增注释行。落地前必须先备份原文件字节，numstat 必须是 N/0，^spring: 与 ^verify: 根键各自仍恰好 1 个，总行数 196 → 196+N。
- 新增纯 JUnit 5 绑定测试类（放 verify-service 的 config 测试包，照 VerifyOutboxRelayIntervalDefaultTest 模板；零 @SpringBootTest、零中间件/网络依赖）：① 用 YamlPropertySourceLoader 断言两键值为 20 与 4；② 反射断言 VerifyOutboxRelay 的 @Value 字面仍为 ${verify.outbox.relay-send-concurrency:1}（生产代码默认值未改）；③ 断言 hikari 块仍有 initialization-fail-timeout: -1；④ 断言两个根键各恰好 1 个。verify-service offline 测试数 123 → 123+n 全绿。
- 确认轮 D：用 --mode=offline package 重建 jar（记录 jar sha256 变化），无任何注入启动，跑 100x2000；必须满足：建池日志 sendConcurrency=4、>1s 空档 ≤5 且中位 ≤2000ms、slope(D)/mean(slope(A1),slope(A2)) ≥ 1.5、M4/M5/M6 全过。
- D 不过 ⇒ 按备份字节回滚 application.yml（cmp rc=0）、删除新测试类、复跑 offline 证明测试数回到 123，台账写「落地失败已回滚」，裁决改判未定支。

## 7. 三支裁决
- 落地支：6 个计数轮全部有效（逐轮 M1/M4/M5/M6 过）＋ M2 两比值 ≥1.5 ＋ M3 控制门 ≤20% ＋ C 臂重复性 ≤30% ＋ 确认轮 D 全过。
- 反证支：M2 两比值均 ≤1.0，或出现可归因于并发的 M4/M6 红（连接超时 >0、消费饿死、重复 eventId、retry>0）⇒ 记录反证与最小反例，不落地，并写明「N>1 未获授权」。
- 未定支：其余一切情形（有效轮不足、预算耗尽、结果互斥、D 失败已回滚）⇒ 只报数字与噪声，不凑结论、不外推。

## 8. 开工读数（指导侧 2026-09-30 亲跑值；逐项一致后才许动文件，任一不符 ⇒ 停手回报原文，不要自行解释）
- HEAD = 10a08c3b74b1e77c3c1d83327e33c12b54410e84；origin/main = ccd64f03c533cb38c23c0b2b52dd2cd83ff2a45b；git rev-list --left-right --count origin/main...main = 0 5（若指导侧已推送则为 0 0，两种都接受，照实记录是哪一个）。
- 工作树脏项仅 ?? spec/changes/add-verify-degrade-status-index/（零触碰）＋本任务目录。
- bash scripts/verify/mvn-verify.sh --mode=offline → rc=0，七模块 36/41/33/103/123/59/10，Skipped 全 0。
- --mode=offline --static=verify-service → rc=1，You have 867 Checkstyle violations（门槛 ≤867；spotbugs/pmd 被阻断＝未覆盖）。
- verify-service/src/main/resources/application.yml：196 行、CR=0、^verify: 根键 1 个、^spring: 根键 1 个、L40 initialization-fail-timeout: -1、L122 outbox:、L123 relay-interval-ms: 500；全文件无 maximum-pool-size、无 relay-send-concurrency。
- VerifyOutboxRelay.java：493 行；L68 batch-size:100、L72 max-retry:16、L79 relay-diagnostics-enabled:false、L83-84 relay-diagnostics-window-ms:10000、L96 relay-send-concurrency:1、L126-127 @Scheduled 默认 5000/10000、L157 单次 selectPendingBatch（无排空循环）、L214 批次摘要日志、L414 建池日志。
- 主规格 spec/specs/sport-record-verify/spec.md：2806 行、121 个 ### Requirement:、零触碰（blob 存 LF、工作树检出 CRLF，两种读法都要写明测的是哪一面）。
- work/mailbox/PLAN.md：1250 行、CR=0（纯追加，既有行含 L4 零改动）。
- spec/changes：工作树 22 个在途目录 / archive 43。
- 词面门：正则从 .github/workflows/ci.yml 现场提取，len=26、8 分支；含 --untracked 的四形态（原样 / LC_ALL=C / zh_CN.UTF-8 / C.UTF-8）全 ZERO_HIT rc=1；正向对照 rc=0（探针必须植在非忽略路径，用完 git rm --cached + 删文件 + git status --porcelain 逐字还原）。
- 契约门：本任务 spec.md 落盘后无参 rc=1（预期），--open TASK-164 --baseline=10a08c3b74b1e77c3c1d83327e33c12b54410e84 rc=0；收口后无参必须 rc=0。
- 磁盘 Free ≈ 215.9 GB（门槛 ≥100 GB）；sports 的 java 进程 0 个（宿主机上属于 examOnline / crmAndRag 等其它项目的 java.exe 不许杀，只记录数量作共变量）。
- PLAN.md 16 个受保护数字 token 的新基线计数（本任务结束时不得减少）：13.4=7、18.0=9、73.93=8、68.8=4、6315=5、1.8612=4、3.3066=4、5.7056=4、9.408=4、36525962432=4、36586847965=3、36438897772=4、36399582548=3、36098038547=3、2806=7、598=3。

## 9. 只改清单（超出即红）
落地支：application.yml、新测试类、docs/perf/data/exp-outbox-relay-concurrency-pool-drain.json、docs/perf/判别-outbox-relay-并发与池-排空斜率.md、spec/changes/prove-verify-outbox-relay-concurrency-pool-drain/{proposal.md,specs/sport-record-verify/spec-delta.md,tasks.json}（纯 ADDED）、work/mailbox/PLAN.md（纯追加）、work/mailbox/tasks/TASK-164/{spec.md,handoff.md}。未定/反证支：去掉前两项，其余同。原始证据一律写 ignored 的 docs/perf/data/raw/task164-*。提交分批（业务证据 / 报告+JSON / 三件套 / 台账），逐路径 add，提交信息走 UTF-8 文件 -F，主题行 + 空行 + 正文要点。

## 10. 收尾硬条（缺一条即视为未收口）
run-perf.sh stop-services → 验证 sports 的 java 进程 = 0 → 复跑 --mode=offline（rc=0，模块数与落地状态自洽）→ 复跑静态门（≤867）→ 词面门四形态 + 对照 → 无参 mailbox-contract.sh rc=0 → git diff --check rc=0 且每笔 git show --check rc=0 → docker ps -a 留档 → PLAN 纯追加验收记录（含 16 token base vs HEAD 计数、外部门槛栏写「未达外部门槛（本次不 push，待下次授权由 CI 复验）」）。

## 11. 台账勘误义务（照实写，不许静默）
在 PLAN 追加节写一行勘误：work/mailbox/tasks/TASK-163/handoff.md §4.2 的 W_C 行（QPS 128.5 / wall 1.56 / P_peak 134 / 排空 6.053）与原始证据 task163-wC-slope.txt（P_peak=165 / drain 7.454）、task163-wC-c100-summary.json（qps 55.85 / wall 3.581）及已入库 exp-outbox-relay-drain-rate.json（55.9/3.58/165/7.454）矛盾，slope=22.1358 两处相同；W_C 为丢弃预热轮，不影响 TASK-163 裁决与落地。不得 amend 已收口的 10a08c3。

## 12. 禁止事项
不改任何 src/main 下的 .java、Mapper、SQL、索引、pom、scripts；不动 relay-interval-ms（已落地 500）、batch-size、max-retry；不 push、不建 PR、不 stash、不 git add -A / add .；不触碰 spec/changes/add-verify-degrade-status-index/ 与 task131-scratch-mysql 容器；不杀其它项目的 java 进程；不清理演示库任何行（运行证据）；不翻案 TASK-153（批末统一标记 SENT = NO-GO）与 TASK-154（等待成本不可归因 = NO-GO）；不改写 TASK-143/144/145/146/152/156/159/160/161/162/163 任何已入库数字；不把 slope 换算成 P50 或声称端到端延迟改善；不外推到更高到达率、更长窗、生产多实例；不声称全链路（leaderboard / mapmatch / postgis 未起）。

## 13. 未覆盖与不得推出（照抄进 handoff）
spotbugs/pmd 未覆盖（被 checkstyle 阻断）；--mode=online 与 CI 未跑 ⇒ 未达外部门槛；四服务局部栈 ⇒ 榜单消费与真实 R5 降级未覆盖，全程 R5 降级路径；池尺寸 20 是本机演示环境的判别值，不是生产容量规划；即使落地也不构成对 TASK-161 三支结论的翻案，S_prod(N) 与本轮 slope 不得并列成优化前后。

## 14. handoff.md 六项交回物
① 一句话裁决与三支归属（含全部 slope 与比值原文）；② 起点全 SHA、每笔提交 SHA 与逐路径清单、shortstat；③ 只改清单逐项对齐与零修改声明；④ M0-M6 逐门实测退出码与原始读数（含每轮共变量）；⑤ 假设与预注册反例逐条说明触碰情况；⑥ 未覆盖项与不得推出的结论。

## 15. 工具与陷阱（照做，别自创）
命令行全程无中文（含 grep/sed 模式），中文一律先写 UTF-8 无 BOM 文件再用；bash 一律写成 .sh 文件再用 D:\git\Git\bin\bash.exe 执行，禁 bash -lc 内联；需抓非零 rc 的段落不要放 set -e 下；Maven 唯一入口 bash scripts/verify/mvn-verify.sh，禁裸 mvn / MAVEN_OPTS / 仓库根 .mvn/maven.config；跑 offline 前确认 sports 的 java 进程为 0（jar 锁会导致 clean FAILURE）；从 mvn/Java 日志提数字先 tr -d '\r'（CRLF 会让 grep 3.1 的行尾锚点失配）；行数用 bash wc -l，不要用 PowerShell 的 Measure-Object -Line（它不数空行）；git show <sha>:<path> 取到的是 LF blob、工作树是 CRLF，报数要写明测的是哪一面；git grep --untracked 必须放在 pattern 之前且不搜 ignored 文件。

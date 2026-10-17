# TASK-265 执行交接（handoff）

> 状态：**已实施待复核**（2026-10-18 实施；C-01/C-02 双提交落库，七门禁实测全绿；待独立复核子 agent 审查与指导亲核）

## 1. 开工基线

- 开工基线：main @ `eeb84001ccb326b1d8339320ac0be0a44e7e4f66`（TASK-265 立项笔；派发书口径一致）
- C-01 业务提交：`b61806e`（`perf(benchmark): 增强LoadTest多链路压测引擎并落地全链路容量基准测试与报告（TASK-265）`；3 文件 +564/−14，其中新脚本 100755）
- 任务书：`work/mailbox/tasks/TASK-265/spec.md`

## 2. 改动集清单（C-01/C-02 落库后逐行回填，与 `git diff --name-only eeb8400` 双向全等）

- 改动清单（恰 4 项 = C-01 工具/脚本/报告 3 + C-02 台账 1，逐行路径 token 供判据 B 机器比对）：
  - scripts/perf/LoadTest.java
  - scripts/perf/benchmark-e2e.sh
  - docs/perf/benchmark-e2e-capacity-report.md
  - work/mailbox/tasks/TASK-265/handoff.md
- 零触碰面声明：除上述 4 文件外，全仓微服务生产 Java 业务代码、既有单测与契约测试、pom.xml、package.json、web 前端、docker-compose、.env、sql 等 100% 零触碰；未盲调 HikariCP 默认池容量与应用配置，全部压测为基线观测。

## 3. 门禁读数（实测回填）

| # | 门禁 | 命令 | rc | 关键读数 |
| --- | --- | --- | --- | --- |
| 1 | 后端 offline 全量七模块 | `mvn-verify.sh --mode=offline test`（经 D:\git\Git\usr\bin\bash.exe --login） | 0 | BUILD SUCCESS；surefire **63/78/221/321/203/110/36**（common/gateway/user/record/verify/leaderboard/mapmatch）严格持平基线，零失败零错误零跳过 |
| 2 | 压测编排实测 | `bash scripts/perf/benchmark-e2e.sh --label=task265`（四场景 × 100/300/500 × 2000/3000/5000） | 0 | rc=0 十二档全绿落盘 `docs/perf/data/raw/bench-e2e-task265/`（rollup.csv + 12×summary.json/raw.csv + cpu-mem.tsv）；补采 `--label=task265supp`（c300 档水位采样 4×sampler-*.tsv）；报告 `docs/perf/benchmark-e2e-capacity-report.md` 内嵌全部读数 |
| 3 | 契约门 | `mailbox-contract.sh --open=TASK-265 --baseline=eeb8400` | 0 | C-01 后预跑 rc=0（进行中声明放行）；C-02 后终态复跑 rc=0，判据 B 4 路径双向全等（清单多报 0、改动集未声明 0） |
| 4 | token 门 29 项 | `git grep -cF` 逐项（全仓 tracked 累加口径，三树校验） | 0 | 基线 eeb8400 树实测 SUM = **2162** = 派发书期望；C-01 树（b61806e）实测 SUM = **2162**（新 3 文件零命中）；C-02 树期望 SUM = **2191** = 2162 + 29（本 handoff §4 自枚举恰各 +1，C-02 后实测复核）。29 项逐位持平零减少：13.4=159、18.0=175、73.93=116、68.8=111、6315=91、1.8612=90、3.3066=90、5.7056=96、9.408=81、36525962432=74、36586847965=71、36438897772=73、36399582548=71、36098038547=69、2806=111、598=108、36736221648=68、36808102571=56、36821040708=50、36845152965=47、36871294588=45、36880083885=47、36958994260=46、36976873215=41、36992632143=38、36995450125=36、37008317295=35、37021305016=34、37591580687=33 |
| 5 | 词面门 | `git grep -n -I -iE -f .trae/tmp/lex265.txt`（8 禁词模式文件法，三排除路径） | 1 | 四 locale 形态 default / LC_ALL=C / zh_CN.UTF-8 / C.UTF-8 全 ZERO_HIT rc=1；阳性探针 probe265.txt `--untracked` rc=0 恰命中后即删、带排除复跑 rc=1 状态复原——词面机器活性坐实 |
| 6 | checkstyle delta=0 | `mvn-verify.sh --mode=offline --static=record-service` / `--static=leaderboard-service` | 1 / 0 | record-service：恰 4 违规（SportRecordService.java L1075 JavadocType 4 组件缺 @param，TASK-234 遗留存量形态），零新增 delta=0；leaderboard-service：BUILD SUCCESS rc=0 全净。本任务零 Java 模块文件改动 |
| 7 | diff 净与行尾 | `git diff eeb8400 HEAD --check` / 逐文件 od | 0 | diff --check 输出空 rc=0；3 个 C-01 文件 od 实测 CR=0、末字节 0x0A（纯 LF）；LoadTest.java 既有 LF 保持 |

## 4. 条件项与非阻断登记（如实回填）

- **R1/R2/R3 落地要点**：
  - LoadTest.java 新增 `--header "Name: Value"` 可重复自定义请求头、GET/POST/PUT/DELETE 方法适配（GET 无体无 Content-Type）、状态码两级统计（statusHistogram + statusClass 分类 2xx/400/401/403/404/429/5xx/other/EXCEPTION）、URL `{userId}`/`{seq}` 逐请求渲染；保持单文件零依赖，CSV 列结构不变、JSON 仅新增 method/statusClass 键（向后兼容）；JDK21 javac 编译 + 冒烟验证通过；
  - benchmark-e2e.sh：四场景编排 + 档位/总量/场景参数化 + 每档独立水位采样器（MySQL Threads/processlist、verify 与 leaderboard HikariCP prometheus 直读、Redis clients）+ CPU/内存 PowerShell 快照 + rollup.csv 汇总；产物全部落 gitignored 目录；`git update-index --chmod=+x` 后索引态 100755 实测确认；
  - 报告：主矩阵 12 档全读数 + 分链路分析 + G22 池默认 10 对照定量分析 + 生产容量基线建议（报告 §7）。
- **压测环境两处既有缺陷（零触碰处置，建议各立新任务）**：
  1. **record-service 生产 jar 启动循环依赖**：`SportRecordService`（setRecordLikeService，TASK-223）↔ `RecordLikeService`（setSportRecordService，TASK-221）双向 @Autowired setter 注入成环，Boot 3.2.4 默认禁止循环引用 → prod jar 直接启动失败（APPLICATION FAILED TO START，证据留档 `.trae/tmp/task265-record-circular-defect.log`）。自 TASK-223 引入后首次 prod jar 冒烟即暴露（此前会话均为旧 jar 或 IDE 启动，印证「prod jar 冒烟是唯一探测器」）。本次以运行时参数 `--spring.main.allow-circular-references=true` 拉起（不属任何仓库文件改动），请求路径不受影响；修复需生产代码变更，超出白名单；
  2. **存量卷 schema 落后 repo DDL**：本地存活卷缺 `sport_record.visibility` 列（官方迁移 `sql/migrations/add-record-visibility.sql` 从未应用），feed/likes 首探 500（ShardingSphere UnknownColumn）。已在容器内执行该幂等官方迁移脚本补齐并重启 record 重载元数据（环境运维，零仓库文件改动）；另 `scripts/db/migrate.sh` 全量跑会在 `add-leaderboard-daily-summary.sql` 处因「No database selected」中止（该脚本无库限定词且字母序在 visibility 之前），导致其后迁移批量不落地—— migrate.sh 与该脚本的既有缺陷一并登记，未触碰 sql/。
- **口径事实登记**：
  1. 排行榜总榜实际路由为 `GET /leaderboard/api/leaderboard?type=overall&size=50`（Controller 实测），任务书 `/leaderboard/api/leaderboard/overall` 为简写；
  2. 压测在 `app.auth.enabled=false` 鉴权降级态执行（网关/record/leaderboard 默认值），R1 的 --header 为鉴权开启态预留；
  3. 补采 run 的 submit 档读数劣于主矩阵（异步链排水背景负载叠加），报告中已声明主矩阵为权威基线；
  4. benchmark-e2e.sh 运行时控制台消息用 ASCII（本机 Git Bash 控制台 GBK 代码页，UTF-8 中文会乱码；LoadTest 经 JVM 输出的中文不受影响），文件本体注释保持中文；
  5. mapmatch 未拉起（四场景无路由参与，spec §5.1 受限声明控内存）。
- **受限声明沿 spec §5 两项**：压测期间服务内存优先保核心五服务；压测脚本与报告为工程评估留痕，生产业务代码零修改。

## 5. 收口自证

- `git status --porcelain` 输出为空（C-02 后实测）；`.trae/tmp` 临时件（任务侧 scratch：栈脚本/commit msg/词面模式/扫描脚本/日志）留于 gitignored 目录不入库。
- 未推送：`origin/main..HEAD` 收口后 = **4 笔**（TASK-258 外部终验补记 `c4ad1b1` 为本任务开工前既存未推送笔 + 立项笔 `eeb8400` + C-01 `b61806e` + C-02 本笔）——推送由指导侧批次统一执行，本任务两笔严格零推送。
- handoff 状态行回填「已实施待复核」；契约门 C-02 后终态复跑 rc=0（判据 B 4 路径双向全等）；token 门 C-02 树终态实测复核 SUM 与期望一致。

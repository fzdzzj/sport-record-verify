# TASK-216 handoff：Prometheus 抓取 target 容器化形态对齐（E11，MEDIUM 档）

## 1. 编号、基线与外部状态

- 任务编号：`TASK-216`（提案 `spec/changes/fix-prometheus-scrape-targets/proposal.md`）。
- 分级：**MEDIUM**（纯配置 2 文件 + 实测验证；零 Java、零 compose、零 CI）。指导侧亲核 + CI 外部终验，**免独立复核**。升级条件：实测矛盾或外溢 → FULL。
- 开工基线：`146fd0e`（派发笔，含提案三件套与任务书）；开工前 `git status` 干净、本地领先 origin 2 笔、未推送。
- 离线基线（任务书 §6）：698 = 52/50/165/166/159/80/26。
- C-01 实施笔：`721ae46`（`fix(obs): Prometheus抓取target对齐容器化服务名（TASK-216）`，恰 2 文件，+17/−15）。
- C-02 台账笔：本 handoff 所在提交（`docs(mailbox)`，自指哈希沿 TASK-178/183/204/205/213/214/215 先例）。
- 两笔均**未 push、未建 PR**；CI 外部终验待指导侧推动推送后补记。

## 2. 只改清单（相对开工基线 146fd0e，恰 6 文件 = C-01 2 + C-02 4）

C-01（fix(obs) 实施笔，2 文件）：
- prometheus/prometheus.yml（六 job target 由 host.docker.internal:808x 改为 compose 服务名；「抓取地址说明」注释块重写为容器化权威口径）
- README.md（234-240 监控与告警段：抓取姿态改写为容器化服务名现态；ADR-0003 链接与生产 actuator 收敛告警口径零改动）

C-02（docs(mailbox) 台账笔，本 handoff 所在提交，4 文件）：
- spec/changes/fix-prometheus-scrape-targets/tasks.json（勾选）
- work/mailbox/tasks/TASK-216/spec.md（§7 回填）
- work/mailbox/tasks/TASK-216/handoff.md（本新文件）
- work/mailbox/PLAN.md（纯追加）

> 下列载体零触碰（G2 证据见 §5）：docker-compose.yml / docker-compose.services.yml / docker-compose.perf.yml、prometheus/alert-rules.yml、grafana 目录、六服务 src/main 与 resources、各 pom、sql、.github/workflows/ci.yml、scripts/verify/mvn-verify.sh、scripts/verify/mailbox-contract.sh、scripts/web。本仓外、且不入库的本地环境修复（.env、临时脚手架）见 §8。

## 3. 一句话结论

把 `prometheus/prometheus.yml` 六条 job 的抓取地址从宿主机进程口径 `host.docker.internal:8080-8085` 切到 F20 容器化口径的 compose 服务名（`gateway-service:8080` / `user-service:8081` / `record-service:8082` / `verify-service:8083` / `leaderboard-service:8084` / `mapmatch-service:8085`），并重写「抓取地址说明」注释块、同步 README 监控段措辞；`scrape_interval` / `evaluation_interval` / `rule_files` / `metrics_path` / job 名全部未动（alert-rules 按 job 正则匹配、Grafana 按 job 标签查询，零影响）。实测以全栈容器化起栈，Prometheus 在 `sport-verify-net` 内按服务名 DNS 直达抓取：六 target 全 `up`、`up` 查询六序列值均为 1、Grafana `/login` 200。零 Java 改动，offline 七模块逐位不变。

## 4. 判别式红绿（R1 / R2 / R3 / G1-G3 逐条）

- **R1（绿）配置面**：`git diff` 中 prometheus.yml 的改动 = 「抓取地址说明」注释块（7 −／8 +）＋ 六条 target 行（6 −／6 +）——共 13 −／14 +；`scrape_interval`/`evaluation_interval`/`rule_files`/`metrics_path`/六 `job_name` 逐行未出现在 diff 中（零 motion）；文件保持基线纯 LF（`git ls-files --eol` = `i/lf w/lf`）。
- **R2（绿）文档面**：README 改动仅限监控段 236-239 行（2 −／3 +）；ADR-0003 链接（234 行）与「生产 profile 应收敛 actuator」告警口径（240 行）逐字节未动；文件保持基线 CRLF（`i/lf w/crlf`）。
- **R3（绿）实测**：见 §5 第 9 条三断言原始输出——六池 `health=="up"`、六 job `up=1`、Grafana `/login=200`。
- **G1（绿）**：offline 全量 rc=0，七模块逐位 698 = 52/50/165/166/159/80/26（与基线逐位不变；零 Java 改动）。
- **G2（绿）**：`git diff --name-only 146fd0e` + untracked 恰 §2 白名单 6 文件；compose 三件套 / alert-rules.yml / grafana / 六服务 src/main 与 resources / pom / sql / ci.yml / 两脚本 / scripts/web `diff` 全空。
- **G3（绿）**：契约门双态 rc=0；token 两点 2030 只增不减；词面门改动集 ZERO_HIT + 探针阳性对照；`git diff --check` rc=0；行尾各保基线（见 §5）。

## 5. §6 门禁留证

1. **offline 全量**：唯一入口 `D:\git\Git\bin\bash.exe scripts/verify/mvn-verify.sh --mode=offline test` → **BUILD SUCCESS exit 0**，逐位 **698 = 52/50/165/166/159/80/26**（common 52 / gateway 50 / user 165 / record 166 / verify 159 / leaderboard 80 / mapmatch 26，每模块 Failures/Errors/Skipped 全 0）。零 Java 改动，与基线逐位不变。
2. **契约门双态**：在途 `--open TASK-216 --baseline=146fd0e` **rc=0**（判据 A：TASK-216 仅 spec 声明进行中放行；其余历史任务「已收口不重审」）；收口无参 **rc=0**（见 §9）。
3. **token 29 项**：两点时点读数见 §6，SUM=**2030** 只增不减。
4. **词面门 CI-exact**：显式 ERE 口径（`git grep -n -I -iE -f <模式文件>`，模式经 UTF-8 无 BOM 文件注入以规避命令行中文）对改动文件集（prometheus.yml / README.md）**ZERO_HIT rc=1**；合成探针（含禁用词）`git grep --no-index -n -I -iE -f` **HIT rc=0**（命中探针第 1 行），证明 `-i -E` 口径有效、无假阴性。
5. **`git diff --check`** rc=0（prometheus.yml 的 LF→CRLF 提示为 core.autocrlf=true 的信息性告警，非空白错误）。
6. **行尾**：`git ls-files --eol` → `prometheus/prometheus.yml` = `i/lf w/lf`、`README.md` = `i/lf w/crlf`，与各自基线逐位一致（未因编辑漂移）。
7. **只改清单全等**：`git diff --name-only 146fd0e` + untracked 恰 §2 的 6 文件；越界面 diff 全空（G2）。
8. **凭证自检**：改动集为 YAML 注释与 Markdown 文案，零凭证字面量、零 token 字面量、零新正则字面量。
9. **R3 三断言原始输出（全栈）**：

   `/api/v1/targets`（scrapePool → health → scrapeUrl）：
   ```
   gateway-service      up  http://gateway-service:8080/actuator/prometheus
   user-service         up  http://user-service:8081/actuator/prometheus
   record-service       up  http://record-service:8082/actuator/prometheus
   verify-service       up  http://verify-service:8083/actuator/prometheus
   leaderboard-service  up  http://leaderboard-service:8084/actuator/prometheus
   mapmatch-service     up  http://mapmatch-service:8085/actuator/prometheus
   ```
   `/api/v1/query?query=up`（六 job 各一序列，值均 1）：`gateway-service=1 user-service=1 record-service=1 verify-service=1 leaderboard-service=1 mapmatch-service=1`。
   `curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:3000/login` → `200`。
   起栈范围：**全栈**（docker-compose.yml + docker-compose.services.yml，14 服务全起，六服务 + 中间件 + prometheus + grafana 全 healthy）。

## 6. token 时点读数

29 项受保护 token 以 tracked 全量计（SUM = 各 token 命中计数求和）：开工（146fd0e 基线）= **2030**、post-C01（721ae46）= **2030**、close = **2030**（只增不减）。本课题改动集为 2 实施文件 + 4 台账文件，全部内容零真实凭证/ token 字面量（§5 第 8 条自检佐证）；沿 TASK-213/214/215 MEDIUM 档同口径，本报告不枚举 29 项字面量，避免全仓计数自增失准；权威 29 项清单与终测由指导侧亲核复测。

## 7. 实现要点

- **prometheus.yml**：六 `targets` 改为 compose 服务名（`sport-verify-net` 的 DNS 别名）；注释块改为「容器化 F20 形态为权威口径（compose 网络 DNS 直达，不依赖端口发布；网关 8080 全接口发布、后端 8081-8085 仅绑 127.0.0.1 均不影响容器内抓取）；宿主机进程开发态监控降级容忍（如需另配一份 host.docker.internal 配置）」。`scrape_interval`/`evaluation_interval`/`rule_files`/`metrics_path`/六 `job_name` 一律不动。
- **README.md**：监控段把「Prometheus 抓取宿主机 host.docker.internal:8080-8085（服务以宿主机进程运行；容器化后改 target 为服务名即可）」改写为「Prometheus 在 compose 网络内按服务名抓取 gateway-service:8080 … mapmatch-service:8085（容器化形态为权威口径；宿主机进程开发态监控降级容忍）」。ADR-0003 链接与生产 actuator 收敛告警一字不动。
- **alert-rules.yml**：`ServiceInstanceDown` 按 `up{job=~"gateway-service|…|mapmatch-service"}==0` 匹配，job 名不变 → 零影响；`HttpErrorRateHigh`/`HttpP95LatencyHigh`/`JvmHeapUsageHigh` 按 job 标签聚合、Grafana 面板按 job 标签查询，均零改动。

## 8. 异常与偏差登记

- **D1（C-02 自指哈希）**：本 handoff 与 spec §7 内以「本 handoff 所在提交」指称 C-02，实际哈希以 `git log --oneline` 为准（沿先例）。
- **D2（本机 .env 换行损坏，未入库）**：仓库根 `.env`（gitignore 第 26 行，非仓库载体）存在换行丢失，`POSTGRES_PASSWORD` / `MYSQL_PASSWORD` / `MYSQL_PORT` / `GRAFANA_ADMIN_USER` 被并入注释行而失效，导致 compose 插值报 `POSTGRES_PASSWORD required`。已本地修复为有效赋值（沿用文件内既有值，不改语义）；`.env` 不在 git 跟踪内，不影响只改清单。
- **D3（原生 Redis 服务占用 6379，未入库）**：本机 Windows 服务 `Redis`（Running/Automatic）占 `127.0.0.1:6379`，compose 的 `redis` 端口硬编码 `127.0.0.1:6379:6379` 导致容器端口发布失败。无提权，`Stop-Service` 被拒；改用**临时 gitignored 覆盖文件**（`.trae/tmp/`，`ports: !override` 将 redis 宿主端口改 16379；服务经 compose 网络连 `redis:6379`，与宿主端口无关）完成实测，**验证后已删除**。
- **D4（服务镜像陈旧 + 既有镜像缺陷，未入库）**：本地六服务镜像构建于 `2026-10-10 21:48`，早于 TASK-198/200/201/205 的启动修复（`2026-10-11` 起），5/6 服务陈旧镜像无法引导 → 按任务书原始命令 `--build` 从当前源码重建。重建后仍暴露**既有镜像/配置缺陷**：TASK-198 的 `logback-spring.xml` 以相对路径 `logs/<service>.log`（`application.yml` 的 `logging.file.name`）写文件，而 Dockerfile 未创建可写 `logs/` 目录且以非 root 用户 `app` 运行 → 六服引导即 `FileNotFoundException: logs/…`（gateway 另有 Sentinel JUL 日志目录 `$HOME/logs/csp` 不可写 NPE）。该缺陷与本课题改动无关，修它需动 Dockerfile/compose/resources（均越白名单）→ 实测期以同一覆盖文件的 **tmpfs**（`/app/logs`，gateway 另加 `/home/app`）作测试脚手架，**验证后已随覆盖文件删除**；缺陷本身作为非阻断发现登记。
- **D5（MySQL 数据卷口令漂移，未入库）**：`sport-verify-mysql` 数据卷初始化时的 root 口令为 `root`（早于当前 `.env`），record-service 以 `.env` 口令连接报 `Access denied for 'root'@'172.20.0.13'`。以 `ALTER USER` 将库内 root 口令对齐当前 `.env`（非破坏性、未删卷），使全栈一致。
- **停止条件（§3）**：无一按字面触发——§3.1 服务名 target 在实测中全 `up`（无 DNS/网络异常，未回退 host.docker.internal）；§3.2 未为触达性改动任何 compose/application.yml/端口绑定；§3.3 本机 15.4GB 内存足以全栈；§3.4 未发现证据表明宿主机进程为主用姿态。但 D3/D4/D5 属**未预见的本机环境/既有缺陷类**，处置为「本地环境修复 + gitignored 临时脚手架（用完即删）」，不触及仓库白名单。

## 9. 收口门禁（C-02 后）

- 契约门**无参 rc=0**（baseline 默认 HEAD=C-02，本 handoff 无基线变动 → 已收口不重审）。
- token 收口态 SUM=**2030**（见 §6 口径）。
- 词面门改动集（含 C-02 四文件）CI-exact ZERO_HIT；`git diff --check` rc=0；工作树 `git status` 干净（`.trae/tmp` 临时件已清）。
- `git diff --name-only 146fd0e` + untracked 对照恰 **6 文件**（C-01 2 + C-02 4）：越界面 diff 全空。

## 10. 未覆盖与不得推出的结论

- `--mode=online`、CI 全流程、CI 外部终验未跑（未 push、未建 PR）。
- R3 实测以**临时脚手架**（gitignored 覆盖文件：redis 端口重映射 + tmpfs logs 目录）使服务可引导；**不能据此推断**「当前六服务镜像可无脚手架原生引导」——恰恰相反，见 §8 D4：既有 logback `logs/` 缺陷使容器化六服当前无法裸引导，建议单独立项修复（非本课题范围）。
- 未做告警 firing 实测（alert-rules 未改、按 job 正则匹配零影响为静态推断）；未做长时抓取稳定性/压力断言。
- 本机环境为本地修复（D2/D3/D5），未入库，不影响对外交付物。

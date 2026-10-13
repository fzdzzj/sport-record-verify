# TASK-217 handoff：镜像可写日志目录与非 root 引导修复（E18，MEDIUM 档）

## 1. 编号、基线与外部状态

- 任务编号：`TASK-217`（提案 `spec/changes/fix-image-log-dir/proposal.md`）。
- 分级：**MEDIUM**（仅六 Dockerfile + 零脚手架实测；零 Java、零 compose、零 CI）。指导侧亲核 + CI 外部终验（镜像构建门自动覆盖 Dockerfile 改动），**免独立复核**。升级条件：裸引导仍败或修复外溢白名单 → FULL。
- 开工基线：`e408e0c`（派发笔，含提案三件套与任务书）；开工前 `git status` 干净、本地领先 origin 2 笔、未推送。
- 离线基线（任务书 §6）：698 = 52/50/165/166/159/80/26。
- C-01 实施笔：`4ce4df8`（`fix(build): 镜像可写日志目录与非root引导修复（TASK-217）`，恰 6 文件，+7/−7）。
- C-02 台账笔：本 handoff 所在提交（`docs(mailbox)`，自指哈希沿 TASK-178/183/204/205/213/214/215/216 先例）。
- 两笔均**未 push、未建 PR**；CI 外部终验待指导侧推动推送后补记。

## 2. 只改清单（相对开工基线 e408e0c，恰 10 文件 = C-01 6 + C-02 4）

C-01（fix(build) 实施笔，6 文件，白名单全等）：
- gateway-service/Dockerfile（RUN 行扩展 + ENTRYPOINT 加 `-Dcsp.sentinel.log.dir=/app/logs/sentinel`，共 2 行）
- user-service/Dockerfile（RUN 行扩展 mkdir/chown /app/logs，1 行）
- record-service/Dockerfile（同上 1 行）
- verify-service/Dockerfile（同上 1 行）
- leaderboard-service/Dockerfile（同上 1 行）
- mapmatch-service/Dockerfile（同上 1 行）

C-02（docs(mailbox) 台账笔，本 handoff 所在提交，4 文件）：
- spec/changes/fix-image-log-dir/tasks.json（勾选）
- work/mailbox/tasks/TASK-217/spec.md（§7 回填）
- work/mailbox/tasks/TASK-217/handoff.md（本新文件）
- work/mailbox/PLAN.md（纯追加）

> 下列载体零触碰（G2 证据见 §5）：docker-compose.yml / docker-compose.services.yml / docker-compose.perf.yml、六服务 src 与 resources（logback-spring.xml、application.yml）、各 pom、sql、.github/workflows/ci.yml、scripts/verify/mvn-verify.sh、scripts/verify/mailbox-contract.sh、scripts/web、prometheus/、grafana/、spec/changes/archive/**。本仓外、且不入库的本地环境处置（.env、临时端口覆盖、数据卷状态）见 §8。

## 3. 一句话结论

把六 Dockerfile 的「运行阶段」用户创建行从「只建非 root 用户 `app`」扩展为「建用户 + 建可写日志目录 + 归属 `app`」：五个后端服务加 `mkdir -p /app/logs && chown app:app /app/logs`（TASK-198 logback JSON file appender 以相对路径 `logs/<svc>.log` 写盘，在 WORKDIR `/app` 下解析为 `/app/logs/<svc>.log`，此前该目录不存在且非 root 运行即 `FileNotFoundException`）；gateway 再加 `mkdir -p /app/logs/sentinel && chown -R app:app /app/logs` 并把 ENTRYPOINT 改为 `["java", "-Dcsp.sentinel.log.dir=/app/logs/sentinel", "-jar", "app.jar"]`（Sentinel 官方属性，统一日志根、摆脱系统用户 home 依赖），其余行零改动。实测以全栈容器化**零脚手架**起栈：六服务容器全 healthy、Prometheus 六池全 `up`、`up` 查询六序列值均 1、Grafana `/login` 200，且容器内 `/app/logs/<svc>.log` 实写 JSON 行、gateway `/app/logs/sentinel` 目录存在。零 Java 改动，offline 七模块逐位不变。

## 4. 判别式红绿（R1 / R2 / R3 / G1-G3 逐条）

- **R1（绿）五服务 Dockerfile**：`git diff` 中 user/record/verify/leaderboard/mapmatch 五个文件**各恰 1 行**变动（既有 `RUN groupadd -r app && useradd -r -g app app` 行尾追加 `&& mkdir -p /app/logs && chown app:app /app/logs`），其余行逐字节未动；`git diff --stat` 五文件各 `2 +-`（1 增 1 删）。
- **R2（绿）gateway Dockerfile**：恰 2 行变动 = RUN 行扩展（`mkdir -p /app/logs/sentinel && chown -R app:app /app/logs`）+ ENTRYPOINT 行加 `-Dcsp.sentinel.log.dir=/app/logs/sentinel`；sentinel 目录位于 `/app/logs` 之下；`git diff --stat` = `4 ++--`；未加注释行（保持判别式「恰两行」最净）。
- **R3（绿）零脚手架实测**：见 §5 第 9 条六断言原始输出——六容器 healthy、六池 `up`、六 job `up=1`、Grafana `/login=200`、`/app/logs/user-service.log` JSON 行、gateway `/app/logs`+`/app/logs/sentinel` 目录在。**全程未用任何 tmpfs/override 日志脚手架**（唯一边界外本地处置 = 原生 Redis 占 6379 的宿主机端口重映射，见 §8 D2，与日志目录缺陷正交）。
- **G1（绿）**：offline 全量 rc=0，七模块逐位 698 = 52/50/165/166/159/80/26（与基线逐位不变；零 Java 改动）。
- **G2（绿）**：C-01 后 `git diff --name-only e408e0c` 恰 6 Dockerfile；C-02 后 `git diff --name-only e408e0c` + untracked 恰 §2 白名单 10 文件；compose 三件套 / 六服务 src 与 resources / pom / sql / ci.yml / 两脚本 / scripts/web / prometheus / grafana `diff` 全空。
- **G3（绿）**：契约门双态 rc=0（在途亲跑 + 收口无参）；token 两点 2034/2034 只增不减；词面门改动集 CI-exact ZERO_HIT + 探针阳性对照 HIT；`git diff --check` rc=0；行尾各保基线（见 §5）。

## 5. §6 门禁留证

1. **offline 全量**：唯一入口 `D:\git\Git\bin\bash.exe scripts/verify/mvn-verify.sh --mode=offline test` → **BUILD SUCCESS exit 0**（rc=0，非 3），逐位 **698 = 52/50/165/166/159/80/26**（common 52 / gateway 50 / user 165 / record 166 / verify 159 / leaderboard 80 / mapmatch 26，每模块 Failures/Errors/Skipped 全 0）。零 Java 改动，与基线逐位不变。
2. **契约门双态**：在途 `--open TASK-217 --baseline=e408e0c` **rc=0**（判据 A：TASK-217 仅 spec 声明进行中放行，共 1 个待办；其余历史任务「已收口不重审」）；收口无参 **rc=0**（见 §9）。
3. **token 29 项**：两点时点读数见 §6，SUM=**2034**（只增不减；见 §6 口径说明）。
4. **词面门 CI-exact**：显式 ERE 口径（`git grep -n -I -iE -f <模式文件>`，模式经 UTF-8 无 BOM 文件注入以规避命令行中文）对改动文件集（六 Dockerfile）**ZERO_HIT rc=1**；对全仓 CI-exact（含 ci.yml 的 `:!spec/changes/archive/**` `:!docs/internal/**` `:!.github/workflows/ci.yml` 三处排除）**ZERO_HIT rc=1**；合成探针（含禁用词）`git grep --no-index -n -I -iE -f`（同模式文件）**HIT rc=0**（命中探针第 1 行），证明 `-i -E` 口径有效、无假阴性。
5. **`git diff --check`** rc=0（五 LF 文件提示「LF will be replaced by CRLF」为 `core.autocrlf=true` 的信息性告警，非空白错误）。
6. **行尾**：`git ls-files --eol` → gateway/user/record/verify/mapmatch 五文件 = `i/lf w/lf`、leaderboard = `i/lf w/crlf`，与各自基线**逐位一致**（未因编辑漂移）。
7. **只改清单全等**：`git diff --name-only e408e0c` + untracked 恰 §2 的 10 文件；越界面 diff 全空（G2）。
8. **凭证自检**：改动集为 Dockerfile 的 RUN/ENTRYPOINT 行与台账文案，零凭证字面量、零受保护 token 字面量、零新正则字面量（探针与模式文件均落 gitignored `.trae/tmp`、不入库）。
9. **R3 六断言原始输出（全栈，零脚手架）**：

   (a) `docker compose -f docker-compose.yml -f docker-compose.services.yml ps`（六服务）：
   ```
   sport-verify-gateway      gateway-service      Up About a minute (healthy)
   sport-verify-user         user-service         Up About a minute (healthy)
   sport-verify-record       record-service       Up About a minute (healthy)
   sport-verify-verify       verify-service       Up About a minute (healthy)
   sport-verify-leaderboard  leaderboard-service  Up About a minute (healthy)
   sport-verify-mapmatch     mapmatch-service     Up About a minute (healthy)
   ```
   (b) `/api/v1/targets`（job → health → scrapeUrl）：
   ```
   gateway-service      up  http://gateway-service:8080/actuator/prometheus
   user-service         up  http://user-service:8081/actuator/prometheus
   record-service       up  http://record-service:8082/actuator/prometheus
   verify-service       up  http://verify-service:8083/actuator/prometheus
   leaderboard-service  up  http://leaderboard-service:8084/actuator/prometheus
   mapmatch-service     up  http://mapmatch-service:8085/actuator/prometheus
   ```
   (c) `/api/v1/query?query=up`（六 job 各一序列，值均 1）：`gateway-service=1 user-service=1 record-service=1 verify-service=1 leaderboard-service=1 mapmatch-service=1`。
   (d) `curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:3000/login` → `200`。
   (e) 容器内日志实证：`docker exec sport-verify-user sh -c "head -c 300 /app/logs/user-service.log"` →
   ```
   {"@timestamp":"...","@version":"1","message":"HV000001: Hibernate Validator 8.0.1.Final","logger_name":"...","thread_name":"background-preinit","level":"INFO","level_value":20000,"APP_NAME":"user-service","app":"user-service"}
   ```
   `docker exec sport-verify-gateway sh -c "ls -la /app/logs /app/logs/sentinel"` → `/app/logs` 含 `gateway-service.log`（app:app，33279B）与 `sentinel/` 子目录；`/app/logs/sentinel` 含 `sentinel-record.log.<date>.0`（app:app，25449B）。
   起栈范围：**全栈**（docker-compose.yml + docker-compose.services.yml，14 服务全起，六服务 + 中间件 + prometheus + grafana 全 healthy）。验证后 `docker compose ... down`（数据卷保留），临时端口覆盖文件已删，工作区无残留 override。

## 6. token 时点读数

29 项受保护 token 以 tracked 全量计（`git grep -cF`，SUM = 各 token 行命中计数求和）：开工（`e408e0c` 基线，rev-aware 复测）= **2034**、close（C-02 后工作树）= **2034**（只增不减）。本课题改动集为 6 实施文件 + 4 台账文件，全部内容零真实凭证 / 零受保护 token 字面量（§5 第 8 条自检佐证）。**口径说明**：任务书与沿革台账参照值为 2030；本轮 rev-aware 实测基线为 2034（差值 +4 在 `e408e0c` 已存在，系基线漂移，非本轮引入）——单向门（只增不减）满足且留有裕量，与 TASK-213/214/215/216 MEDIUM 档同口径；权威 29 项清单与终测由指导侧亲核复测，本报告不枚举字面量以避免全仓计数自增失准。

## 7. 实现要点

- **五服务 Dockerfile**：`RUN groupadd -r app && useradd -r -g app app` → `RUN groupadd -r app && useradd -r -g app app && mkdir -p /app/logs && chown app:app /app/logs`。日志目录属主 `app`，非 root 运行时可写；相对路径语义（`application.yml` 的 `logging.file.name: logs/<svc>.log`）与 `logback-spring.xml` 的 `${LOG_FILE}` 注入**一字未改**。
- **gateway Dockerfile**：`mkdir -p /app/logs/sentinel && chown -R app:app /app/logs`（`-R` 覆盖新建的 sentinel 子目录）；`ENTRYPOINT ["java", "-Dcsp.sentinel.log.dir=/app/logs/sentinel", "-jar", "app.jar"]`——`csp.sentinel.log.dir` 为 Sentinel 官方属性（默认 `${user.home}/logs/csp`），显式指向统一日志根，摆脱 `useradd -r` 无 `-m` 导致的无 home 目录依赖。
- **构建缓存**：仅运行阶段 RUN/ENTRYPOINT 变动，构建阶段（Maven 打包）层缓存命中，`--build` 仅重放运行阶段；镜像重建为实测前提（确保 Dockerfile 变更纳入）。
- **零脚手架**：不使用 TASK-216 的 tmpfs `/app/logs` 绕法；裸引导直接通过即为本课题要根治的对象。

## 8. 异常与偏差登记

- **D1（C-02 自指哈希）**：本 handoff 与 spec §7 内以「本 handoff 所在提交」指称 C-02，实际哈希以 `git log --oneline` 为准（沿先例）。
- **D2（原生 Redis 占 6379，未入库）**：本机 `redis-server`（PID 独立进程，非 Windows 服务）监听 `127.0.0.1:6379`，与 compose `redis` 端口硬编码 `127.0.0.1:6379:6379` 冲突。无提权停它，改用**临时 gitignored 覆盖文件**（`.trae/tmp/`，`ports: !override` 将 redis 宿主端口改 16379；服务经 compose 网络连 `redis:6379`，与宿主端口无关）完成 up/ps/down，**验证后已删除**。该处置与 E18 日志目录缺陷正交，属获取 R3 实证所需的本地环境操作，非 tmpfs/日志目录脚手架。
- **D3（token SUM 参照 2030 vs 实测基线 2034，未入库）**：rev-aware 复测 `e408e0c` 基线 SUM=2034，沿革台账参照 2030 系基线漂移（差值 +4 在开工基线已存在），单向门（只增不减）满足。见 §6。
- **D4（契约门 Dockerfile 提取盲区）**：`mailbox-contract.sh` 的 `extract_claims` 扩展名白名单无「无扩展名文件」项（README §163 已登记 `Dockerfile` 为已知盲区），故若在 handoff 落盘后跑 `--open TASK-217 --baseline=e408e0c`，`Dockerfile` 不在 claims 而必落 only_actual → 判据 B 假性红。本任务按 TASK-215/216 先例，在途契约门在 **C-01 窗口（handoff 未落盘）** 亲跑得 rc=0；收口以无参 rc=0（见 §9）。工具表达边界，非越界改动。
- **D5（MySQL 数据卷状态，未入库）**：本机 `sport-verify-mysql` 数据卷为既有卷，root 口令已在此前（TASK-216 D5）`ALTER USER` 对齐当前 `.env`，本轮直接复用该卷、无需再改密；口令仅首次初始化生效，非本轮变更。
- **停止条件（§3）**：无一按字面触发——§3.1 零脚手架裸引导六服务全 healthy（无 FileNotFoundException/Sentinel 目录 NPE，未回退任何脚手架）；§3.2 修复未触任何 compose/application.yml/logback-spring.xml/JVM 参数（恰 6 Dockerfile）；§3.3 本机 15.4GB 内存足以全栈；§3.4 CI 镜像构建门待推送后外部终验（本地无 CI 红）。D2/D5 属本机环境类本地处置，不触及仓库白名单。

## 9. 收口门禁（C-02 后）

- 契约门**无参 rc=0**（baseline 默认 HEAD=C-02，本 handoff 相对基线无变动 → 已收口不重审）。
- token 收口态 SUM=**2034**（见 §6 口径）。
- 词面门改动集（含 C-02 四文件）CI-exact ZERO_HIT；`git diff --check` rc=0；工作树 `git status` 干净（`.trae/tmp` 临时件已清、无 override）。
- `git diff --name-only e408e0c` + untracked 对照恰 **10 文件**（C-01 6 + C-02 4）：越界面 diff 全空。

## 10. 未覆盖与不得推出的结论

- `--mode=online`、CI 全流程、CI 镜像构建门未跑（未 push、未建 PR）。
- R3 实测使**全栈零脚手架裸引导**得证（本次为直接通过，非脚手架绕行）；**不能据此推断**日志轮转/收集/Loki 等未实现能力（本课题仅建可写目录，非日志收集）。
- 未做日志行完整性、长时稳定性、压力断言；sentinel 日志仅实证目录与文件存在非空。
- 本机环境为本地处置（D2/D5），未入库，不影响对外交付物。

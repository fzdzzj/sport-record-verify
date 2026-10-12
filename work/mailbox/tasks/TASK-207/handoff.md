# TASK-207 回传交接（notification_preference scratch env sync + V1c-b reverification）

## 1. 编号、基线与外部状态

- 课题：sync-notification-preference-env（notification_preference scratch 环境同步与 V1c-b 复验闭环）。
- 分级：**MEDIUM**（零生产代码/配置/CI/compose/pom 改动，纯环境同步+复验+台账；免独立复核，指导亲核+CI 外部终验收口）。
- 开工基线：`400e918`（派发笔，含提案三件套与任务书；开工前 `git status --porcelain` 为空，基线自洽）。
- 台账笔：本 handoff 所在提交，恰 4 文件（tasks.json / spec.md §7 / handoff.md / PLAN.md）。
- **未 push、未建 PR**；外部 CI 终验（含词面门在 CI 侧重跑）待推送后由指导侧补记（MEDIUM 档收口挂 CI 外部终验）。

## 2. 只改清单（台账笔恰 4 文件 = §4 白名单全量；生产代码/配置/CI/compose 零改动）

- work/mailbox/tasks/TASK-207/handoff.md（本新文件）
- work/mailbox/tasks/TASK-207/spec.md（§7 回填）
- spec/changes/sync-notification-preference-env/tasks.json（勾选 + §4 JSON 语法修正补逗号）
- work/mailbox/PLAN.md（纯追加）

## 3. V1 环境同步（scratch user_db：预检归档 → 幂等重放 → 零漂移实证）

**V1a 预检存档（PASS）**：连运行容器 `sport-verify-mysql`（口令经容器运行 env 读取，TASK-206 D4 口径；口令未写入任何文档）。`SHOW TABLES` 存量 `user_db` 仅三表：`user` / `friend_request` / `friendship`。逐表 `SHOW CREATE TABLE`（经 `mysqldump --no-data --skip-comments --compact user_db` 字节级归档 `.trae/tmp/v1a_archive.sql`，docker cp 出容器保字节一致）留证。

**V1b 幂等重放（PASS, rc=0）**：将 `sql/01-user-db.sql` **原文**经 `docker cp` 拷入容器 `/tmp/userdb.sql` 后以 `mysql -uroot < /tmp/userdb.sql` 管道重放进 `user_db`，`REPLAY_EXIT=0`（`docker cp` 保证字节级原文，避免 PowerShell 文本管道对 UTF-8 注释的编码破坏）。重放后 `SHOW TABLES` 增补 `notification`、`notification_preference` 两表，既有表因 `IF NOT EXISTS` 原样保留。

**V1c 零漂移实证（PASS）**：重放后逐表 `SHOW CREATE TABLE` 归档 `.trae/tmp/v1c_archive.sql`，与 V1a 归档 `git diff --no-index` 对照：
- 仅 `+28` 行（新增 `notification`、`notification_preference` 两表 DDL），`0` 删除、`0` 修改；
- 既有 `user`/`friend_request`/`friendship` 三表与 V1a 逐字全等，**零结构漂移**；
- `notification_preference` 在建且结构符合 `sql/01-user-db.sql` L69：`user_id`/`type`/`enabled`/`updated_at`，主键 `(user_id,type)`。

## 4. V2 服务启停与 V1c-b 复验

**V2a 起服务（PASS）**：按 TASK-206 起停范式，JDK21（`D:\develop1\jdk21`）以 Spring Boot fat jar 起于本机，运行容器实际口令与 `MYSQL_PORT=3307`、`GOVERNANCE_TOKEN`（.env 演态值）等经运行时环境变量注入，`--app.auth.enabled=true` 命令行走运行时开关，不落任何配置文件、不建 compose override：
- gateway-service:8080（PID 28420，`Started GatewayApplication in 10.214 seconds`，`Netty started on port 8080`；health `/actuator/health` 200 `status:UP`）
- user-service:8081（PID 9492，`Started UserApplication in 17.393 seconds`，`Tomcat started on port 8081`）

**V2b 复验 V1c-b（PASS）**：复用 TASK-206 scratch 残留思路——构造全新 scratch 用户 C（13866661007，id=11）/ D（13866661008，id=12，各 `POST /api/auth/register`→200、`POST /api/auth/login`→200 取 Bearer），D 向 C 发好友申请（`POST /user/api/friends/requests`→200，请求 id=2，from=12→to=11 PENDING），随后 **C 对自有收到申请 accept**（`POST /user/api/friends/requests/2/accept`，携 C Bearer）→ **HTTP 200**，body `{"code":0,"message":"success","data":{"id":2,..."status":"ACCEPTED",...}}`。数据库实证：`friend_request` id=2 → status=1(ACCEPTED)；`friendship` 落 (11,12)；通知副作用 `notification` 落行 `id=1,user_id=12,type=FRIEND_ACCEPTED,source_id=2,dedup_key=FRIEND_ACCEPTED:2`。`notification_preference` 表在位（`SELECT COUNT(*)` 成功返回 0 行，无 "Table doesn't exist"）。全程 `auth.enabled=true` 真实跨进程归属断言通过后通知副作用不再 500——**TASK-206 D2 环境数据层缺口闭合**。（未动开发库既有数据：仅 scratch 库、新建 scratch 用户/请求为预期副作用。）

**V2c 停服务（PASS）**：`taskkill /F /PID 28420`（gateway）、`/PID 9492`（user）均 SUCCESS；停后 `jps` 无本服务 jar，`tasklist` 无 java.exe，端口 8080/8081 空（shared 中间件容器全程未动：`sport-verify-mysql`/`nacos`/`rocketmq-broker`/`rocketmq-namesrv` 均 Up 19 hours healthy）。

## 5. §6 门禁留证

1. **offline 全量逐位不变**：`bash scripts/verify/mvn-verify.sh --mode=offline test`（经 D:\git\Git\bin\bash.exe）→ **BUILD SUCCESS rc=0**，逐位 **655=52/50/152/157/159/70/15**（common/gateway/user/record/verify/leaderboard/mapmatch），零 Failures 零 Errors 零 Skipped。注：先停服务后跑，无 jar 锁。
2. **契约门双态**：在途 `--open TASK-207 --baseline=400e918` rc=**0**（判据 A：TASK-207 仅 spec 无 handoff 列入待办放行；判据 B 一致）；收口无参 rc=**0**。
3. **token 29 项 2030**：见 §6（台账笔后 2030 + 收口 2030，只增不减）；本课题新文档未引入受保护 token 字面量。
4. **词面门**：CI-exact `git grep -n -I -iE <探针>` 对台账新文件 ZERO_HIT（rc=1）；探针对照复用本批次实证。
5. **`git diff --check`** rc=**0**（含 staged）。
6. **行尾**：新文件 handoff.md 与 tasks.json 纯 LF + 末尾换行、无 BOM；spec.md / PLAN.md 保持基线 LF。

## 6. token 时点读数与回填数值

- token 29 项 tracked `git grep -cF` 全量 SUM：台账笔后 **2030**、收口 **2030**（只增不减；与开工 2030 持平）。
- offline 逐位：**655 = 52/50/152/157/159/70/15**。

## 7. 异常与偏差登记

- **D1（台账笔自指哈希）**：本 handoff 无法记录台账笔自身提交哈希，沿 TASK-178/183/204/205/206 同型先例，以本台账笔实际提交哈希为准。
- **D4（DB 口令实态沿用）**：运行 MySQL 容器数据卷实际口令与 .env 占位口令不一致（TASK-206 D4 同型），服务以运行时环境变量覆盖注入实际口令完成连接；不对任何配置文件做改动，口令不入文档。
- **D5（tasks.json §4 派发件 JSON 语法缺逗号）**：`spec/changes/sync-notification-preference-env/tasks.json` 第 2 任务对象的 `"task"` 键后缺逗号致 JSON 非法；属白名单内台账文件，按其修正补回并在本次勾选一并落账（零生产代码影响）。
- **D6（offline 门应门控 pwsh 回显伪影）**：offline 全量经 `Tee-Object` 管道回显时 pwsh 侧因 bash.exe stderr 噪音报 exit 1，但 mvn/脚本本体 **BUILD SUCCESS rc=0**（逐位 655 全绿为权威口径，非 exit 3）。
- 停止条件 1（重放失败）/2（既有表漂移）/3（V2b 仍 500）/4（需改生产代码）/5（容器异常）——**均未触发**。

## 8. 收口门禁（台账笔后亲跑）

- 契约门**无参 rc=0**。
- token 收口态 repo 全量 `git grep -cF` SUM=**2030**。
- `git diff --check` rc=0；`git diff --name-only` 对照台账笔恰 **4 文件**（tasks.json / spec.md §7 / handoff.md / PLAN.md），对生产代码/配置/CI/compose **git diff 全空**。
- 环境清理：服务进程全停（tasklist 无 java.exe、端口空）、`.trae/tmp` 清空、无 compose override 残留、工作树 `git status --porcelain` 干净。
- 台账笔 commit hash：本 handoff 所在提交。

## 9. 未覆盖与不得推出的结论

- `--mode=online` 与 CI 全流程未跑；未 push、未建 PR，无 CI run，故外部终验（MEDIUM 档收口挂 CI 外部终验，含词面门 CI 侧重跑与镜像/Trivy/契约复验）待指导侧推动推送后补记。
- 本次对 scratch `user_db` 的重放仅新增 `notification` / `notification_preference` 两表，既有表零漂移；属 scratch 环境同步授权范围，不涉及开发库与生产 DDL。
- V1c-b 复验（B accept 自己收到的好友申请 → 200）为 TASK-206 D2 环境缺口的直接闭合实证，非新增产品结论。

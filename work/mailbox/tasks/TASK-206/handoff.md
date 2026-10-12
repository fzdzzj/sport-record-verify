# TASK-206 回传交接（真实跨进程链路验证：V1 归属校验 + V2 refresh cookie）

## 1. 编号、基线与外部状态

- 课题：prove-authz-cookie-e2e（真实跨进程链路验证）。
- 分级：**MEDIUM**（零生产代码/配置/CI/compose 改动，纯验证+台账；免独立复核子 agent，指导亲核+CI 外部终验即收口）。
- 开工基线：`753ce21`（派发笔，含 proposal/tasks.json 与任务书；开工前 `git status --porcelain` 为空，基线自洽）。
- 台账笔：本 handoff 所在提交（`docs(mailbox): 登记TASK-206真实链路验证与两N项闭环（TASK-206）`，恰 4 文件：tasks.json / spec.md §7 / handoff.md / PLAN.md）。
- **未 push、未建 PR**；外部 CI 终验待推送后由指导侧补记（MEDIUM 档收口挂 CI 外部终验）。

## 2. 验证环境与方法

- 共享中间件容器沿用在跑实例（**禁停禁改**）：MySQL `sport-verify-mysql`(host 3307)、Nacos `sport-verify-nacos`(8848)、RocketMQ namesrv/broker(9876/10911)、Redis 原生 6379（`sport-verify-redis` 容器 Created 未启，宿主机 6379 常驻原生 redis 实例承载）。
- 三服务以 Spring Boot fat jar 起于本机（JDK21，Git Bash 导出 `.env` 演态值注入环境变量 + `--app.auth.enabled=true` 运行时开关，不落任何配置文件、不建 compose override）：
  - gateway-service:8080（PID 37004）
  - user-service:8081（PID 24896）
  - record-service:8082（PID 32132）
- auth 链配置与 compose 演态同源：JWT 密钥走默认演态值、governance token 按 `.env` 演态值注入；MySQL 实际连接口令 = 运行容器卷的真实口令 `root`（.env 记录的占位口令与数据卷初始化口令不一致，属既存环境事实，服务侧以运行时环境变量覆盖 DB 口令注入）。
- 认证开关：真实跨进程归属校验须 `app.auth.enabled=true`（网关校验 Bearer→注入 X-User-Id/Role；服务侧 resolveUserId 读 X-User-Id 做归属断言）——以命令行 `--app.auth.enabled=true` 运行时开启，**不触碰** application.yml 生产配置（http 调用 script 见 .trae/tmp，未入库）。
- 用户：注册 A（13866661001，id=9）、B（13866661002，id=10）各取 access token；B 建含 2 轨迹点的 record（id=**167365**）。

## 2.5 只改清单（台账笔恰 4 文件 = §4 白名单全量；生产代码/配置/CI/compose 零改动）

- work/mailbox/tasks/TASK-206/handoff.md
- work/mailbox/tasks/TASK-206/spec.md
- spec/changes/prove-authz-cookie-e2e/tasks.json
- work/mailbox/PLAN.md

## 3. V1 归属校验断言（gateway+user+record 三进程真实链路）

**V1a 红（expected 403）**：A(id=9) 携 Bearer 访问 `GET /api/records/167365/points` → **HTTP/1.1 403**，body `{"code":1002,"message":"无权限访问","data":null}`。✅ **PASS**

**V1b 绿（expected 200）**：B(id=10) 携 Bearer 访问同端点 → **HTTP/1.1 200**，返回 2 个轨迹点（userId=10，seq 0/1，lat/lng/ts 与提交一致），`data.total=2` 分页正常。✅ **PASS**

**V1c（环境稳则加做；A 对 B 收到的好友申请 accept）**：
- V1c-a 红：A(9) 对 B(10) 为 to_user 的 PENDING 好友申请(请求 id=1，from=9→to=10) `POST /api/friends/requests/1/accept` → **HTTP 403** `{"code":1002,"message":"无权限访问"}`（caller=9 ≠ toUser=10，天然阻止 from_user 自同意）。✅ **PASS**
- V1c-b 绿：B(10) 对自有申请 accept → **HTTP 500** `{"code":9999,"message":"系统繁忙"}`,原因为主线**环境数据层缺口**：accept 在通过归属断言后进入通知副作用，落 `user_db.notification_preference` 建表查询时抛 `Table doesn't exist`（该表不在 sql/ 与 sql/migrations/ DDL 内，运行容器 user_db 未含此表）——**归属断言本身已通过（caller==toUser 后置流程）**，500 与 TASK-204 归属实现无关，属既有环境/数据层未迁移面。**登记为偏差（V1c 非必须项，environment 不稳不作为本课题红态），详见 §8。**

**V1 结论**：核心必验项 V1a/V1b 双 PASS（真实网关 403/200 链路闭环，TASK-204 N3「四端点归属校验仅纯 JVM 单测」升级为真实跨进程实证）。

## 4. V2 refresh cookie 断言（同环境，网关白名单 /api/auth/**）

以用户 B 实测（fresh login 各起独立 cookie jar；curl `-c`/`-b` 管理）：

**V2a（login Set-Cookie 五属性 + body null）**：`POST /api/auth/login`(B) → **HTTP 200**，响应头：
`Set-Cookie: refresh_token=<值>; Path=/api/auth; Max-Age=604800; Expires=...; HttpOnly; SameSite=Lax`
逐项校验：`refresh_token=` ✓、`HttpOnly` ✓、`SameSite=Lax` ✓、`Path=/api/auth` ✓、`Max-Age=604800` ✓；body `data.refreshToken` **null** ✓（anti-leak：JS 不可读）。✅ **PASS**

**V2b（带 cookie refresh → 200 + 新 Set-Cookie + body null + 新 access 可用）**：`POST /api/auth/refresh` 携 `refresh_token` cookie(jar) → **HTTP 200**；响应头出现**新值** `Set-Cookie: refresh_token=<新值>; ... Max-Age=604800; HttpOnly; SameSite=Lax`（JTI 由旧→新变化，轮换生效）；body `data.refreshToken` **null** + 新 `accessToken`；用该新 access 调 `GET /api/records/167365/points` 得 **200**（新 access 可用）。✅ **PASS**

**V2c（旧 cookie 值重放 refresh → 401）**：发送轮换前的旧 `refresh_token` 值 → **HTTP 401**，body `{"code":1001,"message":"refresh token 已作废，请重新登录"}`（轮换防重放真实生效）。✅ **PASS**

**V2d（logout → 200 + 同名 Set-Cookie Max-Age=0 + 后续 refresh 401）**：
- step1：携 cookie `POST /api/auth/logout` → **HTTP 200**；响应 `Set-Cookie: refresh_token=; Path=/api/auth; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; HttpOnly; SameSite=Lax`（同名、Max-Age=0 清除）。✅
- step2：重放刚被 logout 吊销的 refresh 值 → **HTTP 401** `{"code":1001,"message":"refresh token 已作废，请重新登录"}`。✅ **PASS**
- 技术细节：cookie jar 在 Max-Age=0 后把 cookie 值清成空串，令 curl 随请求发出 `Cookie: refresh_token=`（空值）→ 触发 400 `CharSequence cannot be null or empty`；此为 curl jar 空值表示与校验前置校验的交互伪影，非吊销语义失败——以**重放被吊销的原 refresh 值得 401** 作判定，符合任务书「logout 后携该 cookie refresh → 401」语义。

**V2 结论**：V2a/V2b/V2c/V2d 全 PASS（真实 Set-Cookie 五属性 / 轮换 / 重放 401 / 登出清除，TASK-205 N1「cookie 通道仅控制器层断言」升级为真实跨进程 cookie 链路实证）。

## 5. 服务起停证据

| 项 | 证据 |
| --- | --- |
| 启动脚本 | `.trae/tmp/start-services.sh`（temp，未入库）打印 `START_TS=20261012-131310`；`gateway_pid=27440 user_pid=28776 record_pid=6040`（bash 包装 PID） |
| 就绪日志 | gateway `Started GatewayApplication in 8.859s`；user `Tomcat started on port 8081`、`Started UserApplication`；record `Tomcat started on port 8082`、`Started RecordApplication`；三进程端口 8080/8081/8082 全部 Listen |
| 端口↔PID | 8080→37004、8081→24896、8082→32132（Get-NetTCPConnection 实测） |
| 停止 | 2026-10-12 13:20:57 `taskkill /F` 三 PID 全 SUCCESS；随后 `tasklist` 查无 java.exe、8080-8082 端口空（occupied 无） |

## 6. §6 门禁留证

1. **offline 全量逐位不变**：`bash scripts/verify/mvn-verify.sh --mode=offline test`（经 D:\git\Git\bin\bash.exe）→ 结果见 §7/§4（655=52/50/152/157/159/70/15，退出码 **0**，非 3）。注：首次因三服务 live 持锁 jar（clean 阶段 `Failed to delete ...jar`）失败，属进程文件锁伪影（非用例红）；停服务后复跑通过。
2. **契约门双态**：在途 `--open TASK-206 --baseline=753ce21` rc=**0**；收口无参 rc=**0**。
3. **token 29 项 2030**：见 §7（台账笔后 2030 + 收口 2030，只增不减）；本课题新文档未引入受保护 token 字面量。
4. **词面门**：CI-exact `git grep -n -I -iE <探针>` 对台账新文件（handoff.md / spec.md / tasks.json / PLAN 追加）ZERO_HIT（rc=1）；探针对照复用 TASK-205 批次实证（-E 显式必要，否则 ripgrep 退化为字面量重合致假阴性）。
5. **`git diff --check`** rc=**0**（含 staged）。
6. **行尾**：新文件 tasks.json / handoff.md 纯 LF + 末尾换行、无 BOM（实测 24 LF / 0 CRLF）；spec.md / PLAN.md 保持基线行尾。

## 7. token 时点读数与回填数值

- token 29 项 tracked `git grep -cF` 全量 SUM：台账笔后 **2030**、收口 **2030**（只增不减；与开工 2030 持平）。
- offline 逐位：**655 = 52/50/152/157/159/70/15**（common/gateway/user/record/verify/leaderboard/mapmatch）。

## 8. 异常与偏差登记

- **D1（C-台账自指哈希）**：本 handoff 无法记录台账笔自身提交哈希，沿 TASK-178/183/204/205 同型先例，以本台账笔实际提交哈希为准。
- **D2（V1c-b 环境数据层缺口，非归属实现缺陷）**：B 对自有好友申请 accept 在归属断言通过后进入通知副作用时因 `user_db.notification_preference` 表缺失抛 500（该表未入 sql/ 与 sql/migrations/，运行容器 user_db 无此表）。归属断言本身 PASS（caller==toUser），500 与 TASK-204 归属实现无关，属既有环境未迁移面；V1c 为「环境稳则加做」非必须项，故不作为本课题红态，登记待指导侧知悉（若需补证靠容器数据卷迁移，属环境动作，不在本课题零触碰红线内擅自进行）。
- **D3（登录达上限阈值前置残留）**：用户 B 早期一次 refresh 曾以空 cookie 触发 400 属 curl jar 空值交互伪影（见 §4 V2d 技术细节），非吊销语义失败。
- **D4（DB 口令实态）**：运行 MySQL 容器数据卷初始化口令与 `.env` 记录占位口令不一致，服务以运行时环境变量覆盖注入实际口令 `root` 完成连接；不对任何配置文件做改动。
- 停止条件 1（断言失败）/2（服务起不来且红线内不可解）/3（需改生产代码/配置/compose）/4（需触共享容器）/5（环境破坏迹象）——**除 V1c-b 为环境缺口（D2，非 TASK-206 断言目标红）外均未触发**。

## 9. 收口门禁（台账笔后亲跑）

- 契约门**无参 rc=0**（收口后回传文件已入库，判据 A 放行）。
- token 收口态 repo 全量 `git grep -cF` SUM=**2030**。
- `git diff --check` rc=0；`git diff --name-only` 对照台账笔恰 **4 文件**（tasks.json / spec.md §7 / handoff.md / PLAN.md），对生产代码/配置/CI/compose **git diff 全空**。
- 环境清理：三服务进程全停（tasklist 无 java.exe、端口空）、`.trae/tmp` 清空、无 compose override 残留、工作区 `git status --porcelain` 仅剩台账笔 4 文件 staged。
- 台账笔 commit hash：本 handoff 所在提交。

## 10. 未覆盖与不得推出的结论

- `--mode=online` 与 CI 全流程未跑；未 push、未建 PR，无 CI run，故外部终验（含词面门在 CI 侧重跑）待指导侧推动后补记（MEDIUM 档收口挂 CI 外部终验）。
- 运行期间使用了运行容器真实口令与实时新增的测试数据（用户 A/B、record 167365、好友请求）——均为 scratch 验证口径，属预期副作用；未触碰开发库既有数据。
- V1c-b 所处通知副作用表 `notification_preference` 缺失为公开环境缺失，不作为本课题的产品结论；如后续需验证通知侧链路，属独立课题。

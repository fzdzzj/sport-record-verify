# TASK-206 任务书：真实跨进程链路验证（V1 归属校验 + V2 refresh cookie）

> 分级预注册（2026-10-12 三档制）：**MEDIUM**——零生产代码/配置/CI/compose 改动，纯验证+台账；
> 免独立复核子 agent（指导亲核+CI 外部终验即收口）；矛盾或断言失败升级 FULL。
> 派发基线：本任务书所在派发笔（契约门 --baseline 参数）。开工前 `git status --porcelain` 确认干净。

## §0 红线（零触碰面）
- 一切生产代码与配置（src/main、pom、application.yml）、ci.yml、mvn-verify.sh、mailbox-contract.sh、docker-compose*、.trivyignore
- 共享中间件容器禁停禁改（MySQL/Redis/RocketMQ/Nacos 沿用在跑实例；数据库用 scratch 库）
- 禁建 docker-compose.override*（历史教训：残留即契约门失败）
- 归档、.codex/、.trae/（.trae/tmp 中间产物用后删）、work/mailbox 既有内容（PLAN.md 纯追加除外）

## §1 验证目标（在册 N 项闭环）
| N 项 | 内容 | 本课题断言面 |
|---|---|---|
| TASK-204 N3 | 四端点归属校验仅纯 JVM 单测 | V1：真实网关→服务 403/200 |
| TASK-205 N1 | cookie 通道仅控制器层断言 | V2：真实 Set-Cookie/轮换/重放 401/登出清除 |

## §2 预注册验证矩阵
### V1 归属校验（gateway+user+record 三进程）
1. 起 jar：user/record/gateway（连已跑中间件+scratch 库；端口冲突 `--server.port` 覆盖；auth 链配置与 compose 演态同源，JWT secret/governance token 按 .env 演态值注入环境变量）。
2. 注册并登录用户 A、B（或 scratch 库既有测试用户）→ 各取 access token。
3. B 创建含轨迹点的 record → 记录 id。
4. **断言 V1a**：A 携 Bearer 访问 `GET /api/records/{B记录id}/points` → HTTP 403。
5. **断言 V1b**：B 携 Bearer 访问同端点 → HTTP 200 且返回轨迹点。
6. （环境稳则加做）A 携 Bearer 对 B 收到的好友申请 `POST /api/friends/requests/{id}/accept` → 403；B 自操作 → 200/业务码成功。

### V2 cookie 通道（同环境）
7. **断言 V2a**：`POST /api/auth/login`（B）→ 响应头 `Set-Cookie` 含 `refresh_token=<值>`、`HttpOnly`、`SameSite=Lax`、`Path=/api/auth`、`Max-Age=604800`；body `data.refreshToken` 为 null。
8. **断言 V2b**：携 cookie `POST /api/auth/refresh` → 200 + 新 `Set-Cookie`（值变化）+ body null + 新 access 可用。
9. **断言 V2c**：旧 cookie 值重放 refresh → 401（轮换防重放真实生效）。
10. **断言 V2d**：携 cookie `POST /api/auth/logout` → 200 + `Set-Cookie` 同名 `Max-Age=0`；此后携该 cookie refresh → 401。

## §3 停止条件（触发即停手回报，禁现场修码/改配置）
1. 任一断言失败 = 潜在缺陷实证 → 记录完整输出（请求/响应/服务日志）后停手，升级指导侧裁决（可能另立 FULL 缺陷课题）。
2. 服务 jar 起不来（依赖缺失/端口/中间件连不通）且无法在不触碰红线内解决。
3. 需要改动任何生产代码/配置/compose 才能推进。
4. 验证行为需要触碰共享中间件容器（停/改）。
5. 环境破坏迹象（中间件异常、其他服务受扰）立即停进程并回报。

## §4 白名单（验证产出仅 4 台账文件，无 C-01 实施笔）
1. spec/changes/prove-authz-cookie-e2e/tasks.json（勾选）
2. work/mailbox/tasks/TASK-206/spec.md（§7 回填）
3. work/mailbox/tasks/TASK-206/handoff.md（新建：验证报告）
4. work/mailbox/PLAN.md（纯追加）
（.trae/tmp 中间产物不提交、用后删；若确需留存 curl 脚本，作为 handoff 附录文本嵌入，不落独立 tracked 文件）

## §6 门禁（MEDIUM 档照跑；零改动的直接证据）
- offline 全量逐位不变：655=52/50/152/157/159/70/15（唯一入口 bash scripts/verify/mvn-verify.sh --mode=offline test，经 D:\git\Git\bin\bash.exe，退出码 3 不得记 PASS）。
- 契约门双态：--open TASK-206 --baseline=<派发笔哈希>；收口无参。
- token 29 项 2030 持平（新文档禁 token 字面量与字面正则）。
- 词面门：CI-exact 口径（-n -I -iE 显式 -E）对台账新文件 ZERO_HIT；本批次探针对照复用（TASK-205 批次已实证 -E 必要性）。
- git diff --check；台账新文件纯 LF+末尾换行；token 时点读数两点（台账笔后+收口）。
- 环境清理留证：服务进程全停（jps/tasklist 证据）、.trae/tmp 空、无 override 残留、工作区 porcelain 空。

## §7 回填（台账笔后填写）
- 台账笔哈希：___（自指，沿 D1 先例）___
- V1a/V1b/（V1c 如做）：V1a=**PASS**(A 携 Bearer 访问 B 的 /points → 403/1002)；V1b=**PASS**(B 访问 → 200 返回轨迹点)；V1c=**a PASS**(A 对 B 收到好友申请 accept → 403)/**b 偏差**(B 自 operation 在归属断言通过后因 `user_db.notification_preference` 表缺失抛 500，属环境数据层缺口，非归属实现缺陷)。V2a/V2b/V2c/V2d：**全 PASS**。
- 服务起停证据（进程清单/时间戳）：起 2026-10-12 13:13（start-services 打印 START_TS=20261012-131310，gateway/user/record 三 jar 启动就绪：gateway 8.859s、user 15.296s、record 24.483s）；端口↔PID 8080→37004、8081→24896、8082→32132；停 2026-10-12 13:20:57 taskkill /F 三 PID 全 SUCCESS，随后 tasklist 无 java.exe、8080-8082 端口空。
- offline 逐位确认：655 = **52/50/152/157/159/70/15**（rc=0）；token：2030 → **2030**（只增不减）。
- 偏差与未覆盖登记：D1 台账笔自指哈希；D2 V1c-b 因 notification_preference 表缺失 500（环境缺口，非断言目标红）；D3 curl jar 空 cookie 400 交互伪影；D4 运行 MySQL 卷口令与 .env 占位不一致（运行时环境变量覆盖）。详见 handoff.md §8。

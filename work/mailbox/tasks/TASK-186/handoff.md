# TASK-186 add-notification-read-receipt 通知已读回执·跨端已读同步 —— 回传笔（handoff / 执行侧）

派发：指导 Agent（2026-10-09）；执行：执行侧。结论见 §2，证据见 §5，提交表见 §8（显式哈希）。push 由指导侧另行授权执行，执行侧不推送。

## 1. 开工规程核验

- **工作树基线全符**：仅两组在途派发材料「spec/changes/add-notification-read-receipt/（提案三件套）+ work/mailbox/tasks/TASK-186/spec.md（任务书）」；另 1 笔未推送提交 `4007fc5`（TASK-185 第 27 次门槛补记笔）零触碰、不 push；`git rev-list --left-right --count origin/main...main` 开工 = `0 1`（派发笔入库后 `0 2`，C-01 后 `0 3`，C-02 后 `0 4`）。
- **红线逐条核验**（§0）：REST 契约零改动（不改 Controller / 路径 / 参数 / 返回语义；不增任何 REST 端点）；不引入 client→server STOMP SEND；零 DB schema、零新依赖（后端零 pom 改动、前端零 lockfile 改动）；零触碰（gateway、compose、ci.yml、mvn-verify.sh、mailbox-contract.sh、client.ts、typed-router.d.ts、App.vue、useNotificationBell.ts、notifications.page.vue、其余六模块）；markRead / markAllRead 返回语义零改动，publish 失败仅告警；markAllRead 只发一条 kind=all 回执，禁逐条发（回执风暴防护，载荷级 O(1)）；词面门正则字面量不入任何 tracked 文档与输出；offline 全量基线 498（36/41/81/127/144/59/10）只增不减（实测 512），--static=record-service 811 不增；唯一 mvn 入口仅 `bash scripts/verify/mvn-verify.sh`；bash 仅 `D:\git\Git\bin\bash.exe` 且只执行脚本文件；逐路径 add、`-F` 消息无 BOM；未触发任何停止条件。

## 2. 一句话结论与三支裁决

**通知已读回执管道（后端扇出+前端订阅）及单测落地，Java offline 新基线 512（user-service 81→95，+14）只增不减，静态 811 持平，web 三件套及 frozen-lockfile 全绿，全门禁通过，判定 PASSED**；契约门在途/无参都 rc=0。外部终验待推送后下一次外部门槛（第 28 次）CI 绿。

### 2.1 三支判定

| 支 | 判定 | 依据 |
| --- | --- | --- |
| PASSED | ✓ 主支 | 管道后端三处 + 前端一处第二队列订阅 + 单测矩阵全绿 + web 三件套 + Java 新基线只增不减（36/41/95/127/144/59/10=512，user-service 81→95）+ static 811 不增 + 契约门在途 rc=0 + 全门禁绿 |
| FAILED | ✗ | 未触发（无停止条件触发、无门禁回归） |
| 外部终验 | 待推送 | 推送后下一次外部门槛（第 28 次）CI 绿；红则按签名归因，禁重试刷绿 |

## 3. 只改清单（与 `git diff --name-only` 逐条比对）

全任务实际足迹 = 派发笔 4 条 + C-01 6 条 + C-02 4 条，去重后共 12 处文件：

**派发笔（执行侧零改动入库）**：
1. `spec/changes/add-notification-read-receipt/proposal.md`
2. `spec/changes/add-notification-read-receipt/tasks.json`
3. `spec/changes/add-notification-read-receipt/specs/sport-record-verify/spec-delta.md`
4. `work/mailbox/tasks/TASK-186/spec.md`

**C-01（实施笔）**：
1. `user-service/src/main/java/com/sportverify/user/ws/NotificationReadReceipt.java`
2. `user-service/src/main/java/com/sportverify/user/ws/NotificationPushRelay.java`
3. `user-service/src/main/java/com/sportverify/user/service/NotificationService.java`
4. `user-service/src/test/java/com/sportverify/user/ws/NotificationPushRelayTest.java`
5. `user-service/src/test/java/com/sportverify/user/service/NotificationServicePushTest.java`
6. `web/src/api/notificationWs.ts`

**C-02（台账收口笔）**：
1. `spec/changes/add-notification-read-receipt/tasks.json`
2. `work/mailbox/tasks/TASK-186/spec.md`
3. `work/mailbox/tasks/TASK-186/handoff.md`
4. `work/mailbox/PLAN.md`

> 零触碰清单：`gateway-service/`、`docker-compose*.yml`、`ci.yml`、`mvn-verify.sh`、`mailbox-contract.sh`、`web/client.ts`、`web/typed-router.d.ts`、`web/src/App.vue`、`web/src/composables/useNotificationBell.ts`、`web/src/pages/notifications.page.vue`、其余六模块 src、`.codex/`、`.trae/`。

## 4. 偏差登记

| # | 偏差 | 性质与处置 |
| --- | --- | --- |
| D1 | **C-02 自身哈希自指**：handoff §8 无法在写文件时预知本笔哈希（TASK-186 执行范围为派发/C-01/C-02 三笔，不另开订正笔）。处置：§8 列出派发笔 `38bbcad`、C-01 `d4ebd92` 显式哈希；C-02 自指为台账收口笔，其显式哈希在本回传报告给出（沿 TASK-182/183/185 台账终态化先例的固有自指事实）。 | 台账提交表终态化固有的单一自指；三笔主题与任务书 §4 逐字一致，无主题偏差。 |
| D2 | **词面门 repo 全量口径**：本仓在历史存档 `spec/changes/archive/add-two-level-cache/tasks.json` 命中既有禁用措辞、`.github/workflows/ci.yml` 自带正则本体——两处均为公开门禁 CI 显式排除路径（见 ci.yml `Public docs wording self-check` 步骤 exclude 集：`spec/changes/archive/**`、`docs/internal/**`、`.github/workflows/ci.yml`）。按 CI 权威口径复扫 repo 全量四形态 ZERO_HIT。本 D2 行不落任何词面门正则字面量（TASK-184 F1 教训）。 | CI 权威口径处置；本任务改动文件集四形态独立 ZERO_HIT 已实测。 |

## 5. 实施证据（含验收要点判据）

### 5.1 回执三态与发布钩子（NotificationService）
- `markRead` 单条标记已读首写成功（受影响行数 > 0）→ 触发已读回执发布 `NotificationReadReceipt.single(userId, id, readAt)`。
- `markAllRead` 全部标记已读成功（影响行数 N > 0）→ 触发已读回执发布 `NotificationReadReceipt.all(userId, readAt)`。
- 影响行数 0（非本人归属 / 通知不存在 / 已是已读态）→ 零回执（`verify(relay, never()).publishRead(any())`）。
- 发布失败仅告警不影响业务返回值（`publishRead` 抛异常时 `markRead` 仍返 true、`markAllRead` 仍返实际行数，尽力而为，前端 60s 轮询兜底）。

### 5.2 回执风暴防护（载荷级 O(1) 设计）
- `markAllRead` 即使一次流转成百上千条通知，也只发布**恰好一条** kind=all 的回执载荷，禁逐条扇出，从载荷设计上根绝已读回执风暴。

### 5.3 跨实例扇出与丢弃兜底（NotificationPushRelay）
- `@PostConstruct` 订阅两 topic：`notification:push` 与 `notification:read`；`@PreDestroy` 双退订对称释放。
- 收到 `notification:read` 广播后反序列化，经 `/queue/notification-read` 投递给在线会话；非法 JSON 或缺失必要字段载荷丢弃告警不抛。
- 多实例无双推：Spring 用户目标注册表只投递本实例持有的会话，无会话静默跳过。

### 5.4 前端订阅与同端幂等双刷（notificationWs.ts）
- `onConnect` 内增订 `/user/queue/notification-read`，与既有 `/user/queue/notifications` 同构，复用同一 `onMessage` 回调。
- 反应同构为预注册设计决策：收到回执与收到新推均触发未读数刷新 + 列表重载。
- **同端幂等双刷说明项**：发起端 A 执行标记已读操作后本地已触发一次刷新；随后收到自己触发的回执又触发一次刷新。由于刷新操作均为只读 GET 请求（`refreshUnread` + `loadList`），幂等无害，不影响界面与数据一致性，登记为设计说明项。

## 6. 逐门实测表

| 门 | 读数 | rc |
| --- | --- | --- |
| tasks.json 语法（收口态） | `json.tool` 可解析；2 tasks 全 passes、steps 全 completed | 0 |
| 词面门（C-01 改动文件集 tracked 四形态） | 四形态全 ZERO_HIT（default/C/zh_CN.UTF-8/C.UTF-8 均 rc=1）；探针三态 HIT rc=0；PROBE_GONE=yes | 1（预期非零） |
| 词面门（repo 全量，CI 权威 exclude 口径四形态） | 四形态全 ZERO_HIT（见 §4 D2） | 1（预期非零） |
| `git diff --check`（C-01 提交前 + C-02 提交前） | 干净，无空白错误 | 0 |
| 契约门在途 `--open TASK-186 --baseline=38bbcad`（C-01 提交前） | 判据 A 两件套 + 1 待办放行 + 判据 B 一致 | 0 |
| 契约门无参（C-02 后复跑） | 判据 A 两件套齐（TASK-186 含 handoff）+ 判据 B 清单一致 | 0 |
| web type-check | `vue-tsc --noEmit` 无输出 | 0 |
| web build | `vite build` ✓ built（TMP 工作区化） | 0 |
| typed-router.d.ts 与提交一致 | `git diff --exit-code -- web/src/typed-router.d.ts` 无声 | 0 |
| lockfile frozen 预演 | `pnpm --dir web install --frozen-lockfile` | 0 |
| offline 全量 `--mode=offline test`（C-01 实施态） | `36/41/95/127/144/59/10` = **512**，Failures/Errors/Skipped 全 0，BUILD SUCCESS（user-service 81→95，+14） | 0 |
| `--static=record-service` | Checkstyle **811**（持平，未增），rc=1 为基线违规模块预期 | 1（预期） |
| token 29 项 | 开工 SUM=1943；收口态实测 SUM=1972，只增不减（见 §7） | 只增不减 |
| 只改清单全等 | 实际改动集恰 §3 清单；`git status --porcelain` 收口后为空 | 全等 |

## 7. token 前后读数（口径 repo 全量 `git grep -cF`）

**开工实测（SUM=1943，TASK-185 收口真值 1943 为参照）**：
`13.4`=152、`18.0`=156、`73.93`=109、`68.8`=103、`6315`=83、`1.8612`=83、`3.3066`=83、`5.7056`=89、`9.408`=74、`36525962432`=67、`36586847965`=64、`36438897772`=66、`36399582548`=64、`36098038547`=62、`2806`=104、`598`=99、`36736221648`=61、`36808102571`=49、`36821040708`=43、`36845152965`=40、`36871294588`=38、`36880083885`=40、`36958994260`=39、`36976873215`=34、`36992632143`=31、`36995450125`=29、`37008317295`=28、`37021305016`=27、`37591580687`=26。

**C-01 后实测（SUM=1943）**：C-01 业务实现与单测均未引入受保护 token 字面量，29 项读数逐位与开工持平。

**C-02 收口复测（SUM=1972，只增不减）**：收口态实测 repo 全量 `git grep -cF` 为 **SUM=1972**（派发笔入库 spec.md §5 纳入 tracked 索引，29 项齐 +1 净增 29 项；台账/handoff/PLAN 纯追加不引入任何受保护 token 字面量；收口读数以收口态实测为准，只增不减）。

## 8. 提交表（显式哈希，禁时效指针）

| 笔 | 提交 | 主题（`-F` 消息文件无 BOM） |
| --- | --- | --- |
| 派发笔 | `38bbcad493fce8d9e9fd53e40eaca29510a26dcf`（`38bbcad`） | `docs(spec): 派发 TASK-186 已读回执提案与任务书` |
| C-01 实施 | `d4ebd926c4383e190522a2fb143c01cfa0dab913`（`d4ebd92`） | `feat(user): 通知已读回执跨端扇出并前端订阅第二队列（TASK-186）` |
| C-02 台账收口 | 本笔（自指，见 §4 D1；显式哈希以本回传报告给出） | `docs(mailbox): 登记 TASK-186 已读回执验收与台账闭环（TASK-186）` |

父锚定：`git rev-list --left-right --count origin/main...main` 开工 `0 1`、派发后 `0 2`、C-01 后 `0 3`、C-02 后 `0 4`（对外显式计数）。push 由指导侧另行授权执行，执行侧不推送。

## 9. 未覆盖项

1. **真实链路联调（可选）UNDETERMINED**：本机网关 + user-service + Redis + 双浏览器标签跨端已读同步未展开（中间件环境不可达）。沿 TASK-182/185 口径登记 UNDETERMINED 不判失败；链路语义等价（双队列订阅、扇出投递、回执三态、风暴防护、断流轮询兜底）由单测逐字覆盖。
2. **同端幂等双刷**（说明项）：操作端 A 收到自己产生的已读回执触发二次刷新，只读 GET 幂等无害，不需做客户端去重。
3. **client→server STOMP SEND**（明确不做）：REST 仍是唯一权威变更通道，帧通道只承载事件扇出。
4. **历史回执补发**（明确不做）：WS 断流期间的已读流转不补发回执，跨端一致性由 60s 轮询兜底自愈。

## 10. 外部终验

推送后下一次外部门槛（GitHub Actions，第 28 次）CI 绿（web 档 + build 档 + 词面门）为外部终验；红则按签名归因（不带预设、禁重试刷绿）。新 run 号以文本登记，不扩受保护 token 集合。

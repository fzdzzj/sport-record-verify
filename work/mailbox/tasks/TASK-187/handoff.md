# TASK-187 add-notification-preference 通知偏好设置·按类型开关 —— 回传笔（handoff / 执行侧）

派发：指导 Agent（2026-10-09）；执行：执行侧。结论见 §2，证据见 §5，提交表见 §8（显式哈希）。push 由指导侧另行授权执行，执行侧不推送。

## 1. 开工规程核验

- **工作树基线全符**：仅两组在途派发材料「spec/changes/add-notification-preference/（提案三件套）+ work/mailbox/tasks/TASK-187/spec.md（任务书）」；另 1 笔未推送提交 `10f46eb`（TASK-186 第 28 次门槛补记笔）零触碰、不 push；`git rev-list --left-right --count origin/main...main` 开工 = `0 1`（派发笔入库后 `0 2`，C-01 后 `0 3`，C-02 后 `0 4`）。
- **红线逐条核验**（§0）：闸门位置严格定于 createNotification 落库前单一权威闸门，被拦截类型不落库、不推送、不计未读；既有 REST 契约零改动，仅新增 GET/PUT /api/notifications/preferences 两端点；WS 管道零触碰（notificationWs.ts / relay / read receipt 零改动）；sql/01-user-db.sql 纯追加 notification_preference 新表，既有表零改动；后端零 pom、前端零 lockfile、零新依赖；NotificationType 常量集合零扩；前端零新页面，typed-router.d.ts / App.vue 零触碰且 typed-router 零漂移；词面门正则字面量绝不写入任何 tracked 文档或输出；offline 全量基线 512 只增不减（实测 527），--static=record-service 811 不增；唯一 mvn 入口仅 `bash scripts/verify/mvn-verify.sh`；bash 仅 `D:\git\Git\bin\bash.exe` 且只执行脚本文件；逐路径 add、`-F` 消息无 BOM；未触发任何停止条件。

## 2. 一句话结论与三支裁决

**通知偏好设置（按类型开关）后端落库前单一权威闸门、读写服务、REST 端点与前端卡片接线落地，单元测试新增 15 条（全仓 512→527，user-service 95→110），静态 811 持平，scratch DB 验证与 web 三件套全绿，全门禁通过，判定 PASSED**；契约门在途/无参均 rc=0。外部终验待推送后下一次外部门槛（第 29 次）CI 绿。

### 2.1 三支判定

| 支 | 判定 | 依据 |
| --- | --- | --- |
| PASSED | ✓ 主支 | 偏好落库前单一权威闸门 + 读写服务与复合主键原子 upsert + GET/PUT 端点 + 前端设置卡片 + 单测矩阵全绿（全仓 527，user-service 95→110，+15）+ scratch DB 验证留证 + web 三件套 + 静态 811 不增 + 契约门在途/无参 rc=0 + 全门禁绿 |
| FAILED | ✗ | 未触发（无停止条件触发、无门禁回归） |
| 外部终验 | 待推送 | 推送后下一次外部门槛（第 29 次）CI 绿；红则按签名归因，禁重试刷绿 |

## 3. 只改清单（与 `git diff --name-only` 逐条比对）

全任务实际足迹 = 派发笔 4 条 + C-01 13 条 + C-02 4 条，去重后共 19 处文件：

**派发笔（执行侧零改动入库）**：
1. `spec/changes/add-notification-preference/proposal.md`
2. `spec/changes/add-notification-preference/tasks.json`
3. `spec/changes/add-notification-preference/specs/sport-record-verify/spec-delta.md`
4. `work/mailbox/tasks/TASK-187/spec.md`

**C-01（实施笔）**：
1. `sql/01-user-db.sql`
2. `user-service/src/main/java/com/sportverify/user/controller/NotificationController.java`
3. `user-service/src/main/java/com/sportverify/user/dto/NotificationPreferenceView.java`
4. `user-service/src/main/java/com/sportverify/user/dto/UpdateNotificationPreferenceRequest.java`
5. `user-service/src/main/java/com/sportverify/user/entity/NotificationPreference.java`
6. `user-service/src/main/java/com/sportverify/user/mapper/NotificationPreferenceMapper.java`
7. `user-service/src/main/java/com/sportverify/user/service/NotificationPreferenceService.java`
8. `user-service/src/main/java/com/sportverify/user/service/NotificationService.java`
9. `user-service/src/test/java/com/sportverify/user/controller/NotificationControllerTest.java`
10. `user-service/src/test/java/com/sportverify/user/service/NotificationPreferenceServiceTest.java`
11. `user-service/src/test/java/com/sportverify/user/service/NotificationServicePushTest.java`
12. `web/src/api/client.ts`
13. `web/src/pages/notifications.page.vue`

**C-02（台账收口笔）**：
1. `spec/changes/add-notification-preference/tasks.json`
2. `work/mailbox/tasks/TASK-187/spec.md`
3. `work/mailbox/tasks/TASK-187/handoff.md`
4. `work/mailbox/PLAN.md`

> 零触碰清单：`gateway-service/`、`docker-compose*.yml`、`ci.yml`、`mvn-verify.sh`、`mailbox-contract.sh`、`web/typed-router.d.ts`、`web/src/App.vue`、`web/src/api/notificationWs.ts`、`web/src/composables/useNotificationBell.ts`、其余六模块 src、`.codex/`、`.trae/`。

## 4. 偏差登记

| # | 偏差 | 性质与处置 |
| --- | --- | --- |
| D1 | **C-02 自身哈希自指**：handoff §8 无法在写文件时预知本笔哈希（TASK-187 执行范围为派发/C-01/C-02 三笔，不另开订正笔）。处置：§8 列出派发笔 `aa7e6aa`、C-01 `7472774` 显式哈希；C-02 自指为台账收口笔，其显式哈希在本回传报告给出（沿 TASK-182/185/186 台账终态化先例的固有自指事实）。 | 台账提交表终态化固有的单一自指；三笔主题与任务书 §4 逐字一致，无主题偏差。 |
| D2 | **词面门 repo 全量口径**：本仓在历史存档 `spec/changes/archive/add-two-level-cache/tasks.json` 命中既有禁用措辞、`.github/workflows/ci.yml` 自带正则本体——两处均为公开门禁 CI 显式排除路径（见 ci.yml `Public docs wording self-check` 步骤 exclude 集：`spec/changes/archive/**`、`docs/internal/**`、`.github/workflows/ci.yml`）。按 CI 权威口径复扫 repo 全量四形态 ZERO_HIT。本 D2 行不落任何词面门正则字面量（TASK-184 F1 教训）。 | CI 权威口径处置；本任务改动文件集四形态独立 ZERO_HIT 已实测。 |

## 5. 实施证据（含验收要点判据）

### 5.1 闸门位置裁决与执行（单一权威闸门）
- 闸门定于 `NotificationService.createNotification` 首行：当用户偏好关闭该类型通知时，直接 `return false`。
- 被拦截时不调用 `notificationMapper.insertIgnore`（不落库）、不调用 `publishPush`（不推送）、不计未读、轮询不可见，全链路一致静默。
- 返回语义扩展：`false = 同 dedup_key 已存在（幂等跳过）或 用户偏好关闭该类型通知`，javadoc 载明；两调用方（`NotificationEventConsumer` 与 `FriendService`）均为 fire-and-forget，忽略返回值，安全无副作用。
- 偏好与通知同库同命运：查询走 PK(user_id, type) 点查，不预设 fail-open 分支。

### 5.2 偏好读写服务与行级原子 upsert
- 复合主键 `(user_id, type)`，不适用单一 `@TableId`，沿用项目 mapper 风格，零新插件。
- `NotificationPreferenceMapper` 使用 `@Insert` 注解原生实现 `ON DUPLICATE KEY UPDATE enabled = VALUES(enabled), updated_at = VALUES(updated_at)`，行级原子幂等。
- `NotificationPreferenceService.getPreferences(userId)`：三类全量视图（`RECORD_VERIFIED`、`RECORD_REJECTED`、`FRIEND_ACCEPTED`），DB 缺行补默认 `enabled=true`（无预填充任务）。
- `NotificationPreferenceService.upsert`：使用服务端时间更新 `updated_at`。

### 5.3 scratch DB 验证（MySQL 8.0 容器实跑留证）
- DDL 执行：`notification_preference` 建表成功，主键 `(user_id, type)` 生效。
- 首次插入新行：`ROW_COUNT() = 1`。
- 重复同值 upsert：`ROW_COUNT() = 0`，`COUNT(*) = 1`，行级原子且无重复行。
- 值翻转 update：`ROW_COUNT() = 2`，更新为目标值 `enabled = 1`。

### 5.4 前端设置卡片与门控
- `web/src/pages/notifications.page.vue` 列表区下内嵌「通知偏好」卡片，三类中文标签（记录通过 / 记录驳回 / 好友通过）各一 `a-switch` 与保存按钮。
- `loggedIn` 门控：未登录展示空态引导，不向后端发起任何 preferences 请求。
- 保存成功使用 Ant Design Vue `message.success` 提示并按接口返回值刷新表单；失败沿 `[code] message` `a-alert` 内规展示。

### 5.5 不追溯与 SETNX 24h 窗口边界说明项
- **不追溯历史**（说明项）：开启偏好仅对未来新通知生效，不追溯补发历史被拦截通知。
- **SETNX 24h 窗口边界**（说明项）：MQ 判定事件被偏好闸门拦截后，Redis SETNX 已置 24h 幂等标记；24h 内若用户重新开启偏好且 MQ 发生重试投递，由于 SETNX 尚未过期将不再重投落库，登记为设计说明项。

## 6. 逐门实测表

| 门 | 读数 | rc |
| --- | --- | --- |
| tasks.json 语法（收口态） | `python -m json.tool` 可解析；2 tasks 全 passes、steps 全 completed | 0 |
| 词面门（C-01 改动文件集 tracked 四形态） | 四形态全 ZERO_HIT（default/C/zh_CN.UTF-8/C.UTF-8 均 rc=1）；探针三态 HIT rc=0；PROBE_GONE=yes | 1（预期非零） |
| 词面门（repo 全量，CI 权威 exclude 口径四形态） | 四形态全 ZERO_HIT（见 §4 D2） | 1（预期非零） |
| `git diff --check`（C-01 提交前 + C-02 提交前） | 干净，无空白错误 | 0 |
| 契约门在途 `--open TASK-187 --baseline=aa7e6aa`（C-01 提交前） | 判据 A 两件套齐 + 1 待办放行 + 判据 B 一致 | 0 |
| 契约门无参（C-02 后复跑） | 判据 A 两件套齐（TASK-187 含 handoff）+ 判据 B 清单一致 | 0 |
| web type-check | `vue-tsc --noEmit` 无输出 | 0 |
| web build | `vite build` ✓ built（TMP 工作区化） | 0 |
| typed-router.d.ts 与提交一致 | `git diff --exit-code -- web/src/typed-router.d.ts` 无声 | 0 |
| lockfile frozen 预演 | `pnpm --dir web install --frozen-lockfile` | 0 |
| offline 全量 `--mode=offline test`（C-01 实施态） | `36/41/110/127/144/59/10` = **527**，Failures/Errors/Skipped 全 0，BUILD SUCCESS（user-service 95→110，+15） | 0 |
| `--static=record-service` | Checkstyle **811**（持平，未增），rc=1 为基线违规模块预期 | 1（预期） |
| scratch DB 验证 | MySQL 8.0 容器实跑建表成功 + 首次插入 1 行 + 重复同值 0 行 + 翻转更新 2 行 | 0 |
| token 29 项 | 开工 SUM=1972；收口态实测 SUM=1972，只增不减（见 §7） | 只增不减 |
| 只改清单全等 | 实际改动集恰 §3 清单；`git status --porcelain` 收口后为空 | 全等 |

## 7. token 前后读数（口径 repo 全量 `git grep -cF`）

**开工实测（SUM=1972，TASK-186 收口真值 1972 为参照）**：
`13.4`=153、`18.0`=157、`73.93`=110、`68.8`=104、`6315`=84、`1.8612`=84、`3.3066`=84、`5.7056`=90、`9.408`=75、`36525962432`=68、`36586847965`=65、`36438897772`=67、`36399582548`=65、`36098038547`=63、`2806`=105、`598`=100、`36736221648`=62、`36808102571`=50、`36821040708`=44、`36845152965`=41、`36871294588`=39、`36880083885`=41、`36958994260`=40、`36976873215`=35、`36992632143`=32、`36995450125`=30、`37008317295`=29、`37021305016`=28、`37591580687`=27。

**C-01 后实测（SUM=1972）**：C-01 业务实现与单测均未引入受保护 token 字面量，29 项读数逐位与开工持平。

**C-02 收口复测（SUM=1972，只增不减）**：台账/handoff/PLAN 纯追加不引入任何受保护 token 字面量，收口态实测 repo 全量 `git grep -cF` 为 **SUM=1972**，只增不减。

## 8. 提交表（显式哈希，禁时效指针）

| 笔 | 提交 | 主题（`-F` 消息文件无 BOM） |
| --- | --- | --- |
| 派发笔 | `aa7e6aa1825b30ae0e4434fdc5954f628d7a1ecf`（`aa7e6aa`） | `docs(spec): 派发 TASK-187 通知偏好提案与任务书` |
| C-01 实施 | `7472774e9c2bc89e101b631f7c8d331605c5ad49`（`7472774`） | `feat(user): 通知偏好按类型开关闸门与设置端点（TASK-187）` |
| C-02 台账收口 | 本笔（自指，见 §4 D1；显式哈希以本回传报告给出） | `docs(mailbox): 登记 TASK-187 通知偏好验收与台账闭环（TASK-187）` |

父锚定：`git rev-list --left-right --count origin/main...main` 开工 `0 1`、派发后 `0 2`、C-01 后 `0 3`、C-02 后 `0 4`（对外显式计数）。push 由指导侧另行授权执行，执行侧不推送。

## 9. 未覆盖项

1. **真实链路联调（可选）UNDETERMINED**：本机网关 + user-service + Redis + 浏览器偏好开关端到端验证未展开（中间件全栈未起）。沿 TASK-182/185/186 口径登记 UNDETERMINED 不判失败；链路语义等价（落库前闸门拦截、静默不推送不落库、默认开启、更新幂等）由单测与 scratch DB 实跑覆盖。
2. **不追溯历史**（说明项）：偏好重新开启仅对未来通知生效，不追溯补发历史。
3. **SETNX 24h 窗口边界**（说明项）：MQ 判定事件被偏好闸门拦截后，Redis SETNX 已置 24h 幂等标记；24h 内若用户重新开启偏好且 MQ 发生重试投递，由于 SETNX 尚未过期将不再重投落库，登记为设计说明项。
4. **偏好变更广播**（明确不做）：低频自服务设置，变更端本地即可见，不做实时 WS 广播。

## 10. 外部终验

推送后下一次外部门槛（GitHub Actions，第 29 次）CI 绿（web 档 + build 档 + 词面门）为外部终验；红则按签名归因（不带预设、禁重试刷绿）。新 run 号以文本登记，不扩受保护 token 集合。

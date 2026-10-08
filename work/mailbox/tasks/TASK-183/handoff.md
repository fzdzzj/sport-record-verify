# TASK-183 add-notification-web-bell 通知 Web 铃铛与通知列表页 —— 回传笔（handoff / 执行侧）

派发：指导 Agent（2026-10-08）；执行：执行侧。结论见 §2，证据见 §5；提交表见 §8（显式哈希）。

## 1. 开工规程核验

- **工作树基线核对全符**：仅两组在途文件「spec/changes/add-notification-web-bell/（提案三件套）+ work/mailbox/tasks/TASK-183/spec.md（任务书）」；另 1 笔未推送提交 `8258d40`（TASK-182 补记登记笔）零触碰、不 push；`git rev-list --left-right --count origin/main...main` 开工 = `0 1`（派发笔入库后 `0 2`，C-01 后 `0 3`，C-02 后 `0 4`）。
- **App.vue 现场与派发笔假设相符**：a-layout-header 内按钮列 + `goTo(path)` 导航函数在位（§0.9 停止条件未触发）。
- **后端契约冻结、零 Java 改动**：未改 api 模块 / 六服务 src / sql / root pom / ci.yml / scripts；`pnpm-lock.yaml` 与 `package.json` 未动（`@ant-design/icons-vue` 已在 dependencies）。
- **未触发任何停止条件**：type-check / build 红均非本任务代码所致（详见 §4 D1 环境工具面）；无需改后端契约 / api 模块 / 锁文件 / client.ts 既有函数签名。

## 2. 一句话结论与三支裁决

**五 file 齐（client.ts 尾部纯追加 / useNotificationBell.ts 新增 / App.vue 顶栏铃铛 / notifications.page.vue 新增 / typed-router.d.ts 再生成入库），web 三件套全绿 + Java 抽验不回归，判定为 PASSED**（三支见 §2.1）；唯一环境面偏差为 build 需把 TMP 指到可写目录（§4 D1），非代码问题。外部终验待推送后下一次外部门槛 CI 绿。

### 2.1 三支判定

| 支 | 判定 | 依据 |
| --- | --- | --- |
| PASSED | ✓ 主支 | web 三件套全绿（type-check rc=0 / build rc=0 / typed-router.d.ts 与提交一致）+ Java 抽验不回归（offline 480 逐位、static 811 持平）+ 验收要点全达成（未读数来自 /unread-count 且操作后即时同步、未登录零请求、5003 直接展示、空列表正常态） |
| FAILED | ✗ | 未触发（无 type-check / build 红，无 Java 基线漂移） |
| 外部终验 | 待推送 | 推送后下一次外部门槛 CI 绿（web 档 + build 档双绿）；红则按签名归因，禁重试刷绿 |

## 3. 只改清单（与 `git diff --name-only` 逐条比对）

全任务实际足迹 = 派发笔 4 条 + C-01 5 条 + C-02 4 条，分笔如下：

**派发笔（执行侧零改动入库）**：
1. `spec/changes/add-notification-web-bell/proposal.md`
2. `spec/changes/add-notification-web-bell/tasks.json`
3. `spec/changes/add-notification-web-bell/specs/sport-record-verify/spec-delta.md`
4. `work/mailbox/tasks/TASK-183/spec.md`

**C-01（前端实现笔）**：
1. `web/src/api/client.ts`
2. `web/src/App.vue`
3. `web/src/composables/useNotificationBell.ts`
4. `web/src/pages/notifications.page.vue`
5. `web/src/typed-router.d.ts`

**C-02（台账收口笔）**：
1. `spec/changes/add-notification-web-bell/tasks.json`（闭环全勾）
2. `work/mailbox/tasks/TASK-183/spec.md`（§7 收口记录纯追加）
3. `work/mailbox/tasks/TASK-183/handoff.md`（本文件）
4. `work/mailbox/PLAN.md`（纯追加 §验收记录）

> 零触碰清单：Java 侧一切、`web/package.json`、`web/pnpm-lock.yaml`、`ci.yml`、`scripts/`、`client.ts` 既有行（仅尾部追加新类型与新函数）、`docker-compose*.yml`。`git status --porcelain` 收口后为空。

## 4. 偏差登记

| # | 偏差 | 性质与处置 |
| --- | --- | --- |
| D1 | **C-01 build 首跑 rc=1：esbuild 在系统 `%TEMP%` 清理临时目录 `remove ... Access is denied`**（`C:\Users\fzdzzj\AppData\Local\Temp\esbuild-*`）。3279 模块全部 transform 成功，失败仅在 esbuild 服务临时目录删除这一 OS 文件权限面；同一 `TMP/TEMP/TMPDIR` 指到仓内 `.trae/esbuild-tmp` 后 build rc=0，`dist/` 正常产出。直测 PowerShell 在 `%TEMP%` 建/删文件不受限，判定为本机 Windows 环境工具面（esbuild 服务目录句柄/权限）而非代码问题。 | 环境工具面工作区化处置：构建用仓内可写 TMP（`.trae/esbuild-tmp`，gitignored，用毕保留于 gitignored 目录）；非代码、非任务书偏差，登记不进 FAILED。type-check / build 绿为权威读数。 |
| D2 | 台账提交表终态化：C-02 自身哈希因自指无法在写文件时预知，按本仓 TASK-182 C-04b/c 先例追加 C-02b/C-02c 两笔零构建面订正——回填 C-02 与 C-02b 显式哈希、订正父锚定 `0 3`→`0 5`（含开工基线 `8258d40` 的 1 笔 ahead）。 | 台账提交表终态化（单一自指固有事实的仓库惯例处置）；C-01/C-02 主题串与任务书 §4 逐字一致，无主题偏差。 |

## 5. 实施证据（含验收要点判据）

### 5.1 未登录零请求判据
- `useNotificationBell.refreshUnread()` 首行 `if (!hasAccessToken()) { unread.value = 0; return }` —— 未登录不发起任何 `/user/api/notifications/*` 请求，铃铛 `unread` 恒 0 且无徽标数值触发。
- `notifications.page.vue`：`loggedIn = computed(() => hasAccessToken())`；`loadList()`/`onMounted` 仅在 `loggedIn` 时调用，未登录顶部展示引导登录 a-alert、表格空态、不发请求。

### 5.2 未读数同步判据
- 模块级单例 `unread = ref(0)` 由 App.vue（读）与通知页（操作后 `refreshUnread()`）共享同一实例，不引入状态库、不用事件总线。
- 单条已读成功 → `record.isRead = 1` 行内翻转 + `await refreshUnread()`；全部已读成功 → 展示 `本次流转 N 条`（取 `res.data`）+ `loadList()` + `refreshUnread()`。铃铛徽标与页面即时同步。
- 刷新策略（登录态）：挂载首拉 + `setInterval` 60s + `document.visibilitychange`（visible→刷新、hidden→跳过本轮 interval 回调，`onPollTick` 内判 `document.visibilityState==='visible'`）。

### 5.3 5003 展示判据
- 单条/全部已读 catch：`errorMessage = e.code ? \`[${e.code}] ${e.message}\` : (e.message||'操作失败')` 以 a-alert 展示；单条失败列表不变、行状态不翻转（`record.isRead` 仅成功后才置 1）。沿 friends.page.vue 错误码区范式。5003＝不存在/已读/非本人。

### 5.4 鉴权与契约
- 四函数均走 `/user/api/notifications` 前缀、不传 userId（身份由网关注入的 X-User-Id 认定，`client.ts` 既有注释「userId omitted when auth」沿 friends/leaderboard/record 先例）。Bearer / 401 refresh 沿 client.ts 既有封装零改动。
- `NotificationViewDTO` 八字段与后端 `NotificationView` 对齐：id / type / sourceId / title / content / isRead / createdAt / readAt（isRead: number，createdAt/readAt: string 可空）。

## 6. 逐门实测表

| 门 | 读数 | rc |
| --- | --- | --- |
| tasks.json 语法（收口态） | `json.tool` 可解析；2 tasks 全 passes、steps 全 completed | 0 |
| 词面门（C-01 五改动文件集 tracked 四形态） | 四形态全 ZERO_HIT（default/C/zh_CN.UTF-8/C.UTF-8 均 rc=1）；撤排除对照 HIT rc=0；探针三态 rc=0；PROBE_GONE=yes | 1（预期非零） |
| `git diff --check`（C-01 提交前 + C-02 提交前） | 干净，无空白错误 | 0 |
| 契约门在途 `--open TASK-183 --baseline=8258d40`（派发笔前） | 判据 A 两件套 + 1 个待办放行 + 判据 B 一致 | 0 |
| 契约门在途 `--open TASK-183 --baseline=1a92f26`（C-01） | 判据 A 两件套 + 1 个待办放行 + 判据 B 一致 | 0 |
| 契约门待收口无参（C-02 后复跑） | 判据 A 两件套齐（TASK-183 含 handoff）+ 判据 B 清单一致 | 0 |
| web type-check（收口） | `vue-tsc --noEmit` 无输出 | 0 |
| web build（收口） | `vite build` `✓ built in ~16s`，`dist/` 产出 | 0 |
| typed-router.d.ts 与提交一致 | `git diff --exit-code -- web/src/typed-router.d.ts` 无声 | 0 |
| offline 全量 `--mode=offline test`（收口） | `36/41/63/127/144/59/10` = **480**，Failures/Errors/Skipped 全 0，BUILD SUCCESS | 0 |
| `--static=record-service` | Checkstyle **811**（持平，未增），rc=1 为基线违规模块预期 | 1（预期） |
| token 29 项 | 开工实测 SUM=451；收口复测只增不减（见下） | 只增不减 |
| 只改清单全等 | C-02 实际改动集恰 §3 四条；`git status --porcelain` 收口后为空 | 全等 |

## 7. 收口门自我取证

- **G1 web 三件套**：type-check rc=0；build rc=0（TMP 工作区化，见 §4 D1）；typed-router.d.ts `git diff --exit-code` 无未入库漂移。
- **G2 Java 抽验**：offline 全量 480（`36/41/63/127/144/59/10`）逐位 rc=0 全绿零跳过（模块 33 无；verify=144、leaderboard=59、mapmatch=10……与派发笔基线逐位一致，Java 零改动证不回归）；`--static=record-service` Checkstyle 811 持平 rc=1 预期。
- **G3 契约门**：C-02 提交后 `bash scripts/verify/mailbox-contract.sh` **无参** → rc=0（判据 A 两件套齐 + 判据 B 清单一致）。
- **G4 词面门**：C-01 改动文件集 + tracked 四形态 ZERO_HIT rc=1 + 探针三态 rc=0。
- **G5 token**：PLAN.md 追加后 29 项计数 ≥ 开工实测 SUM=451（追加文本不含任何 token 字面量），只增不减。

**token 开工实测（SUM=451）**：`13.4`=22、`18.0`=24、`73.93`=23、`68.8`=23、`6315`=20、`1.8612`=19、`3.3066`=19、`5.7056`=19、`9.408`=19、`36525962432`=19、`36586847965`=18、`36438897772`=19、`36399582548`=18、`36098038547`=18、`2806`=25、`598`=18、`36736221648`=17、`36808102571`=12、`36821040708`=10、`36845152965`=9、`36871294588`=9、`36880083885`=10、`36958994260`=10、`36976873215`=10、`36992632143`=9、`36995450125`=7、`37008317295`=8、`37021305016`=9、`37591580687`=8。

## 8. 提交表（显式哈希，禁时效指针）

| 笔 | 提交 | 主题（`-F` 消息文件无 BOM） |
| --- | --- | --- |
| 派发笔 | `1a92f26e5ec8d1bbd696dc2a62e5e67310074963` | `docs(spec): 派发 TASK-183 通知 Web 铃铛提案与任务书` |
| C-01 前端实现 | `4a2486524a8fe9d75cd99f676264499c5aa2322f` | `feat(web): 通知铃铛与通知列表页接入通知中心（TASK-183）` |
| C-02 台账收口 | `5f8a420634d65209d2108f7300bfe3d5aa9c0bca` | `docs(mailbox): 登记 TASK-183 前端验收与台账闭环（TASK-183）` |
| C-02b 提交表终态化订正 | `a5c7f969dec52656d1eafd5b40ca0d8a1987e360` | `docs(mailbox): 终态化 TASK-183 台账笔提交表（TASK-183）` |
| C-02c 父锚定订正 | `5ee8f95ef69a72637c20e9834b605ce5fb5ac50e` | `docs(mailbox): 订正 TASK-183 台账父锚定为五笔终态（TASK-183）` |

父锚定（终态）：`git rev-list --left-right --count origin/main...main` = `0 6`（开工基线 `8258d40` TASK-182 补记笔 1 笔 + 本任务派发笔 + C-01 + C-02 + C-02b + C-02c 五笔 = 6）. 派发笔入库后 `0 2`；C-01 后 `0 3`；C-02 后 `0 4`；C-02b 后 `0 5`；C-02c 后 `0 6`；开工 `0 1`。push 由指导侧另行授权执行，执行侧不推送。C-02b/C-02c 均为台账提交表的哈希与父锚定订正（零构建面、仅改 handoff §8，沿本仓 TASK-182 C-04b/c 先例）；C-02c 自身为终态手尾提交，其哈希与由此追加的总计数（`0 7`）以本回传报告的显式哈希为准（沿 TASK-182 终态手尾先例，不预写自身表项）。

## 9. 未覆盖项

1. **真门 + 网关登录态的手动功能演示未在本机展开**：本任务全为前端改动，功能演示需网关+服务 `app.auth.enabled=true` 且登录会话有效（index 页既有演示要求）；本机 auth=false 时通知查询返回空列表属既定降级行为（与 friends / leaderboard 同口径），登记为说明项不判失败（§6.4）。web 无单测框架（package.json 无测试脚本），如实登记不虚构单测数。
2. 实时推送（WebSocket/SSE）不在范围，铃铛新鲜度由 60s 轮询承载（沿 TASK-182 边界）。
3. 点赞 / 排榜变动通知仍属后端二期候选，本变更只消费既有三类（RECORD_VERIFIED / RECORD_REJECTED / FRIEND_ACCEPTED）。

## 10. 外部终验

推送后下一次外部门槛（GitHub Actions）CI 绿（web 档 + build 档双绿）为外部终验；红则按签名归因（不带预设、禁重试刷绿）。第 25 次外部门槛的新 run 号以文本登记，不扩受保护 token 集合。

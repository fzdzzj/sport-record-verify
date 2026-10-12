# TASK-210 回传交接（设置页通知偏好 UI，纯前端）

## 1. 编号、基线与外部状态

- 课题：add-notification-preference-ui（设置页「通知偏好」开关区块，零后端）。
- 分级：**MEDIUM**（纯前端零核心逻辑；指导亲核 + CI 外部终验收口，免独立复核）。
- 开工基线：`a581972`（派发笔，含提案三件套 proposal/spec-delta/tasks.json 与任务书；开工前 `git status --porcelain` 为空，基线自洽）。
- 未 push、未建 PR；外部 CI 终验待推送后由指导侧补记。

## 2. 只改清单（C-01 恰 2 文件；C-02 恰 4 台账文件；两笔合计 6 文件 = 从 `a581972` 的改动集）

C-01（feat(web) 实施笔 `bc0c6a2`）：

- README.md
- web/src/pages/settings.page.vue

> R1 产物 client.ts 的 `getNotificationPreferences` / `updateNotificationPreferences` 为
> TASK-187 既有实现（TASK-187 已在 client.ts 尾部纯追加两函数且经契约门验证），本次仅做
> 契约一致性代码审查取证，不改动该文件——故 C-01 改动集不含 client.ts。

C-02（docs(mailbox) 台账笔，本 handoff 所在提交）：

- spec/changes/add-notification-preference-ui/tasks.json（勾选）
- work/mailbox/tasks/TASK-210/spec.md（§7 回填）
- work/mailbox/tasks/TASK-210/handoff.md（本新文件）
- work/mailbox/PLAN.md（纯追加）

## 3. 一句话结论

设置页（`/settings`）改密表单下方新增「通知偏好」卡片：`onMounted` 经
`getNotificationPreferences()`（GET `/user/api/notifications/preferences`）加载，按响应
动态渲染每行一个 `a-switch`（类型列表不硬编码，仅对 `type` 做人性化文案映射）；切换时
`updateNotificationPreferences([{type, enabled}])`（PUT 同路径单行批次）成功 `message`
反馈并以响应回填本地态；加载失败非致命提示 + 空区块。README 鉴权节补一行 UI 入口。
后端零改动；全部 §2.5 判别式 + §6 门禁按 MEDIUM 档留证。

## 4. 判别式红绿（R1/R2/G1/G2）

- **R1（绿，契约一致性代码审查取证）**：`web/src/api/client.ts:300-309`——
  `getNotificationPreferences()` 用 `api.get('/user/api/notifications/preferences')`；
  `updateNotificationPreferences(prefs)` 用 `api.put('/user/api/notifications/preferences', body)`，
  body 为 `Array.isArray(prefs)?{preferences:prefs}:prefs`（即 `{preferences:[{type,enabled}]}`），
  与任务书 §1 后端契约逐项对上：GET 路径 `/api/notifications/preferences`（client 侧带网关前缀
  `/user`，与既有 `listNotifications` 同 `/user/api/notifications` 前缀范式一致）、PUT 同路径 +
  body `{preferences:[{type,enabled}]}`。路径/方法/body 形态三元一致，无越界。
- **R2（绿，动态渲染判别）**：settings.page.vue 新增偏好区块——
  ① 渲染 `v-for="item in prefRows"`，`prefRows` 由 `onMounted(loadPreferences)` 的 GET 响应
  `(res.data||[])` 灌入，**源码无硬编码类型数组**（`TYPE_LABELS` 仅作文案映射，不决定渲染哪些行）；
  ② `a-switch` `@change` → `onPrefToggle(item)` PUT `[{type, item.enabled}]` 单行批次；
  ③ 成功后 `message.success` 并以 PUT 响应体回填 `prefRows` 本地态；失败回滚开关 + `message.error`；
  ④ 加载失败 `prefLoadError` 非致命提示 + 空区块，不阻断改密。
- **G1（绿）**：`pnpm run type-check`（`vue-tsc --noEmit -p tsconfig.app.json` 在 web 目录）**rc=0**。
  （本机未跑 `pnpm build`；S6 口径「vue-tsc rc=0 或 CI web job 权威」已满足，无 esbuild 受阻记录。）
- **G2（绿/核空）**：`git diff a581972..HEAD`（user-service/gateway-service/api/common/pom/sql/ci.yml/
  scripts/verify 等全部后端与基础设施路径）**全空**，纯前端零后端改动（审计证据见 §5.1 与 §8）。

## 5. §6 门禁留证

1. **offline 全量逐位**：唯一入口 `D:\git\Git\bin\bash.exe scripts/verify/mvn-verify.sh --mode=offline test` →
   **BUILD SUCCESS rc=0，逐位 675 = 52/50/162/157/159/80/15 复测确认**（纯前端零后端改动，各模块计数与基线一致，无回归）。
2. **契约门双态**：在途 `--open TASK-210 --baseline=a581972` rc=**0**（判据 A 两件套齐含 1 个待办进行中 = TASK-210 仅 spec 列待办放行；判据 B 一致）；收口无参 rc=**0**。
3. **token 29 项 2030**：见 §6（开工/close 2030；post-C01 实测 2030，只增不减）；本课题新文档零受保护 token 字面量。
4. **词面门**：CI-exact（-n -I -iE 显式 -E）`git grep -n -I -iE 正则 -- README.md web/src/pages/settings.page.vue`
   对改动文件集 ZERO_HIT rc=**1**；合成探针阳性对照（`git add` 后含词样文 grep 同词表）HIT rc=**0**，证明 -E/-i 口径有效、无假阴性。
5. **`git diff --check`** 均 rc=**0**；两笔 `git show --check` rc=**0**。
6. **行尾**：settings.page.vue 保持基线 LF；README.md 保持基线 CRLF；handoff.md 新文件纯 LF + 末尾换行、无 BOM。
7. **web**：`pnpm run type-check`（vue-tsc --noEmit）rc=**0**。

## 6. token 时点读数

- token 29 项 tracked `git grep -cF` 全量 SUM：开工=**2030**、post-C01=**2030**、close=**2030**（只增不减；新文档零 token 字面量）。

## 7. 异常与偏差登记

- **D1（台账笔自指哈希）**：本 handoff 无法记录 C-02 自身哈希，沿 TASK-178/183/204/205/206/207/208/209
  同型先例，以 C-02 实际提交哈希为准（`git log --oneline`）。
- **D2（R1 产物为既有代码）**：client.ts 两偏好函数为 TASK-187 既有实现，本次仅代码审查取证
  （路径/方法/body 与任务书 §1 逐项对上），未改动该文件；C-01 改动集因此为 2 文件（settings.page.vue + README.md），
  落在任务书 §4 白名单 2-3 文件目标内。
- **D3（word-check 探针机制）**：git grep 仅扫描 tracked 文件，untracked 探针样文不可见；探针阳性对照
  经 `git add` 后 grep 含词样文得 HIT rc=0、随后 `git rm --cached` 还原（无残留），证明 -E/-i 口径有效。
- **停止条件（§3）**：无一条触发——R1 artist 契约一致无需改后端、无新范式冲突、vue-tsc 类型错误不涉白名单外文件。

## 8. 收口门禁（C-02 后亲跑）

- 契约门**无参 rc=0**；token close=**2030**；`git diff --check` rc=0；`git diff --name-only a581972` 对照恰 **6 文件**
  （C-01 2 + C-02 4），对 api 模块/gateway/common/各服务/父 pom/sql/ci.yml/mvn-verify.sh/mailbox-contract.sh/compose **git diff 全空**。
- 工作树 `git status --porcelain` 干净（`.trae/` 排除、`.trae/tmp` 已清空）。

## 9. 未覆盖与不得推出的结论

- `--mode=online`、CI 全流程、真实浏览器可视交互、真实 token 会话下 toggle→PUT 联调链路未跑；
  未 push、未建 PR，无 CI run，外部终验待指导侧推动推送后补记（MEDIUM 档：指导亲核 + CI 外部终验收口）。
- **无性能/行为断言**：本任务不改变后端通知偏好语义，仅新增设置页视觉入口；开关状态以服务端返回为准。
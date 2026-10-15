# TASK-236 handoff：社交与点赞交互补齐——动态流与记录详情页点赞交互组件与计数实时同步（MEDIUM 档）

## 1. 编号、基线与外部状态

- 任务编号：`TASK-236`（外约：`work/mailbox/tasks/TASK-236/spec.md`；本文件为回传）。
- 分级：**MEDIUM**（前端核心交互与状态同步、边界防御与 E2E 强化）。
- 开工基线：`69cb306`（工作树干净；开工前仅本任务 `spec.md` 未跟踪）。
- C-01 实施笔：`b6699f2`（提交信息 `feat(web): TASK-236 ...（C-01 实施笔）`，恰 4 文件 = 白名单实施面全等）。
- C-02 台账笔：本 handoff 所在提交（`docs(mailbox)`，自指哈希沿 TASK-220/221/224/231/232/233/234/235 先例，实际以 `git log` 为准）。
- 两笔均**未 push、未建 PR**。

## 2. 只改清单（相对开工基线 69cb306，恰 6 文件 = C-01 4 + C-02 2）

C-01（实施笔，恰 4 文件 = 白名单实施面全等）：

- web/src/pages/feed.page.vue
- web/src/pages/record-detail.page.vue
- web/e2e/feed.e2e.ts
- web/e2e/helpers.ts

C-02（`docs(mailbox)` 台账笔，恰 2 文件）：

- work/mailbox/tasks/TASK-236/spec.md
- work/mailbox/tasks/TASK-236/handoff.md

> 白名单外零触碰：生产后端六服务 `src/main` 与 `src/test`、`api/`、`common/`、根 pom、`package.json`、`playwright.config.ts`、`work/mailbox/PLAN.md` 与 `work/mailbox/HANDOVER.md` 均零 diff；零新依赖。临时验证日志落 gitignored `.trae/tmp/`，不入库。

## 3. 一句话结论

动态流与详情页点赞闭环一次补齐：① `feed.page.vue` 每张动态卡片操作行新增点赞按钮（已赞态 `type="primary"` 高亮 + 计数 `赞 (n)`/`已赞 (n)`），列表加载完毕后经 `Promise.allSettled` 异步调取当前页记录的点赞态（单条失败不影响整页渲染与列表可用性），点击经 per-card loading 锁防重、按当前态调 `likeRecord`/`unlikeRecord` 并以服务端返回的 `liked/likeCount` 同步本地态，失败 `message.error`（6001 特化「未通过校验的记录不可点赞」），未登录或 `status` 非 2/5 置灰 + Tooltip 防呆；② `record-detail.page.vue` 操作区（与「导出 GPX」并列）新增点赞组件，`load()` 中摘要与点赞态 `Promise.allSettled` 并发调取、失败静默降级不阻断轨迹与配速渲染（403/6001 安全降级），点击一键切换实时同步计数与高亮，未登录或 verdict 已知非 PASSED 置灰防呆；③ `helpers.ts` 的 like 路由改为动态状态记忆（`Stores.likes` Map：GET 返回当前态、POST 赞 +1、DELETE 踩 -1，初始 0 赞/未赞），`feed.e2e.ts` 补齐点赞交互断言（按钮可见且展示计数 → 点击已赞高亮 + POST 确发 → 再点取消 + DELETE 确发），原有分页与列表断言不受影响，全量 **12 passed** 全绿。前端 type-check 0 错误 / 32 测 / build / e2e 全绿，白名单恰 6 文件，零新依赖。

## 4. 判别式与验收要点

- **R1 动态流点赞交互与状态同步（绿）**：卡片操作行与「查看详情」并列点赞按钮，`likeLabel` 显示 `赞 (n)`/`已赞 (n)` 且已赞态 `type="primary"` 高亮；`loadList` 成功后 `loadLikes()` 以 `Promise.allSettled` 对当前页记录调 `getRecordLike`，rejected 单条静默跳过；`toggleLike` 以 per-card `likeLoadingMap` 锁防重复，按 `likeMap[id].liked` 分支调 `unlikeRecord`/`likeRecord`，成功以 `res.data` 覆盖本地 `{ liked, likeCount }`；失败 `message.error`（6001 特化提示）；未登录或 `status` 非 2/5 时按钮 `disabled` + Tooltip（`请先登录` / `未通过校验的记录不可点赞`）。
- **R2 详情页点赞交互（绿）**：`record-detail.page.vue` 与「导出 GPX」并列点赞按钮 + 计数（`赞 (n)`/`已赞 (n)` 高亮）；`load()` 内 `getVerifyResult` 与 `getRecordLike` 以 `Promise.allSettled` 并发调取，点赞态失败静默降级不阻断轨迹与配速渲染（403/非通过 6001 安全降级）；`toggleLike` 一键切换并实时同步计数与高亮；未登录或 verdict 已知且非 PASSED 置灰 + Tooltip 防呆，verdict 未知（摘要降级）时放行点击、由后端权威判定（6001 提示兜底）。
- **R3 E2E 强化与契约对齐（绿）**：`helpers.ts` 新增 `Stores.likes` Map 动态状态记忆，`/record/api/records/:id/like` 路由 GET 返回当前态、POST `liked=true` 且计数 +1、DELETE `liked=false` 且计数 -1（下限 0）；`feed.e2e.ts` 新增断言：卡片点赞按钮可见且展示 `赞 (0)` → 点击后按钮转 `已赞 (1)` 且 `POST /record/api/records/2000/like` 恰 1 次 → 再点恢复 `赞 (0)` 且 DELETE 恰 1 次；原有分页与列表断言不受影响，全量 **12 passed** 稳定通过。
- **R4 红线（绿）**：`git diff --name-only 69cb306` + untracked 恰 6 文件全等于 §2 清单，无多报无漏报；零新依赖（仅复用 ant-design-vue `a-button`/`a-tooltip`/`message` 与既有样式）。

## 5. 验证命令与输出片段

1. **前端 type-check**：`pnpm --dir web type-check` → rc=0（`vue-tsc --noEmit -p tsconfig.app.json`，0 错误）。
2. **前端单测**：`pnpm --dir web test` → rc=0：`Test Files 4 passed (4)` / `Tests 32 passed (32)`（vitest）。
3. **前端构建**：`pnpm --dir web build`（`TMP/TEMP` 重定向 `.trae/tmp`，沿 TASK-224/231/232/233/234/235 同款处置）→ rc=0：`✓ built in 30.38s`（chunk >500 kB 为既有非阻断 advisory）。
4. **前端 E2E 全量**：`pnpm --dir web e2e`（同 TMP/TEMP 重定向）→ **`12 passed (1.3m)`，rc=0**（chromium 单 worker，vite preview 生产产物 + route-mock；`pnpm build` 由 webServer 命令内嵌执行）。
5. **契约门禁**：`bash scripts/verify/mailbox-contract.sh --open=TASK-236 --baseline=69cb306`（`D:\git\Git\bin\bash.exe`，Git Bash 可用实跑）→ 退出码 0：判据 A 两件套齐（spec.md + handoff.md 均在）+ 判据 B 只改清单与实际改动集一致（`git diff --name-only 69cb306` + untracked 恰 6 文件全等）。

## 6. 未覆盖、遗留风险与不得推出的结论

- **点赞态非实时全局同步**：仅当前页可见记录异步拉取点赞态；翻页/刷新后重新拉取，跨页计数以服务端为准（无 WebSocket 点赞推送，与 TASK-235 通知推送面正交）。
- **详情页点赞防呆以 verify-result 为代理**：`record-detail.page.vue` 无记录 `status` 直读，以 `verifyResult.verdict`（1=PASSED 覆盖 通过/复审通过 两类可赞记录）作防呆代理；verdict 未知（摘要降级）时放行点击、由后端 6001 兜底提示——前端不绕过、服务端归属/状态校验为权威。
- **e2e 点赞断言未覆盖置灰态**：feed 夹具 22 条 status 全为 2/5（全部可赞），且 `fixtures/feed.ts` 不在白名单内不可改，故 E2E 未断言非通过置灰路径；置灰/防呆逻辑由 type-check + 代码评审承载。
- **`--mode=online` / `--static`（Checkstyle/SpotBugs/PMD）CI 全流程未跑**（未 push、未建 PR），外部门禁待指导侧推动后补记。

## 7. 偏差登记

- **D1（行尾规约）**：本机 `core.autocrlf=true`，写入编辑器产生 bare LF，`git add` 时归一 CRLF（warning 属预期）；`git diff --numstat 69cb306` 复核全为行级小 diff（feed.page.vue +81/-2、record-detail.page.vue +97/-10、helpers.ts +25/-6、feed.e2e.ts +12/-0），无整文件重写残迹。
- **D2（e2e 与 build 串行规避 webServer 超时）**：沿 TASK-234 D3 / TASK-235 D4 先例，`pnpm --dir web build` 与 `pnpm --dir web e2e` 串行执行（CPU 抢占会导致 preview 未在 180s webServer 窗口内起服）；本次 build 30.38s 后独立跑 e2e，`12 passed (1.3m)`。

## 8. 停止条件

白名单内实施与测试改动完成，验证命令全绿（前端 type-check 0 错误 + 32 测 + build + e2e 12 passed），台账两件套落盘并经契约门禁退出码 0；`PLAN.md` / `HANDOVER.md` 与白名单外文件零触碰。后续如需推进 CI push、外约脚本 bash 实跑留证（本机 Git Bash 可用已实跑）、feed 夹具增加非通过条目以 E2E 断言置灰态、点赞态跨页实时推送，须新开任务，不在本任务扩大范围。

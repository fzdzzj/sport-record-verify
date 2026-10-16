# TASK-257 台账（handoff）

> **TASK**：E 系批4b-2——前端测试可观测三项（E8+E14+E26，MEDIUM 档）
> **开工基线**：`1772102`（TASK-256 封盘笔，main HEAD；开工自证 `git rev-parse HEAD` = 1772102f437c1f9cc23f655da2103f842f454f78、`git status --porcelain` 为空）
> **方案**：2026-10-16 E 系批4b 指导侧立项侦察三项全部坐实；批4b-2 纯前端批次
> **状态**：已实施完成（2026-10-17：E8/E14/E26 三判别式全落地，七门禁全绿，双提交落库待指导侧亲核收口 + CI 外部终验；实施侧零推送零 PR）

## 1. 开工自证（实施侧回填）

- 开工基线核验：`git rev-parse HEAD` = `1772102f437c1f9cc23f655da2103f842f454f78`（= 派发基线 1772102）、`git status --porcelain` 为空，两件套齐。基线哈希已回填 spec「开工基线」行与本档引言行。
- 白名单条件项定档（E14 清障暴露面，C-01 前定档、C-01 提交体登记）：
  1. 既有 8 个 e2e 用例文件接入 `assertNoUnmocked(ctx)`（spec §2 明文豁免的 unmocked 扩展 + §3 R2「接入全部用例」要求）：delete-record / feed / filter-pagination / goal-card / login-redirect / record-list / record-submit / top-nav，各在既有 assertNoUnhandled 调用旁追加 1 行 + import 行扩名，共 12 调用点。
  2. 基线红订正（1772102 纯净 worktree 实测复现 3 failed，非本笔引入；订正以最小面同步既有断言）：feed.e2e `RUNNING` 断言自 TASK-245 B27 徽标化起基线即红 → 订正 `'🏃 跑步'`；goal-card.e2e `'距离：32.5 / 50 km'`/`'32.5 / 60 km'` 自 TASK-242 B19 距离两位小数格式化起基线即红 → 订正 `'32.50 / 50.00 km'`/`'32.50 / 60.00 km'`（沿 spec §2「如需动以条件项登记」机制）。
  3. unmocked 清障补 mock 三端点（此前静默兜底 404，本笔 no-unmocked 断言暴露，暴露面以既有用例真实调用为界）：`POST /record/api/records/likes/batch`（feed loadLikes 静默吞错）、`GET /user/api/friends/requests`（friends 页待处理申请，空页 mock）、`GET /user/api/user/profile`（settings 页挂载 loadProfile）。沿 TASK-250 条件项先例登记。

## 2. 只改清单全等声明（实施侧回填）

- git diff --name-only 1772102 与 spec §2 白名单双向全等核验：C-01 恰 18 文件 = spec §2 业务/测试 10 项 + 条件项 8 文件（§1 定档）；台账 2 项仅 C-02 阶段动；与白名单逐项双向全等，无漏改。
- 改动清单（20 项 = C-01 业务/测试 18 + C-02 台账 2，逐行路径 token 供判据 B 机器比对）：
  - web/src/api/client.ts
  - web/src/api/__tests__/client.test.ts
  - web/README.md
  - web/e2e/helpers.ts
  - web/e2e/fixtures/notifications.ts
  - web/e2e/notifications.e2e.ts
  - web/src/pages/leaderboard.page.vue
  - web/src/pages/feed.page.vue
  - web/src/pages/index.page.vue
  - web/src/pages/notifications.page.vue
  - web/e2e/delete-record.e2e.ts
  - web/e2e/feed.e2e.ts
  - web/e2e/filter-pagination.e2e.ts
  - web/e2e/goal-card.e2e.ts
  - web/e2e/login-redirect.e2e.ts
  - web/e2e/record-list.e2e.ts
  - web/e2e/record-submit.e2e.ts
  - web/e2e/top-nav.e2e.ts
  - work/mailbox/tasks/TASK-257/spec.md
  - work/mailbox/tasks/TASK-257/handoff.md
- 未跟踪文件核验（C-01 前 `git ls-files -o --exclude-standard`）：恰白名单第 6 项新建文件一项；.trae/tmp/ 属 gitignore 不纳入比对。

## 3. 门禁读数（实施侧回填）

| # | 门禁 | 命令 | rc | 关键读数 |
| --- | --- | --- | --- | --- |
| 1 | 后端 offline 全量七模块 | `mvn-verify.sh --mode=offline test` | 0 | BUILD SUCCESS；surefire 63/78/215/302/200/108/35 与开工基线逐位持平（派发覆盖口径：spec §4 所记 59/75/205/302/195/108/35 为立项旧值，实际基线 63/78/215/302/200/108/35，TASK-256 已刷新）——白名单零后端文件 + 实测复核双证 |
| 2 | 前端 type-check + vitest | `pnpm type-check` / `pnpm test` | 0 | vue-tsc 0 错；4 文件 63 测全绿（既有 61 + E8 新增 2） |
| 3 | 前端 E2E 全量 | `pnpm e2e` | 0 | 14/14 全绿（既有 12 用例 + notifications 新增 2），48.1s；零真实网络断言保持；基线红 3 用例订正后随全量绿（§1 条件项 2） |
| 4 | 契约门 --open=TASK-257 --baseline=1772102 | `mailbox-contract.sh --open=TASK-257 --baseline=1772102` | 0 | C-01 后态 rc=0（TASK-257 台账未动判已收口）；C-02 后复跑见 §7 |
| 5 | token 门 29 项 | `git grep -cF <token>` 逐项（29 token 清单沿 TASK-175 §7 + 28/29 项追加笔） | — | SUM 基线 1772102 = 2017 vs 工作树 = 2017，逐位持平、零减少、改动集零命中（口径：匹配行数累加，TASK-254 已登记行命中/文件出现次数口径差非阻断） |
| 6 | 词面门（模式文件法 + 探针阳性对照） | `git grep -n -I -iE -f <模式文件>`（CI 权威口径：8 词 + 三排除路径） | — | 权威口径 ZERO_HIT（rc=1 通过态）；阳性探针（非 gitignore 的 .txt 落仓根，含两禁词）`--untracked` rc=0 恰 2 行命中后即删（首探针 .tmp 被 .gitignore L34 忽略致 --untracked 跳过，换 .txt 后坐实） |
| 7 | diff 净 | `git diff --check` / `git show --check` | 0 | C-01 前后均净；新文件 notifications.e2e.ts 末字节 0x0A、零 CR；两 Write 重写文件（fixtures/notifications.ts、notifications.e2e.ts）零 CR；改文件保持既有行尾 |

## 4. 判别式逐项实施登记（实施侧回填）

- R1（E8）：`client.ts` 模块级单例 `refreshPromise` + `sharedRefresh()`（首个 401 发起 `POST /api/auth/refresh` withCredentials、并发 401 await 同一 Promise、settled 后 finally 清引用）；`/api/auth/*` 跳过清单与 `_retry` 单请求单次语义不变；失败路径每败者 catch 触发 triggerAuthFail 各一次（与不共享基线同计数不放大）；refresh 200 但 body 缺 accessToken 语义保持（不重试、透传原错误、不触发 auth-fail）。`client.test.ts` 新增：R1 两并发 401 恰一次 refresh + 两原请求各以新 token 重试成功（adapter 恰 5 调用、businessAuths 旧 2 新 2）；R2 两并发 401 refresh 失败恰一次 refresh + 零重试 + failHandler 恰 2 次。README L51/L61 语义同步（含「并发 401 共享单次刷新，败者不再清赢家 token」）。
- R2（E14）：`helpers.ts` ApiCtx 增 `unmocked` 列表（handleRecord/handleUser 两兜底 404 前 `ctx.unmocked.push`）+ `assertNoUnmocked` 导出并接入全部用例（§1 条件项 1）；`fixtures/notifications.ts` 静态常量改 `createNotificationStore` 状态记忆（13 行 id 61..49，未读恰 3 = id 55/50/49 派生自列表，第 1 页恰 1 未读保 top-nav strict 断言；markRead/batchRead/markAllRead/clearRead 全链路流转）；补两写端点 mock（batch-read 返回流转条数、read 返回删除条数）；`notifications.e2e.ts` 新建两 describe：通知页列表渲染 + 标已读/批量已读/清空已读三按钮真实点击（徽标 3→2→0 收敛、批量 2 条流转、清空 13 行至空态、写请求各恰 1 次）+ unmocked/unhandled 双零断言。
- R3（E26）：leaderboard.loadLb / feed.loadList / index.loadRecords / notifications.loadList 四加载函数内联局部 `loadSeq` 序号守卫（前置自增捕获、await 返回后序号不一致丢弃——不写数据态/错误态、finally 仅当前序号清 loading、错误分支同守卫、index 越界回退的第二次 await 后同守卫），不抽 composable；`notifications.e2e.ts` 增 leaderboard 快速切换用例（独立 route 装配——/leaderboard/api/* 不在两前缀 mock 内；好友榜首 zset 请求延迟 3s fulfill、总榜后发即时；断言越过延迟时点后终态仍为后发数据、陈旧响应零渲染；实测 4.2s 稳定，未触发 §5 时序降级）。

## 5. 受限与偏差声明（实施侧回填，对照 spec §5 三项预注册）

1. E8 真实弱网并发未实测（§5.1 预注册）——单测以 mock axios 时间片收敛语义承载（并发 401 共享判定依赖微任务序，Vitest 真实拦截器链全执行）；真实浏览器多组件并发 401 场景无 E2E 覆盖。
2. E26 E2E 时序模拟稳定（§5.2 降级条款未触发）：radio 单选钮「点击已选中项不触发 change」用例内消化（初值 overall → 首击好友榜发起延迟请求、再击总榜即时），延迟 3s vs 即时 fulfill 竞态余量充足，连续两轮全量 E2E 均绿。
3. E14 unmocked 清障以既有用例真实调用面为界（§5.3 预注册）——三端点补 mock 均有既有用例真实调用为证（top-nav 好友页/settings 页、feed.e2e 点赞态）；未覆盖页面（无 e2e 用例者）不做穷举扩面。
- 另（环境旁注，沿 TASK-256 先例）：Bash 脚本执行经 `D:\git\Git\usr\bin\bash.exe --login`（`bin\bash.exe` 因本机 WSL ext4.vhdx 挂载故障不可用）；`pnpm build`/`pnpm e2e` 需 `TMP/TEMP` 重定向仓内 `.trae/tmp/build-tmp`——C 盘用户 Temp 下 esbuild 超 1MB 输入临时文件（esbuild-<hash>）删除被拒 EACCES 稳定复现，重定向后构建/14 用例全过（环境级异常，零仓库文件触碰，临时目录用后清）。

## 6. 提交与哈希（实施侧回填）

- C-01（实施）：`033110a` — `fix(web): E系批4b-2前端测试可观测三项（TASK-257）`（18 文件，+431/−37，恰 spec §2 白名单 10 项 + 条件项 8 文件；条件项三块全登记于提交体）
- C-02（台账）：提交时自指哈希不可知，沿 git log 后补先例由封盘笔/终验笔落库（本笔仅 spec.md + handoff.md 纯回填）

## 7. 行尾与自证（实施侧回填）

- 新文件 `web/e2e/notifications.e2e.ts` 纯 LF + 完整末尾换行（末字节 0x0A、CR 计数 0，od 实测）；fixtures/notifications.ts 重写后零 CR；改文件保持既有行尾；`git diff --check` rc=0、`git show --check HEAD` rc=0；.trae/tmp 临时件（tokens257.txt / lex-pattern257.txt / c01-msg.txt / offline 日志 / 探针）用后清理。
- 完成 C-02 后 `git status --porcelain` 为空自证（原文见收官汇报）。

# TASK-227: E2E 真实浏览器验证批（H1，P6，MEDIUM）

派发基线：TASK-226 完成后接续（同一执行序）。工作树干净；token 基线 SUM=2036。提案三件套=spec/changes/e2e-browser-batch/。

## S0 红线与例外

- **S0 明列例外（沿 typed-router 先例格式）**：web/package.json 与 web/pnpm-lock.yaml 允许触碰（Playwright devDependency + e2e script）；除此之外 web 配置、vite.config.ts、typed-router.d.ts（无新路由）、一切后端/Java/sql/pom/ci.yml/scripts/compose 零触碰。
- E2E 本地执行，**不入 CI**；证据与 trace 落 gitignored .trae/tmp。
- Playwright 引入重议裁决（预注册）：TASK-215 裁定不引入时前端债小，现 9 课题积账+交互面扩大，重议通过；chromium 单浏览器，版本锁定。

## S1 已知事实

web 为 vue3+vite+arco；API 前缀 /record/api/* 与 /user/api/*（client.ts）；页面：登录、index（记录列表/统计/目标卡片/过滤条）、feed、record-detail、notifications（recon 核对页面全集与路由）。

## S2 预注册设计

- e2e/ 目录：playwright.config.ts（chromium、webServer 起 vite dev 或 preview）、fixtures/（用户/记录/目标/feed 夹具）、8 条 spec（登录跳转/记录列表渲染/提交表单校验与提交/筛选联动+重置页码/删除确认与刷新/feed 卡片与分页/目标卡片与对话框/顶栏导航）。
- route-mock：page.route 拦截两前缀 API 返回夹具；零后端依赖。
- package.json：devDependency playwright + @playwright/test，scripts.e2e；浏览器二进制不入库（.gitignore 确认）。

## S2.5 判别式

R1 依赖落地（pnpm install 成功、版本锁定、lock 同步）；R2 8 条 spec 全绿（每条断言核心交互而非仅页面加载）；R3 route-mock 覆盖两前缀零真实网络请求（断言 no-unhandled）；R4 web 四件套不回归（test 31/type-check/build 3283/typed-router 零 diff）；R5 证据留痕（运行输出+至少 1 条 trace 落 .trae/tmp 并在 handoff 登记）；R6 后端零 diff。

## S3 停止条件

1. Playwright 安装或 chromium 下载被网络阻断且无镜像可用。
2. route-mock 无法承载某交互（需真实后端）→ 该条登记未覆盖并降清单，不引入后端。
3. 需改 vite.config/路由/后端才能跑通 → 停止上报。

## S4 白名单（预估 12-16）

web/package.json、web/pnpm-lock.yaml、web/e2e/**（config+fixtures+8 spec）、web/.gitignore（如需）、README；台账 4 文件。逐字符全路径登记。

## S6 门禁（MEDIUM，web-only）

web 四件套 + E2E 套件全绿 + typed-router 零 diff + diff --check + 行尾（新文件纯 LF 无 BOM）+ token 2036 持平 + 词面门 ZERO_HIT/探针正控 + 契约双态（--open TASK-227 --baseline=<开工 HEAD>）。零 Maven（政策 web-only 档）。独立复核四块（用户派发）；指导亲核后与 TASK-226 同批推送，CI 绿为收口前提。

## S7 回填

判别式红绿 + E2E 逐条清单证据 + 偏差 + 未覆盖（真实后端全栈联调仍留 H2/后续、CI 集成未做、多浏览器未覆盖）。

### 回填实测（2026-10-14，C-02 台账笔）

- **首轮停止史（如实登记）**：本课题首轮于 S3.3 停止上报——E2E 批在真实浏览器下无法跑通，根因为**应用启动级路由缺陷**（`createRouter({history})` 未带 `routes`，`unplugin-vue-router@0.19.2` 已不再注入文件路由表），故首轮未提交任何文件，停止笔链 = `e1610de`（停止上报：`docs(mailbox): TASK-227 E2E 批停止上报（应用启动级路由缺陷阻断，S3.3）`）→ `69602b0`（停止报告补记：完整修复候选恰 2 文件已实测全绿）。该缺陷另立 TASK-228 并已先于本续批落地（C-01 `05bcae4` / C-02 `138ec97`），本续批以 `138ec97` 为开工基线重启，从 gitignored `.trae/tmp/t227-preserved/` 保留件恢复套件与依赖并按 S2.5 判别式复跑。
- **续批结论**：Playwright（`@playwright/test` 1.64.0 精确锁版）落地，8 条 route-mock spec、12 用例在真 chromium 下 **12 passed rc=0**；两前缀外真实网络请求零命中；web 四件套不回归；后端零 diff；E2E 仅本地执行、不入 CI。
- **判别式红绿**：R1 绿（`pnpm install --frozen-lockfile` rc=0 且 `Lockfile is up to date`，安装后 lock 零 diff，版本精确锁定）；R2 绿（8 文件 12 用例全绿，逐条断言核心交互：守卫跳转/列表与统计卡/表单校验门与提交结果/筛选重置页码/删除确认与刷新/feed 翻页/目标卡片三用例/顶栏导航与退出）；R3 绿（`page.route` 拦截 `/record/api/**` 与 `/user/api/**` 返回夹具；`page.on('request')` 记录全部后端前缀请求，两前缀之外记入 `unhandled`，`assertNoUnhandled` 断言为空——12 用例共 20 处调用，零真实网络请求）；R4 绿（`pnpm test` 4 文件 31 测 rc=0 / `pnpm type-check` rc=0 / `pnpm build` 3317 modules rc=0 / `typed-router.d.ts` 零 diff rc=0）；R5 绿（运行输出 + 12 份 trace 落 gitignored `.trae/tmp/e2e-results/` 并在 handoff 登记）；R6 绿（后端与 ci.yml、`web/vite.config.ts`、`web/src/**` 相对开工基线零 diff）。
- **R4 数值口径订正**：S2.5 预注册的 build 期望值 3283 写在 TASK-228 修复之前；TASK-228 落地后 `vue-router/auto-routes` 模块被真正引用并打入产物，正确读数 3317 modules（内容面不回归不变：单测 31 持平、type-check 零错、typed-router 零 diff、build 成功）。
- **偏差**：D1（保留件的 `scripts.e2e` 为 `playwright test`，实测在本仓 cwd 下无法发现 `e2e/` 子目录配置——Playwright 只在当前目录找配置，会误扫 vitest 单测并报 `No tests found`，故按 S2「scripts.e2e」意图补 `--config e2e/playwright.config.ts` 一行，白名单内 `web/package.json`，不涉 `vite.config.ts`/路由/后端）；D2（契约门基线口径：用户指定 `--baseline=4596df2`，但判据 B 以单一改动集对**每个**在途回传做全量比对，跨课题基线必然把已收口课题 TASK-228 的 handoff 判为「改动集未声明」，故沿 TASK-224/225/226 先例以本课题真实开工时点 `138ec97` 为基线复跑，两版读数均如实登记）；D3（契约门判据 B 的清单提取器扩展名白名单不含无扩展名 dotfile，`web/.gitignore` 无法被表达为清单 token，故以 `--diff-file` 显式给出 21 项可表达改动集复跑；该文件在 S4 白名单内且已逐字符登记于 §8）；D4（临时件落 gitignored `.trae/tmp`）。明细见 `handoff.md §14`。
- **停止条件**：S3 三条均**未按字面触发**——Playwright 与 chromium 本机可用（无网络阻断）；route-mock 承载全部 12 条交互，无一条需真实后端（无需降清单）；未改 `vite.config.ts`/路由/后端即跑通。
- **未覆盖清单**：真实后端全栈联调（--it 层，仍留 H2/后续）；CI 集成（E2E 明列不入 CI，`ci.yml` 零触碰）；多浏览器（仅 chromium）与移动视口；榜单页 `/leaderboard/api/*` 交互（不在两前缀 mock 范围，避免真实网络请求，候选后续扩展）；WebSocket（`/user/ws-notifications`）推送路径未覆盖。见 `handoff.md §15`。

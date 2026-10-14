# TASK-228 handoff：web 启动级路由缺陷修复（P0 发现，MEDIUM 小档）

## 1. 编号、基线与外部状态

- 课题：TASK-228，变更目录 `spec/changes/fix-web-router-boot`，任务书 `work/mailbox/tasks/TASK-228/spec.md`。
- 开工基线：`4596df2`（TASK-228 派发笔；其下 `69602b0`/`e1610de` 为 TASK-227 停止笔链，勿动）。开工时工作树干净，本地领先 origin 7 笔随批推送。
- 发现链：TASK-227 S3.3 停止上报（E2E 批无法跑通，根因为应用启动级路由缺陷）。
- C-01 实施笔：`fix(web)` 笔（恰 2 文件，见 §2），哈希以 `git log` 为准。
- C-02 台账笔：本 handoff 所在提交（`docs(mailbox)`，自指哈希沿 TASK-220/221/222/223/224/225/226 先例）。
- 两笔均**未 push、未建 PR、未触发 CI**；本机门禁全绿后交指导侧亲核，随批推送。

## 2. 只改清单（相对开工基线 4596df2，恰 6 文件 = C-01 2 + C-02 4，逐字符声明）

C-01（fix(web) 实施笔，恰 2 文件 = S0 红线全等）：

- web/src/main.ts
- web/src/vue-router-auto-shim.d.ts

C-02（docs(mailbox) 台账笔，本 handoff 所在提交，恰 4 文件）：

- spec/changes/fix-web-router-boot/tasks.json
- work/mailbox/tasks/TASK-228/spec.md
- work/mailbox/tasks/TASK-228/handoff.md
- work/mailbox/PLAN.md

> 下列载体零触碰（G2 证据见 §5）：一切页面/组件/路由源码（`web/src/pages/**`、`web/src/App.vue`、`web/src/router/**`）、`web/src/typed-router.d.ts`（`git diff --exit-code` 零 diff）、`web/vite.config.ts`、`web/tsconfig.app.json`、`web/package.json`、`web/pnpm-lock.yaml`、一切后端/Java/sql/pom、`.github/workflows/ci.yml`、`scripts/`、`docker-compose*`。本修复零依赖变更、零配置变更。
> 临时件（挂载探针与门禁脚本、日志、读数）均落 gitignored `.trae/tmp`，不入库、不计入改动集。

## 3. 一句话结论

定位并修复应用启动级路由缺陷：`unplugin-vue-router@0.19.2` 已不再向 `vue-router/auto` 注入带文件路由表的 `createRouter`（其 `package.json#exports` 无 `./auto`，运行时契约改为 `vue-router/auto-routes`），而 `web/src/main.ts` 仍以 `createRouter({ history })` 调用 —— `routes` 为 `undefined`，vue-router 的 `createRouterMatcher` 在 `routes.forEach` 处抛错，应用**根本挂载不上**（任何页面白屏，TASK-227 E2E 因此全线阻断）。修复 = 恰 2 文件：`main.ts` 改为从 `vue-router` 取 `createRouter` 并显式传入 `routes`（`vue-router/auto-routes`），垫片删除会掩盖该缺陷的 `Omit<RouterOptions,'routes'>` 声明、补 `auto-routes` 的值导出类型。修复后 dev 与 preview 双形态真 chromium 挂载探针 PASS（pageerror 0），web 四件套全绿、typed-router 零 diff、门禁全过。

## 4. 判别式红绿（R1-R4 逐条）

**R1 绿（根因复现 + 修复后双形态可挂载）**

- 根因证据（静态、可复现，命令行 `node -e` 断言）：
  - `web/node_modules/unplugin-vue-router/package.json` `version=0.19.2`，`exports` 键共 15 个：`.` `/vite` `/webpack` `/rolldown` `/rollup` `/esbuild` `/options` `/runtime` `/types` `/data-loaders` `/data-loaders/basic` `/data-loaders/pinia-colada` `/volar/sfc-route-blocks` `/volar/sfc-typed-router` `/client` —— **`./auto` 与 `./auto-routes` 均不存在**（`hasOwnProperty` 双断言皆 false）。
  - `web/node_modules/vue-router/package.json` `version=4.6.4`，`exports` 含 `./auto` 与 `./auto-routes`；`./auto` 落到 `node_modules/vue-router/vue-router-auto.d.ts`，全文 36 字节、单行注释 `// augmented by unplugin-vue-router` —— 纯类型占位、零运行时，注释里的“augmented”在 0.19.2 已不成立。
  - 插件产物内 `const MODULE_ROUTES_PATH = \`vue-router/auto-routes\`;` —— 证明 0.19.2 只提供 `auto-routes` 模块，不再改写 `vue-router/auto` 的 `createRouter`。
  - 抛错点：`node_modules/vue-router/dist/vue-router.mjs` L617 `function createRouterMatcher(routes, globalOptions)` → L747 `routes.forEach((route) => addRoute(route))`；`routes === undefined` 时即抛 `Cannot read properties of undefined (reading 'forEach')`。
- 修复前实测（红，dev 形态真 chromium，日志 `.trae/tmp/t228-mount-red.log`，rc=1）：根路径匿名访问未被改写到 `/login`（finalUrlPath=`/`），`#app` childElementCount=0、innerHTML 长度 0、input 0、button 0、body 文本长度 0，`pageerror` 2 条且**两条均为** `Cannot read properties of undefined (reading 'forEach')` —— 与上条抛错点完全对应；`MOUNTED=false RESULT=FAIL`。
- 修复后实测（绿，日志 `.trae/tmp/t228-mount-dev.log` / `t228-mount-preview.log`，rc 均 0）：
  - dev 形态（`pnpm dev --port 5199 --host 127.0.0.1`，vite v7.3.6）：根路径匿名访问被守卫改写到 `/login`（证明路由表存在且匹配成功），`#app` childElementCount=1、innerHTML 长度 6896、input 2、button 11、`pageerror` 0、console error 0、`BOOT_ERROR_MATCHES=0`、`MOUNTED=true RESULT=PASS`。
  - preview 形态（`pnpm build` 生产产物 + `pnpm preview --port 4179 --host 127.0.0.1`）：同上形制，innerHTML 长度 6163、input 2、button 11、`pageerror` 0、`MOUNTED=true RESULT=PASS`。
  - 双形态均以“同一份源码、同一套真 chromium 探针”跑通，差异仅 dev 走 vite 开发服务、preview 走构建产物，排除“只在某一种形态下偶然可挂载”。

**R2 绿（web 四件套 + typed-router 零 diff）**

- `pnpm test` rc=0：Test Files 4 passed (4)、Tests 31 passed (31)（与基线 31 持平，日志 `.trae/tmp/t228-test.log`）。
- `pnpm type-check` rc=0（`vue-tsc --noEmit -p tsconfig.app.json`，零 error TS，日志 `.trae/tmp/t228-typecheck.log`）。
- `pnpm build` rc=0：3317 modules transformed / built（基线 3283；增量来自 `vue-router/auto-routes` 真实路由表模块被真正打进产物——修复前该模块从未被引用，日志 `.trae/tmp/t228-build.log`）。
- `git diff --exit-code -- web/src/typed-router.d.ts` rc=0（零 diff：本修复不新增/变更任何路由，生成物未变，日志 `.trae/tmp/t228-typed.log`）。

**R3 绿（恰 2+4 文件全等）**：C-01 恰 2 文件、C-02 恰 4 文件，与 §2 逐字符一致；越界面 diff 全空，无第三个实施文件（见 §9 的改动集核对）。

**R4 绿（token / 词面 / 契约 / diff --check / 行尾）**：逐项见 §5-§6；功能面交互（登录/列表/提交/筛选/删除/feed/目标/导航 12 用例）由同批 TASK-227 E2E 承载，本课题仅负启动级挂载。

## 5. §6 门禁留证（MEDIUM 小档）

命令与 rc 逐项（读数落 `.trae/tmp/t228-gates.log`）：

1. **web 四件套**：`pnpm test` rc=0 / `pnpm type-check` rc=0 / `pnpm build` rc=0 / `git diff --exit-code -- web/src/typed-router.d.ts` rc=0（见 R2）。
2. **双形态挂载证据**：dev rc=0（PASS）+ preview rc=0（PASS），另附修复前 dev 红 rc=1（见 R1）。
3. **契约门双态**：在途 `--open TASK-228 --baseline=4596df2` **rc=0**（判据 A：TASK-228 仅 spec，已声明放行；判据 B：TASK-227 回传文件相对该基线无变动，已收口不重审）；收口无参 **rc=0**（见 §9）。
4. **token 门**：29 项字面量在 `4596df2` 与工作树两点的 `git grep -cF` 计数和**逐位持平**（读数见 `.trae/tmp/t228-gates.log`；字面量本身按硬约束不入库、不入回报正文）。
5. **词面门**：改动集（6 文件，闸门时点 5 个已存在）`git grep -iE -f pattern` **ZERO_HIT rc=1**；探针阳性对照 **HIT rc=0**（正控形态订正见 D2）。
6. **`git diff --check`**：工作树 rc=0 且暂存区 rc=0（无空白/行尾污染）。
7. **行尾核对**：`web/src/main.ts` 工作树 CRLF 形态（autocrlf checkout 形态、blob LF）、字节 1389 / CR 44 / 末尾 0x0A / 无 BOM；`web/src/vue-router-auto-shim.d.ts` 字节 1264 / CR 21 / 末尾 0x0A / 无 BOM —— 两文件均**保持基线行尾形态**（改动前后同为 CRLF，`git diff` 工作树 vs 索引为空、无行尾噪声 diff）。新增台账文件纯 LF、末尾 0x0A、无 BOM。
8. **零依赖/零配置**：无 `pnpm add`，`web/package.json`/`web/pnpm-lock.yaml`/`web/vite.config.ts`/`web/tsconfig.app.json` 全部零 diff。

## 6. token 时点读数

29 项字面量：`4596df2`（基线）与工作树两点计数和持平，逐项未变；读数与逐项命令落 `.trae/tmp/t228-gates.log`。字面量清单与数值按硬约束不写入本 handoff、不入任何 tracked 文件。

## 7. 实现要点（恰 2 文件）

- `web/src/main.ts`：`import { createRouter } from 'vue-router'` + `import { routes } from 'vue-router/auto-routes'`；`createRouter({ history: createWebHistory(), routes })`。语义变化 = 路由表由“插件隐式注入”改为“显式注入”，这是 unplugin-vue-router 0.19.x 的现行契约。
- `web/src/vue-router-auto-shim.d.ts`：`declare module 'vue-router/auto'` 只保留 `export * from 'vue-router'`（`App.vue` 与各页面仍从该子路径取 `useRouter`/路由类型），**删除** `export function createRouter(options: Omit<RouterOptions,'routes'>): Router` —— 该声明正是掩盖本缺陷的根因（它让 `createRouter({history})` 通过类型检查，却在运行时抛错）；新增 `declare module 'vue-router/auto-routes' { export const routes: RouteRecordRaw[] }`（生成的 `typed-router.d.ts` 只声明 `RouteNamedMap` 等类型、无 `routes` 值导出，故需此值声明；这也是“仅改 main.ts”的朴素修法报 TS2305 的原因）。
- 恢复方式：以 `git apply --cached` 应用保留件 `full-fix-candidate.patch` 后再 `git checkout-index -f` 落地工作树，保证索引 blob 与保留件 post-image 逐字节一致，且工作树保持 autocrlf 的 CRLF 基线形态（不产生行尾噪声）。三处 pre-image blob 哈希核对一致：`web/src/main.ts` 42b187a、`web/src/vue-router-auto-shim.d.ts` 26ebcdf（patch `index` 行同值）。

## 8. 异常与偏差登记

- **D1（环境面，非代码面）**：`pnpm build` 在系统 `%TEMP%` 下偶发失败——`[vite:esbuild-transpile] remove C:\Users\...\Temp\esbuild-<hash>: Access is denied`（esbuild 清理自身临时文件被拒），rc=1，但同次已 `3317 modules transformed`；清空该临时目录后重跑仍复现。将 `TMP`/`TEMP` 重定向到 gitignored `.trae/tmp/esbtmp` 后 `pnpm build` **rc=0（3317 modules / built）**。判定为宿主环境（临时目录杀软/文件锁）面问题，与本次改动无关；本课题全部构建读数均在该重定向下取得（TASK-226 同批日志为未重定向下的 rc=0，属间歇）。本机 `%TEMP%` 普通文件的自建/自删探针正常（created/deleted 皆 True），故非目录 ACL 问题。
- **D2（正控形态订正）**：TASK-226 门禁脚本内的词面门阳性对照采用 `git grep -f pattern -- <probe 文件>`，而探针文件落 gitignored `.trae/tmp`（未跟踪）——`git grep` 默认只搜索引/已跟踪文件，该形态**必然 rc=1（假阴性）**，TASK-226 日志内 `PROBE positive: FAILED (rc=1)` 即此故。本课题改用与 `t227-final-gates.ps1` 一致的正控形态：以同一 pattern 构造 `[regex]::IsMatch("test <首选项> probe")` 断言引擎可命中，得 HIT rc=0。判定：订正门禁脚本缺陷，非放宽门禁（改动集 ZERO_HIT 仍由 `git grep -f pattern` 实跑给出）。
- **D3（探针依赖时点）**：双形态挂载探针 `.trae/tmp/t228-mount.cjs` 经 `createRequire('web/package.json')` 直接消费 `web/node_modules` 内已存在的 `@playwright/test`（1.64.0，上一轮 TASK-227 尝试的安装残留）+ 本机 chromium。TASK-228 时点 Playwright **尚未入库**（属 TASK-227 白名单），故探针**不入库**、零 tracked 影响；本课题未新增任何依赖，`web/package.json` 与 lock 零 diff。
- **D4**：临时脚本、挂载日志、门禁日志、读数均落 gitignored `.trae/tmp`，收尾不删（供独立复核与后续课题复用）。
- **D5**：C-02 自指哈希沿先例（handoff 内不写自身提交哈希，以 `git log` 为准）。

## 9. 收口门禁（C-02 后）

- 改动集核对：`git diff --name-only 4596df2` 恰 6 路径，与 §2 逐字符全等；`git status --porcelain` 干净；无未跟踪的非 `.trae` 文件。
- 契约门**收口无参** rc=0（全台账两件套齐 + 在途回传清单一致）。
- 词面门 / token 门 / `git diff --check` / 行尾：与 §5 同形制复跑，逐项 rc 见 `.trae/tmp/t228-gates.log` 与 `t228-contract-close.log`。

## 10. 未覆盖与不得推出的结论

- **未跑**：CI 全流程（两笔未 push、未建 PR，故无 run 编号）；`--mode=online` 依赖解析。
- **未覆盖**：真实浏览器**功能交互面**（12 用例）不在本课题范围——由同批 TASK-227 E2E 承载；多浏览器（仅 chromium）、移动视口、可访问性未覆盖；dev HMR 下的路由热更新未单独验证。
- **不得推出**：不得据本课题得出“E2E 已通过”的结论（TASK-228 只证明启动级挂载与路由表存在）；不得据 3317 modules 得出体积/性能结论；不得据本机双形态 PASS 得出跨平台（Linux/CI）结论。
- 后端零改动，未跑任何 Maven（web-only 政策档）。

## 11. 复核后补记（订正笔，2026-10-14）

独立复核发现本笔三处证据面表述需如实化。原文一律保留、不追溯改写，订正只做「改字 + 登记」，以免抹去时点差本身：

- **① 在途契约门读数的时点边界**：§5.3 与 §9 所记在途 `--open TASK-228 --baseline=4596df2` **rc=0**，系 `beabc6f`（TASK-227 C-01 E2E 批）落地**之前**的时点读数，非恒常结论。在 HEAD=`fd6b299` **干净工作树**（独立 worktree，`git status` 零改动）复算该命令**恒 rc=1**；`--baseline=69602b0`（§S6 原记口径）同为 **rc=1**。归因＝契约门判据 B 以**单一累积改动集**对**每个**在途回传做全量比对，而这两个基线之上 TASK-228 与 TASK-227 两课题**串批**，两回传互将对方文件判为「改动集未声明」（对 TASK-227 回传列 6 项、对 TASK-228 回传列 22 项）——**非清单违规、非契约违反**，是工具全局比对语义与跨课题基线的冲突（TASK-227 handoff §14-D7 同型登记）。本课题契约证据改由两处承载：**相对开工基线 `4596df2` 的 6 文件改动集全等**（§2 逐字符声明）+ **收口无参 rc=0**（工作树干净、全台账两件套齐，见 §9）。复算读数落 `.trae/tmp/wt-head-readings.log`、`.trae/tmp/wt-head-open228.log`、`.trae/tmp/wt-head-open228-69602b0.log`。
- **② `exports` 键数订正（16 → 15，枚举项不变）**：§4 R1 原文「`exports` 键共 16 个」为误记，正文已改字为 **15 个**；其下逐键枚举的 15 个子路径（`.` `/vite` `/webpack` `/rolldown` `/rollup` `/esbuild` `/options` `/runtime` `/types` `/data-loaders` `/data-loaders/basic` `/data-loaders/pinia-colada` `/volar/sfc-route-blocks` `/volar/sfc-typed-router` `/client`）**一字未动**，`./auto` 与 `./auto-routes` 均不存在的根因结论不变。`spec.md` §7 回填内的同一误记数字不在本笔授权改动面内，此处仅登记，未改其正文。
- **③ 重复件删除（避免双跑互证误读）**：删除 `.trae/tmp/t228-contract-open-substantive.log` —— 其 SHA256 与 `.trae/tmp/t228-contract-open.log` **逐位相同**（`BE2383A52C88637BC6D7EBE9B37D4927AAF4D1225FDBE93D8D29B47983DDD938`），系同一读数的重复落盘件；两份并存会被误读为「两次独立复跑互证」，故收尾删除，只留单份原读数。两文件均落 gitignored `.trae/tmp`，零 tracked 影响。

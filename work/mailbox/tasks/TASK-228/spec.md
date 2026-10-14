# TASK-228: web 启动级路由缺陷修复（P0 发现，MEDIUM 小）

派发基线：HEAD=69602b0（TASK-227 停止态笔之上），工作树干净。发现链：TASK-227 S3.3 停止上报（e1610de/69602b0）——E2E 批头号价值发现。token 基线 SUM=2036。

## S0 红线

恰 2 文件：web/src/main.ts + web/src/vue-router-auto-shim.d.ts（+台账 4 文件）。零页面/路由/组件/后端变更；typed-router.d.ts 零 diff；ci.yml/vite.config.ts/pnpm-lock 零触碰（本修复零依赖变更）。命令行禁中文。

## S2 预注册

main.ts 显式传 routes；shim 删除 `Omit<RouterOptions,'routes'>` 掩盖性声明、改 auto-routes 值声明；dev 与 preview 双形态挂载验证。

## S2.5 判别式

R1 根因复现（exports 断言）+ 修复后双形态可挂载；R2 web 四件套全绿（test 31/type-check/build/typed-router 零 diff）；R3 恰 2+4 文件全等；R4 token/词面/契约/diff --check/行尾。功能面由同批 TASK-227 E2E 12 用例承载。

## S3 停止条件

修复扩散超 2 文件；或需动依赖/配置才能挂载。

## S6 门禁

web 四件套 + 双形态挂载证据 + 契约双态（--open TASK-228 --baseline=4596df2）+ token + 词面 + diff --check + 行尾（改文件保基线）。独立复核四块（用户派发，与 TASK-227 同批评审）。

> **S6 基线口径订正（订正笔，2026-10-14，M6）**：派发行所记 `69602b0` 系笔误，正确口径为**开工基线 `4596df2`**（§1 与 handoff §1 同记）——`69602b0` 是 TASK-227 停止补记笔，位于本课题开工基线之**前**，作为本课题 `--open` 基线不成立。两口径在 HEAD=`fd6b299` 实测**皆 rc=1**，且同为**串批累积集语义**（判据 B 以单一累积改动集对每个在途回传全量比对，TASK-228 与 TASK-227 两回传互判「改动集未声明」），非本课题清单违规。故契约证据**不以在途 rc 承载**，改以**本课题相对 `4596df2` 的 6 文件基线清单全等 + 收口无参 rc=0** 承载（同为 M6 裁决，明细见 `handoff.md §11-①`）。

### 回填（2026-10-14，C-02 台账笔）

- **根因（独立复核）**：`unplugin-vue-router@0.19.2` 的 `package.json#exports` 无 `./auto`、无 `./auto-routes`（16 个子路径逐键列举留证），其产物只提供 `MODULE_ROUTES_PATH = "vue-router/auto-routes"`；`vue-router@4.6.4` 的 `./auto` 落到 36 字节单行注释的纯类型占位文件，注释所称“augmented”在 0.19.2 已不成立。故 `main.ts` 的 `createRouter({ history })` 以 `routes === undefined` 进入 vue-router `createRouterMatcher(routes, ...)` 并在 `routes.forEach` 抛错（`Cannot read properties of undefined (reading 'forEach')`），应用挂载失败。预存在性佐证：`git diff 11364af -- web/src vite.config.ts tsconfig.app.json` 为空，缺陷非本批引入。
- **判别式红绿**：R1 绿（修复前 dev 真 chromium 复现红：`#app` 子节点 0、pageerror 2 条且均命中 `forEach` 抛错点、`MOUNTED=false`；修复后 dev + preview 双形态 PASS：守卫改写到登录页、`#app` 子节点 1、pageerror 0）；R2 绿（`pnpm test` 4 文件 31 测全过 rc=0 / `pnpm type-check` rc=0 / `pnpm build` 3317 modules rc=0 / `typed-router.d.ts` 零 diff rc=0）；R3 绿（恰 2 + 4 文件全等，无第三实施文件）；R4 绿（token 29 项两点持平 / 词面门改动集 ZERO_HIT + 探针正控 HIT / 契约门双态 rc=0 / `git diff --check` 双查 rc=0 / 行尾保基线：两 .ts 文件改动前后同为 CRLF 形态、blob LF、末尾 0x0A、无 BOM）。
- **双形态挂载证据**：`.trae/tmp/t228-mount-red.log`（修复前 dev 红 rc=1）、`t228-mount-dev.log`（修复后 dev 绿 rc=0）、`t228-mount-preview.log`（修复后 preview 生产产物绿 rc=0）；三份均为真 chromium 探针读数（`#app` 子节点/innerHTML 长度/input 与 button 计数/pageerror 与 console error 计数/守卫改写结果）。
- **偏差**：D1（宿主 `%TEMP%` 下 esbuild 清理临时文件被拒致 `pnpm build` 偶发 rc=1，环境面；TMP/TEMP 重定向至 gitignored `.trae/tmp/esbtmp` 后 rc=0，本课题构建读数均在该重定向下取得）；D2（词面门探针正控形态订正——`git grep` 不搜索未跟踪文件，TASK-226 脚本内以 `.trae/tmp` 探针文件作正控为假阴性，改用同 pattern 的 `[regex]::IsMatch` 断言）；D3（挂载探针依赖上一轮 TASK-227 尝试遗留的本机 `@playwright/test` 与 chromium，探针不入库，本课题零依赖新增）；D4（临时件落 gitignored `.trae/tmp`）；D5（C-02 自指哈希沿先例）。明细见 `handoff.md §8`。
- **停止条件**：S3 两条均**未按字面触发**——修复恰 2 文件零扩散（无页面/路由/组件/后端变更）；双形态挂载未改动任何依赖或配置（`package.json`/lock/`vite.config.ts`/`tsconfig.app.json` 全零 diff）。
- **未覆盖清单**：CI 全流程（两笔未 push、无 run）；`--mode=online`；真实浏览器**功能交互面** 12 用例（由同批 TASK-227 E2E 承载，本课题只证启动级挂载）；多浏览器/移动视口/可访问性；dev HMR 路由热更新。见 `handoff.md §10`。
- 指导侧封盘补记（2026-10-14）：run `38586443704`（head=`aee5ca0`）conclusion=success——外部终验一次达成，TASK-228 封盘（C-01 `05bcae4` / C-02 `138ec97`）。复核 BLOCKER-1（在途契约门读数时点失实）由订正笔 `aee5ca0` 如实化处置，契约证据以「相对 `4596df2` 六文件清单全等 + 收口无参 rc=0」承载（§S6 订正注明同笔）。

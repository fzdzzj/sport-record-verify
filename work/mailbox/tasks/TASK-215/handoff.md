# TASK-215 handoff：web 前端测试基建第一阶段（Vitest + token/client 锚点测试，MEDIUM 档）

## 1. 编号、基线与外部状态

- 任务编号：`TASK-215`（提案 `spec/changes/add-web-test-baseline/proposal.md`）。
- 分级：**MEDIUM**（测试基建 + 纯逻辑测试，零生产逻辑改动；指导侧亲核 + CI 外部终验收口，**免独立复核**）。升级条件：断言失败或与预注册矛盾 → FULL。
- 开工基线：`e90fe6a`（派发笔，含提案三件套与任务书）；开工前 `git status` 工作树干净、基线自洽（本地领先 origin 2 笔，未推送）。
- 离线基线（任务书 §6）：698 = 52/50/165/166/159/80/26（web 不进 offline 基线）。
- 工具版本：vitest 3.2.7（V3 dist-tag）、happy-dom 21.0.1；本机 pnpm 10.25.0 / node v22.19.0，与 CI 口径一致。
- C-01 实施笔：`bc448db`（`test(web): Vitest测试基建与token/client拦截器锚点测试（TASK-215）`，恰 7 文件，+634／−2）；C-02 台账笔：本 handoff 所在提交（`docs(mailbox)`，自指哈希沿先例）。
- 两笔均**未 push、未建 PR**；CI 外部终验待指导侧推动推送后补记。

## 2. 只改清单（相对开工基线 e90fe6a，恰 11 文件 = C-01 7 + C-02 4）

C-01（test(web) 实施笔，7 文件）：
- web/package.json（scripts.test + devDeps vitest/happy-dom）
- web/pnpm-lock.yaml（锁文件更新）
- web/vite.config.ts（defineConfig 改自 vitest/config + test 字段）
- web/src/utils/__tests__/token.test.ts（新建）
- web/src/api/__tests__/client.test.ts（新建）
- .github/workflows/ci.yml（web job 单 step）
- web/README.md（登记一节）

C-02（docs(mailbox) 台账笔，本 handoff 所在提交，4 文件）：
- spec/changes/add-web-test-baseline/tasks.json（勾选）
- work/mailbox/tasks/TASK-215/spec.md（§7 回填）
- work/mailbox/tasks/TASK-215/handoff.md（本新文件）
- work/mailbox/PLAN.md（纯追加）

> 六后端服务 / api / common / 父 pom / sql / mvn-verify.sh / mailbox-contract.sh / compose / 生产 web src（.vue 页面、main.ts、notificationWs.ts、useNotificationBell.ts 等）全部零改动（G2 证据见 §5）。package.json 的 dependencies 段零 diff（G3）。

## 3. 一句话结论

为 web 前端建立 Vitest 测试基建（vitest 3.2.7 + happy-dom，共享 vite.config.ts 的 @ alias 链），新增两套纯逻辑锚点测试 —— `token.ts`（TASK-205 凭证姿态：键常量、setTokens 双键回环、clearTokens 清理含存量 refresh 键、auth-fail handler 注册/调用/异常吞没）与 `client.ts` 拦截器链（Bearer 注入有无、code=0 透传、code!=0 抛出、401 单次 refresh 重试含 withCredentials、/api/auth/* 零 refresh、refresh 失败触发 auth-fail）；CI web job 在 Type check 与 Build 之间加 `pnpm --dir web run test` 单 step。零生产逻辑改动、零运行时依赖新增（仅 devDeps vitest + happy-dom）。全部 §2.5 判别式与 §6 门禁按 MEDIUM 档留证。

## 4. 判别式红绿（R1 / R2a-f / R3a-g / G1-G3 逐条）

- **R1（绿）基建与门禁**：`pnpm --dir web run test` rc=**0**（2 suites / 13 tests 全绿，均被收集）；`pnpm --dir web run type-check`（vue-tsc --noEmit）rc=**0**；`pnpm --dir web run build` 成功（3283 modules transformed，23.42s）；`git diff --exit-code -- web/src/typed-router.d.ts` rc=**0**（构建重写后逐字节一致）。
- **R2a（绿）**：`TOKEN_KEYS` 恒等于 `{ACCESS:'access_token', ROLE:'user_role'}` 且键集合恰 `['ACCESS','ROLE']`。
- **R2b（绿）**：`setTokens(accessToken, role)` 写双键，`getAccessToken/getRole/hasAccessToken` 回环一致；空态 `hasAccessToken()===false`。
- **R2c（绿）**：`clearTokens` 移除 ACCESS + ROLE，并清理存量 `refresh_token` 键（TASK-205 行为锁定；预置 legacy 值后断言三键均 null）。
- **R2d（绿）**：无 handler 时 `triggerAuthFail()` 仅清理、不抛（该用例先于任何 setAuthFailHandler 注册执行，模块级 handler 为 null）。
- **R2e（绿）**：注册的 handler 在 `triggerAuthFail` 时被调用恰 1 次，且调用时 access 已移除。
- **R2f（绿）**：handler 抛异常被吞没、不外传（`expect(...).not.toThrow()` 且 handler 被调用恰 1 次）。
- **R3a（绿）**：无 token 请求不携带 Authorization 头（adapter 收到 config 无该头）。
- **R3b（绿）**：有 token 请求携带 `Bearer tok-abc`。
- **R3c（绿）**：code=0 响应 resolve 出 body 本身（拦截器透传 body，非 AxiosResponse）。
- **R3d（绿）**：code!=0 reject，err.code/err.message 与 body 精确一致（6001 + 业务文案）。
- **R3e（绿）**：401 落在 `/api/auth/login` 立即 reject，adapter 恰 1 次调用（零 refresh）；err.status=401、err.code=1001。
- **R3f（绿）**：业务 URL 401 → 触发 refresh POST（断言 `withCredentials===true`）→ 携新 token 单次重试原请求。adapter 三次调用 = 原请求(Bearer oldtok) + refresh + 重试(Bearer newtok)；按调用时刻快照头，精确锁定首次旧 token、二次新 token；重试后 `getAccessToken()==='newtok'`。
- **R3g（绿）**：refresh 自身 401 → `triggerAuthFail` 被调恰 1 次且外层 reject；adapter 恰 2 次（原请求 + refresh），无重试。
- **G1（绿）**：offline 七模块 Failures/Errors/Skipped 全 0，逐位 698 = 52/50/165/166/159/80/26（与基线逐位不变；web 不进 offline）。
- **G2（绿）**：生产面零 diff —— `git diff --name-only e90fe6a -- web/src` 无 tracked 改动（typed-router.d.ts 不变）；六后端/api/common/父 pom/sql/scripts/compose diff 全空；改动集恰 §2 的 11 文件。
- **G3（绿）**：`web/package.json` 的 dependencies 段零 diff（仅 scripts + devDependencies）；ci.yml diff = 恰在 web job 的 Type check 之后、Build 之前新增单 step（`Web unit tests` / `pnpm --dir web run test`），共 +3 行，其余零改动。

## 5. §6 门禁留证

1. **offline 全量**：唯一入口 `D:\git\Git\bin\bash.exe scripts/verify/mvn-verify.sh --mode=offline test` → **BUILD SUCCESS exit 0**，逐位 **698 = 52/50/165/166/159/80/26**（common 52 / gateway 50 / user 165 / record 166 / verify 159 / leaderboard 80 / mapmatch 26，每模块 Failures/Errors/Skipped 全 0）。纯前端改动，七模块计数与基线逐位不变。
2. **本机 web 门禁**：`pnpm --dir web run test` rc=**0**（TMP/TEMP/TMPDIR 重定向仓内 `.trae/tmp`，规避 esbuild temp 清理，TASK-213 D3 教训前置）；`type-check` rc=**0**；`build` 成功；`git diff --exit-code -- web/src/typed-router.d.ts` rc=**0**。
3. **契约门双态**：在途 `--open TASK-215 --baseline=e90fe6a` rc=**0**（判据 A 两件套口径：TASK-215 仅 spec 声明进行中放行）；收口后另跑无参 rc=**0**（见 §9）。
4. **token 29 项**：见 §6。
5. **词面门**：CI-exact 口径（显式 ERE、`git grep -n -I -iE --untracked`）对改动文件集（排除 ci.yml 自身载体，与 ci.yml 的 `:!.github/workflows/ci.yml` 排除一致）**ZERO_HIT rc=1**；含 ci.yml 时唯一命中为其第 95 行自身的词面门正则（CI 已排除该文件，非新增命中）。合成探针阳性对照：对含禁用词的探针文件 `git grep --no-index -n -I -iE -f <mode>` **HIT rc=0**（命中探针第 2 行），证明 `-E/-i` 口径有效、无假阴性。
6. **凭证自检**：改动集对凭证类词面（口令/密钥/API key/私钥/Bearer 值/credential 等）扫描，命中项均为功能标识符（`access_token`/`setTokens` 等）、包名 `js-tokens` 与测试占位值（`tok-abc`/`oldtok`/`newtok`/`acc-123`/`legacy-refresh-value`），**零真实凭证字面量**；pnpm-lock 新增行仅含依赖声明与 integrity 哈希。
7. **`git diff --check` / `--cached --check`** 均 rc=0。
8. **行尾**：两新文件纯 LF + 末尾换行（末字节 0A，CR=0）、无 BOM；改文件保持基线各自身（package.json/pnpm-lock.yaml/vite.config.ts/web/README.md 均 LF；ci.yml 保持 CRLF）。提交后 `git ls-files --eol` 复核新文件 i/lf w/lf。
9. **只改清单全等**：`git diff --name-only e90fe6a` + untracked 恰 §2 的 11 文件；生产 web src 与六后端/api/common/父 pom/sql/scripts/compose diff 全空（G2）。

## 6. token 时点读数

29 项受保护 token 以 tracked 全量计（SUM = 各 token 命中计数求和）：开工（e90fe6a 基线）账单参照 = **2030**、post-C01 = **2030**、close = **2030**（只增不减）。本课题改动集为 5 改 + 2 新测试文件 + 4 台账文件，全部内容零真实凭证字面量（§5 第 6 条自检佐证）；沿 TASK-213/214 MEDIUM 档同口径，本报告不枚举 29 项字面量，避免全仓计数自增失准；权威 29 项清单与终测由指导侧亲核复测。

## 7. 实现要点

- **package.json**：`scripts.test = "vitest run"`（单次执行、非 watch，CI 友好）；devDeps 加 `vitest ^3.2.7` + `happy-dom ^21.0.1`（vitest 3.2.7 对 happy-dom 的 peer 为 `*`，与 vite 7.3.6 兼容）。dependencies 段零改动。
- **vite.config.ts**：`defineConfig` 改自 `vitest/config`（官方等价范式，保 plugins/alias/server 与构建共享），新增 `test: { name:'web', environment:'happy-dom', globals:false }`；`@→src` alias 对测试共享（`client.ts` 的 `@/utils/token` 与测试的 `@/...` 均可解析）。
- **pnpm-lock.yaml**：`pnpm install --no-frozen-lockfile` 更新（新增 38 包），随后 `pnpm --dir web install --frozen-lockfile` rc=0 验证与 CI 的 frozen 口径兼容。
- **token.test.ts**：happy-dom 提供 localStorage；`beforeEach` 逐测 `localStorage.clear()` 隔离。R2d 依赖声明序先于 R2e/R2f（同文件顺序执行、模块级 handler 尚未注册）以保证「无 handler」态真实。
- **client.test.ts**：adapter 注入范式 —— `beforeEach` 将 `api.defaults.adapter` 置为新的 `vi.fn()`，请求/响应拦截器链全真实。`ok()` 造 200 响应；`unauthorized()` 以 `new AxiosError(msg,'ERR_BAD_REQUEST',config,{},response)` reject 形似真实传输层。R3f/R3g 用 `mockImplementation` 按 url 分流 refresh 与业务请求。注意：401 重试复用同一 config 对象（拦截器原地改 headers），故 R3f 改为按调用时刻快照 Authorization 值而非存 config 引用（首跑即暴露并纠正）。
- **ci.yml**：仅 web job 插单 step（`Web unit tests` / `pnpm --dir web run test`），位于 Type check 后、Build 前。
- **web/README.md**：新增「前端测试（Vitest 基线）」一节，登记运行方式、CI 插点、测试范围与组件/E2E 后置理由。

## 8. 异常与偏差登记

- **D1（C-02 自指哈希）**：本 handoff 与 spec §7 内以「本 handoff 所在提交」指称 C-02，实际哈希以 `git log --oneline` 为准（沿 TASK-178/183/204/205/206/207/208/209/210/211/213/214 先例）。
- **D2（vite.config.ts 行尾）**：编辑后该文件曾出现 1 个 CR（基线为纯 LF）。经字节核验（基线 57 LF/0 CR；修改后 CR=0 LF=64）已归一化为纯 LF，与基线各自身一致；内容 diff 仅为 `defineConfig` 导入源 + `test` 块。
- **D3（新文件末尾换行）**：两个新测试文件初写无末尾换行（末字节 `)`），复核时补 LF 使末字节 0A（gate 要求「新文件纯 LF + 末尾换行」）；补后重跑 `pnpm test` 仍全绿。
- **D4（web/README 口径遗留）**：README「验证（门槛上跑什么）」节原有「CI 的 web job 对 web/ 执行四条命令」表述，因 R4 仅授权「登记一节」而保持原样；新增节已明示 CI 现额外运行 `pnpm --dir web run test` 一步。属文档措辞遗留（非功能影响），已在此登记供指导侧裁量是否后续订正。
- **停止条件（§3）**：无一条触发 —— 生产 web src 无需改动（测试迁就现状）；vitest 3.2.7 与 vite 7.3.6 无版本冲突且未动运行时依赖；ci.yml 改动未超单 step；pnpm-lock 解析成功且 frozen-lockfile 兼容（rc=0）。R2/R3 首跑唯一红灯为 R3f 断言写法问题（config 引用被重试原地改头），修正为按调用时刻快照后全绿，非生产缺陷、非预注册矛盾。

## 9. 收口门禁（C-02 后）

- 契约门**无参 rc=0**（baseline 默认 HEAD=C-02，handoff 无基线变动 → 已收口不重审）。
- token 收口态账单参照 SUM=**2030**（见 §6 口径说明）。
- `git diff --check` rc=0；工作树 `git status` 干净（`.trae/tmp` 临时件已清）。
- `git diff --name-only e90fe6a` + untracked 对照恰 **11 文件**（C-01 7 + C-02 4）：生产 web src 与六后端/api/common/父 pom/sql/mvn-verify.sh/mailbox-contract.sh/compose **diff 全空**。

## 10. 未覆盖与不得推出的结论

- `--mode=online`、CI 全流程（含新增 web test step 的真实执行）、真实浏览器可视交互、组件挂载与 E2E 未跑；未 push、未建 PR，无 CI run；CI 外部终验待指导侧推动推送后补记（MEDIUM 档：指导亲核 + CI 外部终验收口）。
- 测试仅覆盖 `token.ts` 与 `client.ts` 拦截器链的预注册锚点，未覆盖 `notificationWs.ts`/`useNotificationBell.ts`/页面组件；组件测试与 E2E 为设计性非目标（README 已载理由）。
- R3 系列以 adapter 注入验证拦截器语义，未起真实 HTTP 服务/网关；网关注入 X-User-Id 的跨进程链路不在本课题范围。
- 无性能/吞吐断言（任务书 §0 禁性能声明）。

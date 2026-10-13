# TASK-218 handoff：轨迹 GPX 导出（E13，MEDIUM 档）

## 1. 编号、基线与外部状态

- 任务编号：`TASK-218`（提案 `spec/changes/add-gpx-export/proposal.md`）。
- 分级：**MEDIUM**（纯前端 + TASK-215 vitest 基线单测；零后端、零依赖、零 CI）。指导侧亲核 + CI 外部终验，免独立复核。
- 开工基线：`9df3d6c`（派发笔，含提案三件套与任务书）；开工前 `git status` 干净、本地领先 origin 2 笔、未推送。
- 离线基线（任务书 §6）：698 = 52/50/165/166/159/80/26。
- C-01 实施笔：`70d9679`（`feat(web): 轨迹GPX导出纯前端实现（TASK-218）`，恰 4 文件，+147）。
- C-02 台账笔：本 handoff 所在提交（`docs(mailbox)`，自指哈希沿 TASK-178/183/204/205/213/214/215/216/217 先例，实际哈希以 `git log` 为准）。
- 两笔均**未 push、未建 PR**；CI 外部终验待指导侧推动推送后补记。

## 2. 只改清单（相对开工基线 9df3d6c，恰 8 文件 = C-01 4 + C-02 4）

C-01（feat(web) 实施笔，4 文件，白名单全等）：
- web/src/utils/gpx.ts（新，generateGpx 纯函数）
- web/src/utils/__tests__/gpx.test.ts（新，R2a-e 五测）
- web/src/pages/record-detail.page.vue（导出 GPX 按钮 + exportGpx handler）
- web/README.md（「轨迹查看与 GPX 导出」功能登记一节）

C-02（docs(mailbox) 台账笔，本 handoff 所在提交，4 文件）：
- spec/changes/add-gpx-export/tasks.json（勾选）
- work/mailbox/tasks/TASK-218/spec.md（§7 回填）
- work/mailbox/tasks/TASK-218/handoff.md（本新文件）
- work/mailbox/PLAN.md（纯追加）

> 下列载体零触碰（G2 证据见 §5）：六后端 src/main/resources/pom、api/common、docker-compose*、.github/workflows/ci.yml、scripts/verify/mvn-verify.sh、scripts/verify/mailbox-contract.sh、scripts/、sql、web/package.json、web/vite.config.ts、web/src/typed-router.d.ts、web/src 其余文件。词面门/探针/token 模式文件均落 gitignored `.trae/tmp`、不入库。

## 3. 一句话结论

在 TASK-213 轨迹页（points 已客户端持有）上加纯前端 GPX 1.1 导出：新建 `web/src/utils/gpx.ts` 的 `generateGpx(points, opts?)`——完整 `<?xml?>` 头 + gpx(version=1.1, creator 默认 sport-verify-web, 命名空间) + metadata(name 默认 record-track, time=首点 ts ISO8601) + trk/trkseg + 逐点 trkpt（lat/lon 属性固定 6 位小数、`<time>=new Date(ts).toISOString()`、保持原始顺序），XML 转义辅助覆盖 `& < > " '`（文本与属性值同覆盖），空数组抛 Error（防御，按钮已按 points.length 门控），零新依赖；页面页脚说明块下加「导出 GPX」a-button（v-if points.length），handler 走 generateGpx → Blob(application/gpx+xml) → objectURL → a[download=`record-{id}.gpx`] 点击 → revoke。TrackPointDTO 无海拔（client.ts:11-19），GPX `<ele>` 合法省略。vitest 5 测全绿；type-check/build 通过；offline 七模块逐位不变。

## 4. 判别式红绿（R1 / R2 / R3 / G1-G3 逐条）

- **R1（绿）gpx.ts 纯函数语义**：GPX 1.1 结构完整（gpx/metadata/trk/trkseg/trkpt 齐备，`<gpx version="1.1" creator="sport-verify-web" xmlns="http://www.topografix.com/GPX/1/1">`）；trkpt 数量 == points 数量；lat/lon 以 `toFixed(6)` 固定 6 位小数；`<time>` 为 `new Date(ts).toISOString()`；name/creator 经 escapeXml 转义（& 最先替换防二次转义）；空数组抛 Error；单点 1 个 trkpt 时 metadata time 与首点一致；无任何新依赖（import 仅 `@/api/client` 类型）。
- **R2（绿）gpx.test.ts 全绿**：vitest 5 测（R2a 单点恰 1 trkpt 且 lat/lon 精确 / R2b 多点数量与顺序（seq 对应）/ R2c time 由 epoch ms 转 ISO8601 已知时间戳断言 / R2d name 含 `& < >` 正确转义含属性值字符 / R2e 空数组抛错）；套件内 5 tests 全过，`pnpm test` 总计 3 套 18 测 rc=0。
- **R3（绿）页面集成**：按钮仅 `points.length > 0` 时渲染（v-if，页脚说明块下）；handler `exportGpx`：`generateGpx(points.value, { name: record-${recordId.value} })` → `new Blob([xml], { type: 'application/gpx+xml' })` → `URL.createObjectURL` → 构造 a 元素 `download=record-{recordId}.gpx` 点击 → `URL.revokeObjectURL`；验证由 type-check rc=0 + build 成功 + 代码审查承载；**真实浏览器下载点击不跑，登记未覆盖**（沿 TASK-213 口径）。
- **G1（绿）**：offline 全量 rc=0，七模块逐位 698 = 52/50/165/166/159/80/26（与基线逐位不变；零后端改动）。
- **G2（绿）**：C-01 后 `git diff --name-only 9df3d6c` + untracked 恰白名单 4 文件；C-02 后恰 8 文件（= 白名单全等）；越界面（六后端/api/common/父 pom/sql/ci.yml/两脚本/compose/web 其余）diff 全空。
- **G3（绿）**：web 门禁 pnpm test rc=0（3 套 18 测）、type-check rc=0、build 成功（3283 modules）、typed-router diff 干净；契约门双态 rc=0（在途 `--open TASK-218 --baseline=9df3d6c` + 收口无参）；token 29 项两点 2034/2034 只增不减；词面门改动集 CI-exact ZERO_HIT + 探针阳性对照 HIT；`git diff --check` rc=0；新文件纯 LF + 末尾换行、无 BOM，改文件保持基线行尾。

## 5. §6 门禁留证

1. **offline 全量**：唯一入口 `D:\git\Git\bin\bash.exe scripts/verify/mvn-verify.sh --mode=offline test` → **BUILD SUCCESS exit 0**（rc=0，非 3），逐位 **698 = 52/50/165/166/159/80/26**（common 52 / gateway 50 / user 165 / record 166 / verify 159 / leaderboard 80 / mapmatch 26，每模块 Failures/Errors/Skipped 全 0）。零 Java 改动，与基线逐位不变。
2. **web 本机四件套**：`pnpm test` rc=0（vitest 3.2.7，**3 套 18 测** = token 6 + **gpx 5** + client 7；TMP/TEMP/TMPDIR 重定向 `.trae/tmp` 防 esbuild access denied）；`pnpm type-check` rc=0；`pnpm build` 成功（3283 modules transformed，vite 7.3.6）；`git diff --exit-code -- web/src/typed-router.d.ts` rc=0（零漂移）。
3. **契约门双态**：在途 `--open TASK-218 --baseline=9df3d6c` **rc=0**（判据 A：TASK-218 仅 spec 声明进行中放行，共 1 个待办；其余历史任务「已收口不重审」）；收口无参 **rc=0**（见 §9）。
4. **token 29 项**：两点时点读数见 §6，SUM=**2034**（只增不减）。
5. **词面门 CI-exact**：显式 ERE 口径（`git grep -n -I -iE -f <模式文件>`，`-E` 显式；模式经 UTF-8 无 BOM 文件注入 .trae/tmp 以规避命令行中文）对 C-01 改动文件集（4 文件）**ZERO_HIT rc=1**；合成探针（gitignored 探针文件含禁用措辞）`git grep --no-index -n -I -iE -f`（同模式文件）**HIT rc=0**（命中探针行），证明 `-i -E` 口径有效、无假阴性。C-02 后对含台账四文件的改动集复扫（见 §9）仍 ZERO_HIT。模式字面量与探针均落 gitignored `.trae/tmp`、不入库（沿 TASK-184 F1 教训，本报告不落正则字面量）。
6. **`git diff --check`** rc=0（README 曾有一处「new blank line at EOF」已修正后复检干净；`LF will be replaced by CRLF` 为 `core.autocrlf=true` 信息性告警，非空白错误）。
7. **行尾**：两新文件（gpx.ts / gpx.test.ts）字节级校验 纯 LF（CR=False）+ 末尾换行（EndsLF=True）+ 无 BOM（BOM=False）；record-detail.page.vue 与 README.md 保持基线 `i/lf w/lf` 逐位未漂移。
8. **只改清单全等**：`git diff --name-only 9df3d6c` + untracked 对照恰 §2 的 8 文件；越界面 diff 全空（G2）。
9. **凭证自检**：改动集为前端工具/测试/页面按钮与台账文案，零凭证字面量、零受保护 token 字面量、零新正则字面量（沿 TASK-188 N1 教训，29 项字面量不枚举入本报告与任何 tracked 新文档）。

## 6. token 时点读数

29 项受保护 token 以 tracked 全量计（`git grep -cF`，SUM = 各 token 行命中计数求和，rev-aware 基线以 `git grep -cF <token> 9df3d6c` 对基线提交计）：开工（`9df3d6c` 基线）= **2034**、收口（C-02 后工作树）= **2034**（只增不减，基线读数沿 TASK-217 更新后口径 2034）。本课题改动集（4 实施文件 + 4 台账文件）全部内容零真实凭证 / 零受保护 token 字面量（§5 第 9 条自检佐证）。**口径说明**：29 项权威清单由指导侧持有复测（沿 TASK-213/215/217 同口径）；本报告不枚举字面量以避免全仓计数自增失准（沿 TASK-188 N1 教训）。

## 7. 实现要点

- **gpx.ts**：`escapeXml` 顺序 `& → < → > → " → '`（& 最先，防二次转义）；`generateGpx` 空数组先抛 `Error` 再组装（防御；页面按钮 v-if 门控后不可达）；`creator` 默认 `sport-verify-web`、`name` 默认 `record-track`；lat/lon 用 `toFixed(6)`（属性值无需再转义——数字无 XML 特殊字符）；`<time>` 用 `new Date(ts).toISOString()`（epoch ms → ISO8601，毫秒三精度）；逐点 push 行内联 `<trkseg>`，保持输入顺序；无 `<ele>`/扩展（DTO 无海拔、GPX 1.1 核心保持简单，与 proposal Non-Goals 一致）。
- **页面**：按钮置于页脚说明块（`points.length` 尾注 p 后）下方，`<a-button size="small" v-if="points.length" @click="exportGpx">导出 GPX</a-button>`；handler 中 `recordId.value` 为当前输入/路由注入的 id，download 名 `record-{id}.gpx`；`URL.revokeObjectURL` 在 click 后立即调用（同帧下载不受影响）。
- **README**：新「轨迹查看与 GPX 导出（add-gpx-export）」一节，两句说明（页面已有轨迹点时下载 `record-{id}.gpx`，纯前端零后端零依赖）。

## 8. 异常与偏差登记

- **D1（C-02 自指哈希）**：本 handoff 与 spec §7 内以「本 handoff 所在提交」指称 C-02，实际哈希以 `git log --oneline` 为准（沿先例）。
- **D2（词面门/探针/token 模式文件）**：中文模式与 29 项字面量经 gitignored `.trae/tmp` 文件注入（`[IO.File]::WriteAllText` UTF-8 无 BOM），不入库、不落 tracked 文档；命令行参数零中文（exit 127 规避）。
- **D3（README 尾行一次修正）**：edit 拼接曾产生「new blank line at EOF」，`git diff --check` 检出后即时修正（删除尾部空行、保留末尾单个换行），复检 rc=0；非白名单外改动，属编辑过程自纠。
- **D4（offline 日志噪音）**：offline 模式 Nacos 连接失败日志（Client not connected / HikariPool 超时）属既有测试环境噪音（服务未起），对七模块计数零影响，`Tests run` 汇总逐位不变。
- **停止条件（§3）**：无一按字面触发——§3.1 TrackPointDTO 为 epoch ms（client.ts:11-19，与预注册一致，未静默适配）；§3.2 无任何改动溢出白名单（package.json/vite.config/typed-router 零触碰）；§3.3 vitest/type-check/build 首跑即全绿，无盲试。

## 9. 收口门禁（C-02 后）

- 契约门**无参 rc=0**（baseline 默认 HEAD=C-02，本 handoff 相对基线无变动 → 已收口不重审）。
- token 收口态 SUM=**2034**（见 §6 口径）。
- 词面门改动集（含 C-02 四文件）CI-exact ZERO_HIT rc=1 + 探针阳性对照 HIT rc=0（已留证）；`git diff --check` rc=0；工作树 `git status` 干净（`.trae/tmp` 临时件 gitignored，无 override）。
- `git diff --name-only 9df3d6c` + untracked 对照恰 **8 文件**（C-01 4 + C-02 4）：越界面 diff 全空。

## 10. 未覆盖与不得推出的结论

- `--mode=online`、CI 全流程未跑（未 push、未建 PR）。
- **真实浏览器下载点击未跑**（沿 TASK-213 口径登记未覆盖）：R3 验证由 type-check + build + 纯函数单测承载；不得据此推断下载在真实浏览器端到端可用（Blob/objectURL/download 属性语义为标准 Web API，但未做实机点击验证）。
- 未做 GPX 文件被第三方工具（如地图/健身 App）解析的兼容性验证（仅按 GPX 1.1 结构生成，未用外部解析器校验）。
- 未做轨迹点 >500 的部分轨迹导出口径声明（沿用页内「单次拉取上限 500 点」已知限制说明，导出内容与页面展示一致）。
- 本机环境为本地处置（D2 临时件），未入库，不影响对外交付物。

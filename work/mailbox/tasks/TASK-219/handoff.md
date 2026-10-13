# TASK-219 handoff：配速趋势分析（P2，MEDIUM 档）

## 1. 编号、基线与外部状态

- 任务编号：`TASK-219`（提案 `spec/changes/add-pace-analysis/proposal.md`）。
- 分级：**MEDIUM**（纯前端 + TASK-215 vitest 基线单测；零后端、零依赖、零 CI）。指导侧亲核 + CI 外部终验，免独立复核。
- 开工基线：`8ba3072`（派发笔，含提案三件套与任务书）；开工前 `git status` 干净、本地领先 origin 2 笔、未推送。
- 离线基线（任务书 §6）：698 = 52/50/165/166/159/80/26。
- C-01 实施笔：`15e1e19`（`feat(web): 轨迹配速趋势图纯前端实现（TASK-219）`，恰 4 文件，+263）。
- C-02 台账笔：本 handoff 所在提交（`docs(mailbox)`，自指哈希沿 TASK-178/183/204/205/213/214/215/216/217/218 先例，实际哈希以 `git log` 为准）。
- 两笔均**未 push、未建 PR**；CI 外部终验待指导侧推动推送后补记。

## 2. 只改清单（相对开工基线 8ba3072，恰 8 文件 = C-01 4 + C-02 4）

C-01（feat(web) 实施笔，4 文件，白名单全等）：
- web/src/utils/pace.ts（新，computePaceSeries/formatPace/isDrawablePace 纯函数）
- web/src/utils/__tests__/pace.test.ts（新，R2a-f 六测）
- web/src/pages/record-detail.page.vue（配速趋势 SVG 区块 + 计算逻辑）
- web/README.md（「轨迹查看与 GPX 导出」节登记配速趋势一小块）

C-02（docs(mailbox) 台账笔，本 handoff 所在提交，4 文件）：
- spec/changes/add-pace-analysis/tasks.json（勾选）
- work/mailbox/tasks/TASK-219/spec.md（§7 回填）
- work/mailbox/tasks/TASK-219/handoff.md（本新文件）
- work/mailbox/PLAN.md（纯追加）

> 下列载体零触碰（G2 证据见 §5）：六后端 src/main/resources/pom、api/common、docker-compose*、.github/workflows/ci.yml、scripts/verify/mvn-verify.sh、scripts/verify/mailbox-contract.sh、scripts/、sql、web/package.json、web/vite.config.ts、web/src/typed-router.d.ts、web/src 其余文件。词面门/探针/token 模式文件均落 gitignored `.trae/tmp`、不入库。

## 3. 一句话结论

在 TASK-213/218 轨迹页（points 已客户端持有，speed 为 m/s 可选字段）上新增「配速趋势」分析区块：新建 `web/src/utils/pace.ts`——`computePaceSeries(points)` 以私有 haversine（R=6371km）逐段累加累计公里（首点 0、单调非递减、空数组→[]），`paceSec = (speed != null && speed > 0) ? 1000/speed : null`（零/缺省记停顿）；`formatPace` 输出 `m'ss"`（45→0'45"、500→8'20"、3659→60'59"、null→'--'）；`isDrawablePace` 承载停顿断线规则（null/0 与 >3600 不可绘）。页面在轨迹 SVG 下方渲染配速折线：X=累计 km、Y=配速秒/公里**倒置（快在上）**、停顿点断线（分段 polyline）、悬停 circle 复用 `<title>` 范式显示 距离+配速+时刻、轴标签给出配速 min/max 与总 km；区块 `v-if` 门控 points>1 且存在至少一个可绘配速点。vitest 6 测全绿（4 套 24 测）；type-check/build 通过；offline 七模块逐位不变。

## 4. 判别式红绿（R1 / R2 / R3 / G1-G3 逐条）

- **R1（绿）pace.ts 纯函数语义**：`computePaceSeries` 首点 distKm=0，其后逐段 haversine 累加（R=6371km），全序列单调非递减；`paceSec = 1000/speed` 精确（speed=2→500、speed=4→250）；speed 为 0 或缺省 → paceSec null（停顿点）且 speed 字段原样保留；空数组 → []；保持原始顺序；`formatPace` 边界正确（null→'--'、45→0'45"、500→8'20"、3659→60'59"，先整体取整再拆 m/s 防 60 秒进位）；`isDrawablePace` null/0 → false、600 → true、3601 → false；零新依赖（import 仅 `@/api/client` 类型）。
- **R2（绿）pace.test.ts 全绿**：vitest 6 测（R2a 已知短段 0.001° 纬度 ≈ 0.1112 km 容差断言 + 首点 0 + 单调非递减 / R2b 1000÷speed 精确 / R2c speed 0 或缺省 → paceSec null / R2d formatPace 边界四例 / R2e 空数组 → [] / R2f isDrawablePace 四值）；`pnpm test` 总计 **4 套 24 测**（token 6 + gpx 5 + client 7 + pace 6）rc=0。
- **R3（绿）页面集成**：区块门控 `paceSectionVisible = points.length > 1 && 存在 ≥1 可绘配速点`；Y 轴倒置（`fy = (max - pace)/range`，小 paceSec=快 靠上）；停顿点（paceSec null 或 >3600）经 `paceGeometry` 置 null 使 `paceSegments` 分段断线；悬停 circle（复用轨迹 SVG `<title>` 范式）显示 `dist=…km pace=… ts=…`；轴标签「快 minPace / 慢 maxPace / 0 / 总 km」；验证由 type-check rc=0 + build 成功 + 代码审查承载；**真实浏览器交互不跑，登记未覆盖**（沿 TASK-213/218 口径）。
- **G1（绿）**：offline 全量 BUILD SUCCESS rc=0，七模块逐位 **698 = 52/50/165/166/159/80/26**（与基线逐位不变；零后端改动）。
- **G2（绿）**：C-01 后 `git diff --name-only 8ba3072` + untracked 恰白名单 4 文件；C-02 后恰 8 文件（= 白名单全等）；越界面（六后端/api/common/父 pom/sql/ci.yml/两脚本/compose/web 其余）diff 全空。
- **G3（绿）**：web 门禁 pnpm test rc=0（4 套 24 测）、type-check rc=0、build 成功、typed-router diff 干净；契约门双态 rc=0（在途 `--open TASK-219 --baseline=8ba3072` + 收口无参）；token 29 项两点 2034/2034 只增不减；词面门改动集 CI-exact ZERO_HIT rc=1 + 探针阳性对照 HIT rc=0；`git diff --check` rc=0；新文件纯 LF + 末尾换行、无 BOM，改文件保持基线行尾（均 i/lf w/lf）。

## 5. §6 门禁留证

1. **offline 全量**：唯一入口 `D:\git\Git\bin\bash.exe scripts/verify/mvn-verify.sh --mode=offline test` → **BUILD SUCCESS**（Total time 10:47 min），逐位 **698 = 52/50/165/166/159/80/26**（common 52 / gateway 50 / user 165 / record 166 / verify 159 / leaderboard 80 / mapmatch 26，每模块 Failures/Errors/Skipped 全 0）。零 Java 改动，与基线逐位不变；log 无任何 `Failures/Errors/Skipped` 非零行。
2. **web 本机四件套**：`pnpm test` rc=0（vitest 3.2.7，**4 套 24 测** = token 6 + gpx 5 + client 7 + **pace 6**；TMP/TEMP/TMPDIR 重定向 `.trae/tmp` 防 esbuild access denied）；`pnpm type-check` rc=0；`pnpm build` 成功；`git diff --exit-code -- web/src/typed-router.d.ts` rc=0（零漂移）。
3. **契约门双态**：在途 `--open TASK-219 --baseline=8ba3072` **rc=0**（判据 A：TASK-219 仅 spec 声明进行中放行，共 1 个待办；其余历史任务「已收口不重审」）；收口无参 **rc=0**（见 §9）。
4. **token 29 项**：两点时点读数见 §6，SUM=**2034**（只增不减）。
5. **词面门 CI-exact**：显式 ERE 口径（`git grep -n -I -iE -f <模式文件>`，`-E` 显式；模式经 UTF-8 无 BOM 文件注入 .trae/tmp 以规避命令行中文）对 C-01 改动文件集（4 文件）**ZERO_HIT rc=1**；合成探针（gitignored 探针文件含禁用措辞）`git grep --no-index -n -I -iE -f`（同模式文件）**HIT rc=0**（命中探针行），证明 `-i -E` 口径有效、无假阴性。C-02 后对含台账四文件的改动集复扫（见 §9）仍 ZERO_HIT。模式字面量与探针均落 gitignored `.trae/tmp`、不入库（沿 TASK-184 F1 教训，本报告不落正则字面量）。
6. **`git diff --check`** rc=0（工作树与暂存区双查；`LF will be replaced by CRLF` 为 `core.autocrlf=true` 信息性告警，非空白错误）。
7. **行尾**：两新文件（pace.ts / pace.test.ts）字节级校验 纯 LF（CR=0）+ 末尾换行（末字节 0A）+ 无 BOM；record-detail.page.vue 与 README.md 保持基线 `i/lf w/lf` 逐位未漂移。
8. **只改清单全等**：`git diff --name-only 8ba3072` + untracked 对照恰 §2 的 8 文件；越界面 diff 全空（G2）。
9. **凭证自检**：改动集为前端工具/测试/页面区块与台账文案，零真实凭证字面量、零受保护 token 字面量（29 项逐项 `git grep -cF` 复测 SUM 持平）、零新正则字面量（沿 TASK-188 N1 教训，字面量不枚举入本报告与任何 tracked 新文档）。

## 6. token 时点读数

29 项受保护 token 以 tracked 全量计（`git grep -cF`，SUM = 各 token 行命中计数求和，rev-aware 基线以 `git grep -cF <token> 8ba3072` 对基线提交计）：开工（`8ba3072` 基线）= **2034**、post-C01（工作树）= **2034**、收口（C-02 后工作树）= **2034**（只增不减）。本课题改动集（4 实施文件 + 4 台账文件）全部内容零真实凭证 / 零受保护 token 字面量（§5 第 9 条自检佐证）。**口径说明**：29 项权威清单由指导侧持有复测（沿 TASK-213/215/217/218 同口径）；本报告不枚举字面量以避免全仓计数自增失准（沿 TASK-188 N1 教训）。

## 7. 实现要点

- **pace.ts**：`haversineKm` 私有（R=6371km，`sin²(dLat/2)+cos(lat1)cos(lat2)sin²(dLng/2)` 后 `2R·asin(√a)`）；`computePaceSeries` 逐点 push、i>0 时先累加本段距离再算配速；speed 先提局部变量再做 null/undefined 判断（首跑 type-check 报 TS18048 索引访问窄化失败，提局部变量后即过——实现过程自纠，非越界改动）；`formatPace` 先 `Math.round` 整体再拆 m/s（避免取余进位出现 60 秒）；`isDrawablePace` = `paceSec !== null && paceSec > 0 && paceSec <= 3600`。
- **页面**：区块置于轨迹 SVG 与导出按钮下方、`</a-card>` 内；`PW=800/PH=200` 与轨迹 viewBox 同宽；`paceGeometry` 与 `paceSeries` 对齐（不可绘点 null），`paceSegments` 把连续可绘点 join 成一段、遇 null 断开（分段 polyline）；悬停复用 `<title>` 范式（`dist=${distKm.toFixed(2)}km pace=${formatPace(paceSec)} ts=${ts}`）；轴标签 `快 minPace`（上）/`慢 maxPace`（下）/`0`（左下）/`总 km`（右下）；区块仅 `points>1 且 ≥1 可绘点` 时渲染。
- **README**：在「轨迹查看与 GPX 导出（add-gpx-export）」节追加一小块登记配速趋势（一句功能 + 一句口径）。
- **零依赖确认**：pace.ts 仅 `import type { TrackPointDTO } from '@/api/client'`；web/package.json 与 pnpm-lock 未动。

## 8. 异常与偏差登记

- **D1（C-02 自指哈希）**：本 handoff 与 spec §7 内以「本 handoff 所在提交」指称 C-02，实际哈希以 `git log --oneline` 为准（沿先例）。
- **D2（词面门/探针/token 模式文件）**：中文模式与 29 项字面量经 gitignored `.trae/tmp` 文件注入（`[IO.File]::WriteAllText` UTF-8 无 BOM），不入库、不落 tracked 文档；命令行参数零中文（exit 127 规避）。
- **D3（offline 后台包装器退出码伪影）**：后台任务包装器对 offline 管道报 `exit 1`，但同构管道 `$LASTEXITCODE` 实测直传 0，且 Maven log 权威显示 **BUILD SUCCESS**（仅成功才打印）——判 rc=0，伪影不进计数。
- **D4（offline 日志噪音）**：offline 模式 Nacos 连接失败日志（Client not connected / HikariPool 超时 / mapmatch 注册失败）属既有测试环境噪音（服务未起），对七模块计数零影响，`Tests run` 汇总逐位不变。
- **停止条件（§3）**：无一按字面触发——§3.1 TrackPointDTO.speed 为可选 m/s（client.ts:11-19，与预注册一致，未静默适配）；§3.2 无任何改动溢出白名单（package.json/vite.config/typed-router/后端零触碰）；§3.3 vitest/type-check/build 修复后首轮全绿（pace.ts 窄化自纠一次，非盲试）。

## 9. 收口门禁（C-02 后）

- 契约门**无参 rc=0**（baseline 默认 HEAD=C-02，本 handoff 相对基线无变动 → 已收口不重审）。
- token 收口态 SUM=**2034**（见 §6 口径）。
- 词面门改动集（含 C-02 四文件）CI-exact ZERO_HIT rc=1 + 探针阳性对照 HIT rc=0（已留证）；`git diff --check` rc=0；工作树 `git status` 干净（`.trae/tmp` 临时件 gitignored，无 override）。
- `git diff --name-only 8ba3072` + untracked 对照恰 **8 文件**（C-01 4 + C-02 4）：越界面 diff 全空。

## 10. 未覆盖与不得推出的结论

- `--mode=online`、CI 全流程未跑（未 push、未建 PR）。
- **真实浏览器配速区块交互未跑**（沿 TASK-213/218 口径登记未覆盖）：R3 验证由 type-check + build + 纯函数单测承载；不得据此推断 SVG 悬停/断线在真实浏览器端到端可用。
- 未做配速图与第三方健身 App 数值口径的对照验证（仅按任务书 pace 惯例 1000/speed 实现，未用外部工具校验）。
- 未做轨迹点 >500 的部分轨迹配速口径声明（沿用页内「单次拉取上限 500 点」已知限制说明，配速图与页面展示同源 points）。
- 本机环境为本地处置（D2 临时件），未入库，不影响对外交付物。

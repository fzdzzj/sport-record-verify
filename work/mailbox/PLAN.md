# sports 项目优化计划（活动看板）

## 当前进度与外部基线

- **最新代码基线与分支**：`main` 分支，最新已封盘至 **TASK-247**（C-01 `89ba3d1` / C-02 `47f1ecb` / 封盘笔 `1b92d19`，批次已推至 `origin/main` 且 CI run `38782194561` 全绿，外部终验达成）；前序 TASK-246（HEAD `a6701b0`）CI run `38774258105` 全绿。
- **会话无缝交接看板**：新主 Agent 接手状态与下一步动作见 [HANDOVER.md](HANDOVER.md)（原地滚动更新，免建新交接文档）。
- **CI 外部门槛状态**：GitHub Actions run `38782194561`（HEAD `1b92d19`，web ✓31s / build ✓10m49s 全 job 绿，TASK-247 封盘批次外部终验达成）。包含全模块 clean verify、Checkstyle/SpotBugs/PMD 静态门、Docker 镜像构建与 Trivy 依赖扫描。
- **历史记录归档索引**：早期 100+ 笔验收记录（TASK-001 至 TASK-210）已完整归档至 [PLAN-history-001-210.md](archive/PLAN-history-001-210.md)。各任务的原始契约与回传留存见 `work/mailbox/tasks/TASK-xxx/`。
- **候选规划与待办状态**：后续方向候选（F/H 系列）与排期规划统一定位在 [BACKLOG.md](BACKLOG.md)。

## 验收收口清单与协作纪律（指导侧常备，子 Agent 禁改本看板）

1. **子 Agent 禁改本看板（解耦红线）**：执行侧子 Agent 只负责修改业务代码、测试与当前任务目录（`spec.md`/`handoff.md`），**禁止触碰 `PLAN.md`**。本看板由指导侧主 Agent 在任务封盘或批次推送时统一汇总登记，杜绝多任务交叠冲突与契约误报。
2. **绑定修订与门槛来源**：记录结论必须绑定 commit id（禁止使用“最新提交”等相对指代），并标清门槛来源（外部门槛提供 CI run 编号与状态；本地实跑标明 `scripts/verify/mvn-verify.sh --mode=online` 实跑结论）。
3. **显式标注外部门槛与覆盖边界**：结论仅来自本地且尚未推送时，必须显式标注「未达外部门槛」；因环境缺失被 skip 的中间件测试必须按「未覆盖」如实记账，不得记为通过。
4. **依赖口径以 online 为准**：offline 与 online 依赖来源冲突时，以 online 结论为唯一对外权威，并在台账中披露差异构件。
5. **分级治理（LIGHT / MEDIUM / FULL）**：轻量任务（文档/注释/小改）免长篇论证与过度门禁；常规功能聚焦定向红绿判别式；高风险架构才执行深层预注册与变异。

验收命令唯一定义处：`scripts/verify/mvn-verify.sh`；台账契约校验：`scripts/verify/mailbox-contract.sh`。

## 近期活跃任务验收记录（TASK-211 至今）

## 验收记录：`TASK-211`（2026-10-12，我的记录端点与个人统计面板，FULL 档）

| 项 | 内容 |
| --- | --- |
| 开工基线 | 派发笔 `ac6e469`（提案三件套 + 任务书）；工作树干净；offline 基线 675=52/50/162/157/159/80/15；token 29 项开工实测 SUM=2030 |
| 本机门禁 | offline 逐位 **684=52/50/162/166/159/80/15** BUILD SUCCESS rc=0（record 157→166 净增 +9，其余六模块逐位不变）；静态门 `--static=record-service` rc=0（TASK-194 口径）；契约门双态 rc=0（在途 `--open TASK-211 --baseline=ac6e469` + 收口无参）；token 29 项 post-C01 2030 / close 2030（只增不减）；词面门改动文件集 CI-exact ZERO_HIT rc=1 + 探针阳性对照 HIT rc=0；`git diff --check` rc=0；行尾（RecordController.java/SportRecordService.java/SportRecordServiceTest.java/README.md 保持基线 CRLF，RecordControllerTest.java/client.ts/index.page.vue 保持基线 LF；RecordSummaryDTO.java 与 handoff.md 新文件纯 LF 无 BOM）；web `vue-tsc --noEmit` rc=0；`web/package.json` 零 diff |
| 判别式 | R1a 仅返本人行（mapper selectPage wrapper 过滤 verify + X-User-Id=200 透传）；R1b 分页边界（page≥1/size cap500/默认20）；R1c auth 开缺头 401/1001、auth 关缺 userId 400、显式 userId 放行；R1d 空结果空 Page 非错误 + DTO 五字段映射；R2a 统计卡 SUM/计数/通过率=PASSED(2)+RE_PASSED(5)；R2b 近30天纯 CSS 条图零新依赖（package.json 零 diff）；R2c 最近记录前10条 createdAt 倒序；G1 record 既有用例零回归 offline 166 全绿 + vue-tsc rc=0；G2 红线 diff 空、api 仅新增 RecordSummaryDTO、未加 index（登记已知限制） |
| 是否到达外部门槛 | **未到达**：两笔均未 push、未建 PR、无 CI run；FULL 档独立复核四块口径与 CI 外部终验待指导侧推动推送后补记 |
| 未覆盖 | `--mode=online`、CI 全流程、真实网关注入 X-User-Id 的跨进程 HTTP 401/400 链路、真实浏览器可视交互未跑；本人记录 >500 条为部分统计（size cap 500 即单次拉取上限）；无性能/吞吐断言 |
| 偏差 | D1 台账笔自指哈希（沿 TASK-178/183/204/205/206/207/208/209/210 先例）；D2 pwsh 管道遮蔽 Maven 退出码以 Maven 明文 BUILD SUCCESS 为准；D3 未跑本机 web build、按 S6 口径 vue-tsc rc=0 + CI web job 权威。明细见 handoff.md §8。C-01（feat(record)，8 文件）/ C-02 台账笔 4 文件 |- 补记（指导侧封盘笔，2026-10-13）：推送 513fe0c..fbe7d1e（4 笔）后第 50 次 CI（run 38239853834，head=fbe7d1e）conclusion=success 全 job 绿——offline 675、镜像、Trivy、词面门、compose config、web build/type-check 全过。MEDIUM 档三环节终裁（指导亲核/回传零矛盾/CI success），TASK-210 封盘。课题终态：settings 页新增通知偏好开关区块（onMounted GET 动态渲染、切换 PUT 单行、响应回填+失败回滚+toggling 防抖）；client.ts 两函数系 TASK-187 既有实现仅审查取证（D2，C-01 实际仅 2 文件=README+settings.page.vue）；后端零触碰、offline 675 与 token 2030 逐位不变。C-01 bc0c6a2/C-02 fbe7d1e。
- TASK-212（LIGHT 档文档订正，2026-10-13）：① NotificationPreferenceView.java:22 javadoc 通知类型三类→四类（补 RECORD_LIKED，对齐 SUPPORTED_TYPES 实况——TASK-211 立项侦察发现该注释滞后曾致误判「点赞通知无法关闭」，实为已可关闭）；② AuthService.java:373 javadoc「HMAC/BCrypt」→「BCrypt」（TASK-208 N1 收口，实为纯 BCrypt）；③ index.page.vue 补 EOF 末尾换行（TASK-211 复核 N2 卫生项）。单笔提交，三文件均为文档/行尾性质零语义改动。
- 补记（指导侧封盘笔，2026-10-13）：推送 fbe7d1e..70cb954（5 笔）后第 51 次 CI（run 38264375773，head=70cb954）conclusion=success 全 job 绿——offline 684、静态、镜像、Trivy、词面门、compose config、web build/type-check 全过。四环节终裁全过，TASK-211 封盘（E1 数据面板课题收官）；TASK-212（E2 LIGHT 档文档订正）随批验证收官，TASK-208 N1/TASK-211 复核 N2/立项侦察注释误判三 N 项一次清零。E1+E2 双课题闭环：GET /api/records/my（X-User-Id 归属/过滤即身份/分页 cap500）+ index 页个人仪表盘（统计卡/30 天纯 CSS 趋势/最近记录，零新依赖）；offline 基线 675→684（record 157→166 净增+9，其余逐位不变），token 2030 持平。C-01 852e97b（8 文件+444/−3）/C-02 0c2c79d；已知限制：>500 条部分统计、user_id 无专用索引、真实浏览器交互未跑（CI web job 权威）。

## 验收记录：`TASK-213`（2026-10-12，记录轨迹查看页 SVG 投影，纯前端 MEDIUM 档）

| 项 | 内容 |
| --- | --- |
| 开工基线 | 派发笔 `cc9ce4a`（提案三件套 + 任务书）；工作树干净；offline 基线 684=52/50/162/166/159/80/15；token 29 项开工实测 SUM=2030 |
| C-01 实施笔 | `7e4be36` `feat(web): 记录轨迹查看页SVG投影（TASK-213）`，恰 4 文件 = §4 白名单（record-detail.page.vue 新 / index.page.vue / typed-router.d.ts / README.md），+203／−0 |
| C-02 台账笔 | 本笔 `docs(mailbox): 登记TASK-213轨迹页验收与台账闭环（TASK-213）`，恰 4 文件（tasks.json 勾选 / spec.md §7 / handoff.md / PLAN.md），自指哈希沿 D1 先例 |
| 目标与范围 | Web 新增 `/record-detail` 页消费既有 `GET /record/api/records/{id}/points`（size=500 单次拉取），min-max 归一化投影 lat/lng 到固定 viewBox SVG（polyline + 起点绿/终点红 + 逐点 circle 悬停 seq/speed/ts），摘要头经既有 verify-result（失败降级仅轨迹）；index 最近记录行「查看轨迹」入口（query 传 recordId）；六后端/api/common/父 pom/sql/ci.yml/scripts/compose/web package.json 零改动 |
| 判别式红绿 | R1 绿（min-max 归一化 + 单点/同坐标退化取 0.5 落中）；R2 绿（403/1002→权限错误态，前端零绕过零本地身份判断）；R3 绿（size=500 单次 + >500 部分轨迹限制标注）；G1 绿（vue-tsc rc=0 + pnpm build 成功）；G2 绿（package.json 零 diff + 后端 diff 空 + typed-router 含新路由）。明细见 handoff.md §4 |
| 本机门禁 | offline 逐位 **684=52/50/162/166/159/80/15** BUILD SUCCESS rc=0（纯前端七模块与基线逐位不变）；契约门双态 rc=0（在途 `--open TASK-213 --baseline=cc9ce4a` + 收口无参）；token 29 项 post-C01 2030 / close 2030（只增不减）；词面门改动文件集 CI-exact ZERO_HIT + 探针阳性对照 HIT；`git diff --check` rc=0；行尾新文件纯 LF+末尾换行无 BOM、改文件保持基线各自身 |
| web | `pnpm run type-check` rc=0；`pnpm run build` 本机成功（TMP 重定向仓内 .trae/tmp 规避 esbuild temp 清理）；typed-router.d.ts 由构建再生成 13 行，CI `git diff --exit-code` 判据前提成立 |
| 是否到达外部门槛 | **未到达**：两笔均未 push、未建 PR、无 CI run；MEDIUM 档外部终验（指导亲核 + CI 外部终验收口）待指导侧推动推送后补记 |
| 未覆盖 | `--mode=online`、CI 全流程、真实网关注入 X-User-Id 的跨进程 403 链路、真实浏览器可视交互未跑；真实地图底图（Leaflet+OSM）为设计性非目标；无性能/吞吐声明 |
| 偏差 | D1 台账笔自指哈希（沿 TASK-178/183/204/205/206/207/208/209/210/211 先例）；D2 client.ts 既有 getRecordPoints 满足 size 参数，未改动（C-01 落 4 文件）；D3 本机 build 成功无 esbuild 阻断；D4 tasks.json 重写补回末尾换行。明细见 handoff.md §8 |
- 补记（指导侧封盘笔，2026-10-13）：推送 70cb954..7f1f9f8（4 笔=TASK-211 封盘笔+TASK-213 派发/C-01/C-02）后第 52 次 CI（run 38273487517，head=7f1f9f8）conclusion=success 全 job 绿（web/build）——offline 684、镜像、Trivy、词面门、compose config、web build/type-check 全过。MEDIUM 档三环节终裁（指导亲核 PASS：git 链 cc9ce4a..7f1f9f8 恰 8 文件与白名单全等、后端/api/common/pom/sql/ci/scripts/compose/package.json diff 全空；R2 归属链抽查——前端零本地身份判断、403/1002→权限错误态服务端权威；R1 投影抽查——min-max 归一化/退化取 0.5/y 翻转北在上，与回传零矛盾；回传 vs 实况一致），TASK-213 封盘（E3 轨迹页课题收官）。课题终态：web 新增 /record-detail 轨迹页消费既有 GET /api/records/{id}/points（归属由服务端 TASK-204 校验裁决），min-max 归一化 SVG 投影（polyline+起点绿/终点红+逐点悬停 seq/speed/ts，单点/同坐标退化落中），摘要头经既有 verify-result 失败降级仅轨迹；入口=首页最近记录「查看轨迹」；size=500 单次拉取（>500 部分轨迹已知限制）；零后端、零新依赖（package.json 零 diff）、client.ts 未改动（D2）。offline 684 逐位不变，token 2030 持平。C-01 7e4be36（4 文件+203/−0）/C-02 7f1f9f8。 |

## 验收记录：`TASK-214`（2026-10-13，mapmatch 契约与偏好鉴权路径测试补强，纯测试 MEDIUM 档）

| 项 | 内容 |
| --- | --- |
| 开工基线 | 派发笔 `4431dbf`（提案三件套 + 任务书）；工作树干净；offline 基线 684=52/50/162/166/159/80/15；token 29 项开工账单参照 SUM=2030 |
| C-01 实施笔 | `cb5c0f1` `test(mapmatch): 契约校验与偏好鉴权路径测试补强（TASK-214）`，恰 4 文件 = §4 白名单（MapMatchRequestValidationTest 新 / MapMatchControllerTest 新 / MatchPropertiesTest 新 / NotificationControllerTest 增补），+321／−0 |
| C-02 台账笔 | 本笔 `docs(mailbox): 登记TASK-214测试补强验收与台账闭环（TASK-214）`，恰 4 文件（tasks.json 勾选 / spec.md §7 / handoff.md / PLAN.md），自指哈希沿 D1 先例 |
| 判别式红绿 | R1a 绿（空 points 恰 1 条 NotEmpty，path=points）；R1b 绿（20000 合法 / 20001 恰 1 条 Size）；R1c 绿（seq=null→points[0].seq、lat=90.5→points[0].lat、lng=null→points[0].lng 各恰 1 条级联违规）；R1d 绿（全合法零违规）；R2 绿（Result.success 包装 + data 同实例 + 请求实例透传 assertSame）；R3a 绿（默认值 200/50/25/300 承重契约逐位）；R3b 绿（mapmatch.match.* 四键 Binder 覆盖）；R4a/R4b 绿（authEnabled=true + X-User-Id=42 → getPreferences(42L)/updatePreferences(42L, body) 命中 SUCCESS）；R4c 绿（claimed=999≠42 → FORBIDDEN(1002) 偏好服务零触达）；G1/G2 绿。明细见 handoff.md §4 |
| 本机门禁 | offline 逐位 **698=52/50/165/166/159/80/26** BUILD SUCCESS rc=0（mapmatch 15→26 净增 +11、user 162→165 净增 +3，五不变模块逐位持平）；static 既有范围双跑 rc=0（--static=record-service + --static 缺省 leaderboard）；契约门双态 rc=0（在途 --open + 收口 --baseline=4431dbf 判据 B 白名单 8 token 全等实审）；token 29 项 post-C01 2030 / close 2030（只增不减，改动集零凭证字面量佐证）；词面门改动文件集 CI-exact ZERO_HIT + 探针阳性对照 HIT；`git diff --check` rc=0；行尾三新文件纯 LF+末尾换行无 BOM、改文件保持基线 |
| 是否到达外部门槛 | **未到达**：两笔均未 push、未建 PR、无 CI run；MEDIUM 档外部终验（指导亲核 + CI 外部终验收口）待指导侧推动推送后补记 |
| 未覆盖 | `--mode=online`、CI 全流程、MockMvc 层 400 校验映射端到端、seq 负值与 lng 越界等未注册校验形态、Nacos 运行时叠加未测（纯 Binder 语义）；无性能/吞吐断言 |
| 偏差 | D1 台账笔自指哈希（沿先例）；D2 任务书 S1「既有 17 测」与实测 14 测为措辞偏差，仅追加预注册 3 测后总数恰 17（diff +52/−0 纯追加，user 162→165 与 §6 口径吻合）；D3 token 权威 29 项清单由指导侧持有复测（沿 TASK-213 同口径）；D4 tasks.json 末尾换行前置保留。明细见 handoff.md §8 |
- 补记（指导侧封盘笔，2026-10-13）：推送 7f1f9f8..a70340d（4 笔=TASK-213 封盘笔+TASK-214 派发/C-01/C-02）后第 53 次 CI（run 38344140935，head=a70340d）conclusion=success 全 job 绿（web/build）——offline、镜像、Trivy、词面门、compose config、web build/type-check 全过。MEDIUM 档三环节终裁全过（指导亲核 PASS：git 链 8 文件全等+红线面 diff 全空+R1c 级联/R2 透传/R3b Binder/R4c 越权 403 定向抽查零矛盾+规模 321=130+77+62+52 逐文件核对；回传 vs 实况零矛盾；CI success 外部终验），TASK-214 封盘（E4 测试补强课题收官）。课题终态：mapmatch 契约测试三件——MapMatchRequestValidationTest 7 测锁 @NotEmpty 空 points/@Size 20000 边界/级联三形态（points[0].seq/lat/lng propertyPath 精确）/全合法零违规、MapMatchControllerTest 2 测（Result.success 包装+请求同实例透传双 assertSame 防 @Data equals 假绿）、MatchPropertiesTest 2 测（默认值 200/50/25/300 黄金值承重契约+Binder 前缀覆盖，MutablePropertySources 无系统源）；NotificationControllerTest 增补 3 测（偏好鉴权态正向×2+claimed 不一致 403+偏好服务零触达）。offline 基线 684→698（mapmatch 15→26 净增+11、user 162→165 净增+3，五模块逐位不变），token 2030 持平。已知限制（非阻断）：MockMvc 层 400 映射端到端未测（纯 Validator 语义承载）、Nacos 运行时叠加未测（纯 Binder 语义）。C-01 cb5c0f1（4 文件+321/−0）/C-02 a70340d。 |

## 验收记录：`TASK-215`（2026-10-13，web 前端测试基建第一阶段 Vitest + token/client 锚点测试，MEDIUM 档）

| 项 | 内容 |
| --- | --- |
| 开工基线 | 派发笔 `e90fe6a`（提案三件套 + 任务书）；工作树干净；offline 基线 698=52/50/165/166/159/80/26（web 不进 offline）；token 29 项开工账单参照 SUM=2030 |
| C-01 实施笔 | `bc448db` `test(web): Vitest测试基建与token/client拦截器锚点测试（TASK-215）`，恰 7 文件 = §4 白名单（package.json / pnpm-lock.yaml / vite.config.ts / token.test.ts 新 / client.test.ts 新 / ci.yml 单 step / web README），+634／−2 |
| C-02 台账笔 | 本笔 `docs(mailbox): 登记TASK-215测试基建验收与台账闭环（TASK-215）`，恰 4 文件（tasks.json 勾选 / spec.md §7 / handoff.md / PLAN.md），自指哈希沿 D1 先例 |
| 判别式红绿 | R1 绿（vitest 2 套 13 测全绿 + type-check rc=0 + build 成功 + typed-router diff 净）；R2a-f 绿（TOKEN_KEYS 精确 / setTokens 双键回环 / clearTokens 清含 refresh 键 / 无 handler 仅清理不抛 / handler 被调 / handler 异常吞没）；R3a-g 绿（无 token 无 Authorization / 有 token Bearer / code=0 透传 body / code!=0 err.code+message / /api/auth/* 401 零 refresh / 业务 401 refresh(withCredentials) 后携新 token 单次重试 / refresh 自身 401 触发 auth-fail）；G1/G2/G3 绿。明细见 handoff.md §4 |
| 本机门禁 | offline 逐位 **698=52/50/165/166/159/80/26** BUILD SUCCESS exit 0（纯前端七模块与基线逐位不变）；web `pnpm test` rc=0（vitest 3.2.7 / 2 套 13 测，TMP 重定向 .trae/tmp）、type-check rc=0、build 成功（3283 modules）、typed-router diff rc=0；契约门双态 rc=0（在途 --open TASK-215 --baseline=e90fe6a + 收口无参）；token 29 项 post-C01 2030 / close 2030（只增不减，改动集零真实凭证字面量佐证）；词面门改动文件集 CI-exact ZERO_HIT + 探针阳性对照 HIT；`git diff --check` rc=0；行尾两新文件纯 LF+末尾换行无 BOM、改文件保持基线各自身（ci.yml CRLF、其余 LF） |
| 是否到达外部门槛 | **未到达**：两笔均未 push、未建 PR、无 CI run；MEDIUM 档外部终验（指导亲核 + CI 外部终验收口，web job 含新 test step）待指导侧推动推送后补记 |
| 未覆盖 | `--mode=online`、CI 全流程（含新 web test step 真实执行）、真实浏览器可视交互、组件挂载与 E2E 未跑；R3 以 adapter 注入验证拦截器语义、未起真实 HTTP/网关；无性能/吞吐断言 |
| 偏差 | D1 台账笔自指哈希（沿先例）；D2 vite.config.ts 编辑器引入 1 CR 已归一化纯 LF；D3 两新测试文件末尾 LF 补齐后重跑仍绿；D4 web README「四条命令」表述因「登记一节」范围限制保留原样、新节已明示新增 step。明细见 handoff.md §8 |
- 补记（指导侧封盘笔，2026-10-13）：推送 a70340d..45f347d（4 笔=TASK-214 封盘笔+TASK-215 派发/C-01/C-02）后第 54 次 CI（run 38348221360，head=45f347d）conclusion=success 全 job 绿（web/build）——offline、镜像、Trivy、词面门、compose config、web type-check/**新 test step 真实 CI 首跑**/build/typed-router diff 全过。MEDIUM 档三环节终裁全过（指导亲核 PASS：git 链 11 文件全等+**ci.yml diff 恰 3 行单 step**（Type check 后 Build 前）+package.json dependencies 段零 diff/devDeps 恰 vitest+happy-dom+生产 web src 零 diff（diff 中 web/src 仅两个 __tests__ 新文件）+R3f 快照断言修正属实非弱化；回传 vs 实况零矛盾；CI success 外部终验），TASK-215 封盘（E8 前端测试基建课题收官）。课题终态：web 测试基线从零到一——vitest 3.2.7 + happy-dom 21.0.1（devDeps 恰两个）、scripts.test=vitest run、vite.config.ts test 字段（@ alias 共享）；锚点测试 2 套 13 测（token 6 测锁 TASK-205 凭证姿态：双键回环/存量 refresh_token 清理/auth-fail handler 注册/触发/异常吞没；client 拦截器 7 测经 adapter 注入锁安全语义：Bearer 注入有/无、code=0 透传、code!=0 带码 reject、401 auth 端点旁路零 refresh、业务 401 单次 refresh 重试（withCredentials+新 token 重放）、refresh 失败登出）；CI web job 增 Web unit tests step。offline 基线 698 逐位不变（web 不进 offline），token 2030 持平。已知限制（非阻断）：R3 为 adapter 注入语义验证未起真实 HTTP/网关、组件挂载与 E2E 未跑（选型裁决后置）；D4 README「四条命令」旧表述留后续随手订正。C-01 bc448db（7 文件+634/−2）/C-02 45f347d。 |

## 验收记录：`TASK-216`（2026-10-13，Prometheus 抓取 target 容器化形态对齐（E11），纯配置 MEDIUM 档）

| 项 | 内容 |
| --- | --- |
| 开工基线 | 派发笔 `146fd0e`（提案三件套 + 任务书）；工作树干净；offline 基线 698=52/50/165/166/159/80/26；token 29 项开工账单参照 SUM=2030 |
| C-01 实施笔 | `721ae46` `fix(obs): Prometheus抓取target对齐容器化服务名（TASK-216）`，恰 2 文件 = §4 白名单（prometheus/prometheus.yml / README.md），+17／−15 |
| C-02 台账笔 | 本笔 `docs(mailbox): 登记TASK-216抓取target对齐验收与台账闭环（TASK-216）`，恰 4 文件（tasks.json 勾选 / spec.md §7 / handoff.md / PLAN.md），自指哈希沿 D1 先例 |
| 判别式红绿 | R1 绿（六 target 改 compose 服务名 + 「抓取地址说明」注释块重写；scrape_interval/evaluation_interval/rule_files/metrics_path/job 名零动；LF 保持）；R2 绿（README 监控段姿态改写为容器化服务名，ADR-0003 链接与生产 actuator 收敛告警口径零动；CRLF 保持）；R3 绿（全栈六 target 全 up，up 查询六序列值=1，Grafana /login=200）；G1/G2/G3 绿。明细见 handoff.md §4 |
| 本机门禁 | offline 逐位 **698=52/50/165/166/159/80/26** BUILD SUCCESS rc=0（零 Java 改动与基线逐位不变）；契约门双态 rc=0（在途 `--open TASK-216 --baseline=146fd0e` + 收口无参）；token 29 项 post-C01 2030 / close 2030（只增不减）；词面门改动集 CI-exact ZERO_HIT + 探针阳性对照 HIT；`git diff --check` rc=0；行尾 prometheus.yml=w/lf、README.md=w/crlf 各保基线 |
| 是否到达外部门槛 | **未到达**：两笔均未 push、未建 PR、无 CI run；MEDIUM 档外部终验（指导亲核 + CI 外部终验收口）待指导侧推动推送后补记 |
| 未覆盖 | `--mode=online`、CI 全流程、告警 firing 实测、长时抓取稳定性；R3 实测以临时脚手架（gitignored、用完删）使服务可引导 |
| 偏差 | D1 台账笔自指哈希（沿先例）；D2 本机 .env 换行损坏本地修复（未入库）；D3 原生 Redis 服务占 6379 → 临时覆盖 `!override` 重映射 16379（未入库、验证后删）；D4 六服务镜像陈旧（构建于 2026-10-10，早于 TASK-198/200/201/205 修复）→ 按原始命令 `--build` 重建，重建后暴露既有 logback `logs/` 目录缺陷（非本课题、修它越白名单，实测期以 tmpfs 脚手架，缺陷登记非阻断）；D5 MySQL 数据卷口令漂移 → `ALTER USER` 对齐 .env（非破坏）。明细见 handoff.md §8 |
| 状态 | TASK-216 全链路闭环，**外部终验待推送后外部门槛 CI 绿**；E11 抓取 target 容器化对齐落地；既有镜像 logback `logs/` 缺陷建议单独立项 |
- 补记（指导侧封盘笔，2026-10-13）：推送 45f347d..1e63ab5（4 笔=TASK-215 封盘笔+TASK-216 派发/C-01/C-02）后第 55 次 CI（run 38359111158，head=1e63ab5）conclusion=success 全 job 绿（web/build）——offline、镜像、Trivy、词面门、compose config、web type-check/test/build 全过。MEDIUM 档三环节终裁全过（指导亲核 PASS：git 链恰 6 文件全等+R1 diff 恰六 target 行+注释块 13−/14+ 逐行核对零夹带+R2 diff 恰监控段 2−/3+ ADR-0003 链接与生产告警口径逐字未动+compose/alert-rules/grafana/六服务 src 零 diff；回传 vs 实况零矛盾；CI success 外部终验），TASK-216 封盘（E11 观测链路对齐课题收官）。课题终态：Prometheus 抓取六 target 由宿主机进程口径（host.docker.internal:808x）切换为容器化权威口径（compose 服务名 DNS 直达，F20 对齐），Linux 宿主机下五后端抓取断裂的既有隐患消除；R3 全栈 14 服务实测三断言全绿（六 targets up/up query 六序列值 1/Grafana 200）；告警规则与仪表盘按 job 名匹配零影响。指导侧偏差裁决：D2-D5 本机环境修复（.env 换行/Redis 6379 重映射/镜像重建/MySQL 卷口令对齐/tmpfs 脚手架）均未入库用完即删，属获取 R3 实证之合理本地操作非越界。新课题候选 E18 登记：既有镜像缺陷——TASK-198 logback 以相对路径 logs/<svc>.log 写盘而镜像未建可写 logs/ 目录且非 root 运行，六服务容器化裸引导全败（本课题以 tmpfs 脚手架绕过），需修 Dockerfile（mkdir logs+chown）或 logback 路径参数化，建议单独立项。offline 基线 698 逐位不变，token 2030 持平。C-01 721ae46（2 文件+17/−15）/C-02 1e63ab5。 |

## 验收记录：`TASK-217`（2026-10-13，镜像可写日志目录与非 root 引导修复（E18），MEDIUM 档）

| 项 | 内容 |
| --- | --- |
| 开工基线 | 派发笔 `e408e0c`（提案三件套 + 任务书）；工作树干净；offline 基线 698=52/50/165/166/159/80/26；token 29 项开工账单参照 SUM=2030（rev-aware 实测基线 2034，见偏差 D3） |
| C-01 实施笔 | `4ce4df8` `fix(build): 镜像可写日志目录与非root引导修复（TASK-217）`，恰 6 文件 = §4 白名单（gateway/user/record/verify/leaderboard/mapmatch 六 Dockerfile），+7／−7 |
| C-02 台账笔 | 本笔 `docs(mailbox): 登记TASK-217镜像日志目录修复验收与台账闭环（TASK-217）`，恰 4 文件（tasks.json 勾选 / spec.md §7 / handoff.md / PLAN.md），自指哈希沿 D1 先例 |
| 判别式红绿 | R1 绿（五服务各恰 1 行 RUN 扩展 mkdir/chown /app/logs，其余逐字节未动）；R2 绿（gateway 恰 2 行 = RUN 扩展 sentinel 目录 + ENTRYPOINT 加 -Dcsp.sentinel.log.dir）；R3 绿（零脚手架全栈：六容器 healthy、六池 up、六 job up=1、Grafana /login=200、/app/logs/<svc>.log JSON 行、gateway sentinel 目录在）；G1/G2/G3 绿。明细见 handoff.md §4 |
| 本机门禁 | offline 逐位 **698=52/50/165/166/159/80/26** BUILD SUCCESS rc=0（零 Java 改动与基线逐位不变）；契约门双态 rc=0（在途 --open TASK-217 --baseline=e408e0c 于 C-01 窗口亲跑 + 收口无参）；token 29 项 post-C01 2034 / close 2034（只增不减）；词面门改动集 CI-exact ZERO_HIT + 全仓 CI-exact ZERO_HIT + 探针阳性对照 HIT；`git diff --check` rc=0；行尾五文件 w/lf、leaderboard w/crlf 各保基线 |
| 是否到达外部门槛 | **未到达**：两笔均未 push、未建 PR、无 CI run；MEDIUM 档外部终验（指导亲核 + CI 镜像构建门外部终验收口）待指导侧推动推送后补记 |
| 未覆盖 | `--mode=online`、CI 全流程、CI 镜像构建门、日志轮转/收集（Loki 等）与长时稳定性；R3 为全栈零脚手架裸引导直接通过（非脚手架绕行） |
| 偏差 | D1 台账笔自指哈希（沿先例）；D2 原生 Redis 占 6379 → 临时 gitignored 覆盖重映射 16379（未入库、验证后删）；D3 token 参照 2030 vs 实测基线 2034（基线漂移，单向门满足）；D4 契约门 Dockerfile 无扩展名提取盲区（README 已知盲区）致落盘后基线比对假性红，故在途门于 C-01 窗口亲跑；D5 MySQL 数据卷口令此前已对齐（复用既有卷）。明细见 handoff.md §8 |
| 状态 | TASK-217 全链路闭环，**外部终验待推送后外部门槛 CI 绿**；E18 镜像可写日志目录缺陷修复落地，六服务容器化零脚手架裸引导恢复 |
- 补记（指导侧封盘笔，2026-10-13）：推送 1e63ab5..1cb4383（4 笔=TASK-216 封盘笔+TASK-217 派发/C-01/C-02）后第 56 次 CI（run 38365738324，head=1cb4383）conclusion=success 全 job 绿（web/build）——**镜像构建门以修复后六 Dockerfile 首建全绿**（外部终验核心），offline、Trivy、词面门、compose config、web 全过。MEDIUM 档三环节终裁全过（指导亲核 PASS：git 链恰 10 文件全等+六 Dockerfile diff 逐行核对零夹带（五服务各恰 1 行 RUN 扩展、gateway 恰 2 行含 sentinel dir 参数）+R3 零脚手架六断言证据详实（六 healthy/六 targets up/up query 六序列值 1/Grafana 200/容器内 JSON 日志行/sentinel-record.log app:app 属主）+回传 vs 实况零矛盾），TASK-217 封盘（E18 镜像日志目录缺陷课题收官）。课题终态：F20 容器化裸引导阻断根治——六镜像运行阶段 mkdir /app/logs（+gateway /app/logs/sentinel 经 csp.sentinel.log.dir 官方属性统一日志根），TASK-198 JSON 日志链路在容器内真实落盘（user-service.log JSON 行+sentinel-record.log 实证）；TASK-216 的 tmpfs 绕法正式退役。指导侧偏差裁决：D2 Redis 端口重映射为与缺陷正交之本地处置合理；D3 token 2034 为既有基线漂移（e408e0c 已存在+4）只增不减门满足；D4 Dockerfile 无扩展名提取盲区沿 README 已知盲区先例在途窗口亲跑。新基准：token 29 项基准读数更新为 2034。C-01 4ce4df8（6 文件+7/−7）/C-02 1cb4383。 |

## 验收记录：TASK-218（GPX 轨迹导出，E13，MEDIUM 档，2026-10-13）

| 项 | 内容 |
| --- | --- |
| 开工基线 | 派发笔 `9df3d6c`（提案三件套 + 任务书）；工作树干净；offline 基线 698=52/50/165/166/159/80/26；token 29 项基准 2034（沿 TASK-217 更新后口径） |
| C-01 实施笔 | `70d9679` `feat(web): 轨迹GPX导出纯前端实现（TASK-218）`，恰 4 文件 = §4 白名单（web/src/utils/gpx.ts 新 / web/src/utils/__tests__/gpx.test.ts 新 / web/src/pages/record-detail.page.vue / web/README.md），+147 |
| C-02 台账笔 | 本笔 `docs(mailbox): 登记TASK-218 GPX导出验收与台账闭环（TASK-218）`，恰 4 文件（tasks.json 勾选 / spec.md §7 回填 / handoff.md 新建 / PLAN.md 纯追加），C-02 自指哈希 D1 先例 |
| 判别式红绿 | R1 绿（GPX 1.1 完整结构、trkpt 计数与序、lat/lon 6 位小数、time ISO8601、name 转义、空数组抛错）；R2 绿（gpx.test.ts 5 测全过，pnpm test 3 套 18 测 rc=0）；R3 绿（按钮 v-if points.length、a[download=record-{id}.gpx]、blob application/gpx+xml；type-check+build+代码审查承载，真实浏览器点击未覆盖）；G1/G2/G3 绿。明细见 handoff.md §4 |
| 本机门禁 | offline 逐位 **698=52/50/165/166/159/80/26** BUILD SUCCESS rc=0（零后端改动与基线逐位不变）；web `pnpm test` rc=0（3 套 18 测：token 6 + gpx 5 + client 7，TMP 重定向 .trae/tmp）、type-check rc=0、build 成功（3283 modules）、typed-router diff rc=0；契约门双态 rc=0（在途 --open TASK-218 --baseline=9df3d6c + 收口无参）；token 29 项两点 2034/2034 只增不减；词面门改动集 CI-exact ZERO_HIT rc=1 + 探针阳性对照 HIT rc=0；git diff --check rc=0；行尾两新文件纯 LF+末尾换行无 BOM、改文件保持基线（均 i/lf w/lf） |
| 是否到达外部门槛 | **未达到**：两笔均未 push、未建 PR、无 CI run；MEDIUM 档外部终验（指导亲核 + CI 外部终验收口，web job 含新 gpx 套件）待指导侧推动推送后补记 |
| 未覆盖/跳过 | `--mode=online`、CI 全流程、真实浏览器下载点击（沿 TASK-213 口径登记未覆盖）、GPX 第三方解析器兼容验证、>500 点部分轨迹导出口径（沿用页内已知限制说明） |
| 偏差 | D1 台账笔自指哈希（沿先例）；D2 词面门/token 模式文件落 gitignored .trae/tmp 不入库；D3 README 尾行一次修正（diff --check 自纠后复检干净）；D4 offline Nacos 连接噪音对计数零影响。明细见 handoff.md §8 |
| 状态 | TASK-218 本地闭环：E13 GPX 导出落地（纯前端零后端零依赖，vitest 3 套 18 测），外部终验待推送后补记 |
- 补记（指导侧封盘笔，2026-10-13）：推送 1cb4383..2e5cbf3（4 笔=TASK-217 封盘笔+TASK-218 派发/C-01/C-02）后第 57 次 CI（run 38371556173，head=2e5cbf3）conclusion=success 全 job 绿（web/build）——**gpx 新测试套件在 CI web job 真实通过**（3 套 18 测），offline、镜像、Trivy、词面门、compose config、web type-check/build 全过。MEDIUM 档三环节终裁全过（指导亲核 PASS：git 链恰 8 文件全等+gpx.ts 全文审读（escapeXml 顺序正确 & 最先防二次转义、GPX 1.1 结构完整、creator/name/属性值全转义、lat/lon toFixed(6)、time ISO8601、空抛错）+页面 diff 零夹带（按钮 v-if points.length 门控、handler=generateGpx→Blob(application/gpx+xml)→objectURL→a[download=record-{id}.gpx]→revoke 与预注册一致）；回传 vs 实况零矛盾），TASK-218 封盘（E13 GPX 导出课题收官）。课题终态：轨迹页「查看→带走」价值闭环——纯前端零后端零依赖 GPX 1.1 导出（utils/gpx.ts 纯函数 5 单测锁定：计数/顺序/精度/ISO8601/转义/空抛），TASK-215 vitest 基建首次承接新功能课题即复利。已知限制（非阻断）：真实浏览器下载点击未跑（TASK-213 口径）、GPX 第三方解析器兼容验证未做、>500 点部分轨迹导出沿页内已知限制。C-01 70d9679（4 文件+147）/C-02 2e5cbf3。 |

## 验收记录：TASK-219（配速趋势分析，P2，MEDIUM 档，2026-10-13）

| 项 | 内容 |
| --- | --- |
| 开工基线 | 派发笔 `8ba3072`（提案三件套 + 任务书）；工作树干净；offline 基线 698=52/50/165/166/159/80/26；token 29 项基线 2034 |
| C-01 实施笔 | `15e1e19` `feat(web): 轨迹配速趋势图纯前端实现（TASK-219）`，恰 4 文件 = §4 白名单（web/src/utils/pace.ts 新 / web/src/utils/__tests__/pace.test.ts 新 / web/src/pages/record-detail.page.vue / web/README.md），+263 |
| C-02 台账笔 | 本笔 `docs(mailbox): 登记TASK-219配速分析验收与台账闭环（TASK-219）`，恰 4 文件（tasks.json 勾选 / spec.md §7 回填 / handoff.md 新建 / PLAN.md 纯追加）；C-02 自指哈希沿 D1 先例 |
| 判别式红绿 | R1 绿（累计距离单调非递减、首点 0、pace=1000÷speed 精确、零/缺省 speed→paceSec null、空→[]、formatPace 边界、isDrawablePace 停顿规则）；R2 绿（pace.test.ts 6 测全过，pnpm test 4 套 24 测 rc=0）；R3 绿（区块门控 points>1 且 ≥1 可绘点、Y 反转快在上、停顿断线分段 polyline、悬停 dist+pace+ts、轴标签配速 min/max+总 km；type-check+build+代码审查承载，真实浏览器交互未覆盖）；G1/G2/G3 绿。明细见 handoff.md §4 |
| 本机门禁 | offline 逐位 **698=52/50/165/166/159/80/26** BUILD SUCCESS rc=0（零后端改动，与基线逐位不变）；web `pnpm test` rc=0（4 套 24 测：token 6 + gpx 5 + client 7 + pace 6，TMP 重定向 .trae/tmp）、type-check rc=0、build 成功、typed-router diff rc=0；契约门双态 rc=0（在途 --open TASK-219 --baseline=8ba3072 + 收口无参）；token 29 项两点 2034/2034 只增不减；词面门改动集 CI-exact ZERO_HIT rc=1 + 探针阳性对照 HIT rc=0；git diff --check rc=0；行尾两新文件纯 LF+末尾换行无 BOM、改文件保持基线（均 i/lf w/lf） |
| 是否到达外部门槛 | **未达到**：两笔均未 push、未建 PR、无 CI run；MEDIUM 档外部终验（指导亲核 + CI 外部终验收口，web job 含新 pace 套件）待指导侧推动推送后补记 |
| 未覆盖/跳过 | `--mode=online`、CI 全流程、真实浏览器配速区块交互（沿 TASK-213/218 口径登记未覆盖）、>500 点部分轨迹（沿用页内已知限制说明） |
| 偏差 | D1 台账笔自指哈希（沿先例）；D2 词面门/token 模式文件落 gitignored .trae/tmp 不入库；D3 offline 后台任务包装器报 exit 1 属采集伪影（同构管道 $LASTEXITCODE 验证 0，Maven log BUILD SUCCESS 为权威）；D4 offline Nacos 连接噪音对计数零影响。明细见 handoff.md §8 |
| 状态 | TASK-219 本地闭环：P2 配速趋势分析落地（纯前端零后端零依赖，vitest 4 套 24 测），外部终验待推送后补记 |
- 补记（指导侧封盘笔，2026-10-13）：推送 2e5cbf3..96e9777（4 笔=TASK-218 封盘笔+TASK-219 派发/C-01/C-02）后第 58 次 CI（run 38380170462，head=96e9777）conclusion=success 全 job 绿（web/build）——**pace 新测试套件在 CI web job 真实通过**（4 套 24 测），offline、镜像、Trivy、词面门、compose config、web type-check/build 全过。MEDIUM 档三环节终裁全过（指导亲核 PASS：pace.ts 全文审读（haversine 标准公式 R=6371/累计距离单调非递减首点 0/pace=1000÷speed/null 归一含 undefined/formatPace 先整体取整再拆分避免 60 秒进位边界/isDrawablePace 规则与预注册一致）+页面 diff 抽查（Y 倒置 fy=(max−pace)/range 快在上+退化 pRange=0 取 0.5 沿 TASK-213 先例+停顿点 null 断线锚分段+悬停 dist/pace/ts+区块门控 points>1 且 ≥1 可绘点）；回传 vs 实况零矛盾），TASK-219 封盘（P2 配速趋势分析课题收官）。课题终态：记录详情页分析能力补全——haversine 累计距离+配速换算+停顿断线的纯函数三件套 6 单测锁定，vitest 基建第二次承接新功能课题复利（4 套 24 测）；轨迹页从「看轨迹」进阶「读配速」。已知限制（非阻断）：真实浏览器配速区块交互未跑（TASK-213/218 口径）、>500 点部分轨迹沿页内已知限制。C-01 15e1e19（4 文件+263）/C-02 96e9777。 |

## 验收记录：TASK-220（连续打卡聚合端点与个人仪表盘卡片，P1，MEDIUM 档，2026-10-13）

| 项 | 内容 |
| --- | --- |
| 开工基线 | 派发笔 `db1542d`（提案三件套 + 任务书）；工作树干净；offline 基线 698=52/50/165/166/159/80/26；token 29 项基线 2034 |
| C-01 实施笔 | `8853079` `feat(record): 连续打卡聚合端点与个人仪表盘卡片（TASK-220）`，恰 10 文件 = §4 白名单（MyStreakDTO 新 / StreakCalculator 新 / StreakCalculatorTest 新 / SportRecordService / RecordController / SportRecordServiceTest / RecordControllerTest / client.ts / index.page.vue / README.md），+357/−2 |
| C-02 台账笔 | 本笔 `docs(mailbox): 登记TASK-220连续打卡验收与台账闭环（TASK-220）`，恰 4 文件（tasks.json 勾选 / spec.md §7 回填 / handoff.md 新建 / PLAN.md 纯追加）；C-02 自指哈希沿 D1 先例 |
| 判别式红绿 | R1a 绿（MyStreakDTO 语义）/ R1b 绿（StreakCalculator 7 场景全过）；R2 绿（status 过滤恰 {PASSED,RE_PASSED} captor 参数值集精确断言 + createdAt 映射自然日去重 + 输出断言）；R3 绿（/my/streak 归属/包装/错误语义与 /my 全同，四测）；R4 绿（streak 卡当前/最长渲染，type-check+build+代码审查承载，真实浏览器未覆盖）；R5 绿；G1/G2/G3 绿。明细见 handoff.md §4 |
| 本机门禁 | offline 逐位 **52/50/165/178/159/80/26** BUILD SUCCESS rc=0（record 166→178 恰 +12 = StreakCalculatorTest 7 + SportRecordServiceTest 1 + RecordControllerTest 4，六模块逐位不变）；web `pnpm test` rc=0（4 套 24 测不变，TMP 重定向 .trae/tmp）、type-check rc=0、build 成功（3283 modules）、typed-router diff rc=0；契约门双态 rc=0（在途 --open TASK-220 --baseline=db1542d + 收口无参）；token 29 项两点 2034/2034 只增不减；词面门改动集 CI-exact ZERO_HIT rc=1 + 探针阳性对照 HIT rc=0；git diff --check rc=0；行尾三新文件纯 LF+末尾换行无 BOM、改文件保持基线行尾 |
| 是否到达外部门槛 | **未达到**：两笔均未 push、未建 PR、无 CI run；MEDIUM 档外部终验（指导亲核 + CI 外部终验收口）待指导侧推动推送后补记 |
| 未覆盖/跳过 | `--mode=online`、CI 全流程、真实浏览器连续打卡卡交互（沿 TASK-213/218/219 口径登记未覆盖）、与第三方健身 App streak 口径对照验证 |
| 偏差 | D1 台账笔自指哈希（沿先例）；D2 词面门/token 模式文件落 gitignored .trae/tmp 不入库；D3 offline Nacos 连接噪音对计数零影响；D4 web build chunk 体积告警为既有非阻断 advisory。明细见 handoff.md §8 |
| 状态 | TASK-220 本地闭环：P1 连续打卡聚合端点与仪表盘卡落地（后端只读聚合 + 前端卡，零新依赖零新表零新 SQL 形态），外部终验待推送后补记 |
- 补记（指导侧封盘笔，2026-10-13）：推送 96e9777..43598e8（4 笔=TASK-219 封盘笔+TASK-220 派发/C-01/C-02）后第 59 次 CI（run 38387682776，head=43598e8）conclusion=success 全 job 绿（web/build）——offline（含 record 178 新基线）、镜像、Trivy、词面门、compose config、web 全过。MEDIUM 档三环节终裁全过（指导亲核 PASS：StreakCalculator 全文审读（anchor 语义=含 today 从 today 否则含 yesterday 从 yesterday 连击活到当日结束否则 0+longest 排序 run 扫描断档重置+HashSet 归一容忍乱序重复+today 注入式便于固定日期断言）+服务层 myStreak 抽查（单列投影 select(getCreatedAt)+eq userId+in status 恰 {PASSED, RE_PASSED}+toLocalDate distinct+LocalDate.now() 注入）；回传 vs 实况零矛盾），TASK-220 封盘（P1 连续打卡课题收官）。课题终态：粘性核心功能落地——有效打卡=PASSED/RE_PASSED 终态（与贡献口径一致防刷）、同日多条算一天、今日无记录从昨日续算（连击活到当日结束）；GET /api/records/my/streak 只读聚合端点（/my 同构归属）+StreakCalculator 纯函数 7 场景单测+仪表盘第 4 统计卡。offline 基线 698→710（record 166→178 恰 +12=StreakCalculatorTest 7+ServiceTest 1+ControllerTest 4，六模块逐位不变），token 2034 持平。已知限制（非阻断）：真实浏览器卡片交互未跑（沿先例口径）、跨时区日界语义未做多时区适配（服务器时区口径）。C-01 8853079（10 文件+357/−2）/C-02 43598e8。 |
## 验收记录：TASK-221（记录可见性三态与读取面访问门，P3，FULL 档，2026-10-13）

| 项 | 内容 |
| --- | --- |
| 开工基线 | 派发笔 1ee1cdc（提案三件套 + 任务书）；工作树干净；offline 基线 52/50/165/178/159/80/26；token 29 项基线 2034 |
| C-01 实施笔 | ff80635 feat(record): 记录可见性三态与读取面访问门（TASK-221），恰 21 文件 = S4 白名单全等（RecordVisibility 新 / add-record-visibility.sql 新 / 无新增测试类——测试均并入既有类），+666/−23 |
| C-02 台账笔 | 本笔 docs(mailbox): 登记TASK-221可见性三态验收与台账闭环（TASK-221），恰 4 文件（tasks.json 勾选 / spec.md §7 回填 / handoff.md 新建 / PLAN.md 纯追加）；C-02 自指哈希沿 D1 先例 |
| 判别式红绿 | R1 绿（枚举 0/1/2 + 迁移二跑幂等命中跳过分支 + 02 diff 恰一行）；R2 绿（门矩阵 13 组合，owner×3 放行/陌生人 PUBLIC 放行/好友 FRIENDS 放行/陌生人 FRIENDS 403/非 owner PRIVATE 403/降级态放行/UserApi 软降级 false 403）；R3 绿（verify-result 越权收口，非 owner PRIVATE→403 body 1002、owner→200、降级态持平）；R4 绿（null→0、显式 1/2 落库、越界 3007 拒绝）；R5 绿（点赞三端点同矩阵）；R6 绿（提交单选钮默认公开 + 我的记录徽标，type-check+build 承载）；G1/G2/G3 绿。明细见 handoff.md §4 |
| 本机门禁 | offline 逐位 **52/50/170/196/159/80/26** BUILD SUCCESS rc=0（record 178→196 恰 +18、user 165→170 恰 +5，其余五模块逐位不变，仅净增不下降）；record-service 静态 rc=0（0 Checkstyle violations/SpotBugs/PMD——唯一声明并强制三件套的改动模块）；web pnpm test rc=0（4 套 24 测不变）、type-check rc=0、build 成功（3283 modules）、typed-router diff rc=0；契约门双态 rc=0（在途 --open TASK-221 --baseline=1ee1cdc，判据 B 25 文件实质核全等） + 收口无参；token 29 项两点 2036/2036 只增不减且 ≥2034；词面门改动集 CI-exact ZERO_HIT rc=1 + 探针阳性对照 HIT rc=0；git diff --check rc=0；迁移幂等二跑在 scratch 库证明（第二次命中跳过分支）；行尾两新文件纯 LF+末尾换行无 BOM |
| 是否到达外部门槛 | **未达到**：两笔均未 push、未建 PR、无 CI run；FULL 档独立 review 复核待指导侧派发 |
| 未覆盖/跳过 | --mode=online、CI 全流程、真实浏览器可见性表单/徽标交互（沿 TASK-213/220 口径）、真实 MySQL/Redis/RocketMQ 跨服务好友判断联调（--it 未执行）、PRIVATE/FRIENDS 存量行视觉回归 |
| 偏差 | D1 C-02 自指哈希；D2 词面门/token/迁移验证临时件落 gitignored .trae/tmp；D3 --static=user-service/api 模块未声明三件套插件、通用配置下存在基线违规（未触碰端点/字段），静态门以 record-service 为准且 rc=0；D4/D5 offline 噪音与 build chunk 告警为既有；D6 独立复核发现 §2 缩写路径致契约门判据 B 失配 + 在途证据时点失实，已展开全路径重建 C-02 修复、双态实测 rc=0。明细见 handoff.md §8 |
| 状态 | TASK-221 本地闭环：记录可见性三态（PUBLIC/FRIENDS/PRIVATE）schema + 跨服务好友判断 + 读取面访问门，外部门槛与独立 review 待指导侧终裁与推送后补记 |
- 补记（指导侧封盘笔，2026-10-13）：FULL 档三环节终裁全过（指导亲核 PASS + 独立复核四块，复核曾发现阻断-1=handoff §2 缩写路径致契约门判据 B 失配+在途证据时点失实，已重建 C-02' d0e10b4 修复并增量复验四项全过）。批次推送（8a12876..d0e10b4 四笔）后外部 CI 三程：run 38411110326（head=d0e10b4）红且叠加 job 记录悬置/不可重试怪象——指导侧曾误判基建抖动；run 38412925568（head=fba7ee5，src 同树台账笔）复红定因=D7（FriendServiceTest:398 纯 Mockito 单测 getSqlSegment 解析 Friendship lambda 缺 TableInfo 惯用法，本地 offline 类序偶然掩盖、CI online 类序暴露，误判就此纠正）；D7 修复笔 1158db6（恰 3 文件 +9：惯用法补齐断言零删弱 + handoff §8 D7 如实登记 + spec §7 补记）随 TASK-222 派发笔 481e644 推送后，run 38417778846（head=1158db6）conclusion=success 全 job 绿（web/build）——外部终验达成（绿头含 D7 修复，如实双记）。TASK-221 封盘。C-01 ff80635 / C-02' d0e10b4 / D7 1158db6。

## 方向候选与待办（指导侧常备）

- 2026-10-13 建档：work/mailbox/BACKLOG.md 为方向候选与待办常备清单（指导侧维护，随立项/封盘更新状态）。首批评审：F1 好友动态流（首推）/F2 记录删除撤回/F3 周月运动目标/F4 我的记录筛选 + H1 E2E 真实浏览器验证批/H2 E5 生产部署姿态演练/H3 LIGHT 清理批；推荐序 F1 → H3 → 其余按需，近期上生产则 H2 提前。
- 本笔顺带修正 TASK-221 状态行历史断尾（补「侧终裁与推送后补记」闭行管道符）。
- 2026-10-14 常备政策（指导侧裁决，进后续任务书 S6）：**offline 门禁分级**——判据唯一：G2 白名单是否跨模块边界或触基建。①白名单严格为「单后端模块 src+test + web + 文档」（不触 api/pom/scripts/sql/测试基建）→ 允许收窄为 `mvn-verify.sh --mode=offline --pl <module> test` 实测该模块逐位，其余模块沿最近绿基线（origin CI 绿）+ 白名单外零 diff 论证不变，台账按此口径登记；②触 api 模块/多后端模块/pom/scripts/sql/测试基建 → 全量逐位，不得收窄（先例：TASK-221 改 record 爆 user-service D7、api DTO 变更六服务重编译、TASK-222 横跨四模块——「以为无关」三次栽坑）；③纯 web / 纯台账笔维持既有豁免（web 四件套 / 仅 diff --check）。合法性论证：其余模块源码与绿基线逐字节相同（白名单外 diff=0）、模块间测试不共享 JVM、offline 无真实跨服务调用，重跑只是复读；外部终验仍由 CI 全量兜底从不省。静态门（仅改动模块）、词面/token/契约门（仅改动集）维持既有收窄形态。首例适用：TASK-223 形状本可收窄（record+web 零 api），但已跑全量不重跑；政策自下一课题生效。

## 验收记录：TASK-222（好友动态订阅与跨服务聚合读路径，P4，FULL 档，2026-10-13）

| 项 | 内容 |
| --- | --- |
| 开工基线 | 派发笔 84a6e2d（TASK-221 封盘笔）；工作树干净；offline 基线 52/50/170/196/159/80/26；token 29 项基线 2036 |
| C-01 实施笔 | 9db644a feat(feed): 好友动态订阅与跨服务聚合读路径（TASK-222），恰 16 文件 = S4 白名单全等（FeedItemDTO 新 / feed.page.vue 新 / typed-router.d.ts 再生成含 /feed 路由沿 TASK-213 提交生成物先例），+730，2 新文件 |
| C-02 台账笔 | 本笔 docs(mailbox): 登记TASK-222好友动态验收与台账闭环（TASK-222），恰 4 文件（tasks.json 勾选 / spec.md §7 回填 / handoff.md 新建 / PLAN.md 纯追加）；C-02 自指哈希沿 D1 先例 |
| 判别式红绿 | R1 绿（auth on feed 恰含 own任意+friends PUBLIC/FRIENDS，createdAt DESC，陌生人 user_id IN 排除）；R2 绿（degrade caller=null→PUBLIC 全站、缺头 1001 /my 同构）；R3 绿（/internal/friends/ids 双向还原 + fallback 空→仅本人 fail-safe）；R4 绿（昵称复用既有 listUsersByIds 当页批量、fallback 空→回退 userId 字符串）；R5 绿（/feed 卡片要素+顶栏「动态」+a-pagination，type-check/build/test 承载）；R6 绿（既有 visibility 门零 diff + feed 预过滤与 assertCanView 等价论证=返回集⊆门可读集，never 越过门）；G1/G2/G3 绿。明细见 handoff.md §4 |
| 本机门禁 | offline 逐位 **52/50/173/206/159/80/26** BUILD SUCCESS rc=0（user 170→173 恰 +3、record 196→206 恰 +10，其余五模块逐位不变，仅净增不下降）；三承载新断言的测试类隔离单跑绿（FriendServiceTest 31 / SportRecordServiceTest 63 / RecordControllerTest 25，D7 惯用法 @BeforeEach init TableInfo）；record-service 静态 rc=0（0 Checkstyle violations/SpotBugs Error size 0/PMD——唯一声明并强制三件套的改动模块）；web pnpm test rc=0（4 套 25 测）、type-check rc=0、build 成功（3283 modules，typed-router 再生成 +13 且二次构建 idempotent）；契约门双态 rc=0（在途 --open TASK-222 --baseline=84a6e2d 判据 A 待办放行 + 判据 B 一致，收口无参）；token 29 项两点 2036/2036 只增不减且 ≥2034，改动集 token 扫描 ZERO_HIT；词面门改动集 CI-exact ZERO_HIT rc=1 + 探针阳性对照 HIT rc=0；git diff --check rc=0；行尾两新文件纯 LF+末尾换行无 BOM |
| 是否到达外部门槛 | **未达到**：两笔均未 push、未建 PR、无 CI run；FULL 档独立 review 复核待指导侧派发 |
| 未覆盖/跳过 | --mode=online、CI 全流程、真实浏览器 /feed 卡片/分页/顶栏交互（沿 TASK-213/220 口径）、真实 MySQL/Redis 跨服务联调（listFriendIds/listUsersByIds 真 Feign + feed 真 SQL，--it 未执行）、陌生人/降级态真实渲染视觉回归 |
| 偏差 | D1 feed 卡片不显示时长（FeedItemDTO 契约按 proposal 不含 duration，卡片据可用字段渲染，非设计变更）；D2 token/词面门/契约/tokencheck 临时件落 gitignored .trae/tmp；D3 --static=user-service/api 模块未声明三件套插件、通用配置下存在基线违规（未触碰代码），静态门以 record-service 为准且 rc=0；D4/D5 offline 噪音与 build chunk 告警为既有；D6 recon 确认昵称批量面复用既有 listUsersByIds（S1「有则复用」不加 briefs 端点）且 typed-router 生成物随 /feed 路由提交沿 TASK-213 先例。明细见 handoff.md §8 |
| 状态 | TASK-222 本地闭环：好友动态 feed（pull-on-read 跨服务聚合读路径，零 schema/零索引/零 MQ，feed 预过滤与 visibility 门等价论证），外部门槛与独立 review 待指导侧终裁与推送后补记 |
- 补记（指导侧封盘笔，2026-10-14）：FULL 档三环节终裁全过（指导亲核 PASS：20 文件全等/红线面空/pageFeed 核心语义与预注册一致；独立复核四块全 PASS 零阻断零非阻断，留痕 N-1 typed-router 生成物沿 7e4be36 先例、N-2 token 扫描措辞过强实质门成立、N-3 proposal duration 措辞矛盾由 D1 披露）。批次推送 1158db6..ffc98bd（3 笔=TASK-221 封盘笔+TASK-222 C-01/C-02）后 run 38431818947：首跑 Build and test（online 全量，user 173/record 206 含 feed 测试与 D7 修复）绿、镜像构建步撞 Maven Central 502 瞬断红；重跑 attempt 2 conclusion=success 全 job 绿（build 11m24s/web 23s）——外部终验达成（502 系上游基建瞬断非代码缺陷，如实记录）。TASK-222 封盘。C-01 9db644a / C-02 ffc98bd。

## 验收记录：TASK-223（记录删除/撤回与单事务级联清理，P5，FULL 档，2026-10-14）

| 项 | 内容 |
| --- | --- |
| 开工基线 | 派发笔 `08732b7`；工作树干净；offline 基线 52/50/173/206/159/80/26；token 29 项基线 2036 |
| C-01 实施笔 | `40ce0d3` `feat(record): 记录删除与撤回及单事务级联清理（TASK-223）`，恰 11 文件 = §4 白名单，+388/−8 |
| C-02 台账笔 | 本笔 `docs(mailbox): 登记TASK-223记录删除验收与台账闭环（TASK-223）`，恰 4 文件（tasks.json 勾选 / spec.md §7 回填 / handoff.md 新建 / PLAN.md 纯追加）；C-02 自指哈希沿 D1 先例 |
| 判别式红绿 | R1 绿（owner 200 / 缺头 1001 / 非 owner 403 1002 / 不存在 404 3001）；R2 绿（单事务级联恰四表：主记录、轨迹分片点、轨迹归档分片点、点赞持久行）；R3 绿（afterCommit best-effort 双层 try-catch 清理 Redis 计数与成员集合，异常吞并留证）；R4 绿（删除后既有读取面 requireRecord 自然 404，零门修改）；R5 绿（S1 recon 定稿榜单为自有聚合持久表，不发 MQ 补偿，登记 Dn 偏差与已知限制）；R6 绿（web 我的记录操作列 Popconfirm 确认 + 删除 + 列表与打卡刷新）；G1/G2/G3 绿。明细见 handoff.md §4 |
| 本机门禁 | offline 逐位 **52/50/173/217/159/80/26** BUILD SUCCESS rc=0（record 206→217 恰 +11 = RecordLikeServiceTest 3 + RecordControllerTest 4 + SportRecordServiceTest 4，其余六模块逐位不变）；三承载新断言的测试类隔离单跑全绿（RecordLikeServiceTest 39 / RecordControllerTest 29 / SportRecordServiceTest 67）；record-service 静态 rc=0（0 Checkstyle violations/SpotBugs Error size 0/PMD——唯一声明并强制三件套的改动模块）；web pnpm test rc=0（4 套 26 测）、type-check rc=0、build 成功（3283 modules）、typed-router diff rc=0；契约门双态 rc=0（在途 --open TASK-223 --baseline=08732b7 判据 A 待办放行 + 判据 B 一致，收口无参）；token 29 项两点 2036/2036 只增不减且 ≥2034，改动集 token 扫描 ZERO_HIT；词面门改动集 CI-exact ZERO_HIT rc=1 + 探针阳性对照 HIT rc=0；git diff --check rc=0；改动文件保持基线行尾 |
| 是否到达外部门槛 | **未达到**：两笔均未 push、未建 PR、无 CI run；FULL 档独立 review 复核待指导侧派发 |
| 未覆盖/跳过 | --mode=online、CI 全流程、真实浏览器我的记录删除行内二次确认/列表刷新交互（沿 TASK-213/220/221/222 口径）、真实 MySQL 分片删除/Redis 键清理跨中间件联调（--it 未执行） |
| 偏差 | D1 榜单数据源定稿为自有聚合持久表，贡献不实时回撤，登记为偏差与已知限制，不引入 MQ 补偿写；D2 Checkstyle ParameterNumber 8 参家法下采用 setter 注入；D3 token/词面门/契约临时件落 gitignored .trae/tmp；D4/D5 offline 噪音与 build chunk 告警为既有。明细见 handoff.md §8 |
| 状态 | TASK-223 本地闭环：记录删除与撤回落地（单事务级联四表 + Redis best-effort 清理 + web Popconfirm 二次确认，零 schema/零新状态值/零 MQ/零跨服务写），外部门槛与独立 review 待指导侧终裁与推送后补记 |
- 补记（指导侧封盘笔，2026-10-14）：FULL 档三环节终裁全过（指导亲核 PASS：15 文件全等/红线面 diff=0/deleteRecord 404→403→级联分片路由与预注册一致；独立复核四块全 PASS 零阻断零非阻断，留痕×3：handoff 测试方法名口径简写、复核期间政策笔 765b366 追加不改白名单全等、Windows TMP 重定向必要性）。批次推送 ffc98bd..765b366（5 笔=TASK-222 封盘笔+TASK-223 派发笔/C-01/C-02+分级政策笔）后 run 38497654651（head=765b366）conclusion=success 全 job 绿——外部终验一次达成。TASK-223 封盘。C-01 40ce0d3 / C-02 e5303c4。榜单贡献不回撤为 D1 已知限制，挂账 F5（拟）待业务精确性要求触发。
## 验收记录：TASK-224（我的记录筛选与前端过滤条联动，P5，MEDIUM 小档，2026-10-14）

| 项 | 内容 |
| --- | --- |
| 开工基线 | 派发笔 `cdaa8b3`；工作树干净；offline 基线 52/50/173/217/159/80/26；token 29 项基线 2036 |
| C-01 实施笔 | `fabeb60` `feat(record): 我的记录筛选与前端过滤条联动（TASK-224）`，恰 8 文件 = §4 白名单，+454/−32 |
| C-02 台账笔 | 本笔 `docs(mailbox): 登记TASK-224我的记录筛选验收与台账闭环（TASK-224）`，恰 4 文件（tasks.json 勾选 / spec.md §7 回填 / handoff.md 新建 / PLAN.md 纯追加）；C-02 自指哈希沿 D1 先例 |
| 判别式红绿 | R1 绿（向后兼容：全参数缺省时 wrapper 与基线等价，既有用例零改全绿）；R2 绿（各过滤维单启用与组合启用语义正确，测试精确断言 wrapper 条件）；R3 绿（非法枚举/越界/start>end 返回 400，缺头 1001 与 auth 关缺参 400 身份门不变）；R4 绿（web 过滤条变更重置 page=1、参数入 query、空态/结果缩减渲染，四件套承载）；R5 绿（api 模块零 diff、零 schema、零新端点、零新路由）；G1/G2/G3 绿。明细见 handoff.md §4 |
| 本机门禁 | offline 首用分级收窄政策：`mvn-verify.sh --mode=offline --pl record-service test` 实测 `record-service` 217→**226**（净增 9 测 = Controller 4 + Service 5，Failures: 0, Errors: 0, Skipped: 0，rc=0），其余六模块按绿基线与零 diff 登记为 **52/50/173/226/159/80/26** BUILD SUCCESS；两承载新断言的测试类隔离单跑全绿（RecordControllerTest 33 / SportRecordServiceTest 72）；record-service 静态 rc=0（0 Checkstyle violations/SpotBugs 0 错误/PMD BUILD SUCCESS）；web pnpm test rc=0（4 套 28 测，净增 2 测）、type-check rc=0、build 成功（3283 modules）、typed-router diff rc=0；契约门双态 rc=0（在途 --open TASK-224 --baseline=cdaa8b3 判据 A 待办放行 + 判据 B 一致，收口无参）；token 29 项两点 2036/2036 持平且 ≥2034，改动集 token 扫描 ZERO_HIT；词面门改动集 CI-exact ZERO_HIT rc=1 + 探针阳性对照 HIT rc=0；git diff --check rc=0；改动文件保持基线 LF 行尾无 BOM |
| 是否到达外部门槛 | **未达到**：两笔均未 push、未建 PR、无 CI run；MEDIUM 档外部终验待指导侧推动推送后补记 |
| 未覆盖/跳过 | --mode=online、CI 全流程、真实浏览器我的记录过滤条交互/列表联动（沿 TASK-213/220/221/222/223 口径）、真实 MySQL 大数据量过滤性能（--it 未执行） |
| 偏差 | D1 台账笔自指哈希沿先例；D2 token/词面门/临时脚本落 gitignored .trae/tmp；D3 Checkstyle ParameterNumber 8 参家法规约适配（Controller 保持恰 8 参，Service 保持恰 7 参）；D4/D5 offline 噪音与 build chunk 告警为既有。明细见 handoff.md §8 |
| 状态 | TASK-224 本地闭环：我的记录筛选落地（四维可选过滤 + 校验 400 + web 过滤条与重置页码，分级政策首用实测 record 净增 9 测，零 api 触碰/零 schema/零新端点/零新路由），外部终验待推送后补记 |
- 补记（指导侧封盘笔，2026-10-14）：MEDIUM 小档三环节终裁全过（指导亲核 PASS：12 文件全等/api 红线 diff=0/四参可选绑定+validateFilterParams 与预注册一致；独立复核四块全 PASS 零阻断，提示×3 裁决合理——HEAD 拓扑含 TASK-223 封盘笔属预期、契约门在途 --diff-file 口径、handoff 方法名表述差异）。分级门禁政策首用成功（record 收窄实测 217→226 +9，其余沿绿基线论证，政策省时兑现）。批次推送 765b366..9ed803f（4 笔=TASK-223 封盘笔+TASK-224 派发笔/C-01/C-02）后 run 38511006257（head=9ed803f）conclusion=success——外部终验一次达成。TASK-224 封盘。C-01 fabeb60 / C-02 65aad17。
## 验收记录：TASK-225（周月运动目标与前端目标卡片，P5，MEDIUM 档，2026-10-14）

| 项 | 内容 |
| --- | --- |
| 开工基线 | 派发笔 `06d17f3`；工作树干净；offline 基线 52/50/173/226/159/80/26；token 29 项基线 2036 |
| C-01 实施笔 | `b7f3029` `feat(record): 周月运动目标与前端目标卡片（TASK-225）`，恰 14 文件 = §4 白名单，+1302/−2 |
| C-02 台账笔 | 本笔 `docs(mailbox): 登记TASK-225周月运动目标验收与台账闭环（TASK-225）`，恰 4 文件（tasks.json 勾选 / spec.md §7 回填 / handoff.md 新建 / PLAN.md 纯追加）；C-02 自指哈希沿 D1 先例 |
| 判别式红绿 | R1 绿（02 追加 sport_goal 幂等建表，真 MySQL scratch 二跑命中跳过分支，uk 约束保护与 upsert 覆盖验证）；R2 绿（GET/POST 缺头 1001、auth 关缺参 400，DELETE 非 owner 403 1002、不存在 404 3001）；R3 绿（同维度二次 POST 覆盖更新 target 不增行，域外/非正数返回 400）；R4 绿（周窗口 ISO 周一 00:00 与自然月窗口边界单测，distance 与 count 聚合各自正确，percent 0-100 整数截断与封顶，仅本人 PASSED 计入，已删/非 PASSED 自然剔除与 TASK-223 联动语义留证）；R5 绿（web 目标卡片渲染/进度条/周月周期标签/达成标签/对话框/删除确认 Popconfirm，client.ts 三方法与单测，四件套承载）；R6 绿（既有面 /my/streak/feed/删除/筛选源码零 diff）；G1/G2/G3 绿。明细见 handoff.md §4 |
| 本机门禁 | offline 全量逐位 **52/50/173/250/159/80/26** BUILD SUCCESS rc=0（sql 触碰按分级政策保守读法全量跑，record 226→250 净增 24 测 = GoalServiceTest 10 + GoalControllerTest 14，其余六模块逐位不变）；scratch 迁移二跑留证完成；两承载新断言的测试类隔离单跑全绿（GoalServiceTest 10 / GoalControllerTest 14）；record-service 静态 rc=0（0 Checkstyle violations/SpotBugs 0 错误/PMD BUILD SUCCESS）；web pnpm test rc=0（4 套 31 测，净增 3 测）、type-check rc=0、build 成功（3283 modules）、typed-router diff rc=0；契约门双态 rc=0（在途 --open TASK-225 --baseline=06d17f3 判据 A 待办放行 + 判据 B 一致，收口无参）；token 29 项两点 2036/2036 持平且 ≥2034，改动集 token 扫描 ZERO_HIT；词面门改动集 CI-exact ZERO_HIT rc=1 + 探针阳性对照 HIT rc=0；git diff --check rc=0；改动文件保持基线 LF 行尾无 BOM |
| 是否到达外部门槛 | **未达到**：两笔均未 push、未建 PR、无 CI run；MEDIUM 档外部终验待指导侧推动推送后补记 |
| 未覆盖/跳过 | --mode=online、CI 全流程、真实浏览器目标卡片与对话框交互/列表联动（沿 TASK-213/220/221/222/223/224 口径）、真实 MySQL 大数据量目标与记录聚合联调（--it 未执行） |
| 偏差 | D1 台账笔自指哈希沿先例；D2 token/词面门/scratch 迁移等临时脚本落 gitignored .trae/tmp；D3 Checkstyle JavadocPackage 规约适配（新建 dto 包补充 package-info.java）；D4/D5 offline 噪音与 build chunk 告警为既有。明细见 handoff.md §8 |
| 状态 | TASK-225 本地闭环：周月运动目标落地（sport_goal 幂等建表 + pull-on-read 实时进度聚合 + web 目标卡片与 CRUD 对话框，全量 offline 实测 record 净增 24 测，零 api 触碰/零 MQ/零跨服务写/零缓存反范式/既有面源码零 diff），外部终验待推送后补记 |
- 补记（指导侧封盘笔，2026-10-14）：MEDIUM 档三环节终裁全过（指导亲核 PASS：18 文件全等/红线面 diff=0/02 恰 +14 行幂等块/GoalService 窗口与状态过滤与预注册一致，亲核留痕 createdAt 窗口锚定经复核对照与 /my/streak 一致不登记；独立复核四块全 PASS 零阻断——scratch 真机二跑由复核侧独立复跑实证，矛盾点×3 均文档/计数口径层面裁留痕：information_schema 表述偏差、spec S2「1001」应为 3001 文本口径、+14/13 计数口径）。批次推送 9ed803f..5954cad（4 笔=TASK-224 封盘笔+TASK-225 派发笔/C-01/C-02）后 run 38525589652（head=5954cad）conclusion=success——外部终验一次达成。TASK-225 封盘。C-01 b7f3029 / C-02 5954cad。

## 验收记录：TASK-226（LIGHT 清理批，H3，LIGHT 档，2026-10-14）

| 项 | 内容 |
| --- | --- |
| 开工基线 | 派发笔 `11364af`（TASK-226/227 派发笔，其下 bc84594 为 TASK-225 封盘笔勿动）；工作树干净；offline 基线 52/50/173/250/159/80/26；token 29 项基线 2036 |
| C-01 实施笔 | 本课题 C-01 `docs` 笔（README 订正 7 处 + BACKLOG 推荐序刷新，恰 3 文件 = README.md / web/README.md / work/mailbox/BACKLOG.md，哈希以 git log 为准） |
| C-02 台账笔 | 本笔 `docs(mailbox): 登记TASK-226清理批验收与台账闭环（TASK-226）`，恰 4 文件（tasks.json 勾选 / spec.md §7 回填 / handoff.md 新建 / PLAN.md 纯追加）；C-02 自指哈希沿 D1 先例 |
| 判别式红绿 | R1 绿（留痕-1 清偿核验：ff80635→9db644a→40ce0d3 均缺末尾换行，fabeb60（TASK-224）编辑时补入末尾 0x0A；HEAD 字节级实测 20113 字节/纯 LF/末 4 字节 7D 0A 7D 0A，本课题对该文件零 diff，清偿事实登记）；R2 绿（README 订正 7 处逐处留对照：user-service/record-service 模块职责行补新功能、web「仅脚手架」与「无服务端我的记录列表」两处旧口径订正、快速开始与目录约定措辞）；R3 绿（user-service 1023 / api 487 项通用 checkstyle sun_checks 基线违规台账化登记，不修码，候选修复沿 TASK-018 先例）；R4 绿（BACKLOG 推荐序行刷新，F 系已清空）；G1 绿（offline --pl record-service 250 逐位不变 0/0/0 rc=0）；G2 绿（改动集恰 C-01 3 + C-02 4 = 7 文件，越界面 diff 全空）；G3 绿（token 2036/2036 持平、词面门 CI-exact ZERO_HIT + 探针正控 HIT、diff --check rc=0、改动文件保持基线行尾：README.md 工作树 CRLF 为 autocrlf checkout 形态 blob LF，web/README.md 与 BACKLOG 纯 LF）。明细见 handoff.md §4 |
| 本机门禁 | offline `--pl record-service` 逐位 **250** 不变（Failures: 0, Errors: 0, Skipped: 0，rc=0，BUILD SUCCESS）；`git diff --check` rc=0（工作树与暂存区双查）；token 29 项两点 2036/2036 持平且 ≥2034，改动集 7 文件 token 扫描 ZERO_HIT；词面门改动集 CI-exact ZERO_HIT rc=1 + 探针阳性对照 HIT rc=0；契约门双态 rc=0（在途 --open TASK-226 --baseline=11364af 判据 A 两件套 + 判据 B 一致，收口无参）；行尾核对（RecordControllerTest 纯 LF + 末尾 0x0A + 无 BOM 零 diff；改动文件保持基线行尾） |
| 是否到达外部门槛 | **未达到**：两笔均未 push、未建 PR、无 CI run；LIGHT 档免独立复核，指导亲核后随 TASK-227 批次推送 |
| 未覆盖/跳过 | --mode=online、CI 全流程（未 push）；user-service/api 静态违规修复（红线：仅登记）；record-submit 页「无服务端我的记录列表」旧口径文本订正（web src 白名单外，登记候选）；RecordControllerTest 留痕-1 清偿为中途提交既有事实的核验登记，非本课题新动作 |
| 偏差 | D1 留痕-1 预注册项实况偏差（已由 TASK-224 中途清偿，零 diff 登记清偿事实）；D2 静态台账为通用规则集基线违规、非声明模块；D3 record-submit 页旧口径文本白名单外未改；D4 临时件落 gitignored .trae/tmp；D5 C-02 自指哈希沿先例。明细见 handoff.md §8 |
| 状态 | TASK-226 本地闭环：LIGHT 清理批落地（留痕-1 清偿核验登记 + README 订正 7 处 + 静态偏差台账化 + BACKLOG 刷新，零功能变更、offline record 250 逐位不变），指导亲核后随 TASK-227 批次推送 |

## 验收记录：TASK-228（web 启动级路由缺陷修复，P0 发现，MEDIUM 小档，2026-10-14）

| 项 | 内容 |
| --- | --- |
| 开工基线 | 派发笔 `4596df2`（TASK-228 任务书注册笔；其下 `69602b0` 为 TASK-227 停止补记笔、`e1610de` 为停止上报笔，勿动）；工作树干净；本地领先 origin 7 笔随批推送；token 29 项基线持平 |
| C-01 实施笔 | 本课题 C-01 `fix(web)` 笔，恰 2 文件 = `web/src/main.ts` + `web/src/vue-router-auto-shim.d.ts`（`createRouter` 显式注入 `vue-router/auto-routes` 的 `routes`；垫片删除掩盖性 `Omit<RouterOptions,'routes'>` 声明、补 `auto-routes` 值导出类型），哈希以 `git log` 为准 |
| C-02 台账笔 | 本笔 `docs(mailbox)`，恰 4 文件（`spec/changes/fix-web-router-boot/tasks.json` 勾选 / `work/mailbox/tasks/TASK-228/spec.md` §7 回填 / `work/mailbox/tasks/TASK-228/handoff.md` 新建 / `work/mailbox/PLAN.md` 纯追加）；C-02 自指哈希沿先例 |
| 根因（独立复核） | `unplugin-vue-router@0.19.2` 的 `package.json#exports` 逐键列举无 `./auto`、无 `./auto-routes`，产物只提供 `MODULE_ROUTES_PATH = "vue-router/auto-routes"`；`vue-router@4.6.4` 的 `./auto` 落到 36 字节纯类型占位（单行注释 `// augmented by unplugin-vue-router`）。故 `main.ts` 以 `routes === undefined` 进入 `createRouterMatcher(routes, ...)`，在 `routes.forEach` 抛 `Cannot read properties of undefined (reading 'forEach')`。预存在性：`git diff 11364af -- web/src vite.config.ts tsconfig.app.json` 为空 |
| 判别式红绿 | R1 绿（修复前 dev 真 chromium 复现红：`#app` 子节点 0 / pageerror 2 条均为 `forEach` 抛错 / `MOUNTED=false`；修复后 dev 与 preview 双形态 PASS：守卫改写登录页 / `#app` 子节点 1 / input 2 / button 11 / pageerror 0）；R2 绿（`pnpm test` 4 文件 31 测 rc=0、`pnpm type-check` rc=0、`pnpm build` 3317 modules rc=0、`typed-router.d.ts` 零 diff rc=0）；R3 绿（恰 2 + 4 文件全等，零扩散）；R4 绿（token 两点持平 / 词面门改动集 ZERO_HIT + 探针正控 HIT / 契约双态 rc=0 / `git diff --check` 双查 rc=0 / 行尾保基线） |
| 本机门禁 | web 四件套全绿（test 31 / type-check / build 3317 modules / typed-router 零 diff）；双形态挂载证据三份（修复前 dev 红 + 修复后 dev 绿 + preview 绿）；契约门双态 rc=0（在途 `--open TASK-228 --baseline=4596df2` + 收口无参）；token 29 项两点持平；词面门改动集 CI-exact ZERO_HIT rc=1 + 探针阳性对照 HIT rc=0；`git diff --check` 工作树与暂存区 rc=0；行尾核对（两 .ts 保持 CRLF 形态 / blob LF / 末尾 0x0A / 无 BOM，`git diff` 工作树 vs 索引为空）；零依赖零配置变更 |
| 是否到达外部门槛 | **未达到**：两笔均未 push、未建 PR、无 CI run；MEDIUM 小档待指导侧亲核后随批推送 |
| 未覆盖/跳过 | CI 全流程；`--mode=online`；真实浏览器功能交互面 12 用例（由同批 TASK-227 E2E 承载）；多浏览器/移动视口/可访问性；dev HMR 路由热更新；零 Maven（web-only 政策档，后端零改动） |
| 偏差 | D1 宿主 `%TEMP%` 下 esbuild 清理临时文件被拒致 `pnpm build` 偶发 rc=1（环境面，TMP/TEMP 重定向 gitignored `.trae/tmp/esbtmp` 后 rc=0）；D2 词面门探针正控形态订正（`git grep` 不搜索未跟踪文件，TASK-226 旧形态为假阴性）；D3 挂载探针消费上一轮 TASK-227 尝试遗留的本机 Playwright，探针不入库；D4 临时件落 gitignored `.trae/tmp`；D5 C-02 自指哈希沿先例。明细见 `handoff.md §8` |
| 状态 | TASK-228 本地闭环：启动级路由缺陷已定位并修复（恰 2 文件、零依赖零配置变更），双形态真 chromium 挂载通过、web 四件套全绿、门禁全过；指导侧亲核后与同批 TASK-227 E2E 一批推送 |

## 验收记录：TASK-227（E2E 真实浏览器验证批，H1，P6，MEDIUM，2026-10-14）

| 项 | 内容 |
| --- | --- |
| 开工基线 | 本课题**续批**开工基线 `138ec97`（TASK-228 C-02 台账笔；其下 `05bcae4` 为 TASK-228 C-01 启动级路由修复笔，未修则 E2E 全数阻断，故 227 必须在 228 之后）；首轮派发基线为 `69602b0` 之上（首轮于 S3.3 停止上报，零提交） |
| 首轮停止史 | 首轮 E2E 无法在真实浏览器跑通，S3.3 停止上报，根因=**应用启动级路由缺陷**（`unplugin-vue-router@0.19.2` 不再注入 `vue-router/auto` 文件路由表，`routes` 为 `undefined` 时 `createRouterMatcher` 在 `routes.forEach` 抛错）；停止笔链 `e1610de`（停止上报）→ `69602b0`（停止报告补记，附带「完整修复候选=恰 2 文件」实测全绿）；缺陷另立 TASK-228 并已先落地 |
| C-01 实施笔 | 本课题 C-01 `feat(e2e)` 笔，恰 18 文件 = S4 白名单全等（`web/package.json` devDependency `@playwright/test` 精确锁版 + `scripts.e2e` / `web/pnpm-lock.yaml` / `web/.gitignore` 新增 / `web/README.md` E2E 小节与旧口径订正 / `web/e2e/**` 14 文件 = config + helpers + 8 spec + 4 fixtures），哈希以 `git log` 为准 |
| C-02 台账笔 | 本笔 `docs(mailbox)`，恰 4 文件（`spec/changes/e2e-browser-batch/tasks.json` 勾选 / `work/mailbox/tasks/TASK-227/spec.md` §7 回填 / `work/mailbox/tasks/TASK-227/handoff.md` 续批回传追加（首轮停止报告原样保留）/ `work/mailbox/PLAN.md` 纯追加）；C-02 自指哈希沿先例 |
| 判别式红绿 | R1 绿（`pnpm install --frozen-lockfile` rc=0 且 `Lockfile is up to date`、安装后 lock 零 diff、1.64.0 精确锁定）；R2 绿（8 spec / 12 用例真 chromium 全绿 rc=0，逐条断言核心交互）；R3 绿（`page.route` 拦截两前缀 + `page.on('request')` 后端前缀监视，两前缀外记 `unhandled` 并断言为空，12 用例共 20 处调用、零真实网络请求）；R4 绿（test 31 / type-check / build / typed-router 零 diff）；R5 绿（运行输出 + 12 用例目录与 12 份 trace 落 gitignored `.trae/tmp/e2e-results`）；R6 绿（后端与 ci.yml、`web/vite.config.ts`、`web/src/**` 相对开工基线零 diff） |
| 本机门禁 | web 四件套全绿（test 4 文件 31 测 / type-check 零错 / build 3317 modules / typed-router 零 diff）；E2E `pnpm e2e` rc=0（12 passed，8 文件）且 `--list` 交叉一致 `Total: 12 tests in 8 files`；契约门双态 rc=0（在途以真实开工时点 `138ec97` 为基线 + `--diff-file` 21 项可表达改动集；收口无参）；token 29 项两点持平且新增 15 个未跟踪文件固定串零命中；词面门改动集 CI-exact ZERO_HIT rc=1 + 新增文件显式扫描零命中 + 探针阳性对照 HIT rc=0；`git diff --check` 工作树与暂存区 rc=0；行尾 bad=0（新增 15 文件纯 LF 无 BOM，改动文件保基线形态）；零 Maven（web-only 政策档） |
| 是否到达外部门槛 | **未达到**：C-01/C-02 两笔均未 push、未建 PR、无 CI run；指导亲核后与同批 TASK-228 一批推送，CI 绿为收口前提 |
| 未覆盖/跳过 | 真实后端全栈联调（`--it` 层，仍留 H2/后续）；CI 集成（E2E 明列不入 CI，`ci.yml` 零触碰）；多浏览器（仅 chromium）与移动视口、可访问性；榜单页交互（`/leaderboard/api/*` 不在两前缀 mock 范围，避免真实网络请求，候选后续扩展）；WebSocket 推送路径；`--mode=online` |
| 偏差 | D1 保留件 `scripts.e2e` 为 `playwright test`，实测在本仓 cwd 下无法发现 `e2e/` 子目录配置（误扫 vitest 单测并报 `No tests found` rc=1），据 S2 意图补 `--config e2e/playwright.config.ts` 一行（白名单内，不涉 vite.config/路由/后端）；D2 契约门基线口径——用户指定的 `4596df2` 跨 TASK-228 时判据 B 全局比对必红，改以真实开工时点 `138ec97` 复跑并两版登记；D3 判据 B 提取器扩展名白名单不含无扩展名 dotfile，`web/.gitignore` 不可表达，另以 `--diff-file` 21 项复跑；D4 临时件落 gitignored `.trae/tmp`；D5 R4 的 build 期望值 3283 为 TASK-228 修复前读数，正确读数 3317；D6 C-02 自指哈希沿先例。明细见 `handoff.md §14` |
| 状态 | TASK-227 本地闭环：E2E 真实浏览器批从保留件恢复并按 S2.5 判别式复跑达成（8 spec / 12 用例 route-mock 全绿、零真实网络请求、后端零 diff、web 四件套不回归），首轮 S3.3 停止史与停止笔链如实登记；指导亲核后随批推送 |
复核后证据链订正笔（`docs(mailbox)`，2026-10-14，TASK-226/227/228 批，恰 5 文件、纯台账+文档、零代码、未 push）：BLOCKER-1＝证据面时点失实——TASK-228 handoff §5.3 与 TASK-227 handoff §14-D2/D3 所记在途契约门 `rc=0` 系 `beabc6f` 落地前的时点读数；HEAD=`fd6b299` 干净工作树（独立 worktree）复算：`--open TASK-228 --baseline=4596df2` rc=1、`--open TASK-227 --baseline=4596df2` rc=1、`--open TASK-227 --baseline=69602b0` rc=1（三者同为**串批累积集语义**——判据 B 以单一累积改动集对每个在途回传全量比对，两课题互判「改动集未声明」，非清单违规）、`--open TASK-227 --baseline=138ec97` rc=1（`web/.gitignore` dotfile 不可表达）、`--diff-file` 21 项口径 rc=0 属时点快照；替代契约口径＝本课题相对开工基线改动集全等（TASK-228 6 文件 / TASK-227 22 文件）+ 收口无参 rc=0（HEAD 干净工作树 rc=0）；M1/M2（`web/README.md`：`*.spec.ts`→`*.e2e.ts`、`pnpm e2e` 订正为实际脚本形态含 `--config e2e/playwright.config.ts` 并补 webServer `--host 127.0.0.1`）、M3（`t227r-gates.log` 第 9 行 ZERO_TOUCH_VIOLATION 成因＝出证脚本误用 `4596df2` 而非本课题开工基线 `138ec97`，文本结论「相对 `138ec97` 零 diff」正确）、M6（TASK-228 spec.md §S6 契约基线口径 `69602b0`→`4596df2`，两口径 HEAD 复算皆 rc=1）同笔订正；M4-M6/L1-L3 留痕登记。
补记（指导侧封盘笔，2026-10-14，TASK-226/227/228 批）：批次推送 `5954cad..aee5ca0`（12 笔）后 run `38586443704`（head=`aee5ca0`）conclusion=success——三课题外部终验一次达成（web 31s：修复后路由的首次 CI 真实构建通过；build 11m46s：全模块测试/静态门/词面门/6 服务镜像/Trivy 全过）。TASK-226（LIGHT 档免复核，指导亲核 7 文件全等、offline record 250 逐位不变）：C-01 `839d4cc` / C-02 `890f397`。TASK-228（MEDIUM 小档，复核技术面通过，BLOCKER-1 证据面时点失实由订正笔 `aee5ca0` 如实化处置，契约证据以「相对 `4596df2` 六文件清单全等 + 收口无参 rc=0」承载）：C-01 `05bcae4` / C-02 `138ec97`。TASK-227（MEDIUM 档，首轮 S3.3 停止发现 P0 启动级路由缺陷→TASK-228 修复后续批达成，复核技术面通过，D7/D8 随订正笔登记）：C-01 `beabc6f` / C-02 `fd6b299`。三课题封盘；本封盘笔随下一课题批次推送。

## 验收记录：TASK-229（web 交互面修整批，H4 候选，MEDIUM 档，2026-10-15）

| 项 | 内容 |
| --- | --- |
| 开工基线 | `2829f34`（main 分支，工作树干净） |
| 任务内容与白名单 | 恰 2 个实施文件（`web/vite.config.ts` + `web/src/pages/record-submit.page.vue`）+ 台账 2 文件（`work/mailbox/tasks/TASK-229/spec.md` + `work/mailbox/tasks/TASK-229/handoff.md`）；零后端改动、零依赖包改动 |
| 关键修复 | ① Vite 代理配置解耦：将前缀精确到斜杠（`/record/`、`/admin/`）并增加 `bypass: (req) => (req.headers.accept?.includes('text/html') ? req.url : undefined)`，消除了地址栏直载 `/record-submit`、`/record-detail`、`/admin*` 等深链时被误代理到网关端口的缺陷；② `record-submit.page.vue` 的 `a-alert` 详情内容用 `<template #description>` 具名插槽包裹，对齐 `ant-design-vue` 4.2.6 组件规范，解决提交结果详情未落屏问题 |
| 本机门禁 | `pnpm --dir web type-check` rc=0；`pnpm --dir web test` 4 套 31 测全绿 rc=0；`pnpm --dir web build` 3317 modules 产出成功 rc=0；`git diff --exit-code -- web/src/typed-router.d.ts` 零 diff rc=0；契约门无参 `scripts/verify/mailbox-contract.sh` rc=0（判据 A 两件套齐 + 判据 B 清单一致）；PowerShell 静态结构断言全通过 |
| 是否到达外部门槛 | **未到达**：本地闭环，尚未执行 git commit / push，无新 CI run |
| 状态 | TASK-229 本地闭环，待指导侧统一提交并推进推送 |

## 验收记录：TASK-230（删除记录联动回撤榜单里程贡献，F5 候选，TASK-223 D1 挂账，MEDIUM 档，2026-10-15）

| 项 | 内容 |
| --- | --- |
| 开工基线 | `cb865be`（main 分支，工作树干净） |
| C-01 实施笔 | `4bae3fe` `feat(leaderboard): 删除记录联动回撤榜单里程贡献（TASK-223 D1）`（恰 6 文件，+307/−8） |
| 任务内容与白名单 | 恰 6 个实施文件（`LeaderboardApi.java` / `LeaderboardApiFallback.java` / `InternalLeaderboardController.java` / `SportRecordService.java` / `InternalLeaderboardControllerTest.java` / `SportRecordServiceTest.java`）+ 台账 5 文件；零依赖包变动、零架构状态机破坏 |
| 核心实现 | ① `LeaderboardApi` 新增 `POST /internal/leaderboard/records/{recordId}/rollback` 契约，fallback 显式抛 4008 拒绝静默；② `InternalLeaderboardController` 承接既有 `leaderboardService.rollbackOnRejected`，复用其分布式互斥锁与乐观锁状态机；③ `SportRecordService` 经 setter 注入 `LeaderboardApi`（构造方法维持 7 参符合 Checkstyle 8 参家法），在删除事务 `afterCommit` 回调内 best-effort 调用，异常吞并留证，坚守最终一致性（ADR-0009） |
| 本机门禁 | offline 单测重跑 `mvn clean test -pl leaderboard-service,record-service` 全绿（common 52 / record 255 / leaderboard 85，Failures 0 Errors 0，exit code 0）；静态检查 `--static=leaderboard-service` 与 `--static=record-service` 均 0 Checkstyle 违规 BUILD SUCCESS；契约门无参 `scripts/verify/mailbox-contract.sh` rc=0 |
| 是否到达外部门槛 | **未到达**：C-01 已提交，待指导侧统一提交台账 C-02 并推送触发 CI 外部终验 |
| 状态 | TASK-230 本地闭环，指导主 Agent 独立复核通过，TASK-223 D1 挂账彻底清偿 |

## 验收记录：TASK-231（个人统计服务端聚合端点与页面历史文案清理，MEDIUM 档，2026-10-15）

| 项 | 内容 |
| --- | --- |
| 开工基线 | `9d1e79f`（main 分支，工作树干净） |
| C-01 实施笔 | `ee95c49` `feat(record): 新增个人统计聚合端点 /my/stats 并接入首页统计卡（TASK-231）`（恰 9 文件，+353/−40 加上新 DTO 全文） |
| C-02 任务两件套 | `e4f0aa2` `docs(mailbox): 登记TASK-231个人统计聚合端点实施与台账闭环（TASK-231）`（恰 2 文件，+122/−0） |
| 任务内容与白名单 | 相对开工基线恰 11 文件 = 白名单全等（实施 9 文件 + 任务两件套 2 文件）；白名单外零扩散（零额外依赖、零越界配置触碰、零未声明路由） |
| 核心实现 | ① 后端新增 `GET /record/api/records/my/stats` 端点，身份语义与 `/my`、`/my/streak` 同构；`SportRecordService.myStats` 单查全量轻量投影（`distance`, `status`, `createdAt`）并在内存聚合，输出 `totalCount`、`totalDistance`、`passCount`、`passRate` 与恒 30 键升序的 `dailyDistances`（无记录日补 0.00），彻底清偿 TASK-211 的「500 条统计上限」技术债；② 前端 `client.ts` 导出 `getMyStats`，`index.page.vue` 统计卡三项与每日里程条只读该聚合端点，彻底解耦表格分页与过滤对统计卡的影响；③ 彻底清洗 `index.page.vue` 与 `record-submit.page.vue` 中残留的「无列表 API」历史陈旧文案 |
| 本机门禁 | offline 单测重跑 `mvn clean test -pl record-service` 全绿（261 测全过，净增 6 测，exit code 0）；前端 `pnpm type-check` rc=0，`pnpm test` 4 套 32 测全过（净增 1 测，exit code 0），`pnpm build` 3317 modules rc=0；契约门无参 `scripts/verify/mailbox-contract.sh` rc=0 |
| 是否到达外部门槛 | **未到达**：C-01/C-02 已提交，待指导侧统一提交台账闭环笔并随批推送触发 CI 外部终验 |
| 独立复核结论 | 用户派发独立复核子 Agent 终审全过（四块均 **PASS**，BLOCKER 0，偏差 D 0，提示留痕 N-1~N-7）：<br>• 块 1：11 文件与白名单全等，三处旧文案中两处属于本任务范围已清空零残留，外部文件零扩散；<br>• 块 2：后端 261 测、前端三件套（type-check/test 32/build 3317）、契约无参 rc=0 全部独立复现通过；<br>• 块 3：哈希、时间戳、日志工件真实性吻合，N-1 确认无参契约走短路，白名单一致性由开工态 `--open` 与直接 git diff 证明；N-2 首跑期望修正诚实留证；<br>• 块 4：N-3 确认 Playwright `helpers.ts` 未映射 `/my/stats` 导致 `record-list.e2e.ts` 三断言失效（CI 不跑 e2e 不阻断，但登记挂账后续任务必清）；N-4 确认存在索引范围扫描但内存缓冲与 `loadData` 翻页触发存在大容量优化空间；N-5 跨时区容错安全（NaN 兜底为 0）；N-6 加载态耦合（loading 包含 stats）；N-7 BigDecimal 数字与字符串前端双态兼容 |
| 状态 | TASK-231 本地闭环，指导侧终裁 **ACCEPT 予以封盘**；遗留 N-3（E2E mock 同步）与 N-4（聚合下推）登入 BACKLOG 挂账 |

## 验收记录：TASK-232（个人统计 SQL 聚合下推与页面加载态解耦，MEDIUM 档，2026-10-15）

| 项 | 内容 |
| --- | --- |
| 开工基线 | `af97413`（main 分支，工作树干净） |
| C-01 实施笔 | `ceec5c6` `feat(record): 个人统计 SQL 聚合下推与首页加载态解耦（TASK-232）`（恰 5 文件，+306/−114） |
| C-02 任务两件套 | `bf4c7b9` `docs(mailbox): 登记TASK-232个人统计SQL聚合下推与加载态解耦验收与台账闭环（TASK-232）`（恰 2 文件） |
| 任务内容与白名单 | 相对开工基线恰 7 文件 = 白名单全等（实施 5 文件 + 任务两件套 2 文件）；红线文件零扩散（零 pom 变更、零配置越界、零未声明路由） |
| 核心实现 | ① 服务端 SQL 聚合下推：`SportRecordMapper` 新增 `selectAggByUserId` 注解 SQL，采用 `<script>+<foreach>` 动态绑定 `PASS_STATUS_CODES` 枚举，单行查出 `COUNT(*)`、`COALESCE(SUM(distance),0)`、`COUNT(CASE WHEN status IN (...) THEN 1 END)`，映射至新增 `RecordAggDTO`；② 30 天每日里程窗口下推：`selectDailyDistanceSince(userId, windowStart)` 增加时间窗下界过滤，命中既有复合索引 `idx_user_time (user_id, created_at)` 做范围扫描，仅拉取近 30 天记录，彻底消灭全历史记录回表与大内存缓冲开销；③ 边界健壮性：`SportRecordService.myStats` 增加聚合行缺失全 0 兜底，`setScale(2, HALF_UP)` 归一化；④ 前端解耦体验：`index.page.vue` 拆分 `loadRecords()` 与 `loadStats()`，列表翻页（`onPageChange`）与筛选变更（`onFilterChange`, `resetFilters`）仅调用轻量 `loadRecords()`，不再重复打统计与打卡接口；解耦 `tableLoading` 与首屏 `loading`，翻页时不整卡闪烁 |
| 本机门禁 | 后端 `record-service` 262 测全绿（净增 1 测 `myStats_nullAggregateRowFallsBackToZero`，exit code 0）；前端 `pnpm type-check` rc=0，`pnpm test` 4 套 32 测全过，`pnpm build` 3317 modules 产出成功 rc=0；契约门无参 `scripts/verify/mailbox-contract.sh` rc=0 |
| 是否到达外部门槛 | **未到达**：C-01/C-02 已提交，待指导侧统一提交台账闭环笔并随批推送触发 CI 外部终验 |
| 独立复核结论 | 用户派发独立复核子 Agent 终审四块全过（总体 **PASS**，BLOCKER 0，偏差 D1/D2 2 项，提示留痕 N1~N5 5 项）：<br>• 块 1：7 文件严格全等白名单，红线面零触碰；<br>• 块 2：前端三件套与契约无参全绿；后端独立复跑 1 红（TimingHikari 抖动）1 绿、隔离跑 3 次全绿；<br>• 块 3：哈希、测试数、修改行数均吻合；<br>• 块 4：MyBatis 注解 SQL 经真 MyBatis 3.5.16 构建 SqlSource 解析验证通过，窗口与 NPE 兜底严密，前端翻页解耦彻底；<br>• 偏差裁决：D1 认定为 `TimingHikariDataSourceWiringTest` 墙钟 1000ms 阈值在宿主负载下的偶发抖动（1012ms 越线 1.2%），D2 修正留证中对该断言重载的误述；N1~N5 提示留痕在案 |
| 状态 | TASK-232 本地闭环，指导侧终裁 **ACCEPT 予以封盘**；D1 计时抖动修复与 H5 E2E mock 同步登记后续待办 |

## 验收记录：TASK-233（测试稳定性强化与 Web E2E 路由契约对齐，MEDIUM 小档，2026-10-15）

| 项 | 内容 |
| --- | --- |
| 开工基线 | `60aee22`（main 分支，工作树干净） |
| C-01 实施笔 | `5e675e1` `test: 测试稳定性强化与 Web E2E 路由契约对齐（TASK-233）`（恰 5 文件，+75/−8） |
| C-02 台账两件套 | `d375b8e` `docs(mailbox): TASK-233 独立复核通过回传并封盘台账`（恰 2 文件） |
| C-03 订正笔 | `64459c8` `test: 订正复核BLOCKER-1——连接等待窗口2000→10000恢复反死锁判别力（TASK-233）`（恰 3 文件，+28/−9） |
| 任务内容与白名单 | 相对开工基线恰 7 文件 = 白名单全等（实施 5 文件 + 任务两件套 2 文件）；生产代码（六服务 `src/main` 与 `web/src`）及红线文件 100% 零触碰 |
| 核心实现 | ① 后端计时测试稳定化：`TimingHikariDataSourceWiringTest` 中将 `firstCloseMs/doubleCloseMs` 放宽至 `< 3000ms`（消除了 1012ms 的 1s 级线程调度抖动假阴性）；针对复核发现的 BLOCKER-1（测试原 2000ms 窗口使 3000ms 判别失效），在 C-03 中将 `wrapper.setConnectionTimeout` 上调至 `10000ms`（并联动 `join(15s)`），恢复了 3.3 倍以上的反死锁判别余量，正常健康 close 保持 `< 2ms`；② Web E2E 路由 mock 与断言对齐：`helpers.ts` 新增 `GET /record/api/records/my/stats` mock 分支返回 `myStats()`，对齐 25 条测试夹具全量口径（总数 25、总里程 194.00、通过数 6、通过率 24.0%），消除了落入 404 兜底导致的断言失败；`record-list.e2e.ts` 同步更新统计卡断言；③ 严格模式冲突修复：`record-submit.e2e.ts` 将 `recordId: 9001` 断言范围精准收窄至 `.ant-alert` 容器，消除了因 TASK-229 修复结果详情插槽引发的页面重复元素撞车 |
| 本机门禁 | 后端 Maven 单测 262 测全绿（`TimingHikariDataSourceWiringTest` 10/10 稳定通过，无偶发红）；前端 `type-check` 0 错误；`pnpm test` 4 套 32 测全过；`pnpm build` 3317 modules 成功；Playwright E2E 本地全量实跑 **12 passed (1.0m)** 100% 全绿；无参契约门 `mailbox-contract.sh` rc=0 |
| 是否到达外部门槛 | **未到达**：C-01/C-02/C-03 已提交，待指导侧统一提交台账闭环笔并随批推送触发 CI 外部终验 |
| 独立复核结论与裁决 | 用户派发独立复核子 Agent 终审四块全过（技术面 ①②③ 全绿，Playwright E2E 12 passed 全绿实证；④ 揭示 BLOCKER-1 计时窗口需从 2s 上调至 10s）；实施侧通过 C-03 订正笔（`64459c8`）彻底消除 BLOCKER-1，恢复 3.3 倍反死锁判别余量，测试与台账如实闭环 |
| 状态 | TASK-233 本地闭环，指导侧终裁 **ACCEPT 予以封盘**；T1 与 H5 挂账彻底清偿 |

## 验收记录：TASK-234（原有功能健壮化：物理合理性推导、榜单周期联调与目标边界防御，MEDIUM 档，2026-10-15）

| 项 | 内容 |
| --- | --- |
| 开工基线 | `0224ea4`（main 分支，工作树干净） |
| C-01 实施笔 | `169e367` `feat(record,web): TASK-234 原有功能健壮化——物理合理性推导/榜单周期联调/目标边界防御`（恰 7 文件，+507/−14） |
| C-02 台账两件套 | `54977f5` `docs(mailbox): TASK-234 物理合理性推导/榜单周期联调/目标边界防御验收与台账闭环（TASK-234）`（恰 2 文件） |
| 任务内容与白名单 | 相对开工基线恰 9 文件 = 白名单全等（实施 7 文件 + 任务两件套 2 文件）；红线文件（pom.xml、package.json、client.ts、PLAN.md、HANDOVER.md）100% 零触碰，零新外部依赖 |
| 核心实现 | ① 记录提交物理合理性与自动推导：`SportRecordService.submit` 增加物理合理性校验（显式或推导后的 `startTime <= endTime`、`startTime` 不得为未来时间），未显式提供且 points 非空时，逐字段自动推导补齐——首末点时间/时长（UTC 墙钟）、相邻点球面 Haversine 累加距离（保留两位对齐 DECIMAL(8,2)），根治库内关键指标落 NULL 脏数据；前端 `record-submit.page.vue` 加载样例时提取并带齐推导字段；② 榜单周期查询死逻辑修复：`leaderboard.page.vue` 重构为 `lbMode`（`range` 周期视图与 `zset` 总榜视图），激活「今日 / 本周 / 本月」对 `getLeaderboardRange` 的真实调用，死代码彻底消除；③ 运动目标边界与整数性收紧：`GoalService.upsertGoal` 对 `metric=1`（次数）采用 `stripTrailingZeros().scale() <= 0` 强制要求正整数（安全放行 `8.00` 等尾零数值），设置 `<= 10000` 合理上限防溢出；周期窗口过滤由 `createdAt` 改为 `COALESCE(start_time, created_at)`，离线补传运动归属周期得到修正；④ 前端展示细节补齐：`index.page.vue` 补齐 `visibility === 0` 绿色「公开」标签 |
| 本机门禁 | 后端 Maven 单测 **275** 测全绿（净增 13 测，0 失败 0 错误，BUILD SUCCESS [03:19 min]）；前端 `type-check` 0 错误；`pnpm test` 4 套 32 测全过；`pnpm build` 3317 modules 产出成功（21.08s）；Playwright E2E 本地全量实跑 **12 passed (42.5s)** 100% 全绿；无参契约门 `mailbox-contract.sh` rc=0 |
| 是否到达外部门槛 | **未到达**：C-01/C-02 已提交，待指导侧统一提交台账闭环笔并随批推送触发 CI 外部终验 |
| 独立复核结论与裁决 | 指导侧独立四块核验全过（总体 **PASS**，BLOCKER 0，偏差 D1~D4 均合规解释与复验清除）：7 个实施文件 + 2 个两件套严丝合缝全等白名单；六道门禁全量独立复跑稳定全绿；实施侧误判的 D1（bash 不可用）经指导侧使用 `D:\git\Git\bin\bash.exe` 实跑官方契约脚本验证完全通过（rc=0）；技术与业务实现严谨，终审裁定 **ACCEPT 予以封盘** |
| 状态 | TASK-234 本地闭环，指导侧终裁 **ACCEPT 予以封盘** |

## 验收记录：`TASK-235`（核心链路缺陷修复与社交闭环：榜单结算窗口矫正、申诉判定回写、打卡时间对齐与待审申请一键处理，MEDIUM 档，2026-10-15）

| 项 | 内容 |
| --- | --- |
| 开工基线 | `ef838b4`（main 分支，工作树干净） |
| C-01 实施笔 | `3d31065` `feat(leaderboard,verify,record,user,web): TASK-235 核心链路缺陷修复与社交闭环——榜单结算窗口矫正/申诉回写/打卡对齐/待审一键处理`（恰 11 文件，+566/−47） |
| C-02 任务两件套 | `42f6f2e` `docs(mailbox): TASK-235 核心链路缺陷修复与社交闭环验收与台账闭环（TASK-235）`（恰 2 文件） |
| 任务内容与白名单 | 相对开工基线恰 13 文件 = 白名单全等（实施 11 文件 + 任务两件套 2 文件）；白名单外（api、pom.xml、package.json、其他服务）100% 零触碰，零新外部依赖 |
| 核心实现 | ① 榜单结算时间覆盖 Bug 根治：`LeaderboardContributionMapper.markSettled` SQL 追加 `AND settled_at IS NULL`，结算仅盖戳未结算行、绝不覆盖历史结算时间，彻底解除了每 10 分钟定时任务将历史已结算贡献刷成当前时间导致今日/周/月周期范围榜失真的系统级缺陷；② 申诉改判状态闭环：`VerifyService.reviewAppeal` 在管理员终审改判后，显式执行 `verification_result.verdict` 回写并主动失效本地 Caffeine 缓存（`RESULT_CACHE_PREFIX + recordId`），消除记录表显示 `RE_PASSED` 但详情与判定接口仍展示 `REJECTED` 的状态割裂；③ 打卡与统计时间口径对齐：`SportRecordService.myStreak` 与 `myStats` 统一按 `startTime` 归自然日（null 回退 `createdAt`），消除跨零点与离线补传导致的误断签，与 TASK-234 目标服务保持统一；④ 社交待办一键闭环：`user-service` 新增 `GET /api/friends/requests` 查询待处理申请列表，前端 `friends.page.vue` 新增「收到的好友申请」卡片一键同意/拒绝并联动刷新，移除必须线下询问 `requestId` 手工输入的反人类交互 |
| 本机门禁 | 后端全 reactor 9 模块 clean test 全绿：830 测 0 失败 0 错误（BUILD SUCCESS 12:25 min；user 178 测 +5、record 277 测 +2、verify 161 测 +2、leaderboard 86 测 +1）；前端 `type-check` 0 错误；`pnpm test` 4 套 32 测全过；`pnpm build` 3317 modules 产出成功（25.83s）；Playwright E2E 本地全量实跑 **12 passed (41.7s)** 100% 全绿；契约门 `mailbox-contract.sh --open=TASK-235 --baseline=ef838b4` 退出码 0（判据 A 两件套齐 + 判据 B 清单一致） |
| 是否到达外部门槛 | **未到达**：C-01/C-02 已提交，待指导侧统一提交台账闭环笔并随批推送触发 CI 外部终验 |
| 独立复核结论与裁决 | 指导侧独立四块核验全过（总体 **PASS**，BLOCKER 0，偏差 D1~D4 均合规解释与复验清除）：11 个实施文件 + 2 个两件套严丝合缝全等白名单；六道门禁全量独立复跑稳定全绿；D1 确认表结构与任务书口径偏差（`verification_result` 无 `recheck_result` 字段，该列属 appeal 表已写入，回写 verdict + 缓存失效符合架构设计）；技术与业务实现严谨，终审裁定 **ACCEPT 予以封盘** |
| 状态 | TASK-235 本地闭环，指导侧终裁 **ACCEPT 予以封盘** |

## 验收记录：`TASK-236`（社交与点赞交互补齐：动态流与记录详情页点赞交互组件与计数实时同步，MEDIUM 档，2026-10-15）

| 项 | 内容 |
| --- | --- |
| 开工基线 | `69cb306`（main 分支，工作树干净） |
| C-01 实施笔 | `b6699f2` `feat(web): TASK-236 社交与点赞交互补齐——动态流与详情页点赞交互与状态同步（C-01 实施笔）`（恰 4 文件，+202/−13） |
| C-02 任务两件套 | `b41fe19` `docs(mailbox): TASK-236 社交与点赞交互补齐验收与台账闭环（C-02 台账笔）`（恰 2 文件，+158/−0） |
| 任务内容与白名单 | 相对开工基线恰 6 文件 = 白名单全等（实施 4 文件 + 任务两件套 2 文件）；生产后端代码、配置及红线文件 100% 零触碰，零新外部依赖 |
| 核心实现 | ① 好友动态流点赞闭环：`feed.page.vue` 每张动态卡片操作行新增点赞按钮（已赞态 `type="primary"` 高亮 + `赞 (n)`/`已赞 (n)` 计数），列表加载后由 `Promise.allSettled` 并发异步拉取当前页点赞态（单条失败静默降级不阻断整页）；点击经 per-card `likeLoadingMap` 锁防重，按态调用 `likeRecord`/`unlikeRecord` 并以服务端最新返回值覆盖本地状态，失败弹出 `message.error`（特化 6001 提示）；未登录或记录状态非 2/5 时置灰 + Tooltip 提示「未通过校验的记录不可点赞」防呆；② 记录详情页点赞闭环：`record-detail.page.vue` 操作区与「导出 GPX」并列点赞按钮，`load()` 时摘要与点赞态并发调取，点赞异常静默降级不阻断轨迹与配速渲染，点击一键切换实时同步，verdict 已知非 PASSED 或未登录置灰防呆；③ E2E 强化与契约对齐：`helpers.ts` 的 like 路由升级为动态状态记忆（`Stores.likes` Map：GET 返回当前态、POST 赞 +1、DELETE 踩 -1），`feed.e2e.ts` 新增点赞按钮渲染与连续点击交互断言（0 赞 → POST 赞 +1 且高亮 → DELETE 踩 -1 且恢复 0 赞），全量 12 测稳定全绿 |
| 本机门禁 | 前端 `type-check` 0 错误（rc=0）；`pnpm test` 4 套 32 测全过（rc=0）；`pnpm build` 3317 modules 产出成功（18.83s，rc=0）；Playwright E2E 本地全量实跑 **12 passed (44.4s)** 100% 全绿（rc=0）；契约门 `mailbox-contract.sh --open=TASK-236 --baseline=69cb306` 退出码 0（判据 A 两件套齐 + 判据 B 清单一致） |
| 是否到达外部门槛 | **未到达**：C-01/C-02 已提交，待指导侧统一提交台账闭环笔并随批推送触发 CI 外部终验 |
| 独立复核结论与裁决 | 指导侧独立四块核验全过（总体 **PASS**，BLOCKER 0，偏差 D1~D2 合规解释）：4 个实施文件 + 2 个两件套严丝合缝全等白名单；前端全套验证与契约门禁独立实测稳定全绿；点赞交互、防抖与 6001 状态防呆严谨自洽，终审裁定 **ACCEPT 予以封盘** |
| 状态 | TASK-236 本地闭环，指导侧终裁 **ACCEPT 予以封盘** |

## 验收记录：`TASK-237`（全链路闭环体验补齐：记录申诉前台打通、通知中心交互下钻与顶栏登录感知，MEDIUM 档，2026-10-15）

| 项 | 内容 |
| --- | --- |
| 开工基线 | `e745c25`（main 分支，工作树干净） |
| C-01 实施笔 | `1d37ed0` `feat(web): TASK-237 全链路闭环体验补齐——申诉直通、通知下钻与顶栏登录感知（C-01 实施笔）`（恰 5 文件，+118/−16） |
| C-02 任务两件套 | `8177e97` `docs(mailbox): TASK-237 全链路闭环体验补齐验收与台账闭环（C-02 台账笔）`（恰 2 文件，+160/−0） |
| 任务内容与白名单 | 相对开工基线恰 7 文件 = 白名单全等（实施 5 文件 + 任务两件套 2 文件）；生产后端代码、配置及红线文件 100% 零触碰，零新外部依赖 |
| 核心实现 | ① 首页被拒记录申诉直通：`index.page.vue` 列表操作列新增 `canAppeal(status)` 门控（1 校验中 / 3 拒绝 / 6 维持拒绝 / 7 人工审核）→「判定/申诉」链接，`goVerdict` 带 `query.id` 直达 `/verdict`，彻底终结手动敲 URL 查因的断层体验；既有「查看轨迹」「删除」保留不变；② 记录详情页申诉引导：`record-detail.page.vue` 摘要卡片在 `verdict === 2`（拒绝）时渲染警告提示与直达按钮，一键带参直通 `/verdict?id=${recordId}`；③ 通知中心交互下钻与自动已读：`notifications.page.vue` 表格 `title`/`sourceId` 列对可下钻通知渲染链接——`RECORD_*` 跳详情（`RECORD_REJECTED` 直达判定页）、`FRIEND_ACCEPTED` 跳好友页，未读项点击自动 `markNotificationRead` 并刷新角标（失败静默不阻断跳转）；④ 顶栏登录态感知与设置入口：`App.vue` 顶栏根据 `loggedIn` 动态渲染，登录态展示「已登录: USER/ADMIN」身份标签 +「设置」（跳 `/settings`）+「退出」，未登录恢复「注册/登录」；⑤ E2E 断言对齐：`top-nav.e2e.ts` 对齐「设置」导航验证与退出后登录态恢复断言，全量 12 测稳定全绿 |
| 本机门禁 | 前端 `type-check` 0 错误（rc=0）；`pnpm test` 4 套 32 测全过（rc=0）；`pnpm build` 3317 modules 产出成功（19.62s，rc=0）；Playwright E2E 本地全量实跑 **12 passed (38.1s)** 100% 全绿（rc=0）；契约门 `mailbox-contract.sh --open=TASK-237 --baseline=e745c25` 退出码 0（判据 A 两件套齐 + 判据 B 清单一致） |
| 是否到达外部门槛 | **未到达**：C-01/C-02 已提交，待指导侧统一提交台账闭环笔并随批推送触发 CI 外部终验 |
| 独立复核结论与裁决 | 指导侧独立四块核验全过（总体 **PASS**，BLOCKER 0，偏差 D1~D3 合规解释）：5 个实施文件 + 2 个两件套严丝合缝全等白名单；前端全套验证与契约门禁独立实测稳定全绿；D3 认定 ref 承载 localStorage 登录态并由 logout/watch 同步属合理设计防陈旧值；终审裁定 **ACCEPT 予以封盘** |
| 状态 | TASK-237 本地闭环，指导侧终裁 **ACCEPT 予以封盘** |

## 验收记录：`TASK-238`（运动轨迹全链路真实化与反作弊合理性：长轨迹全量突破、GPX完整导出、SVG等比投影与闭环判定合理性，MEDIUM 档，2026-10-15）

| 项 | 内容 |
| --- | --- |
| 开工基线 | `b1c0e3a`（main 分支，工作树干净，CI run 38692154084 全绿） |
| C-01 实施笔 | `b693fe9` `feat(web,verify): TASK-238 运动轨迹全链路真实化——全量长轨迹与GPX导出、SVG等比投影与折返起征保护（C-01 实施笔）`（恰 4 文件，+184/−28） |
| C-02 任务两件套 | `50f85f7` `docs(mailbox): TASK-238 运动轨迹全链路真实化验收与台账闭环（C-02 台账笔）`（恰 2 文件，+191/−1） |
| 任务内容与白名单 | 相对开工基线恰 6 文件 = 白名单全等（实施 4 文件 + 任务两件套 2 文件）；生产后端其他代码、配置及红线文件 100% 零触碰，零新外部依赖 |
| 核心实现 | ① 详情页长轨迹突破与 GPX 完整导出：`record-detail.page.vue` 的 `load()` 首包 500 后，若 `total > records.length` 则自动 `while (allPoints.length < totalPoints && allPoints.length < 20000)` 循环分页按批追加拉取至全量（每批 500 点，空批 break 防死循环），文案改为「共 X 个轨迹点（完整加载）…」并移除 500 点截断警告；`generateGpx(points.value)` 自然基于完整点集导出；② SVG 局部等距等比投影：`record-detail.page.vue` 结合 `avgLat` 余弦修正 `spanX`，统一缩放比 `scale=min(innerW/spanX, innerH/spanY)` 居中等比渲染（viewBox 800×400、PAD 16），全同坐标退化居中兜底，正方形/圆形轨迹不再拉伸变形；③ R4 规则起征门槛保护：`R4DistanceRule.java` 在 `cumulative < minCumulativeMeters` 时直接返回 null 豁免判定，超门槛原比值判定一字未动；`VerifyProperties.java` 新增 `minCumulativeMeters = 500.0`；新建 `R4DistanceRuleTest.java` 覆盖短闭环豁免、长折返命中、正常直线不命中三用例 |
| 本机门禁 | 后端 `mvn -B -ntp -o -s .mvn-settings.xml clean test -pl verify-service -am` BUILD SUCCESS（Tests run: 164, Failures: 0, Errors: 0，rc=0）；前端 `type-check` 0 错误（rc=0）；`pnpm test` 4 套 32 测全过（rc=0）；`pnpm build` 产出成功（rc=0）；Playwright E2E 本地实跑 **12 passed (31.0s)** 100% 全绿（rc=0）；契约门 `mailbox-contract.sh --open=TASK-238 --baseline=b1c0e3a` 退出码 0（判据 A 两件套齐 + 判据 B 清单一致） |
| 是否到达外部门槛 | **未到达**：C-01/C-02 已提交，待指导侧统一提交台账闭环笔并随批推送触发 CI 外部终验 |
| 独立复核结论与裁决 | 指导侧独立四块核验全过（总体 **PASS**，BLOCKER 0，偏差 D1~D3 合规解释）：4 个实施文件 + 2 个两件套严丝合缝全等白名单；前端全套验证与契约门禁独立实测稳定全绿；数学等比投影算法精炼准确，长轨迹分页防死锁健全，反作弊起征保护与规范兼容性严密；终审裁定 **ACCEPT 予以封盘** |
| 状态 | TASK-238 本地闭环，指导侧终裁 **ACCEPT 予以封盘** |

## 验收记录：`TASK-239`（好友榜查询高并发性能优化——Redis Pipeline 批量分数检索与单次往返，MEDIUM 档，2026-10-15）

| 项 | 内容 |
| --- | --- |
| 开工基线 | `57128e8`（main 分支，工作树干净，TASK-238 封盘笔，CI run 38692154084 全绿） |
| C-01 实施笔 | `3cf0406` `feat(leaderboard): TASK-239 好友榜查询引入 Redis Pipeline 批量获取积分（C-01 实施笔）`（恰 3 文件，+90/−59） |
| C-02 任务两件套 | `7c6d9ef` `docs(mailbox): TASK-239 好友榜 Pipeline 批量优化验收与台账闭环（C-02 台账笔）`（恰 2 文件，+103/−3） |
| 任务内容与白名单 | 相对开工基线恰 5 文件 = 白名单全等（实施 3 文件 + 任务两件套 2 文件）；网关、前端、其他微服务代码及红线文件 100% 零触碰，零新外部依赖 |
| 核心实现 | ① 好友榜 Pipeline 单次往返批量点查：`LeaderboardService.topFriends` 彻底移除原 `for (start += 500)` 对总榜 `leaderboard:overall` 循环批次扫描，基于去重好友 ID 集合（`distinctFriendIds`）通过 `stringRedisTemplate.executePipelined` 执行 `connection.zSetCommands().zScore` 一次性拉齐全体好友得分，网络往返由数十次/百次降至恰 1 次（RTT=1），时间复杂度由 $O(N_{zset})$ 降至 $O(N_{friends})$；② 内存过滤/排序/截断契约自洽：内存过滤未上榜（null）与非正分数，按分数降序（同分按 member 字符串降序 tie-breaker，100% 对齐 Redis ZREVRANGE 稳定排序），截取前 `size` 条交由 `assemble` 组装返回；③ 业务契约 100% 保持：只显示好友（本人与非好友均不在榜）、无好友/未上榜/降级安全返回空榜，`fetchAllFriendIds` 降级与分页拉齐契约一字不动；④ 单元测试全套适配：既有单测 `topFriend_filtersNonFriends` 与分页契约测试适配 Pipeline 打桩，改写批次扫描用例为 `topFriend_usesPipeline_batchRetrievesFriendScoresInSingleRoundTrip`（断言 Pipeline 恰 1 次且整榜扫描 0 次），新增 `topFriend_sortsFriendsByScoreDescending_andTiesByMemberDescending`（同分 member 降序、null 过滤、size 截断全对），并适配 `LeaderboardCacheInvocationTest` 缓存穿透调用断言 |
| 本机门禁 | 后端 `mvn -B -ntp -o -s .mvn-settings.xml clean test -pl leaderboard-service -am` BUILD SUCCESS（Tests run: 87, Failures: 0, Errors: 0，rc=0）；前端 `type-check` 0 错误（rc=0）；前端单元测试 4 套 32 测全过（rc=0）；契约门 `mailbox-contract.sh --open=TASK-239 --baseline=57128e8` 退出码 0（判据 A 两件套齐 + 判据 B 清单一致） |
| 是否到达外部门槛 | **未到达**：C-01/C-02 已提交，待指导侧统一提交台账闭环笔并随批推送触发 CI 外部终验 |
| 独立复核结论与裁决 | 指导侧独立四块核验全过（总体 **PASS**，BLOCKER 0，偏差 D1 白名单扩展合理）：3 个实施文件 + 2 个两件套严丝合缝全等白名单；后端 87 测与前端全套门禁独立实测稳定全绿；Pipeline 改造使高频好友榜接口性能与总榜大小彻底解耦，同分排序完全对齐 Redis 原生语义；终审裁定 **ACCEPT 予以封盘** |
| 状态 | TASK-239 本地闭环，指导侧终裁 **ACCEPT 予以封盘** |
 
## 验收记录：`TASK-240`（存储性能与网络吞吐双重优化——friendship 表 user_high 索引补齐与动态流 Feed 点赞态批量下推，MEDIUM 档，2026-10-15）

| 项 | 内容 |
| --- | --- |
| 开工基线 | `d2c6d62`（main 分支，工作树干净，TASK-239 封盘笔与优化看板登记） |
| C-01 实施笔 | `86be2d8` `feat(perf): TASK-240 存储索引补齐与Feed点赞态批量下推（C-01 实施笔）`（恰 8 文件，+298/−9） |
| C-02 任务两件套 | `f0ea149` `docs(mailbox): TASK-240 存储与网络性能优化验收与台账闭环（C-02 台账笔）`（恰 2 文件，+239/−0） |
| 任务内容与白名单 | 相对开工基线恰 10 文件 = 白名单全等（实施 8 文件 + 任务两件套 2 文件）；生产网关、其他服务代码及红线文件 100% 零触碰，零新外部依赖 |
| 核心实现 | ① B6 存储慢查消灭：`sql/01-user-db.sql` 中 `friendship` 建表补齐 `KEY idx_user_high (user_high)`；新建幂等迁移脚本 `sql/migrations/add-idx-friendship-user-high.sql`（采用 `information_schema.statistics` 判存在模式，与 `add-idx-record-status.sql` 同口径，供存量库安全执行），彻底消除 `FriendService.listFriendIds` 与 `listFriends` 在较大用户 ID 侧的全表扫描，支持 MySQL `index_merge` 高效聚合；② B7 服务端点赞态批量下推与 Redis Pipeline：`LikeController.java` 新增 `POST /api/records/likes/batch` 端点（入参 `List<Long> recordIds`，经网关注入 `X-User-Id` 认定身份）；`RecordLikeService.java` 实现 `getLikesBatch(recordIds, userId)`：空/null 入参安全返回空列表、去重保序、逐条 `selectById` 经 `sportRecordService.assertCanView` 校验（记录不存在或不可视安全跳过，绝不拖垮整批）、`stringRedisTemplate.executePipelined` 单次往返（RTT=1）批量获取 `COUNT` 与 `SISMEMBER`、缓存 miss 时回退既有 `readCount`（DB 兜底+回填）；③ B7 前端单请求直出：`web/src/api/client.ts` 导出 `getLikesBatch(recordIds)`；`web/src/pages/feed.page.vue` 重构 `loadLikes()` 为单次批量请求，动态流首屏网络包由 20 次并发 HTTP 骤减为 1 次批量下推，catch 静默降级保障页面高可用；④ 测试全套强化：`RecordLikeServiceTest.java` 新增 7 个单元测试（覆盖 Pipeline 单次往返、空入参安全、去重保序、缓存未命中兜底回填、不可视记录安全过滤、userId=null 降级、全不可视空返回）；`web/src/api/__tests__/client.test.ts` 新增 R1 `getLikesBatch` 路由与请求体契约测试 |
| 本机门禁 | 后端 `mvn -B -ntp -o -s .mvn-settings.xml clean test -pl record-service,user-service -am` BUILD SUCCESS（Tests run: 284, Failures: 0, Errors: 0，rc=0）；前端 `pnpm run type-check` 0 错误（rc=0）；前端单元测试 4 套 33 测全过（rc=0）；契约门 `mailbox-contract.sh --baseline=d2c6d62` 退出码 0（判据 A 两件套齐 + 判据 B 清单一致，0 在途） |
| 是否到达外部门槛 | **未到达**：C-01/C-02 已提交，待指导侧统一提交台账闭环笔并随批推送触发 CI 外部终验 |
| 独立复核结论与裁决 | 指导侧独立四块核验全过（总体 **PASS**，BLOCKER 0，偏差 D1 台账自指约定合规）：8 个实施文件 + 2 个两件套严丝合缝全等白名单；后端 284 测与前端 33 测及契约门禁独立实测稳定全绿；B6 索引消除了核心社交表隐蔽慢查风险，B7 Pipeline 批量下推大幅降低网关与前端并发压力，容错降级与权限隔离设计严谨；终审裁定 **ACCEPT 予以封盘** |
| 状态 | TASK-240 本地闭环，指导侧终裁 **ACCEPT 予以封盘** |
 
## 验收记录：`TASK-241`（前台交互闭环与客户端幂等防重——申诉管理直达自动回填与运动记录提交表单requestId固化加固，MEDIUM 档，2026-10-15）

| 项 | 内容 |
| --- | --- |
| 开工基线 | `92df499`（main 分支，工作树干净，TASK-240 封盘笔与交接看板更新） |
| C-01 实施笔 | `8f7601d` `feat(web): TASK-241 申诉管理自动回填与记录提交幂等固化（C-01 实施笔）`（恰 4 文件，+130/−9） |
| C-02 任务两件套 | `a6cd2c1` `docs(mailbox): TASK-241 申诉闭环与表单幂等加固验收与台账闭环（C-02 台账笔）`（恰 2 文件，+190/−0） |
| 任务内容与白名单 | 相对开工基线恰 6 文件 = 白名单全等（实施 4 文件 + 任务两件套 2 文件）；生产后端代码、网关、pom.xml、package.json 及红线文件 100% 零触碰，零新外部依赖 |
| 核心实现 | ① B8 申诉管理闭环与自动回填：`web/src/pages/verdict.page.vue` 申诉成功 Alert 内渲染直通链接（`router-link` 到 `/admin-appeal?id=${appealResult.id}`，文案「去管理后台审核 &rarr;」）；`web/src/pages/admin-appeal.page.vue` 引入 `useRoute` 与 `onMounted`，挂载时自动读取 `route.query.id` 或 `route.query.appealId` 并回填至 `form.id`，彻底消除盲打 ID；终判成功卡片渲染直通链接直达 `/verdict?id=${result.recordId}`（文案「查看记录最新判定结果 &rarr;」），形成端到端完整闭环；② B9 记录提交表单客户端 requestId 固化加固：`web/src/pages/record-submit.page.vue` 在途防篡改（`submitting=true` 期间 `requestId` 输入框与「重新生成」按钮添加 `:disabled="submitting"` 绑定，且 `genRequestId()` 在途短路）、`onSubmit()` 开头提交防空校验（`if (!requestId.value || !requestId.value.trim()) genRequestId()`）、失败重试保持（catch 分支严格保留当前 `requestId` 不变，并在 `submitError` 提示区引导用户安全重试）、下一条轮换（提供 `prepareNextRecord()`「准备提交下一条（刷新 requestId）」按钮并重置提交结果，避免后续提交撞 3004）、3004 语义清晰化（捕获 3004 时提示「重复提交拦截（幂等命中，返回原记录）」并安全回填）；③ 测试全套强化：`web/src/api/__tests__/client.test.ts` 新增 `submitAppeal`（2 例）与 `reviewAppeal`（2 例）路由契约单测 |
| 本机门禁 | 前端 `pnpm run type-check` 0 错误（rc=0）；前端单元测试 4 套 37 测全过（rc=0）；契约门 `mailbox-contract.sh --baseline=92df499` 退出码 0（判据 A 两件套齐 + 判据 B 清单一致，0 在途） |
| 是否到达外部门槛 | **未到达**：C-01/C-02 已提交，待指导侧统一提交台账闭环笔并随批推送触发 CI 外部终验 |
| 独立复核结论与裁决 | 独立复核子 Agent 实跑全过（总体 **PASS**，BLOCKER 0，三权分立严格履行）：6 个白名单文件严丝合缝；前端类型与 37 测及契约门禁独立实测稳定全绿；B8 彻底打通了申诉提交与后台审核的双向通道，B9 强化了前端表单在弱网重发下的幂等稳定性；终审裁定 **ACCEPT 予以封盘** |
| 状态 | TASK-241 本地闭环，指导侧终裁 **ACCEPT 予以封盘** |
 
## 验收记录：`TASK-242`（存储慢查消灭与社交闭环及体验提升——周期榜复合索引补齐、解除好友链路打通与目标空态优化，MEDIUM 档，2026-10-15）

| 项 | 内容 |
| --- | --- |
| 开工基线 | `76b1b47`（main 分支，工作树干净，B10-B19 侦察归档与看板更新基线） |
| C-01 实施笔 | `8804c71` `feat(perf): TASK-242 周期榜复合索引补齐、解除好友链路打通与目标空态体验提升（C-01 实施笔）`（恰 9 文件，+215/−12） |
| C-02 任务两件套 | `cd0f5be` `docs(mailbox): TASK-242 存储慢查消灭与社交闭环及体验提升验收与台账闭环（C-02 台账笔）`（恰 2 文件，+211/−0） |
| 任务内容与白名单 | 相对开工基线恰 11 文件 = 白名单全等（实施 9 文件 + 任务两件套 2 文件）；生产其他后端微服务、网关、pom.xml、package.json 及红线文件 100% 零触碰，零新外部依赖 |
| 核心实现 | ① B10 存储索引补齐与慢查消灭：`sql/02-record-db.sql` 为 `leaderboard_contribution` 添加 `KEY idx_status_settled (status, settled_at)`；新建幂等迁移脚本 `sql/migrations/add-idx-leaderboard-contribution-status-settled.sql`（采用 `information_schema.statistics` 防重），彻底消除周期榜聚合与定时结算全表慢查，消除 Javadoc 技术债挂账；② B14 好友社交全链路闭环：后端 `FriendController.java` 新增 `@DeleteMapping("/{targetUserId}")` 端点，经 `resolveUserId` 安全认定调用者身份；`FriendService.java` 实现 `deleteFriend(userId, targetUserId)`（参数非空防自删校验、Redisson 锁 `lock:friend:low_high` 保护、`(user_low, user_high)` 规范化删除、影响 0 行不报错幂等安全）；`FriendServiceTest.java` 新增 5 个单测（成功解除、幂等安全、自删拦截、反向归一、空参拦截，总用例 36 全绿）；前端 `client.ts` 导出 `deleteFriend` 并覆盖路由契约测试；前端 `friends.page.vue` 补齐 `loadFriends()` 挂载自加载，好友表格增加「操作」列与 `a-popconfirm` 确认气泡，解除成功后自动刷新好友列表；③ B19 前端体验优化：`web/src/pages/index.page.vue` 当 `metric === 1`（次数）时消除 `.00` 小数显示；近 30 天每日里程在累计总里程为 0 时渲染优雅引导空态卡片并支持一键跳转提交页 |
| 本机门禁 | 后端 `mvn -B -ntp -o -s .mvn-settings.xml clean test -pl user-service -am` BUILD SUCCESS（Tests run: 183, Failures: 0, Errors: 0，rc=0）；前端 `pnpm run type-check` 0 错误（rc=0）；前端单元测试 4 套 39 测全过（rc=0）；契约门 `mailbox-contract.sh --open=TASK-242 --baseline=76b1b47` 退出码 0（判据 A 两件套齐 + 判据 B 清单一致，0 在途） |
| 是否到达外部门槛 | **未到达**：C-01/C-02 已提交，待指导侧统一提交台账闭环笔并随批推送触发 CI 外部终验 |
| 独立复核结论与裁决 | 独立复核子 Agent 实跑全过（总体 **PASS**，BLOCKER 0，三权分立严格履行）：11 个白名单文件严丝合缝；后端 183 测、前端类型与 39 测及契约门禁独立实测稳定全绿；B10 消除了核心周期榜慢查，B14 补齐了好友社交删除动作与初载断层，B19 修复了目标次数常理小数违和并提升了空态体验；终审裁定 **ACCEPT 予以封盘** |
| 状态 | TASK-242 本地闭环，指导侧终裁 **ACCEPT 予以封盘** |
 
## 验收记录：`TASK-243`（核心服务高可用与容灾加固——Outbox死信隔离、规则缓存防击穿硬化与好友申请超时防死锁，MEDIUM 档，2026-10-15）

| 项 | 内容 |
| --- | --- |
| 开工基线 | `cf2deae`（main 分支，工作树干净，TASK-243 方案 B 立项准备基线） |
| C-01 实施笔 | `b50829a` `feat(reliability): TASK-243 核心服务高可用与容灾加固（C-01 实施笔）`（恰 8 文件，+226/−28） |
| C-02 任务两件套 | `3f6856c` `docs(mailbox): TASK-243 核心服务高可用与容灾加固验收与台账闭环（C-02 台账笔）`（恰 2 文件，+232/−0） |
| 任务内容与白名单 | 相对开工基线恰 10 文件 = 白名单全等（实施 8 文件 + 任务两件套 2 文件）；生产前端、网关、pom.xml、package.json 及红线文件 100% 零触碰，零新外部依赖 |
| 核心实现 | ① B11 Outbox 毒丸事件防死信扫描劣化：`sql/03-verify-db.sql` 注释补充 `EXHAUSTED` 说明；`VerifyEventOutboxMapper.java` 新增 `markExhausted(id)` 端点（带 `status = 'PENDING'` 幂等防护）；`VerifyOutboxRelay.java` 抽取公共 `onRowFailure` 处理失败，在投递或标记达到 `maxRetry`（16 次）时调用 `markExhausted` 将消息移入 `EXHAUSTED` 终态并记录 error 告警（`totals.exhausted++`），彻底移出 `PENDING` 扫描集消除 I/O 放大；新增 4 个单测全绿；② B12 规则缓存并发防击穿硬化：`RuleCacheService.java` 在 `rebuildFromDb` 未获锁分支增加至多 3 次（50ms 间隔）轮询重读 Redis 机制，命中有效值或 EMPTY 哨兵直接回填本地 Caffeine 并返回，避免并发线程回源打穿 `rule_version` DB；单测断言 `dbLoader.get()` 触发 0 次；③ B13 好友申请生命周期闭环与防死锁：`FriendService.java` 在 `createRequest` 中对创建时间早于 7 天前的同向/反向 PENDING 申请做惰性流转至 `CANCELLED(3)`，解除互斥阻断并允许创建新单，根除双向永久死锁；`listPendingRequests` 增加 7 天内有效过滤；单测全覆盖 |
| 本机门禁 | 后端 `mvn -B -ntp -o -s .mvn-settings.xml clean test -pl verify-service,user-service -am` BUILD SUCCESS（Tests run: 168, Failures: 0, Errors: 0，rc=0）；前端 `pnpm run type-check` 0 错误（rc=0）；前端单测 4 套 39 测全过（rc=0）；契约门 `mailbox-contract.sh --open=TASK-243 --baseline=cf2deae` 退出码 0（判据 A 两件套齐 + 判据 B 清单一致，0 在途） |
| 是否到达外部门槛 | **未到达**：C-01/C-02 已提交，待指导侧统一提交台账闭环笔并随批推送触发 CI 外部终验 |
| 独立复核结论与裁决 | 独立复核子 Agent 实跑全过（总体 **PASS**，BLOCKER 0，三权分立严格履行）：10 个白名单文件严丝合缝；后端 168 测与前端全套门禁独立实测稳定全绿；B11 彻底隔绝了死信毒丸对热轮询扫描的污染，B12 阻断了规则失效瞬时击穿打爆 DB 的风险，B13 解除了好友申请双向永久死锁的系统缺陷；终审裁定 **ACCEPT 予以封盘** |
| 状态 | TASK-243 本地闭环，指导侧终裁 **ACCEPT 予以封盘** |

## 验收记录：`TASK-244`（治理闭环、存储优化与高并发防爆——申诉待审列表驱动、通知索引慢查消除与批量已读、总榜Redis内存防爆与定时结算对账补偿，MEDIUM 档，2026-10-15）

| 项 | 内容 |
| --- | --- |
| 开工基线 | `a870d1c`（main 分支，工作树干净，TASK-244 方案 C 立项准备基线） |
| C-01 实施笔 | `e753b39` `feat(governance): TASK-244 治理闭环、存储优化与高并发防爆（C-01 实施笔）`（恰 15 文件，+435/−21） |
| C-02 任务两件套 | `0c7b7a5` `docs(mailbox): TASK-244 治理闭环、存储优化与高并发防爆验收与台账闭环（C-02 台账笔）`（恰 2 文件，+245/−0） |
| 任务内容与白名单 | 相对开工基线恰 17 文件 = 白名单全等（实施 15 文件 + 任务两件套 2 文件）；生产 record-service、网关、pom.xml、package.json 及红线文件 100% 零触碰，零新外部依赖 |
| 核心实现 | ① B15 申诉待审队列与管理后台表格驱动闭环：`VerifyController.java` 新增 `@GetMapping("/pending")`，`VerifyService.java` 实现 `listPendingAppeals()` 过滤 `PENDING` 申诉并按 id 倒序返回；前端 `client.ts` 导出 `fetchPendingAppeals()`；`admin-appeal.page.vue` 增加「待审核申诉列表」卡片与表格，支持初载与终判自动加载并具备「填入审核」一键联动，彻底终结盲打 ID；VerifyControllerTest 与 client.test.ts 单测全覆盖；② B16 记录删除与榜单回撤自动对账自愈：`LeaderboardContributionMapper.java` 新增 `selectOrphanContributions`（NOT EXISTS 查已被物理删除记录的残留 ACTIVE 贡献）；`LeaderboardService.java` 在 `settleAndReconcile` 结算期间自动识别孤儿贡献行并调用 `updateStatus` 迁移为 `ROLLED_BACK`，从当期 target/ZSet 剔除；LeaderboardServiceTest 单测全覆盖；③ B17 通知中心索引优化与批量标记已读：`sql/01-user-db.sql` 补齐 `KEY idx_user_id (user_id, id)`；新增幂等迁移脚本 `sql/migrations/add-idx-notification-user-id.sql`，彻底消除分页慢排序 `Using filesort`；`NotificationController.java` 新增 `@PatchMapping("/batch-read")`，`NotificationService.java` 实现 `batchMarkRead(userId, ids)` 批量更新并触发回执；前端 `client.ts` 导出 `batchMarkNotificationsAsRead`，`notifications.page.vue` 表格开启行多选复选框并支持「批量标记已读」；NotificationServiceTest 与 client.test.ts 单测全覆盖；④ B18 总榜 Redis 内存防爆与倾倒重构：`LeaderboardService.java` 的 `settleAndReconcile` 彻底重构移除 `range(OVERALL_ZSET_KEY, 0, -1)` 全量 dump 进 JVM，改为基于 `opsForZSet().scan(...)` 游标分批遍历比对（带 null 安全与异常防护）；结算末尾执行 `removeRange` 容量截断保留前 10,000 名，防止未活跃成员无限堆积打爆 Redis 内存；LeaderboardServiceTest 补齐单测 |
| 本机门禁 | 后端 `mvn -B -ntp -o -s .mvn-settings.xml clean test -pl verify-service,user-service,leaderboard-service -am` BUILD SUCCESS（Reactor 6 模块全绿，verify 169 / user 189 / leaderboard 91 用例全部通过，rc=0）；前端 `pnpm run type-check` 0 错误（rc=0）；前端单测 4 文件 41 测全过（rc=0）；契约门 `mailbox-contract.sh --open=TASK-244 --baseline=a870d1c` 退出码 0（判据 A 两件套齐 + 判据 B 清单一致，0 在途） |
| 是否到达外部门槛 | **未到达**：C-01/C-02 已提交，待指导侧统一提交台账闭环笔并随批推送触发 CI 外部终验 |
| 独立复核结论与裁决 | 独立复核子 Agent 实跑全过（总体 **PASS**，BLOCKER 0，三权分立严格履行）：17 个白名单文件严丝合缝；后端 449 测、前端类型与 41 测及契约门禁独立实测稳定全绿；B15 终结了盲打 ID，B16 实现了跨服务离线自愈，B17 消除了慢排序并打通多选批量操作，B18 治理了内存倾倒与 Redis 膨胀隐患；终审裁定 **ACCEPT 予以封盘** |
| 状态 | TASK-244 本地闭环，指导侧终裁 **ACCEPT 予以封盘** |

## 验收记录：`TASK-245`（前台端到端业务与全功能体验增强闭环——记录详情指标与可见性修改、申诉全链路进度条与管理外链修复、用户资料与好友批量、动态流视觉与GPX导入，MEDIUM 档，2026-10-15）

| 项 | 内容 |
| --- | --- |
| 开工基线 | `6f4d766`（main 分支，工作树干净，TASK-245 方案 D 立项准备基线） |
| C-01 实施笔 | `23f58e3` `feat(experience): TASK-245 前台端到端业务与全功能体验增强闭环（C-01 实施笔）`（恰 21 文件，+708/−64） |
| C-02 任务两件套 | `5a275ab` `docs(mailbox): TASK-245 前台端到端业务与全功能体验增强闭环验收与台账闭环（C-02 台账笔）`（恰 2 文件，+212/−0） |
| 任务内容与白名单 | 相对开工基线恰 21 文件 = 19 白名单 + 经主 Agent 决策（方案 B）放宽的 2 个 api DTO 契约文件（`SportRecordDTO` 与 `FriendRequestDTO`）；生产 verify-service、leaderboard-service、gateway-service、pom.xml、package.json 及红线文件 100% 零触碰，零新外部依赖 |
| 核心实现 | ① B22+B29 记录详情全能卡与可见性修改：`RecordController` 新增 `PATCH /records/{id}/visibility`（0/1/2 严格校验，非本人 403）与 `GET /records/{id}`；`SportRecordService` 实现值域校验与归属防护；`record-detail.page.vue` 补齐运动指标卡（🏃/🚴/🚶 中文类型、里程 km、耗时、平均配速）+ 原地可见性下拉切换即保存；SVG 起终点高亮特征颜色（绿色起点 `#52c41a`、红色终点 `#ff4d4f`）；② B26 申诉全链路透明追踪：`SportRecordService` 新增 `getAppeal` 由记录状态权威推导申诉进度/结论；`verdict.page.vue` 彻底移除跳转管理后台（403）的荒谬外链，引入 `<a-steps>` 进度条组件，已有申诉自动回显并禁用重复提交防 3005 冲突；③ B25 用户资料维护与好友批量操作：`UserController` 新增 `GET/PUT /api/user/profile` 支持查看与修改用户昵称；`FriendService.listPendingRequests` 批量补齐申请人真实昵称 `fromNickname`；`FriendController` 新增 `batch-accept` 与 `batch-reject` 端点；`settings.page.vue` 增加个人资料卡片；`friends.page.vue` 表格开启行多选复选框并支持「批量同意」与「全部忽略」；④ B27 动态流视觉与空态引导：`feed.page.vue` 卡片头部增加首字主题彩色头像与中文运动类型徽标；空态采用 `<a-result>` 丰富展示并提供「去添加好友」「去记录运动」引导按钮；⑤ B28 轨迹 GPX 文件导入解析：`gpx.ts` 新增纯正则实现的 `parseGpx`（零依赖，鲁棒提取经纬度并补齐 seq/speed/ts 递推）；`record-submit.page.vue` 增加「导入 GPX 文件」支持拖拽/选择文件自动解析填入表单并推导时间与距离；⑥ 测试全套强化：新增 `FriendServiceTest`（批量与资料）、`SportRecordServiceTest`（详情与可见性）、`gpx.test.ts`（11 测）、`client.test.ts` 契约扩充（32 测） |
| 本机门禁 | 后端 `mvn -B -ntp -o -s .mvn-settings.xml clean test -pl record-service,user-service -am` BUILD SUCCESS（Tests run: 298, Failures: 0, Errors: 0，rc=0）；前端 `pnpm run type-check` 0 错误（rc=0）；前端单元测试 4 套 55 测全过（rc=0）；契约门 `mailbox-contract.sh --open=TASK-245 --baseline=6f4d766` 退出码 0（判据 A 两件套齐 + 判据 B 清单一致，0 在途） |
| 是否到达外部门槛 | **已到达**：随批推送 a6701b0 后经第 56 次 CI（run 38774258105）conclusion=success 全 job 绿（web ✓20s / build ✓11m41s 外部终验全过） |
| 独立复核结论与裁决 | 独立复核子 Agent 实跑全过（总体 **PASS**，BLOCKER 0，三权分立严格履行）：改动集恰 21 文件（19 白名单 + 2 如实披露的 api DTO）；后端 298 测、前端类型与 55 测及契约门禁独立实测稳定全绿；B22/B29/B26/B25/B27/B28 端到端打通了前台高频交互，彻底终结了外链越权、盲打与无头像等断层体验；终审裁定 **ACCEPT 予以封盘** |
| 状态 | TASK-245 本地闭环，指导侧终裁 **ACCEPT 予以封盘** |

## 验收记录：`TASK-246`（后台架构韧性与存储生命周期治理——B20 Outbox历史清理与毒丸重放、B21 通知历史清理与索引、B23 好友榜多级缓存防穿透、B24 网关统一异常标准包装，MEDIUM 档，2026-10-16）

| 项 | 内容 |
| --- | --- |
| 开工基线 | `fa9858a`（main 分支，工作树干净，TASK-246 方案 E 立项准备基线） |
| C-01 实施笔 | `761f4ed` `feat(reliability): TASK-246 后台架构韧性与存储生命周期治理（C-01 实施笔）（修改 18 个业务代码与测试文件）`（恰 18 文件，+922/−12） |
| C-02 任务两件套 | `be3853e` `docs(mailbox): TASK-246 后台架构韧性与存储生命周期治理验收与台账闭环（C-02 台账笔）（完整回填 handoff.md 改动清单、门禁结果与已知受限）`（恰 1 文件，+65/−0） |
| 任务内容与白名单 | 相对开工基线恰 19 文件 = 18 业务/测试文件 + 1 台账文件 `handoff.md`；含对既有断言适配的 `LeaderboardCacheInvocationTest.java`（旧断言与 B23 缓存机制互斥，已如实披露并满足契约判据 B）；生产 record-service、根与各子模块 pom.xml、package.json 及红线文件 100% 零触碰，零新外部依赖 |
| 核心实现 | ① B20 Outbox 历史清理与毒丸人工重放：`VerifyEventOutboxMapper` 新增 `selectExhausted`（查 EXHAUSTED 毒丸行）、`replayExhausted`（`SET status='PENDING', retry_count=0 WHERE id=#{id} AND status='EXHAUSTED'` 幂等重放）、`cleanSentEvents`（`DELETE WHERE status='SENT' AND sent_at < #{before} LIMIT #{limit}` 分批删除）；`VerifyController` 新增 `GET /api/outbox/exhausted`（limit cap 200）、`POST /api/outbox/exhausted/{id}/replay`、`DELETE /api/outbox/sent`（默认 7 天）；前端 `client.ts` 导出三个 API，`admin.page.vue` 增加「Outbox 消息补偿与死信看板」卡片支持初载、一键重放与清理；`VerifyControllerTest` 8 测覆盖；② B21 通知中心历史清理与索引补齐：`sql/01-user-db.sql` 为 `notification` 表补齐 `KEY idx_read_created (is_read, created_at)`；新增幂等迁移脚本 `sql/migrations/add-idx-notification-read-created.sql`；`NotificationMapper` 新增 `cleanExpiredReadNotifications` 与 `cleanUserReadNotifications`；`NotificationService` 增加参数校验与清理；`NotificationController` 新增 `DELETE /api/notifications/read`（X-User-Id 认定清空本人已读）与 `DELETE /api/notifications/expired`；前端 `notifications.page.vue` 增加「清空已读」危险按钮与确认弹窗；`NotificationServiceTest` 4 测全覆盖；③ B23 好友榜本地多级缓存防穿透：`LeaderboardService` 引入专用 Caffeine 缓存（15s TTL、maximumSize 2000，Key=`userId:size`）；`topFriends` 优先命中本地缓存返回，未命中拉取后非空回填（空榜不缓存防背锅）；`LeaderboardServiceTest` 与 `LeaderboardCacheInvocationTest` 验证二次调用命中缓存且 Feign/Pipeline 次数不增；④ B24 网关未捕获异常统一标准包装：`gateway-service` 新增 `GatewayExceptionHandler`（实现 `ErrorWebExceptionHandler` 与 `Ordered`，Order=-1），拦截 `ResponseStatusException`、`ConnectException`（503）、`TimeoutException`（504）、404、通用 500，统一输出 `application/json;charset=UTF-8` 的 `{"code":status,"message":msg,"data":null}` Result 契约 JSON；严格遵循网关零 common 依赖边界；`GatewayExceptionHandlerTest` 7 测全覆盖 |
| 本机门禁 | 后端 `mvn -B -ntp -o -s .mvn-settings.xml clean test -pl verify-service,user-service,leaderboard-service,gateway-service -am` BUILD SUCCESS（Reactor 7 模块全绿，user-service 200 测、leaderboard-service 92 测全部通过，rc=0）；前端 `pnpm run type-check` 0 错误（rc=0）；前端单测 4 文件 61 测全过（rc=0）；契约门 `mailbox-contract.sh --open=TASK-246 --baseline=fa9858a` 退出码 0（判据 A 两件套齐 + 判据 B 清单一致，0 在途） |
| 是否到达外部门槛 | **已到达**：随批推送 a6701b0 后经第 56 次 CI（run 38774258105）conclusion=success 全 job 绿（web ✓20s / build ✓11m41s 外部终验全过） |
| 独立复核结论与裁决 | 独立复核审查官实跑全过（总体 **PASS**，BLOCKER 0，三权分立严格履行）：19 个改动文件严丝合缝；后端、前端类型、前端单测与契约门禁独立实测稳定全绿；B20 建立了死信排查自愈通道并消除了历史消息膨胀，B21 补齐了索引并打通了历史与用户已读治理，B23 根除了高并发下好友榜对 user-service 的穿透雪崩风险，B24 终结了非标 HTML 报错保证了全站 Result 契约统一；终审裁定 **ACCEPT 予以封盘** |
| 状态 | TASK-246 本地闭环，指导侧终裁 **ACCEPT 予以封盘** |

## 验收记录：`TASK-247`（E5 生产部署姿态演练——CORS 动态多域名注入、跨域 Cookie 凭证透传与 TLS 终止协同，候选 H2，MEDIUM 档，2026-10-16）

| 项 | 内容 |
| --- | --- |
| 开工基线 | `e3ed868`（main 分支，工作树干净，TASK-247 立项准备提交） |
| C-01 实施笔 | `89ba3d1` `feat(gateway): 落实生产部署姿态演练与CORS/TLS/Cookie协同治理（TASK-247）`（恰 5 文件，+238/−0） |
| C-02 任务两件套 | `47f1ecb` `docs(mailbox): 登记TASK-247实施完成与门禁回填（TASK-247）`（恰 2 文件，+42/−13） |
| 任务内容与白名单 | 相对开工基线恰 7 文件 = 5 业务/测试文件（`gateway-service/src/main/resources/application.yml`、`docker-compose.services.yml`、`scripts/verify/env.example`、新增 `GatewayProductionPostureTest.java`、`README.md`）+ 2 台账两件套（spec.md/handoff.md），与白名单全等；record-service、verify-service、user-service、leaderboard-service 四大业务微服务、根与各子模块 pom.xml、package.json 及红线文件 100% 零触碰，零新外部依赖 |
| 核心实现 | ① R1 CORS 生产多域名动态注入：真实网关上下文（`@SpringBootTest` RANDOM_PORT）注入 `CORS_ALLOWED_ORIGINS=https://sports.example.com,https://admin.example.com`，真实 HTTP 预检 OPTIONS 断言白名单 Origin 回显 `Access-Control-Allow-Origin` + `Access-Control-Allow-Credentials: true`，恶意 Origin（`https://attacker.com`）不返回 CORS 头（CorsWebFilter 短路，离线稳定）；② R2 反向代理 TLS 终止与协议感知：`server.forward-headers-strategy: framework` 落地 + `ForwardedHeaderTransformer`（framework 等价适配器）断言 `X-Forwarded-Proto/Host/Port` → scheme https + application.yml 资源文件断言；③ R3 跨域 Cookie 凭证透传：真实全局过滤链（RequestIdGlobalFilter → AuthGlobalFilter 白名单透传分支）断言 `Cookie: refresh_token` 下游请求头无损保留；④ R4 Compose 与文档对齐：`docker-compose.services.yml` gateway environment 注入 `CORS_ALLOWED_ORIGINS: "${CORS_ALLOWED_ORIGINS:-http://127.0.0.1:5173,http://localhost:5173}"` 占位、`env.example` 补生产配置说明、`README.md` 补反向代理（Nginx 等）`X-Forwarded-Proto` 协同部署章节 |
| 本机门禁 | 后端 `scripts/verify/mvn-verify.sh --mode=offline --pl gateway-service test` BUILD SUCCESS（Tests run: 62, Failures: 0, Errors: 0, Skipped: 0，rc=0，含新增 GatewayProductionPostureTest 5 测全绿）；`docker compose -f docker-compose.yml -f docker-compose.services.yml config` rc=0；前端 `pnpm run type-check` 0 错误（rc=0）；前端单测 61 测全绿（rc=0）；契约门 `mailbox-contract.sh --open=TASK-247 --baseline=e3ed868` 退出码 0（判据 A 两件套齐 + 判据 B 清单一致） |
| 是否到达外部门槛 | **已到达**：随批推送封盘笔 `1b92d19` 后经 GitHub Actions CI run `38782194561`（web ✓31s / build ✓10m49s）conclusion=success 全 job 绿，外部终验达成（本行由补记笔落库，读数时点 2026-10-16，序数口径因与历史「第 N 次」计数规则不可追溯对齐，按 run ID 直引） |
| 独立复核结论与裁决 | 独立复核审查官全程只读实跑（总体 **PASS**，阻断项 0，结束自证 `git status --porcelain` 为空，三权分立严格履行）：A Git 状态与提交链（恰 2 笔在途、7 文件、C-01/C-02 拆分严丝合缝）+ B 红线抽查（四大业务服务、pom.xml/package.json/pnpm-lock、主看板三文件零 diff）+ C 双端门禁实测复跑（后端 62 测 / compose config 0 错 / 前端类型 0 错 / 前端 61 测 / 契约门 0 五项独立复跑全绿）+ D 证据链核对（handoff §3 门禁读数与实测一致，§4 三处受限口径如实登记，R1/R2/R3 实现与测试代码实体逐行相符，非纯 Mockito 预制）+ E 只改清单全等（声明 7 文件与实际改动集双向比对无偏差）五块全 PASS；非阻断留痕 2 项（Nacos 离线日志噪音属 fail-fast=false 预期、62 为 gateway-service 模块单测口径非全仓总数）；终审裁定 **ACCEPT 予以封盘** |
| 状态 | TASK-247 本地闭环，指导侧终裁 **ACCEPT 予以封盘** |

## 验收记录：`TASK-248`（B23 覆盖增强——好友榜缓存「空榜不缓存防背锅」显式单测断言，MEDIUM 档，2026-10-16）

| 项 | 内容 |
| --- | --- |
| 开工基线 | `770fc40`（main 分支，工作树干净，TASK-248 立项准备提交） |
| C-01 实施笔 | `1ed6f57` `test(leaderboard): 补齐好友榜空榜不缓存显式断言（TASK-248）`（恰 1 文件，+58/−0） |
| C-02 任务两件套 | `d7fa60d` `docs(mailbox): 登记TASK-248实施完成与门禁回填（TASK-248）`（恰 2 文件，+34/−8；实施侧经 `--amend` 归并为单笔台账笔，最终恰两笔在途） |
| 任务内容与白名单 | 相对开工基线恰 3 文件 = 1 测试文件（`LeaderboardCacheInvocationTest.java` 仅新增 2 个测试方法）+ 2 台账两件套（spec.md/handoff.md），与白名单全等；一切生产代码（`src/main`）、根与各子模块 pom.xml、package.json 及红线文件 100% 零触碰，零新外部依赖 |
| 核心实现 | ① R1 `emptyBoardFromDegradation_isNotCached_secondCallRefreshesOnRecovery`（核心防背锅语义）：`listFriends` 首次 `thenThrow` → 第一调断空榜；沿 `LeaderboardServiceTest` L380 先例以 `doReturn(...).when(...)` 重打桩恢复 → 第二调断非空；`verify(userApi, times(2)).listFriends` 锁定「降级空榜未进 15s Caffeine 缓存、两次均真实穿透 Feign」——若误去掉 `isEmpty` 判定，第二调命中缓存使 Feign 只调 1 次，断言即失败；Pipeline 计数按实际执行路径校正为 `times(1)`（首次降级在 `fetchAllFriendIds` 第一步抛异常未触达 Pipeline，经用户确认登记 handoff §4）；② R2 `emptyBoardFromNoFriends_isNotCached_secondCallPenetratesAgain`：无好友空页（total=0）连续两调均空、`times(2)` 同口径穿透。均复用 `CachingTestConfiguration` Spring 代理入口（缓存判别式真实入口口径），行为断言优先（未反射直取私有缓存字段），未删弱/改写任何既有断言 |
| 本机门禁 | 后端 `scripts/verify/mvn-verify.sh --mode=offline --pl leaderboard-service test` BUILD SUCCESS（`LeaderboardCacheInvocationTest` 5 测全绿（原 3+2），模块累计 Tests run: 94, Failures: 0, Errors: 0, Skipped: 0，rc=0，测试数只增不减；离线分级收窄合规——仅触碰 leaderboard-service 测试，其余模块凭白名单外零 diff 论证不变）；前端 `pnpm run type-check` 0 错误（rc=0）；前端单测 4 文件 61 测全过（rc=0）；契约门 `mailbox-contract.sh --open=TASK-248 --baseline=770fc40` 退出码 0（判据 A 两件套齐 + 判据 B 清单一致）；token 门 29 项 tracked 全量 `git grep -cF` 基线 `770fc40`=2011 与收口态持平、只增不减、改动集零 token 字面量；词面门 CI 权威口径显式 `-E` 真实扫描零命中 + 阳性探针 rc=0 + 阴性探针 rc=1；`git diff --check` 净 |
| 指导亲核与裁决 | MEDIUM 档（纯测试新增、零核心逻辑改动）免独立复核子agent（分级政策），CI 外部终验即收口；指导侧亲核三块：紧凑 Git 摘要（工作树净、恰 2 笔在途 `1ed6f57`/`d7fa60d`、`git diff --name-only` 恰 3 文件与白名单全等、rev-list 770fc40..HEAD=2）+ 高风险定向抽查（R1 `doReturn` 重打桩、`times(2)`/`times(1)` 断言语义与 spec/handoff 声明逐行相符、`doReturn` import 完整性）+ 执行侧回报 vs 实际一致性裁决（各门禁读数无矛盾）；终审裁定 **ACCEPT 予以封盘** |
| 是否到达外部门槛 | **已到达**：随封盘批次推送后经 GitHub Actions CI run `38790795113`（HEAD `8b5f01f`，`web` ✓27s / `build` ✓12m00s）conclusion=success 全 job 绿，外部终验达成（本行由补记笔落库，读数时点 2026-10-16） |
| 状态 | TASK-248 本地闭环，指导侧终裁 **ACCEPT 予以封盘** |

## 验收记录：`TASK-249`（D 系推荐序 ①②③ 合并——D1 总榜缓存失效联动 + D3 结算 scan 单遍重构 + D2 appeal 索引 + D6 mapmatch 查询防护，FULL 档，2026-10-16）

| 项 | 内容 |
| --- | --- |
| 开工基线 | `5212bc3`（main 分支，工作树干净，TASK-249 立项笔） |
| C-01 实施笔 | `f9405d8` `fix(leaderboard,mapmatch,sql): 总榜缓存失效联动与结算scan单遍重构及查询防护（TASK-249）`（恰 11 文件，+439/−45，含新增 `sql/migrations/add-idx-appeal-status-id.sql`） |
| C-02 任务两件套 | `91c10c2` `docs(mailbox): 登记TASK-249实施完成与门禁回填（TASK-249）`（恰 2 文件，占位符回填 `5212bc3` + handoff 五节回填） |
| 任务内容与白名单 | 相对开工基线恰 13 文件 = 11 业务/测试/SQL + 2 台账两件套，与白名单全等；四大业务服务 Java（record/user/verify/gateway）、根与各子模块 pom.xml、package.json、`CacheConfig.java`、既有迁移脚本、`PLAN.md`/`HANDOVER.md` 100% 零触碰，零新外部依赖 |
| 核心实现 | ① R1（D1 总榜缓存失效联动）：`LeaderboardService` 注入 `CacheManager` + `OVERALL_CACHE_NAME` 常量 + best-effort `evictOverallCache()`（异常仅 `log.warn` 不抛出），三调用点恰各一次（`applyVerified` credit=true 后 / `rollbackOnRejected` rows==1 后 / `settleAndReconcile` 纠偏完成 `markSettled` 前），幂等路径不调用；**刻意不引入 `@CacheEvict`/`@Cacheable` 代理注解**（ADR-0009 哨兵 `LeaderboardWritePathStaysUnproxiedTest` 断言写路径 bean 零代理），显式经管理器清理 L1+L2；恰 4 处 `new LeaderboardService(...)` 构造点补 `mock(CacheManager.class)`；新增 6 用例锁定 clear 触发/幂等不触发/异常不阻断；② R2（D3 结算 scan 单遍重构）：清理段重写为单次 `Cursor` try-with-resources 单遍迭代 + null 元组安全跳过 + `≥500` 批式 flush + 收尾 flush，**删除 `scanPage()`**（全仓零代码残留），stale 判据/批 500/`pruned` 计数/锁边界语义保持——修复旧实现「成员数 ≥ 批次时终止判据恒真 → 结算线程不可终止」潜伏 P1；新增 4 用例（scan `times(1)` / 600 成员单遍终止且 500+100 两批全量 ZREM / target 全含零 ZREM / scan null 其余步骤照常）；③ R3（D2 appeal 索引）：`03-verify-db.sql` appeal 建表补 `KEY idx_status_id (status, id)`（对齐 outbox 表先例）+ 幂等迁移脚本（information_schema PREPARE 同款），零 Java 改动；④ R4（D6 mapmatch 查询防护）：`MatchProperties` 新增 `maxQuerySpanMeters=2000`/`maxCandidateEdges=2000`，`fetchCandidates` 自适应中点二分递归（对角线米距沿 `DEG_FACTOR`，子块 bbox ⊇ 原块 → 并集超集等价，逐点判定逐位不变），候选超限仅 warn **严禁 LIMIT 截断**（丢真近邻边 → 假离路 → R5 误判）；既有参数默认值零改动 |
| 本机门禁 | 后端全模块 `mvn-verify.sh --mode=offline test` rc=0 BUILD SUCCESS，surefire 七模块 52/62/200/298/175/104/29 全 0F/0E/0S（leaderboard 94→104 +10、mapmatch 26→29 +3，@Test 计数交叉验证 126→139 只增不减）；前端 type-check rc=0 + 61 测全过；契约门 `--open=TASK-249 --baseline=5212bc3` rc=0；token 门 29 项 `git grep -cF` 基线 2011 → HEAD 2014（+3 源于新测试数据/台账合法文本，28 项持平仅 18.0 项 +3，只增不减）；词面门显式 `-E` 零命中 + 探针阳性 rc=0；`git diff --check` 两笔均净 |
| 指导亲核与裁决 | FULL 档全流程（派发 + 白名单 + 全量逐位门禁 + 指导亲核 + 独立复核）；指导侧亲核三块：紧凑 Git 摘要（工作树净、恰 2 笔、13 文件与白名单逐项全等）+ 高风险定向抽查 4 处（D1 evict 三调用点与 best-effort、D3 Cursor 单遍与 null 安全、R3 幂等 PREPARE 先例全等、D6 中点二分超集论证与仅告警不截断）+ 一致性裁决（回传 vs 实测无矛盾）；独立复核审查官五块全 PASS（Git 状态与提交链/红线抽查①-⑥/双端门禁实测复跑/证据链核对含 D1/D3/R3/D6 判别式逐行比对/只改清单程序化双向全等 claimed=13 actual=13），零阻断项，非阻断登记 N1-N4（词面门探针载体等价、token 读数时点差 2013→2014 台账自指、V21/22/23 历史口径落定、token 清单历史提取复算）；受限口径三项如实登记（多实例 L1 不广播沿单实例口径、D2 静态推论 EXPLAIN 未实测、D6 仅 mock 层 warn 验证）；终审裁定 **ACCEPT 予以封盘** |
| 是否到达外部门槛 | **已到达**：随封盘批次推送后经 GitHub Actions CI run `38839626456`（HEAD `5eaa117`，`web` ✓27s / `build` ✓11m05s）conclusion=success 全 job 绿，外部终验达成（本行由补记笔落库，读数时点 2026-10-16） |
| 状态 | TASK-249 本地闭环，指导侧终裁 **ACCEPT 予以封盘** |

## 验收记录：`TASK-250`（D 系卫生与纵深批——D4 改密吊销 KEYS→SCAN + D5 WS STOMP Origin 纵深 + D7 点赞对账分页化 + D8 R5 连续降级告警，FULL 档，2026-10-16）

| 项 | 内容 |
| --- | --- |
| 开工基线 | `3b56032`（main 分支，工作树干净，TASK-250 立项笔） |
| C-01 实施笔 | `32c1d8f` `fix(user,record,verify): 改密吊销SCAN化与WS-Origin纵深及点赞对账分页与R5降级告警（TASK-250）`（恰 12 文件，+506/−105） |
| C-02 任务两件套 | `f1dd3e4` `docs(mailbox): 登记TASK-250实施完成与门禁回填（TASK-250）`（恰 2 文件，占位符回填 `3b56032` + handoff 五节回填） |
| 任务内容与白名单 | 相对开工基线恰 14 文件 = 12 业务/测试/基建 + 2 台账两件套，与白名单 15 项减去未触碰条件项 `RecordLikeReconcilePipelineIT.java` 双向全等；**授权偏差**：D7 分页化使 `RecordLikeServiceTraceIdTest` 两条对账桩失效，经用户显式授权追加为白名单第 15 项（C-02 同步 spec 登记，改动恰两处桩最小适配、断言零删弱）；gateway/leaderboard/mapmatch Java、根与子模块 pom.xml、package.json、`StompConnectAuthInterceptor`、`sql/`、application.yml、`scripts/verify/*`、`PLAN.md`/`HANDOVER.md` 100% 零触碰，零新外部依赖 |
| 核心实现 | ① R1（D4 改密吊销 SCAN 化）：`AuthService.changePassword` 吊销段 `keys()` → `scan(ScanOptions.match(refreshUserPrefix(userId)+"*").count(SCAN_BATCH))` try-with-resources Cursor 游标迭代，`SCAN_BATCH=500L`，安全语义逐字保持（空集 no-op / 异常 `log.error` + `BizException(SYSTEM_ERROR)` / 安全缺口注释原样）；② R2（D5 WS Origin 纵深）：构造器 `@Value("${user.ws.allowed-origins:…}") List<String>` 注入 + `setAllowedOrigins(...)`，与 `StompConnectAuthInterceptor` CONNECT 帧鉴权构成双层防线；compose 注入 `USER_WS_ALLOWEDORIGINS` 复用 `CORS_ALLOWED_ORIGINS` 单一事实源（沿 TASK-247 H2 先例）；③ R3（D7 点赞对账分页化）：`RecordLikeMapper` 新增 `selectDistinctRecordIdsAfter` + `selectPairsByRecordIds` 键集分页（PK 前缀索引覆盖），`RECONCILE_RECORD_BATCH_SIZE=500` 循环（cursor 取本批最大 record_id 严格递增、空批/短批双终止），抽 `flushReconcileChunk`，per-record 纠偏三元组与 pipeline 分批提交不变，`tryLock(3,-1)` watchdog 续期适配长运行，`selectRecordLikePairs()` 保留供 IT 直读；④ R4（D8 R5 降级告警）：`degradationStreak` AtomicInteger + `DEGRADATION_ALERT_THRESHOLD=5`，`recordDegradation(...)` 逐次 warn 附 streak，`==` 恰一次升级 `log.error` 固定语「R5 连续降级告警」防刷屏，成功路径 `getAndSet(0)` + 跨阈值恢复 warn，降级返回 null 不抛语义不变 |
| 本机门禁 | 后端全模块 `mvn-verify.sh --mode=offline test` rc=0 BUILD SUCCESS，surefire 七模块 52/62/202/300/182/104/29 全 0F/0E/0S（user 200→202 +2、record 298→300 +2、verify 175→182 +7，只增不减）；compose config rc=0（`USER_WS_ALLOWEDORIGINS` 注入核验）；前端 type-check rc=0 + 4 文件 61 测全过；契约门 `--open=TASK-250 --baseline=3b56032` rc=0；token 门 29 项 `git grep -cF` 基线 2015 → 收口 2015 持平只增不减；词面门显式 `-E` 零命中 + 探针阳性 rc=0；`git diff --check` C-01 净、C-02 两件套行尾两空格为仓内 markdown 硬换行惯例非新增违规 |
| 指导亲核与裁决 | FULL 档全流程（派发 + 白名单 + 全量逐位门禁 + 指导亲核 + 独立复核）；指导侧亲核三块：紧凑 Git 摘要（工作树净、恰 4 笔、C-01 恰 12 文件）+ 高风险定向抽查（D4 安全语义逐字保持、D7 分页终止判据与锁边界、D5 双防线落地、D8 `==` 恰一次告警）+ 一致性裁决（回传 vs 实测无矛盾、授权偏差链路自洽）；独立复核审查官五块全 PASS（Git 状态与提交链/红线与高风险抽查/双端门禁实测复跑/证据链核对/只改清单程序化双向全等 14=15−1），零阻断项，非阻断登记 N1-N5（四项受限确认、Mapper L64/L73 注释滞后非功能、C-02 行尾空格基线核对确认、台账措辞精度「扩展测试类」vs「新增」、token 读数 2015/2015 时点说明）；终审裁定 **ACCEPT 予以封盘** |
| 是否到达外部门槛 | **已到达**：随封盘批次推送后经 GitHub Actions CI run `38849720261`（HEAD `b0a6cbb`，`web` ✓29s / `build` ✓12m12s）conclusion=success 全 job 绿，外部终验达成（本行由补记笔落库，读数时点 2026-10-16） |
| 状态 | TASK-250 完整闭环（外部终验达成），指导侧终裁 **ACCEPT 予以封盘** |

## 验收记录：`TASK-251`（E 系批1——认证/鉴权面三处破口治理 E1+E2+E3，FULL 档，2026-10-16）

| 项 | 内容 |
| --- | --- |
| 开工基线 | `0af833a`（main 分支，工作树干净，TASK-252 立项笔；串行批1） |
| C-01 实施笔 | `124c93e` `fix(gateway,user): 网关剥离internal凭证与拒绝internal路径及改密迁出白名单与通知清理收敛ADMIN（TASK-251）`（恰 9 文件，+339/−18） |
| C-02 任务两件套 | `fd14459` `docs(mailbox): 登记TASK-251实施完成与门禁回填（TASK-251）`（恰 2 文件，占位符回填 `0af833a` + handoff 五节回填） |
| 任务内容与白名单 | 相对开工基线恰 11 文件 = 9 业务/测试/基建 + 2 台账两件套，与白名单 14 项减去 3 个未触碰条件项（`AuthControllerTest.java` / `web/src/api/client.ts` / `.env.example`（仓内不存在））双向全等；record/verify/leaderboard/mapmatch Java 与测试、common 全 Java、user application.yml、pom.xml、package.json、`scripts/verify/*`、`sql/`、PLAN/HANDOVER 100% 零触碰，零新外部依赖；本地 `.env` 补 INTERNAL_API_TOKEN 一行（未跟踪不入库）；**token 门 3 项各 −1 为课题目标性口径替换（`/api/auth/**` 整段白名单模式、`/actuator/**` 叙述行、README 旧 curl 演示串），经用户显式追认**（SUM 1405→1488 只增、无清零） |
| 核心实现 | ① R1（E3 通知清理收敛 ADMIN）：网关 admin.paths 追加精确串 `/user/api/notifications/expired` + 服务层 `requireAdmin`（X-Role ≠ ADMIN 或头缺失一律 FORBIDDEN 失败关闭、服务零触达）双层防线；② R2（E2 改密迁出白名单）：whitelist 由 `/api/auth/**` 收窄为 register/login/refresh/logout 四端点精确清单（logout 公开理由：`logout(refresh)` 不读 X-User-Id），change-password 走鉴权分支由网关注入 X-User-Id，`resolveUserId` 零改动即通；前端 client.ts 零改动核实成立；③ R3（E1 internal 凭证剥离+路径拒绝+密钥必填）：入口清理段追加 `headers.remove(HEADER_INTERNAL_TOKEN)` 覆盖全分支（gateway 为 common 零依赖叶子模块，采用包内等值常量 + Javadoc 同步义务注释，偏差⑤追认），`hasInternalPathSegment` 逐段谓词先于白名单/鉴权分支 404 拒绝（不泄露存在性），compose 四消费服务 `${INTERNAL_API_TOKEN:?...}` 必填插值（实测缺键 rc=1 失败关闭），ci.yml Provision 步骤补占位行，README/ADR-0007 补记式订正 |
| 本机门禁 | 后端全模块 `mvn-verify.sh --mode=offline test` rc=0 BUILD SUCCESS 9/9，surefire 52/71/205/300/182/104/29（gateway 62→71 +9、user 202→205 +3，全仓 986 只增不减）；compose config rc=0 + `--env-file` 缺键实证 rc=1 必填门；前端 type-check rc=0 + 4 文件 61 测全过；**契约门收口态 rc=1 属工具对非 ASCII 路径 `docs/adr/0007-鉴权设计.md` 的提取表达缺口**（判据 A 通过、未声明项恰 1 条，等价判据 `git -c core.quotePath=false diff --name-only 0af833a` 恰 11 条与清单逐行全等，指导侧与复核官双验）；token 门 29 项 `git grep -cF` 基线 1405 → 收口 1488 SUM 只增（3 减项为目标性替换经用户追认，handoff §3.5 逐项登记）；词面门 CI 权威口径零命中 + 探针阳性 rc=0（指导侧亲跑）；`git diff --check` 净、9 改动文件保基线行尾形态无混合 |
| 指导亲核与裁决 | FULL 档全流程；指导亲核三块 PASS（Git 摘要恰 6 笔 / 高风险抽查：R3 双 remove+先拒绝后白名单+逐段谓词、契约门 CJK 缺口亲验 rc=1 与等价判据 11 条亲跑全等、词面门 CI 口径亲跑零命中解 §6/§3.6 矛盾表述存疑 / 一致性裁决）；token 门 3 项 −1 与「只增不减」硬约束冲突系立项门禁条款设计疏漏，报请用户追认后按授权口径复核；独立复核五块全 PASS（Git 链 / 红线与高风险含二分表亲验、断言零删弱 / 双端门禁全亲跑含 compose 必填门 / 证据链 / 只改清单程序化全等 11=14−3），零阻断项，非阻断 N1-N4（§0 quotePath 陈旧叙述、compose 显式 5 处 vs 叙述四服务、token 29 项清单无追踪定义、token 台账记账粒度 1-2 计数出入——均登记不阻断）；终审裁定 **ACCEPT 予以封盘** |
| 是否到达外部门槛 | **已到达**：随封盘批次推送后经 GitHub Actions CI run `38864817657`（HEAD `30fc66a`，`web` ✓34s / `build` ✓11m37s）conclusion=success 全 job 绿，外部终验达成（本行由补记笔落库，读数时点 2026-10-16；运行 annotations 双 job 再证五 v4 actions 强制跑 Node 24、setup-java v4 停更、ubuntu-latest 2026-10-19 迁移 Ubuntu 26——与 TASK-252 E4 治理面互证） |
| 状态 | TASK-251 完整闭环（外部终验达成），指导侧终裁 **ACCEPT 予以封盘**（N1 §0 陈旧叙述已随封盘笔订正） |

## 验收记录：`TASK-252`（E 系批2——CI runner 迁移预备 E4+E31，MEDIUM 档，2026-10-16）

| 项 | 内容 |
| --- | --- |
| 开工基线 | `b4bdef8`（main 分支 TASK-251 补记笔，工作树干净；串行批2，开工前提 = TASK-251 完整闭环含 CI run `38864817657` 全绿） |
| C-01 实施笔 | `66d0ec3` `chore(ci): 钉扎runner与升级actions及补dispatch试跑与并发超时和trivyignore治理（TASK-252）`（恰 2 文件 +77/−10：ci.yml + .trivyignore） |
| C-02 任务两件套 | `49b02c7` `docs(mailbox): 登记TASK-252实施完成与门禁回填（TASK-252）`（恰 2 文件，占位符回填 `b4bdef8` + handoff 五节回填；收口最终 HEAD） |
| 任务内容与白名单 | 相对开工基线恰 4 文件与白名单双向全等（纯 ASCII 路径，无 TASK-251 的 CJK 契约提取缺口）；全部 Java/测试/web 前端/compose/sql/scripts/README/docs/PLAN/HANDOVER 100% 零触碰，零新工具零新 action |
| 核心实现 | ① R1 钉扎 runner：build/web 两 job `runs-on` → `ubuntu-24.04` + 止血注释；② R2 六 uses 升级零 v4 残留：checkout@v5×2 / setup-java@v5 / cache@v5 / upload-artifact@v6 / setup-node@v5，逐 action changelog 破坏性变更核对结论表登记 handoff §4（全部目标 major 稳定发布，无保持现状项）；③ R3 顶层 `workflow_dispatch`（probe26 boolean default false）+ 新 job `probe-ubuntu26`（runs-on ubuntu-26.04、if 双门禁、步骤同 build 款 Docker 链 + Trivy 门禁跑次，注释注明 10-19 标签可用前禁触发）；④ R4 顶层 concurrency（group `${{ github.workflow }}-${{ github.ref }}` + cancel-in-progress: true）+ timeout-minutes build 45 / web 15 / probe 45；⑤ R5 ci.yml「历史存量 13 项」注释校准为 2 项（CVE-2026-47892/47884，注明订正来源 E31/TASK-252）+ .trivyignore 两条 CVE 各补「复核期限 2027-04」纯注释 |
| 本机门禁 | 后端全模块 offline test rc=0（52/71/205/300/182/104/29 逐位持平 TASK-251 收口值）；compose config -q rc=0；前端 type-check + 4 文件 61 测全绿；契约门 `--open=TASK-252 --baseline=b4bdef8` rc=0（判据 A+B 双过）；token 门 29 项 SUM 2015→2015 持平 TOKEN_VIOLATIONS=0；词面门 CI 权威口径零命中 + 探针双阳性；`git diff --check` 净，4 文件保持 LF；ci.yml 经 pyyaml 机器级解析通过（三 job/八 uses 零 v4 残留） |
| 指导亲核与裁决 | MEDIUM 档免独立复核；指导亲核三块 PASS（Git 摘要恰 3 笔领先 / 高风险抽查：六 uses 零 v4 残留、词面门正则逐字未动、Provision INTERNAL_API_TOKEN 占位行双处在位、typed-router 门禁步骤完好——C-01 diff 中该行删除系 probe job 插入移位重排非误删 / 一致性裁决：四项偏差登记自洽含 spec §4.1 基线旧值订正）；**已授权口径（沿用户追认）**：MEDIUM 档流程 = 指导亲核 → 推送 → CI 外部绿即收口 |
| 是否到达外部门槛 | **已到达**：随封盘批次推送后经 GitHub Actions CI run `38869656012`（HEAD `b599c23`，`web` ✓27s / `build` ✓10m27s）conclusion=success 全 job 绿，外部终验达成（本行由补记笔落库，读数时点 2026-10-16；**probe-ubuntu26 job skipped = R3 workflow_dispatch if 门禁正确生效旁证**——非 dispatch 触发的常规 push 不跑 probe；本课题改动面即 ci.yml，CI 实跑为最直接验证面，升级后五 v4 actions 在 runner 实跑通过） |
| 状态 | TASK-252 完整闭环（外部终验达成），指导侧终裁 **ACCEPT 予以封盘**（受限：ubuntu-26.04 真实试跑待 10-19 标签可用后手动 dispatch probe26=true 验证，读数随补记笔登记） |

## 验收记录：`TASK-253`（E 系批3——数据一致性批 E5+E6+E7+E9，FULL 档，2026-10-16）

| 项 | 内容 |
| --- | --- |
| 开工基线 | `c020587`（main 分支 TASK-253 立项笔，工作树干净；串行批3，开工前提 = TASK-252 完整闭环含 CI run `38869656012` 全绿） |
| C-01 实施笔 | `1c35a27` `fix(record,verify,leaderboard): 调度池并行化与申诉终判兜底对账及结算ZREM复核和归档列迁移（TASK-253）`（恰 8 文件 +657/−8） |
| C-02 任务两件套 | `22a0802` `docs(mailbox): 登记TASK-253实施完成与门禁回填（TASK-253）`（恰 2 文件，占位符回填 `c020587` + handoff 五节回填；收口最终 HEAD） |
| 任务内容与白名单 | 相对开工基线恰 10 文件与白名单双向全等（路径全 ASCII，无 TASK-251 CJK 契约提取缺口）；条件项：spec 书 `application.yml`、仓内实为 `application.properties`（文件头注释自证 snakeyaml 1.x/2.x 冲突禁改 yml），属性键 `spring.task.scheduling.pool.size` 语义逐字等价；零触碰面 `VerifyService`/`VerifyOutboxRelay`/`VerifyEventProducer`/`sql/05-track-point-archive-shards.sql`/`scripts/db/migrate.sh`/api 模块/任一 pom/scripts/verify 100% 未动 |
| 核心实现 | ① R1（E5）：record-service `application.properties` 新增 `spring.task.scheduling.pool.size=4` + 四任务对应关系注释（flush L419/reconcile L515/degrade L97/archive L81），零 Java 改动、不引入 @EnableAsync/SchedulingConfigurer；② R2（E6）：新建 `AppealReconcileService`（@Scheduled fixedDelay 10min + initialDelay 2min；Redisson tryLock `verify:appeal:reconcile` 锁未获取/锁服务异常双跳过分支；`AppealMapper.selectFinalizedBatch(100)` 走既有 idx_status_id；逐行 getRecord 恰 APPEALING 才 statusCallback（from=APPEALING + record.version 乐观锁）幂等闭环；单行 Feign 异常/非 0 响应 catch+log 续批），reviewAppeal 同步回调失败仍抛异常行为不变；③ R3（E7）：`LeaderboardContributionMapper.selectActiveUserIds`（SELECT DISTINCT user_id WHERE status=ACTIVE AND IN `<script>`/foreach 沿 RecordLikeMapper 先例）+ `flushStale` ZREM 前批候选二次复核仍缺才删、复核命中保留、复核异常保守跳过本批（宁漏删不误删，staleBatch.clear 防重收尾），L444 已知权衡注释更新（竞态窗口秒级→毫秒级，残余下轮结算收敛）；④ R4（E9）：新建 `sql/migrations/add-track-archive-column-and-idx.sql`（段 1 archived 列 + idx_archive 与 05 脚本 L239-240 逐字一致；段 2 track_point_archive_0..15 逐分片 idx_record_seq 16 段幂等，只加不删、既有 idx_record 冗余保留头注释登记；information_schema + PREPARE/EXECUTE 沿 add-record-visibility 先例），05 脚本与 migrate.sh 零触碰 |
| 本机门禁 | 后端全模块 offline test rc=0（surefire common 52 / gateway 71 / user 205 / record 300 / verify 188 / leaderboard 108 / mapmatch 29——verify 182→188 +6、leaderboard 104→108 +4 对照 TASK-252 收口态只增不减）；compose config -q rc=0；前端 type-check 0 错 + 4 文件 61 测全绿；契约门 `--open=TASK-253 --baseline=c020587` rc=0（判据 A+B 双过）；token 门 29 项两时点 28 持平 + 恰 1 项 +1（`18.0` 168→169，R3 新单测 `new BigDecimal("18.00")` 行子串命中）无一减少、3 新文件零命中；词面门 CI 权威口径零命中（ZERO_HIT rc=1 通过态）+ 探针阳性对照；`git diff --check` 净 + 双笔 `git show --check` 净，3 新文件纯 LF + 完整末尾换行 |
| 指导亲核与独立复核 | FULL 档双环节全 PASS：指导亲核三块（①Git 摘要：porcelain 空、提交链正确、本地领先恰 4 笔、diff 10 项与白名单全等；②高风险定向抽查：R2 双跳过分支/恰 APPEALING 判定/version 乐观锁实参、R3 保守跳过 + clear 防重、R4 列/索引定义与 05 脚本逐字比对 + 16 分片计数、R1 配置段；③回报一致性裁决：哈希/文件数/行数/门禁读数与实测全等，yml→properties 条件项授权链完整，零矛盾）；独立复核审查官五块全 PASS（Git 状态与提交链 / 红线抽查零命中含索引判别式边界与测试零删弱 / 双端门禁全量复跑逐格全等 / 证据链 1:1 对齐含 C-02 自指口径 / 只改清单双向全等 + 未跟踪 0），零阻断项，N1-N4 非阻断登记（N1 单测 6 用例较 spec 五类多 RE_CONFIRMED 映射属增强、N2 spec 行文 `_00..15` vs 仓内实际表名 `_0..15` 无零前缀——后续任务书统一实际形态、N3 本机无 Nacos/MySQL 依赖服务环境噪声不影响 surefire 计数、N4 yml→properties 条件项授权链独立坐实），只读自证 `git status --porcelain` 空 |
| 是否到达外部门槛 | **已到达**：随封盘批次推送后经 GitHub Actions CI run `38879776160`（HEAD `d791b7a`，`web` ✓27s / `build` ✓11m15s）conclusion=success 全 job 绿，外部终验达成（本行由补记笔落库，读数时点 2026-10-16；**probe-ubuntu26 job skipped = workflow_dispatch if 门禁正确生效旁证**——非 dispatch 触发的常规 push 不跑 probe） |
| 状态 | TASK-253 完整闭环（外部终验达成），指导侧终裁 **ACCEPT 予以封盘**（实施回报 / 指导亲核 / 独立复核三方闭环一致） |

## 验收记录：`TASK-254`（E 系批4a-1——网关与高危簇 E10+E11+E12+E16+两顺手项，FULL 档，2026-10-16）

| 项 | 内容 |
| --- | --- |
| 开工基线 | `9ce65b7`（main 分支 TASK-254 立项笔，工作树干净；本地领先含 TASK-253 补记笔 `8c8e631`，随本封盘批次统一推送） |
| C-01 实施笔 | `342bd9a` `fix(gateway,verify,record): 网关HTTP客户端超时与Sentinel空集护栏及规则快照校验与引擎隔离和likes批量上限与顺手项订正（TASK-254）`（恰 13 文件 +334/−14，1 新增 12 改） |
| C-02 任务两件套 | `fb34665` `docs(mailbox): 登记TASK-254实施完成与门禁回填（TASK-254）`（spec/handoff 回填：头部占位符回填 `9ce65b7` + handoff §1-§7 含 §6 契约门阻断说明；收口最终 HEAD） |
| 任务内容与白名单 | 相对开工基线恰 15 文件与白名单双向全等（工具判据 + 等价判据双通道：契约门判据 B 因 `docs/项目速览手册.md` CJK 路径提取缺口 rc=1 恰 1 条未声明——TASK-251 N1 同型，`git -c core.quotePath=false diff --name-only 9ce65b7` 恰 15 条逐行全等）；零触碰面 LikeController/VerifyProperties/RuleVersionCreateRequest/RuleVersionController/AuthGlobalFilter/api/common/全部 pom/web/scripts/verify/scripts/db/sql/.github/三看板 100% 未动 |
| 核心实现 | ① R1（E10）：gateway yml `spring.cloud.gateway.httpclient` 段 connect-timeout 3000 / response-timeout 10s + `GatewayProbeTest.httpclientTimeoutsConfigured` 资源断言；② R2（E11 前半）：`RuleVersionService.createVersion` 入口 `validateSnapshotRules` 程序化校验（vDrift>0 + legacy r1-r4 与 bySportType 同套 + R5 全套，违规抛 BizException(RULE_VERSION_CONFLICT) 含字段路径与实际值，rules=null 行为不变），VerifyProperties/DTO/Controller 零改动；③ R3（E11 后半）：`VerifyEngine` 规则链 per-rule try/catch（log.error 含规则类名+摘要+堆栈后跳过，聚合不受影响，fail-open 权衡由 R2 前置校验+告警补偿）；④ R4（E12）：`getLikesBatch` 入口去重前 size>50 拒绝式 BizException(SPORT_TYPE_INVALID)（record 域既有码，common 零触碰不可加码），50 推定值未改；⑤ R5（E16）：`toGatewayFlowRules` 空列表 → log.warn + return null（沿解析失败「保持上一版」语义，可见性 private→包私有 C-02 登记）+ 新建 `SentinelGatewayRuleConfigTest` 三用例；⑥ R6 顺手项：RecordLikeMapper L64/L73 Javadoc 订正键集分页游标口径 + healthcheck.sh internal 两行改公开只读路由 + 速览手册 L77/L197/L198 同款订正 |
| 本机门禁 | 后端全模块 offline test rc=0（surefire common 52 / gateway 75 / user 205 / record 302 / verify 195 / leaderboard 108 / mapmatch 29——gateway 71→75 +4、record 300→302 +2、verify 188→195 +7 只增不减）；compose config -q rc=0；前端 type-check 0 错 + 4 文件 61 测全绿；契约门 `--open=TASK-254 --baseline=9ce65b7` 判据 A 过 / 判据 B rc=1 恰 1 条 CJK 路径（工具缺口，等价判据 15 条全等）；token 门 29 项逐位持平无一减少（SUM 口径差异登记 N2：实施侧行命中 1989 / 复核侧文件出现次数 2017，红线一致）；词面门 ZERO_HIT + 探针阳性对照；`git diff --check` 净 + 双笔 `git show --check` 净，新文件纯 LF + 完整末尾换行 |
| 指导亲核与独立复核 | FULL 档双环节全 PASS：指导亲核三块（①Git 摘要：porcelain 空、提交链正确、本地领先恰 4 笔、C-01 13 文件 +334/−14 对上白名单、累积 diff 15 条；②高风险定向抽查 2 处：VerifyEngine per-rule try/catch 恰围 rule.evaluate + Sentinel 空集护栏 warn+null 与解析失败同语义；③回报一致性裁决：测试增量算术吻合、契约门 rc=1 归因与 TASK-251 N1 同型、两偏差授权链齐备，零矛盾）；独立复核子agent五块全 PASS（Git 状态与提交链 / 红线抽查零触碰面全零 diff 含行尾 / 双端门禁 7 门全量复跑逐位一致 / 证据链五处定向抽查与 D1-D3 授权链核对 / 只改清单 15 项双向全等双通道），零阻断项，N1-N3 非阻断登记（N1 任务书两处零触碰面路径描述性笔误（filter/AuthGlobalFilter→auth/、model/VerifyProperties→config/）实存均零 diff、N2 token SUM 计数口径差异建议后续统一、N3 契约门 CJK 工具缺口确认沿 TASK-251 N1 处置），只读自证 `git status --porcelain` 空 |
| 是否到达外部门槛 | **已到达**（补记笔 2026-10-16 落库）：封盘批次（5 笔：TASK-253 补记笔 `8c8e631` + TASK-254 立项笔 `9ce65b7` + C-01 `342bd9a` + C-02 `fb34665` + 封盘笔 `09a86e6`）推送后 GitHub Actions CI run `38892885813` 权威核验 conclusion=**success** 全 job 绿（`build` ✓11m19s / `web` ✓29s / `probe-ubuntu26` skipped=if 门禁正确生效旁证），外部终验达成 |
| 状态 | TASK-254 完整闭环（外部终验达成），指导侧终裁 **ACCEPT 予以封盘**（实施回报 / 指导亲核 / 独立复核三方闭环一致）；补记笔仅本地保留，随下一批次统一推送 |

## 验收记录：`TASK-255`（E 系批4a-2——配置性能余项 E17+E21+E23+E24+E29，FULL 档，2026-10-16）

| 项 | 内容 |
| --- | --- |
| 开工基线 | `f39e693`（main 分支 TASK-255 立项笔，工作树干净；本地领先含 TASK-254 补记笔 `8612d2b`，随本封盘批次统一推送） |
| C-01 实施笔 | `ce4aa4a` `fix(common,mapmatch,record,leaderboard): E系批4a-2配置性能余项五项治理（TASK-255）`（恰 14 文件 +426/−86——N1 封盘笔订正：复核实测 `git show --numstat` 求和，实施侧原誊录 +452/−112 系口径偏差；1 新增 migrations 脚本 13 改） |
| C-02 任务两件套 | `1ac8c60` `docs(mailbox): 登记TASK-255实施完成与门禁回填（TASK-255）`（spec 基线占位符回填 `f39e693` + 状态更新 + handoff §1-§7 回填；收口最终 HEAD） |
| 任务内容与白名单 | 相对开工基线恰 16 文件与白名单双向全等（契约门收口态 rc=1 属 C-02 台账笔固有语义——判据 B 重审将台账文件报「改动集未声明」，等价判据 `git diff --name-only f39e693` 恰 16 条（14 业务 + 2 台账）逐行全等，其余课题零串扰）；零触碰面 SportRecordService/GoalService/八个局部 handler controller/rollbackOnRejected 与 updateStatus 既有调用/MatchProperties/road_schema.sql/migrate.sh/api/pom/web/scripts/verify/scripts/db/.github/三看板 100% 未动 |
| 核心实现 | ① R1（E17-①）：mapmatch yml URL `&connectTimeout=3&socketTimeout=5`（PG JDBC 秒单位）+ hikari `connection-timeout: 5000`（ms）+ `spring.jdbc.template.query-timeout: 2`（s）；② R2（E17-②）：import 脚本影子表 `road_edge_staging (LIKE INCLUDING ALL)` + TRUNCATE staging（主表零锁）+ OsmImporter 表名 arg2 参数化（缺省 road_edge）+ 单事务三 RENAME 原子切换；③ R3（E17-③）：thin 内 `Math.max(2, max)` 下界（max=0/1 不再 NaN fail-open/除零）；④ R4（E21）：common 全局 handleBizException 加 HttpServletResponse 按 ResultCode 枚举映射 1001→401/1002→403/3001→404、其余维持 200 + 415/406/缺头三类框架 Handler（八个 controller 局部 handler 保留不动）；⑤ R5（E23）：MySQL 函数索引 `(user_id, (COALESCE(start_time, created_at)))` migrations 幂等脚本（沿 add-idx-record-status.sql 同构）+ 02 基线 KEY 行双路径，服务层与查询 SQL 零改动（COALESCE 表达式原样，sargable 前提成立），删 selectDailyDistanceSince 失用方法 + RecordAggDTO Javadoc 订正；⑥ R6（E24）：新增 reactivate（SET status + settled_at=NULL）替换 applyVerified 重激活分支，改判里程归位当周/当月窗口，rollbackOnRejected/updateStatus 保留；⑦ R7（E29）：LinkedHashSet 去重保序 + 累计判据告警 + WKT 每边一次预解析，黄金值行为逐位一致 |
| 本机门禁 | 后端全模块 offline test rc=0（surefire common 59 / gateway 75 / user 205 / record 302 / verify 195 / leaderboard 108 / mapmatch 35——common 52→59 +7、mapmatch 29→35 +6 只增不减；leaderboard +0 如实登记：E24 判别式在既有 applyVerified_reactivateAfterRollback_recredits 用例内加 reactivate 被调 + updateStatus 零调用断言，未新增测试方法）；compose config -q rc=0；前端 type-check 0 错 + 4 文件 61 测全绿；契约门 `--open=TASK-255 --baseline=f39e693` 判据 A 过 / 收口态 rc=1 属 C-02 台账固有语义（等价判据 16 条全等）；token 门 29 项逐位持平 SUM=1353 无一减少；词面门 ZERO_HIT + 探针阳性对照；checkstyle delta=0（record 4 违规全在零触碰文件、leaderboard 0）；`git diff --check` 净 + 双笔 `git show --check` 净，新 SQL 文件纯 LF + 完整末尾换行 |
| 指导亲核与独立复核 | FULL 档双环节全 PASS：指导亲核三块（①Git 摘要：porcelain 空、提交链正确、领先恰 4 笔、diff 16 条对上白名单；②高风险定向抽查 2 处：GlobalExceptionHandler 枚举比较非魔数 + 三框架 Handler 与 spec 逐字对齐、reactivate SQL 与 spec R6 逐字一致 + LeaderboardService 净 4 行 + 测试断言坐实 leaderboard +0 登记真实性；③回报一致性裁决：白名单双向全等、测试增量算术吻合、契约门收口 rc=1 归因成立，零矛盾）；独立复核子agent五块全 PASS（Git 状态与提交链 / 红线抽查零触碰面全零 diff / 双端门禁七门全量复跑逐位一致 / 证据链 R1-R7 五处定向核证 + spec §5 五项受限逐一对应 / 只改清单 16 项双向全等），零阻断项，N1-N3 非阻断登记（N1 C-01 计数誊录偏差 +452/−112→+426/−86 封盘笔订正、N2 旧 git clean 假阳性经三重核证非真实残留、N3 提交作者名控制台乱码显示 artifact 非仓内容），只读自证 `git status --porcelain` 空 |
| 是否到达外部门槛 | **已到达**（补记笔翻转，2026-10-16）：封盘批次（5 笔：TASK-254 补记笔 `8612d2b` + TASK-255 立项笔 `f39e693` + C-01 `ce4aa4a` + C-02 `1ac8c60` + 封盘笔 `f1b0dcf`）推送后 GitHub Actions CI run **`38903763620`** 权威核验 conclusion=**success** 全 job 绿（`build` ✓ / `web` ✓ / `probe-ubuntu26` skipped=if 门禁正确生效旁证），headSha `f1b0dcf` 与封盘批次匹配，外部终验达成完整闭环 |
| 状态 | TASK-255 已终裁 **ACCEPT 予以封盘**（实施回报 / 指导亲核 / 独立复核三方闭环一致；CI run `38903763620` 全绿外部终验达成，读数已由补记笔落库登记） |

## 验收记录：`TASK-256`（E 系批4b-1——后端治理八项 E15+E18+E19+E20+E22+E25+E28+E30，FULL 档，2026-10-16）

| 项 | 内容 |
| --- | --- |
| 开工基线 | `e1776aa`（main 分支批4b 立项笔，工作树干净；本地领先含 TASK-255 补记笔 `48457c0`，随本封盘批次统一推送） |
| C-01 实施笔 | `a89d095` `fix(user,record,verify,common,gateway,api): E系批4b-1后端治理八项（TASK-256）`（恰 23 文件 +542/−32：2 新建测试 LoginLockoutConfigTest/UserControllerTest + 21 改，含 VerifyControllerTest +18 行 N-a 追认项见下行） |
| C-02 任务两件套 | `2a2f11f` `docs(mailbox): 登记TASK-256实施完成与门禁回填（TASK-256）`（spec 开工基线回填 + 状态更新 + handoff 七节回填；封盘笔补登 C-02 哈希 + spec §2 计数订正与第 23 项追认补录） |
| 任务内容与白名单 | 相对开工基线恰 25 文件（23 业务/测试 + 2 台账）与白名单双向全等（契约门 C-01 窗口 rc=0，收口态 rc=1 属 C-02 台账笔固有语义、等价判据 25 条全等成立）；**N-a 白名单偏差追认**：VerifyControllerTest.java 不在原 §2 列表（立项笔清单漏列而 §3 R5 明文要求其扩展，§2/§3 内部矛盾），复核核证净 +18 行纯 R5 用例零夹带，终裁追认入白名单；**N-c 计数笔误封盘笔订正**：原 §2「22 项/业务测试 20 项」实为 23 文件；零触碰面 AuthService/TrackPreprocessor/异步两变体/VerifyEngine 规则链/FriendService.reject 本体/三看板等全零 diff（复核目录级核证无「路径不存在」假阴性） |
| 核心实现 | ① R1（E15）：yml threshold 5→20 + 注释全账号兜底口径 + README L223 双维度数值 + LoginLockoutConfigTest 资源断言（YamlPropertySourceLoader+Binder 断 threshold==20，不断言 ip-threshold）；② R2（E18）：publishSubmittedSync（buildMessage 共用抽段 + syncSend try/catch 真值）+ VerifyDegradeService L119/L146 改调，既有异步两变体零改动；③ R3（E19）：@Autowired @Lazy self（保留 @RequiredArgsConstructor）+ batchTransition 经 self.accept/reject 委托 + mock self 结构断言；④ R4（E20）：JwtUtil.parseAccess（type==ACCESS 与 parseRefresh 对称）+ 拦截器 L63 改调 + refresh 拒/access 过/无效拒三用例；⑤ R5（E22）：DTO Boolean+@NotNull + VerifyService 三处 Boolean.TRUE.equals + 缺 pass→Bean Validation 400；⑥ R6（E25）：R2 循环自 i=2 跳首区间（首点哨兵前提开工复验坐实）+ 三判别用例（首区间伪尖峰不计/三真实命中/两真实+一伪不命中）；⑦ R7（E28）：TraceIds 与网关双实现同规则镜像净化（长度 1..64 且字母/数字/下划线/连字符否则 newId，不拒绝请求；正则字面量仅落 Java 代码）+ 双侧四类对称用例 + 既有「原样复用」翻转为「重新生成」；⑧ R8（E30）：updateProfile LambdaUpdateWrapper 单列 set(nickname) + UserControllerTest（TableInfoHelper 预注册 + 单列断言） |
| 本机门禁 | 后端七模块 offline test rc=0（surefire common 63 / gateway 78 / user 215 / record 302 / verify 200 / leaderboard 108 / mapmatch 35——对基线 59/75/205/302/195/108/35：+4/+3/+10/持平/+5/持平/持平只增不减；record 持平 N-b：VerifyDegradeServiceTest 为纯 mock 面平移改调适配、无删测无断言弱化）；token 门 29 项 BASE==HEAD 逐位相等、改动集零命中（SUM 台账 1353 vs 复核 2017 属行命中/文件出现次数口径差异，非阻断登记）；词面门 ZERO_HIT + 探针阳性对照；checkstyle delta=0（record 4 项 pre-existing 于零触碰文件）；diff --check / show --check 净，新文件纯 LF + 末字节 0x0A；执行路径旁注 N-d：bin\bash.exe 因 WSL ext4.vhdx 挂载故障改经 usr\bin\bash.exe --login 同安装等效执行，离线入口语义不变 |
| 指导亲核与独立复核 | FULL 档双环节全 PASS：指导亲核三块（①Git 摘要：porcelain 空、链 e1776aa→a89d095→2a2f11f、C-01 23 文件 +542/−32 对上；②高风险定向抽查 2 处：E15 yml/README 与 spec R1 逐字吻合、E28 双实现镜像同规则且正则字面量仅落代码；③回报一致性裁决：坐实 N-a 根因为立项笔 §2 漏列、N-b record 持平为适配性改动、N-c 计数笔误，零矛盾）；独立复核五块全 PASS（Git 链 / 红线零触碰 / 七门全量复跑逐位一致 / 八判别式逐条符合 / 25 项双向全等），零阻断项，4 项非阻断登记（token 口径 / 契约门收口态固有语义 / §2 计数笔误 / Nacos 离线噪音），N-a~N-d 定向核证全部成立，只读自证 porcelain 空 |
| 是否到达外部门槛 | **已到达**（补记笔翻转，2026-10-17）：封盘批次（5 笔：TASK-255 补记笔 `48457c0` + 批4b 立项笔 `e1776aa` + C-01 `a89d095` + C-02 `2a2f11f` + 封盘笔 `1772102`）推送后 GitHub Actions CI run **`38917734857`** 权威核验 conclusion=**success** 全 job 绿（`build` ✓ 10m56s / `web` ✓ 21s / `probe-ubuntu26` skipped=if 门禁正确生效旁证；两条 varargs annotations 为既有测试文件 javac 警告非阻断），headSha `1772102` 与封盘批次匹配，外部终验达成完整闭环 |
| 状态 | TASK-256 已终裁 **ACCEPT 予以封盘**（实施回报 / 指导亲核 / 独立复核三方闭环一致；CI run `38917734857` 全绿外部终验达成，读数已由补记笔落库登记） |

## 验收记录：`TASK-257`（E 系批4b-2——前端测试可观测三项 E8+E14+E26，MEDIUM 档，2026-10-17）

| 项 | 内容 |
| --- | --- |
| 开工基线 | `1772102`（TASK-256 封盘笔 main HEAD；开工自证 rev-parse 全哈希 + porcelain 空，实施侧回填 spec 与 handoff） |
| C-01 实施笔 | `033110a` `fix(web): E系批4b-2前端测试可观测三项（TASK-257）`（恰 18 文件 +431/−37 = spec §2 白名单 10 项 + 条件项 8 文件；条件项三块均 C-01 前定档并登记于提交体） |
| C-02 任务两件套 | `5788184` `docs(mailbox): 登记TASK-257实施完成与门禁回填（TASK-257）`（首笔 6e30a81 因契约判据 B 要求 handoff 清单节逐行列全改动集 amend，台账未推送双提交不变量保持；§2 逐行 20 项清单供判据 B 机器比对 → 收口态 rc=0） |
| 任务内容与白名单 | 相对开工基线恰 20 文件（18 业务/测试 + 2 台账）与白名单+条件项双向全等；条件项三块：①既有 8 个 e2e 文件接入 assertNoUnmocked（spec §2 明文豁免 + §3 R2「接入全部用例」，12 调用点各 1-2 行）②基线红订正 3 断言（1772102 纯净 worktree 实测复现非本笔引入：feed 'RUNNING' 自 TASK-245 B27 徽标化起基线即红→订正 '🏃 跑步'、goal-card '32.5/50' 自 TASK-242 B19 两位小数起基线即红→订正 '32.50/50.00'，亲核对照页面实况坐实属向演进同步断言）③unmocked 清障补 mock 三端点（likes/batch、friends/requests、user/profile 此前静默兜底 404）；零触碰面后端全模块/api/typed-router/package.json/playwright.config/三看板全零 diff |
| 核心实现 | ①R1（E8）：client.ts 模块级单例 refreshPromise（sharedRefresh——首个 401 发起 refresh、并发 401 await 同一 Promise、settled finally 清引用），/api/auth/* 跳过清单与 _retry 单请求单次语义不变，triggerAuthFail 幂等；client.test.ts 新增两用例（两并发 401 恰一次 refresh 均以新 token 重试成功 / refresh 失败两败者不重试 auth-fail 各一次）；README L51/L61 同步；②R2（E14）：ApiCtx 增 unmocked 列表 + assertNoUnmocked 断言接入全部用例；fixtures/notifications.ts 改 createNotificationStore 状态记忆（未读 3=id 55/50/49 派生、第 1 页恰 1 未读保 top-nav strict）；补 batch-read 与清空已读两写端点 mock；新建 notifications.e2e.ts（列表渲染 + 三按钮真实点击、徽标 3→2→0 收敛 + 清空后空态）；③R3（E26）：leaderboard/feed/index/notifications 四页面加载函数内联请求序号守卫（前置自增捕获、不一致丢弃数据态/错误态/不清 loading、finally 仅当前序号清 loading、错误分支同守卫、不抽 composable）；leaderboard 快速切换 E2E 用例（首 zset 延迟 3s vs 后发即时，断言终态为后发数据，实测 4.2s 稳定未触发 §5 时序降级；落点 notifications.e2e.ts L56-58 注释登记白名单约束下落点理由） |
| 本机门禁 | 后端 offline 七模块 rc=0（surefire 63/78/215/302/200/108/35 与开工基线逐位持平——白名单零后端文件 + 实测复核双证；派发覆盖口径：spec §4 所记 59/75/205/302/195/108/35 为立项时点旧值）；type-check 0 错；vitest 4 文件 63 测全绿（61+2）；E2E 14/14（既有 12 + 新增 2）48.1s 零真实网络断言保持；契约门 C-01 窗口 rc=0 + C-02 后收口复跑 rc=0（判据 B 20=20）；token 门 29 项 SUM 2017 逐位持平零减少、改动集零命中；词面门 ZERO_HIT + 探针阳性对照（首探针误用 .tmp 被 gitignore 忽略致假阴性、换 .txt 坐实）；diff --check / show --check 净，新文件纯 LF + 末字节 0x0A；执行路径旁注：usr\bin\bash.exe --login（沿 TASK-256 N-d 先例）+ pnpm build/E2E 需 TEMP 重定向仓内 .trae/tmp（C 盘用户 Temp esbuild EACCES 环境级，零仓库触碰） |
| 指导亲核 | MEDIUM 档亲核三块全 PASS：①Git 摘要（porcelain 空、恰 2 笔、C-01 18 文件 +431/−37 对上）；②高风险定向抽查 3 处（E8 sharedRefresh 与 spec R1 逐条吻合含 withCredentials 注释保留；基线红订正 3 断言与 feed.page.vue L137 '🏃 跑步'（B27）、index.page.vue L470 toFixed(2)（B19）页面实况逐字吻合且订正最小面恰 3 断言行；notifications.e2e.ts 落点注释完整）；③回报一致性裁决（vitest +2 与提交体两用例一致、E2E 14=12+2、token SUM 2017 与 TASK-256 复核口径一致、契约收口 rc=0 由 handoff 逐行清单支撑 amend 原因自洽；条件项三块提交体+handoff 双登记；受限声明三项与 spec §5 预注册对应），零矛盾 |
| 是否到达外部门槛 | **已到达**（补记笔翻转，2026-10-17）：封盘批次（4 笔：TASK-256 补记笔 `1dd8ab8` + C-01 `033110a` + C-02 `5788184` + 封盘笔 `b1d763c`）推送后 GitHub Actions CI run **`38931526812`** 权威核验 conclusion=**success** 全 job 绿（`build` ✓ 10m44s / `web` ✓ 26s / `probe-ubuntu26` skipped=if 门禁正确生效旁证；两条 varargs annotations 为既有测试文件 javac 警告非阻断），headSha `b1d763c` 与封盘批次匹配，外部终验达成完整闭环 |
| 状态 | TASK-257 已亲核通过完整闭环（MEDIUM 档：实施回报 + 指导亲核两方闭环一致；CI run `38931526812` 全绿外部终验达成，读数已由补记笔落库登记） |

## 验收记录：`TASK-259`（G 系批 G-1——G1 时区语义全链统一，FULL 档，2026-10-17 立项）

| 项 | 内容 |
| --- | --- |
| 开工基线 | main @ `50d1877`（G 系登记笔；工作树含本地未推送笔 a21ee02/50d1877，以开工实测 `git rev-parse HEAD` 回填 handoff 为准） |
| 分级定档 | FULL（G 系唯一 HIGH 项：触碰归日/周期/前后端契约核心语义，禁降级；触碰 compose 部署基建 → 七模块全量逐位验证 + 独立复核子agent + 指导亲核） |
| 亲验证据 | 指导侧 8 处全部坐实（spec §1 行号级）：三帧分裂矩阵（中间件 TZ 东八 + JDBC serverTimezone 7 处东八 vs 六服务容器 JVM=UTC vs 前端浏览器本地解析）；created_at L226 vs 推导 L1068-1069 显式 UTC；归日链 myStreak L874-882/myStats L937-945/GoalService L194-199；feed L169/leaderboard L72-83 |
| 白名单 | 4+5 项：docker-compose.services.yml + SportRecordService.java + SportRecordServiceTest.java（适配并入）+ 台账 5（零触碰 api/其余五服务/pom/package.json/sql/scripts/web/docker-compose.yml/.github） |
| 核心实现 | R1 compose 六服务 TZ=Asia/Shanghai；R2 推导改区 BUSINESS_ZONE 常量（唯一生产代码改动）；R3 L530-532 断言适配（向演进同步非删弱）；R4 边界用例 ≥4（固定 Instant + 显式 ZoneId，runner 时区无关：跨日/23:59/晨跑归日/周月窗口） |
| 门禁要求 | 七门禁全量逐位（surefire 63/78/215/302/200/108/35 只增不减）+ compose config 配置层断言 + 契约/token/词面/checkstyle/diff --check 照跑；双提交 C-01/C-02 |
| 受限预登记 | spec §5 四项：真实容器 JVM user.timezone 运行态读数未实测（配置层承载）、存量 UTC 墙钟数据不迁移、非东八浏览器显示错位已知边界、Nacos 运行时未核 |
| 实施与门禁 | C-01 `48a0a45`（fix(record): G1时区语义全链统一，恰 3 文件 +243/−14：compose 六服务 TZ +18 / 主类 R2+偏差①牵连带 +23/−9 / 测试 R3+R4 六测 +202/−5）+ C-02 `692073b`（台账 +53/−16）；七门禁全绿：offline 七模块 rc=0（63/78/215/**308**/200/108/35，record 302→308 恰 +6 零删减）、compose config 两文件口径 rc=0（TZ 渲染面恰 8 处=六服务+mysql/postgis 既有 2）、契约门三读 rc=0（判据 B 恰 5 token）、token 门 29 项 SUM 2017 逐位持平零命中、词面门 ZERO_HIT+探针阳性对照（.txt）、checkstyle delta=0（恰 4 条既有 JavadocType，L1050→L1059 +9 位移=常量块插入，纯净 worktree 双向坐实）、diff --check 双提交净（业务件 CRLF/台账 LF） |
| 指导亲核 | FULL 档亲核三块全 PASS：①Git 摘要（porcelain 空、b18c9fe→48a0a45→692073b、领先 5 笔、C-01 stat 逐字吻合）；②定向抽查 3 处（R2 BUSINESS_ZONE L1056 常量+L1079-1080 推导改区；偏差① 落点 L1114-1125 三元分支 startTimeDerived ? now(BUSINESS_ZONE) : now()——推导值东八帧/显式值 JVM 帧语义精细正确；R1 TZ 恰 6 处 + R4 六测落点 L603/626/665/693/721）；③回报一致性（+6 与六测吻合、红→绿机理自洽；N-1：C-02 回报 +67/−30 为笔误，实测 +53/−16） |
| 独立复核 | 五块全 PASS 零阻断：Git 链/红线抽查（白名单外 17 项显式路径全零 diff、偏差①与修前基线 L1107 对照成立、全文 ZoneOffset 零残留 import 已移除）/双端门禁 7/7 独立复现（含 UTC runner 反向实测 _JAVA_OPTIONS=-Duser.timezone=UTC 308 全绿——双向 runner 时区无关性坐实）/证据链逐项比对/handoff §2 五行同序全等；N-1~N-6 非阻断（均为文字层笔误：C-02 行数、R1 行数表述、清理件命名、白名单项次引用、五法六测计数、R4-6 相对 Instant 宽松表述——实质均无碍，集中登记本笔不退改 handoff 原文保持执行侧笔迹） |
| 是否到达外部门槛 | **已到达**（补记笔 2026-10-17 翻转）：封盘批次（6 笔 = TASK-257 补记笔 `a21ee02` + G 系登记笔 `50d1877` + 立项笔 `b18c9fe` + C-01 `48a0a45` + C-02 `692073b` + 封盘笔 `552bbb9`）推送后 GitHub Actions CI run **`38990356530`** 权威核验 conclusion=**success** 全 job 绿（`build` ✓ / `web` ✓ / `probe-ubuntu26` skipped=if 门禁正确生效旁证），headSha `552bbb9` 与封盘批次匹配，外部终验达成完整闭环 |
| 状态 | **已终裁 ACCEPT（2026-10-17 指导侧）**：偏差①（validateTimeRationality 比对帧同步，红→绿证据链完整+三元分支语义正确）与偏差②（compose 两文件 CI 权威口径，overlay 既有行为附基线同败证据）均追认；CI run `38990356530` 全绿（2026-10-17 补记笔登记），完整闭环 |

## 验收记录：`TASK-260`（G 系批 G-2——record/leaderboard 口径小批 G2+G5+G9+G11+G13，FULL 档，2026-10-17 立项）

| 项 | 内容 |
| --- | --- |
| 开工基线 | main @ `27181dc`（TASK-260 立项笔；实测 `27181dc0d548` 与派发要求逐位一致，开工 porcelain 空） |
| 分级定档 | FULL（G2 触碰核心服务逻辑并发安全语义就高定档——updateVisibility 整行回写可覆盖 verify statusCallback 乐观锁迁移；G13 条件项可能涉查询形态改写；跨 record/leaderboard/mapmatch 三模块全量逐位 + 独立复核子agent + 指导亲核） |
| 亲验证据 | 五项全部坐实（spec §1 实测行号）：G2 L609-617 整行回写 + @Version 零命中（E30 同根因）；G5 L118 无 tie-break + 先例 DailySummaryMapper L61 `user_id ASC`；G9 ContributionMapper L112 注释「无专用索引」vs DDL 02:63 idx_status_settled 已建 + mapmatch yml L92「MySQL」实为 PostgreSQL；G11 L723-727 裸 start_time vs myStats/goal COALESCE 口径；G13 L778-787 pageFeed 查询形态（filesort 待 EXPLAIN） |
| 白名单 | 5+5 项：SportRecordService.java + SportRecordServiceTest.java + LeaderboardContributionMapper.java + leaderboard 测试落点（执行侧选定登记）+ mapmatch application.yml（注释级）+ 台账 5（零触碰 sql/DDL——G9 索引已建无需补、状态机/MQ/批量参数） |
| 核心实现 | R1 updateVisibility 改 LambdaUpdateWrapper 单列 set（照 E30）+ 并发语义断言（旧快照不覆盖 status/version）；R2 周期榜 ORDER BY 补 `, user_id ASC`（照日榜先例）+ 同分稳定排序断言；R3 两处注释订正（零行为变更）；R4 my 过滤改 COALESCE 口径 + NULL 行归窗断言；R5 前置 EXPLAIN（scratch 库）双结局：filesort 坐实仅改查询形态禁加索引 / 不坐实登记零改动 |
| 门禁要求 | 七门禁全量逐位（surefire 63/78/215/308/200/108/35 只增不减；ContributionMapper 有 diff 静态门纯净 worktree 双向坐实；mapmatch 35 持平注释级零行为）+ 契约/token/词面/checkstyle/diff --check 照跑；双提交 C-01/C-02 |
| 受限预登记 | spec §5 四项：G2 毫秒级窗口单测 mock 语义承载、G13 EXPLAIN 为 scratch 时点快照、G11 仅存量 NULL 行、Nacos 未核 |
| 实施与门禁 | C-01 `3ab2f04`（fix(record)，恰 5 文件 +163/−26：R1 updateVisibility 单列 UPDATE + R4 COALESCE 口径 + R2 tie-break + R3 两处注释订正 + 并发语义/口径/SQL 契约新测）+ C-02 `2434842`（台账 +33/−13）；七门禁全绿：offline rc=0（63/78/215/**310**/200/**109**/35，record +2、leaderboard +1 恰为本笔新测、mapmatch 35 持平）、契约门三读 rc=0（判据 B 恰 7 路径）、token 门基线 2017 逐位持平 + 终态 2046（+29 恰为 handoff §3 枚举行，沿既有口径）、词面门 ZERO_HIT+探针对照、checkstyle delta=0（record 既有 4 条 JavadocType L1059→L1076 +17 位移，纯净 worktree 双向坐实）、diff --check 净；R5 EXPLAIN 四场景（scratch 167,365 行）：auth-on filesort 坐实、OR 拆分计划逐位相同、UNION ALL 外层仍 filesort——消除须加索引触发 §6 停止条件零改动收口 |
| 指导亲核 | 三块全 PASS：①Git 摘要（porcelain 空、恰 2 笔、diff 7 文件与 handoff §2 全等、sql/ 零触碰）；②定向抽查（R1 L623-625 单列 SET 不含 status/version + DTO 局部变量 + 归属校验前置；R2+R3a L114-122 tie-break 落位 + 注释如实登记 idx_status_settled）；③一致性裁决（surefire 净增恰为本笔新测；R5 零改动收口属 spec §3 预登记双结局+§6 停止条件路径非偏差；N2 函数索引 ERROR 3762 如实登记） |
| 独立复核 | 五块全 PASS 零阻断（4/6/6/5/1 子项全过：契约门 C-02 时点纯净 worktree 回放 rc=0 判据 B 通过、token 双读数口径实证、R5 四场景逐位复现、pageFeed 方法体双向提取逐字相同、N2 单双括号一次性库复验后即删）；非阻断 5 项集中登记封盘笔（中 1：契约门 rc 时点性=补记笔窗口并集效应沿时点标签惯例；低 4：token 工作树 SUM 终态订正 2046、N1 idx_archive 实存误列缺失、spec §1 行号微漂、UNION ALL Extra 微差） |
| 是否到达外部门槛 | **已到达**（补记笔 2026-10-17 翻转）：封盘批次（5 笔 = 立项笔 `27181dc` + C-01 `3ab2f04` + C-02 `2434842` + TASK-259 补记笔 `83cf5f2` + 封盘笔 `1dda602`）推送后 GitHub Actions CI run **`38999061241`** 权威核验 conclusion=**success** 全 job 绿，headSha `1dda602` 与封盘批次匹配，外部终验达成完整闭环 |
| 状态 | **已终裁 ACCEPT（2026-10-17 指导侧）**：R5 零改动收口追认（预登记路径内、证据链复核逐位复现）；契约门窗口并集效应沿时点标签惯例封盘笔消化；token 终态读数 2046 订正登记；N2（sql/02:34 函数索引 ERROR 3762——E23 R5 收益从未兑现、新装库 init 中断风险）+ R5 UNION ALL perf 口径登记 BACKLOG 待用户拍板；CI run `38999061241` 全绿 headSha 匹配（2026-10-17 补记笔登记），完整闭环 |

## 验收记录：`TASK-261`（G 系批 G-3——跨服务与查询边界 G3+G4+G10，FULL 档，2026-10-17 立项）

| 项 | 内容 |
| --- | --- |
| 开工基线 | main @ `3e9b4db`（TASK-261 立项笔；实测 `3e9b4db29a0a` 与派发要求逐位一致，开工 porcelain 空；实测领先 origin 1 笔——派发书「2 笔」口径过时系前批封盘笔已随批次推送，执行侧修正登记） |
| 分级定档 | FULL（G3 触碰读取面可见性授权门 assertCanView 批量路径重构 + 跨服务 Feign 消费面，安全语义敏感禁降级；跨 record/user/verify 三模块 → 七模块全量逐位验证 + 独立复核子agent + 指导亲核） |
| 亲验证据 | 三项全部坐实（spec §1 实测行号）：G3 双落点 RecordLikeService L358-372 逐条 selectById+assertCanView + SportRecordService L1184-1185 逐条 areFriends Feign + 修法前提 listFriendIds 契约已存在（api L98 + fallback，零新增契约）+ feed 先例 L789-794；G4 四处 size 直通（pagePoints L531 / 通知 L112-121 / 好友 L347/L432）+ 范式先例 MAX_MY_RECORDS_SIZE=500 双钳制写法 L724-725；G10 VerifyService L230-235 无界 + EXHAUSTED_LIMIT_MAX=200 同文件先例 |
| 白名单 | 9+5 项：record 两主类 + 两测试 + user 两服务类 + user 测试落点（执行侧选定登记）+ VerifyService + verify 测试落点（执行侧选定登记）+ 台账 5（零触碰 api 模块/全部控制器层/其余服务 src/test/pom/package.json/sql/scripts/web） |
| 核心实现 | R1 getLikesBatch 批量重构（selectBatchIds 单查 + listFriendIds 好友集懒拉一次 + FRIENDS contains 内存判定 + assertCanView 公共签名与门矩阵语义不变 + fail-safe 降级对齐）；R2 四处服务层双钳制（safePage/safeSize 沿 L724-725 先例，值 500）；R3 listPendingAppeals LIMIT 200（沿 EXHAUSTED_LIMIT_MAX 值域，Javadoc 语义收窄登记） |
| 门禁要求 | 七门禁全量逐位（surefire 63/78/215/310/200/109/35 只增不减；三服务主类有 diff 静态门纯净 worktree 双向坐实）+ 契约/token/词面/checkstyle/diff --check 照跑；双提交 C-01/C-02 |
| 受限预登记 | spec §5 五项：G3 收益静态推论无压测（mock 语义承载）、好友集懒拉等价性单测承载、G4 上限 500 范式对齐值、G10 防御性语义收窄、Nacos 未核 |
| 实施与门禁 | C-01 `93312b9`（fix(record)，恰 10 文件 +409/−47：R1 assertCanView(List) 公共重载 + loadFriendIdSet 懒拉 fail-safe + getLikesBatch selectBatchIds 批查保序 + R2 四处双钳制 + R3 LIMIT 200 + 新测 13）+ C-02 `66d2449`（台账 2 文件 +59/−1）；七门禁全绿：offline rc=0（63/78/**218**/**318**/**202**/109/35，user +3/record +8/verify +2 恰为本笔新测零删减、mapmatch 35 持平）、契约门三时点 rc=0（判据 B 恰 12 路径；C-01 后曾 rc=1 一次系 handoff 改动清单节叙述行裸 token 提取陷阱，措辞改写后复跑 rc=0 并登记避坑）、token 门基线 2046 逐位持平（C-02 树 2075=2046+29 自枚举效应沿 TASK-260 同型）、词面门四 locale ZERO_HIT + 探针阳性对照、checkstyle delta=0（record 4=4 / verify 976=976 / user 1175→1172 顺带收敛 3 条存量长行，新增行违规交集空，纯净 worktree 双向坐实）、diff --check 净、10 业务文件 w/crlf·blob i/lf（NotificationService 两文件编辑中曾整转 LF 以 perl raw-IO 归回） |
| 指导亲核 | 三块全 PASS：①Git 摘要（porcelain 空、恰 3 笔、diff 12 文件与 handoff §2 逐位一致）；②定向抽查（R1 批量重载 L1230-1262 门矩阵四分支与单条门逐字保持 + loadFriendIdSet L1269-1280 懒拉整批至多一次三路 fail-safe 空集 + selectBatchIds 单查 LinkedHashMap 去重保序；R2 四处钳制值 500 控制器零改动；R3 LIMIT 200 常量拼接 + orderByDesc(id) 保最新）；③一致性裁决（surefire 净增 user +3/record +8/verify +2 与 handoff §4 新增断言落位对账一致；执行侧如实登记八项均合理非阻断） |
| 独立复核 | 五块全 PASS 零阻断（块一 4/4 提交链逐笔对账；块二红线全过——单条门零 hunk、批量重载四分支逐字等价、归属人 null×FRIENDS 边界独立判定等价、loadFriendIdSet 三路 fail-safe、懒拉严格条件、R2/R3 落位、零触碰面 pathspec 双查全空、零新增契约 3e9b4db 与 66d2449 命中位逐字相同；块三 8/8 全亲自跑——offline 七模块逐位、契约门纯净 worktree @66d2449 回放 rc=0、token 29/29 逐位全等 + C-02 树 2075=2046+29 逐 token 验证 + 剔除 handoff 后 2046=2046、词面四 locale+探针、checkstyle 三模块双向 + 新增行交集空、diff/行尾双口径净、mapmatch 持平；块四证据链 7/7 一致 + §4 八条登记独立判定成立；块五三向全等 12=12=白名单映射，主树 15=12+补记笔 3 文件属派发书预警预期形态）；非阻断 4 项（中 1：token 派发书窗口口径未计自枚举效应——复核实测实质 PASS，教训=后续派发书期望值直写「C-02 树 = 基线 + 29」；低 3：loadFriendIdSet warn 语义按先例读法（异常路 warn）、spec「8 用例」实为 9 纯文档口径、record JavadocType 位移基准表述 +17+11=+28 算术吻合） |
| 是否到达外部门槛 | **已到达**（补记笔 2026-10-17 翻转）：封盘批次（5 笔 = 立项笔 `3e9b4db` + C-01 `93312b9` + C-02 `66d2449` + TASK-260 补记笔 `41279cc` + 封盘笔 `878ee9a`）推送后 GitHub Actions CI run **`39009192599`** 权威核验 conclusion=**success** 全 job 绿（web ✓ / build ✓ / probe-ubuntu26 skipped=if 门禁正确生效旁证），headSha `878ee9a` 与封盘批次匹配，外部终验达成完整闭环 |
| 状态 | **已终裁 ACCEPT（2026-10-17 指导侧）**：亲核三块 + 复核五块双 PASS 零阻断；非阻断 4 项集中登记封盘笔（token 派发书口径教训沿自枚举先例追认，低 3 文字层/口径类不退改原文档）；复核收口自证中 detached worktree 系 .codex 会话前既存件未触碰如实登记；CI run `39009192599` 全绿 headSha 匹配（2026-10-17 补记笔登记），完整闭环 |

## 验收记录：`TASK-262`（G 系批 G-4——统一性对齐群 G6+G12+G14+G15+G16+G17，FULL 档，2026-10-17 立项）

| 项 | 内容 |
| --- | --- |
| 开工基线 | main @ `6f7f2cc`（TASK-262 立项笔；实测 `6f7f2cc64277` 逐位一致，开工 porcelain 空，领先 origin 2 笔：TASK-261 补记笔 `54e3c50` + TASK-262 立项笔 `6f7f2cc`） |
| 分级定档 | FULL（G6 改对外 HTTP 契约跨 record/user/leaderboard 三服务八控制器 + G17 触碰 api 模块 Feign 契约面，多模块 + 对外契约变化就高禁降级；跨 api/record/user/leaderboard/verify 五模块 → 七模块全量逐位验证 + 独立复核子agent + 指导亲核） |
| 亲验证据 | 十项全部坐实（spec §1 实测行号）：G6 八处局部 handler 优先级遮蔽公共层（record 三处 L334/L120/L131、user 四处 L130/L235/L178/L246、leaderboard L124）+ 组件扫描确保公共层生效 + 契约 delta 恰两类（record 3001 路径 200→404、AuthController 1002 路径 200→403，其余五控制器零 delta）+ 前端 client.ts 拦截器只看 body.code 零前端影响 + 测试适配面 4 类 11 用例；G12 CacheConfig L49 恒定 5min + RuleCacheService jitter 范式；G14 api PageResult L12-14 Javadoc 规范 vs RecordController L112/L139/L254 三处外抛 MP Page（前端已对齐 PageResult 零消费影响）；G15 LikeEventConsumer L213 与 NotificationEventConsumer L216 重复定义前缀 + VerifyEventOutboxMapper 六处状态字面量内联；G16 CacheConfig L89 Jdk 序列化 vs RuleCacheService JSON 序列化；G17 listPoints 四处死面（RecordApi L53/Fallback L43/InternalRecordController L49/SportRecordService L497）主代码零调用 + VerifyService L119 注释自证 |
| 白名单 | 23+5 项：record 5 主类 + 4 测试 + user 5 主类 + user 测试落点（执行侧选定登记）+ leaderboard 2 主类 + leaderboard 测试落点（执行侧选定登记）+ verify 1 主类 + 1 测试 + api 2 类 + 台账 5（零触碰 common 模块/gateway/mapmatch/pom/package.json/sql/scripts/web/状态机/MQ/批量/索引参数） |
| 核心实现 | R1 G6 主项（删 8 处局部 handler 收敛公共层 + record 3001→404 / Auth 1002→403 两类 delta 契约测 + 11 测试用例改写为公共层等价双断言）；R2 G12 Redis entryTtl 引入 ±60s 均匀抖动；R3 G14 RecordController 三端点 MP Page 改 api PageResult 薄转换；R4 G15 前缀常量与 outbox 状态常量收拢；R5 G16 CacheConfig 改 GenericJackson2JsonRedisSerializer 且 typing 为 As.WRAPPER_ARRAY + 键前缀 :v2:: 升版；R6 G17 删除四处 listPoints 死面 + record surefire 等量补位只增不减 |
| 门禁要求 | 七门禁全量逐位（surefire 63/78/218/318/202/109/35 只增不减；record 删用例等量补位对账）+ 契约门（判据 B 机器比对）+ token 门 29 项逐位持平（期望值口径：C-02 树 = 2046 + 29 = 2075）+ 词面门四 locale ZERO_HIT + 探针阳性对照 + checkstyle delta=0（纯净 worktree 双向坐实）+ diff --check 净；双提交 C-01/C-02 |
| 受限预登记 | spec §5 五项：G6 前端透明性为静态核对无浏览器回归（沿 E21 先例）；G12 jitter 收益为范式对齐推论；G16 旧条目孤儿化依赖 5min TTL 自然过期无 Redis 混存实测；G17 死面判定为静态依据无运行时观测；Nacos 运行时未核 |
| 实施与门禁 | C-01 `3379843`（fix(record)，恰 25 文件 +475/−399：R1 删 8 局部 handler + 两类 delta 契约测 + 11 用例升级双断言；R2 CacheConfig TtlFunction 抖动；R3 RecordController 三端点 PageResult 薄转换；R4 前缀常量与 outbox 状态常量收拢；R5 JSON 序列化对称包裹与 :v2:: 升版；R6 删四处 listPoints 死面 + record surefire 等量补位）+ C-02 `7eed9ec`（台账 1 文件 +14/−14）；七门禁全绿：offline 七模块 rc=0（63/78/219/320/203/110/35，user +1 / record +2 / verify +1 / leaderboard +1 净增全为新测，mapmatch 35 持平）、契约门纯净 worktree 判据 B 26 路径全等 rc=0、token 门基线 2075 逐位持平、词面门四 locale ZERO_HIT + 探针阳性对照、checkstyle delta<=0（五模块双向零新增违规、新增行交集空）、diff --check 净（16 LF + 9 CRLF 保基线） |
| 指导亲核 | FULL 档亲核三块全 PASS：①Git 摘要（porcelain 空、恰 2 笔、diff 26 文件与 handoff §2 逐位一致、零推送）；②定向抽查（RecordController 局部 handler 已删且三端点 PageResult 薄转换落位、CacheConfig TtlFunction 抖动与 JSON 序列化落位、RecordApi listPoints 已删）；③一致性裁决（surefire 净增与落位吻合、C-01 25 文件展开在白名单授权面内、非阻断登记如实） |
| 独立复核 | 五块全 PASS 零阻断：块一 Git 链对账通过（porcelain 空、HEAD=7eed9ec 领先 4 笔）；块二红线与零触碰面 8 项抽查全 PASS（8 处局部 handler 全删、两类 delta 契约用例坐实、11 用例双断言升级、R2/R3/R4/R5/R6 断言全绿、零触碰面全空）；块三双端门禁实测 7 项亲自复跑全 PASS（surefire 63/78/219/320/203/110/35、契约门纯净 worktree rc=0、token 门 2075 精确持平、词面门四 locale ZERO_HIT+探针对照、checkstyle delta<=0 且新增行交集空、diff 净、mapmatch 35 持平）；块四证据链核对 PASS（非阻断 3 项登记）；块五只改清单计数 26=26=26 全等，条目 13 路径缺少 auth/ 坐实为立项笔 spec 笔误，指导侧封盘笔收口订正 |
| 是否到达外部门槛 | **已到达**（补记笔 2026-10-17 翻转）：封盘批次（5 笔 = TASK-261 补记笔 `54e3c50` + 立项笔 `6f7f2cc` + C-01 `3379843` + C-02 `7eed9ec` + 封盘笔 `adcd4c8`）推送后 GitHub Actions CI run **`39029851254`** 权威核验 conclusion=**success** 全 job 绿（`web` ✓ 22s / `build` ✓ 11m6s / `probe-ubuntu26` skipped=if 门禁正确生效旁证），headSha `adcd4c8` 与封盘批次匹配，外部终验达成完整闭环 |
| 状态 | **已终裁 ACCEPT（2026-10-17 指导侧）**：指导亲核三块 + 独立复核五块双 PASS 零阻断；C-01 25 文件展开追认；spec 条目 13 路径笔误随封盘笔订正；非阻断登记项（R1 日志域化收敛/Goal handler 保留理由/outbox 断言）追认收口；CI run `39029851254` 全绿 headSha 匹配（2026-10-17 补记笔登记），完整闭环 |

## 验收记录：`TASK-263`（G 系批 G-5——配置与前端效率群 G8+G20+G21+G7+G18+G19，MEDIUM 档，2026-10-17 立项）

| 项 | 内容 |
| --- | --- |
| 开工基线 | main @ `76a447b`（TASK-263 立项笔；实测领先 origin 2 笔：TASK-262 补记笔 `e2df483` + TASK-263 立项笔，本地保留随批次推送） |
| 分级定档 | MEDIUM（纯配置、前端效率与防御性注解，非安全核心语义，跨 user/leaderboard/verify/record/web 五面；由指导侧亲核 + 子 agent 实施闭环） |
| 亲验证据 | 七项全部坐实（spec §1 实测行号）：G8 四服务（leaderboard L61-63/user L30-33/verify L52-55/record L46-47）仅配 host/port 缺 timeout 矩阵；G20 ci.yml L145-159 web job 缺 pnpm 依赖缓存；G21 notifications.page.vue 三处（L192-193/L267-268/L286-287）串行 await 瀑布；G7 web/dist/assets/index-*.js 1.64MB 超限且 vite.config.ts 缺 build 分包；G18 StompConnectAuthInterceptor L66 抛 MessagingException 丢因 e；G19 User.java L32-33 passwordHash 缺 @JsonIgnore；G22/G23 既有受限事实坐实 |
| 白名单 | 12+5 项：leaderboard yml + user yml + verify yml + record properties + ci.yml + notifications.page.vue + vite.config.ts + StompConnectAuthInterceptor.java + User.java + 3 个测试落点 + 台账 5（零触碰 gateway/mapmatch/pom/package.json/sql/状态机/MQ/批量/索引参数） |
| 核心实现 | R1 G8 四微服务配置 spring.data.redis.timeout=3000ms；R2 G20 ci.yml web job 补 pnpm store cache 步骤；R3 G21 notifications 三处串行改为 await Promise.all([loadList(), refreshUnread()])；R4 G7 vite.config.ts manualChunks 提取 vendor-antdv 与 vendor-vue，主 chunk 降至 500KB 以下；R5 G18 STOMP 异常保留 cause e；R6 G19 User.java passwordHash 补 @JsonIgnore 防御序列化 |
| 门禁要求 | 七门禁全量逐位（surefire 63/78/219/320/203/110/35 只增不减）+ 前端三门禁（type-check 0 / vitest 4 套 63 测全绿 / build 主 chunk < 500KB / typed-router 净）+ 契约门（判据 B 机器比对）+ token 门 29 项逐位持平（期望值口径：C-02 树 = 2075 + 29 = 2104）+ 词面门四 locale ZERO_HIT + 探针阳性对照 + checkstyle delta=0 + diff --check 净；双提交 C-01/C-02 |
| 受限预登记 | spec §5 五项：G22 Hikari 池默认 10 保持不变未压测禁调；G23 慢 SQL 依托既有 Slf4jImpl 与底层慢查日志禁新插件；G8 超时为生产防御标称值无网络注入实测；G20 缓存生效以 GitHub runner 真实运行为准；Nacos 运行时未核 |
| 实施与门禁 | C-01 `3c6a596`（fix(config,web,user)，恰 12 文件 +126/−9：R1 四处 timeout 3000ms 与 Binder 配置单测 + R2 web actions/cache@v5 + R3 notifications 三处 Promise.all + R4 vite manualChunks 主 chunk 降至 90.64KB + R5 STOMP cause e 与测试断言 + R6 User @JsonIgnore 与单测）+ C-02 `8320acb`（台账 1 文件 +37/−15）；七门禁全绿：offline 七模块 rc=0（63/78/221/320/203/110/35，user 219→221 净 +2 恰为两纯 JVM 新测零删减、其余六模块完全持平）、前端三门禁全绿（type-check 0 错、vitest 4 套 63 测全绿、build 产物主 index chunk 90.64KB 消除 500KB 警告、typed-router.d.ts 纯净无漂移）、契约门纯净 worktree 判据 B 13 路径全等 rc=0、token 门基线 2075 逐位持平 + 终态 2104 精确吻合期望值、词面门四 locale ZERO_HIT + 探针阳性对照、checkstyle delta=0、diff --check 净（新文件 LF） |
| 指导亲核 | MEDIUM 档亲核三块全 PASS：①Git 摘要（porcelain 空、恰 2 笔、diff 13 文件与 handoff §2 逐位一致、零推送）；②定向抽查（R1 四微服务超时配置与 Binder 单元测试落位、R2 actions/cache@v5 缓存 step 落位、R3 Promise.all 并行化三处落位、R4 manualChunks 主 chunk 实测 90.64KB、R5 MessagingException cause 传参、R6 @JsonIgnore 与序列化防护断言）；③一致性裁决（surefire 净增与落位吻合、前端构建体积大降符合预期、受限声明如实登记） |
| 独立复核 | 免独立复核子 agent（MEDIUM 档按规范由指导侧亲核把关并终裁） |
| 是否到达外部门槛 | **已到达**（补记笔 2026-10-17 翻转）：封盘批次（5 笔 = TASK-262 补记笔 `e2df483` + 立项笔 `76a447b` + C-01 `3c6a596` + C-02 `8320acb` + 封盘笔 `57f8d92`）推送后 GitHub Actions CI run **`39034968218`** 权威核验 conclusion=**success** 全 job 绿（`web` ✓ 32s / `build` ✓ 10m57s / `probe-ubuntu26` skipped=if 门禁正确生效旁证），headSha `57f8d92` 与封盘批次匹配，外部终验达成完整闭环 |
| 状态 | **已终裁 ACCEPT（2026-10-17 指导侧）**：指导亲核三块全 PASS 零阻断；13 文件白名单双向全等；G8/G20/G21/G7/G18/G19 六项全部收口；CI run `39034968218` 全绿 headSha 匹配（2026-10-17 补记笔登记），完整闭环 |

## 验收记录：`TASK-264`（G 系批 G-6——SQL 函数索引订正与查询形态收尾群，MEDIUM 档，2026-10-17 立项）

| 项 | 内容 |
| --- | --- |
| 开工基线 | main @ `44d5056`（TASK-264 立项笔；实测领先 origin 1 笔：TASK-263 补记笔 `44d5056`，本地保留随批次推送） |
| 分级定档 | MEDIUM（SQL DDL 语法订正与性能/架构决策收尾，非业务逻辑与安全敏感变更；由指导侧亲核 + 子 agent 实施闭环） |
| 亲验证据 | 两项全部坐实：①N2 sql/02-record-db.sql L34 与 add-idx-record-coalesce-time.sql 函数索引 ((user_id), (COALESCE(...))) 双括号经 MySQL 8.0 实证 ERROR 3762 阻断建表与迁移，单括号订正后在真机 MySQL 8.0.46 容器实测通过且 EXPLAIN 证实 range 扫描 key_len=14 完美命中；②R5 pageFeed filesort 改形不可消，依红线保持 Java wrapper 原样，销毁 task260_scratch 临时库 |
| 白名单 | 3+1 项：`sql/02-record-db.sql` + `sql/migrations/add-idx-record-coalesce-time.sql` + `record-service/src/test/java/com/sportverify/record/db/SportRecordSqlContractTest.java` + 台账 `work/mailbox/tasks/TASK-264/handoff.md`（零触碰全仓其余代码/配置/依赖/前端/compose） |
| 核心实现 | R1 N2 函数索引普通列去冗余外括号，修复 DDL 与迁移语法，新增纯 JVM 契约测试，真机实跑留证；R2 R5 查询形态维持现有 wrapper 不变，清理 task260_scratch 库 |
| 门禁要求 | 七门禁全量逐位（surefire 63/78/221/320/203/110/35 只增不减，record 320→321 净增 1 契约测）+ 契约门（判据 B 机器比对）+ token 门 29 项逐位持平（期望值口径：C-02 树 = 2104 + 29 = 2133）+ 词面门四 locale ZERO_HIT + 探针阳性对照 + checkstyle delta=0 + diff --check 净；双提交 C-01/C-02 |
| 受限预登记 | spec §5 三项：全局禁加索引红线严格遵守；MySQL 8.0.13+ 函数索引特性要求；演示库 record_db 存量数据演进留存 |
| 实施与门禁 | C-01 `13453f2`（fix(sql,record)，恰 3 文件 +66/−3：R1 02 建表与迁移脚本订正为单括号合法函数索引 + 新增纯 JVM SportRecordSqlContractTest 契约测试）+ C-02 `09923de`（台账 1 文件 +38/−25）；七门禁全绿：offline 七模块 rc=0（63/78/221/321/203/110/35，record 320→321 净 +1 契约测全绿、其余六模块完全持平、零失败零跳过）、真机 MySQL 迁移脚本执行成功且幂等、EXPLAIN 证实 type=range 且 key=idx_user_coalesce_time（key_len=14）、task260_scratch 成功清理、契约门纯净 worktree 判据 B 4 路径全等 rc=0、token 门基线 2104 逐位持平 + 终态 2133 精确吻合期望值、词面门四 locale ZERO_HIT + 探针阳性对照、checkstyle delta=0、diff --check 净（新文件 LF） |
| 指导亲核 | MEDIUM 档亲核三块全 PASS：①Git 摘要（porcelain 空、恰 2 笔、diff 4 文件与 handoff §2 逐位一致、零推送）；②定向抽查（R1 02 脚本与迁移脚本单括号语法落位且非阻断非法双括号排除、SportRecordSqlContractTest 单测 0.146s 独立跑通、真实 MySQL 容器 task260_scratch 确认已销毁、record_db 复合函数索引建出且 EXPLAIN 走范围扫描）；③一致性裁决（surefire 净增与契约测落位完全吻合、受限声明与关闭决策如实登记） |
| 独立复核 | 免独立复核子 agent（MEDIUM 档按规范由指导侧亲核把关并终裁） |
| 是否到达外部门槛 | **已到达**（补记笔 2026-10-17 翻转）：封盘批次（5 笔 = TASK-263 补记笔 `44d5056` + 立项笔 `8e34aa2` + C-01 `13453f2` + C-02 `09923de` + 封盘笔 `0a7390a`）推送后 GitHub Actions CI run **`39040084087`** 权威核验 conclusion=**success** 全 job 绿（`web` ✓ 24s / `build` ✓ 10m38s / `probe-ubuntu26` skipped=if 门禁正确生效旁证），headSha `0a7390a` 与封盘批次匹配，外部终验达成完整闭环 |
| 状态 | **已终裁 ACCEPT（2026-10-17 指导侧）**：指导亲核三块全 PASS 零阻断；4 文件白名单双向全等；N2 函数索引语法阻断彻底修复并增补纯 JVM 契约测试；R5 维持现有 wrapper 不变收尾关闭，task260_scratch 成功清理；CI run `39040084087` 全绿 headSha 匹配（2026-10-17 补记笔登记），完整闭环 |

## 验收记录：`TASK-258`（方案 B：E13 PostGIS 真实空间库复验，FULL 档，2026-10-17 立项）

| 项 | 内容 |
| --- | --- |
| 开工基线 | main @ `4f3060f`（TASK-258 立项笔；实测领先 origin 1 笔：TASK-264 外部终验补记笔 `96caca4`，本地保留随批次推送） |
| 分级定档 | FULL（PostGIS 空间库交互、真实路网投影匹配算法与反作弊 R5 核心判定链路复验，涉及真实数据库引擎与空间索引实测；跨测试、脚本与运维工具；独立复核子 agent + 指导亲核） |
| 亲验证据 | 三项全部坐实：①E13 内联候选边 SQL 从未对真实库执行，全仓缺少针对 mapmatch 的 IT 现状坐实；②真机容器 sport-verify-postgis 端口 5433 运行正常，road_edge 表 7780 条真实路网数据就绪；③EXPLAIN ANALYZE 实测证实耗时 7.915 ms 并精确命中 idx_road_edge_geom GIST 空间索引，WKT 文本解析格式吻合 |
| 白名单 | 3+1 项：`mapmatch-service/src/test/java/com/sportverify/mapmatch/service/MapMatchSqlContractTest.java` + `mapmatch-service/src/test/java/com/sportverify/mapmatch/service/MapMatchPostgisIT.java` + `scripts/mapmatch/verify-postgis.sh` + 台账 `work/mailbox/tasks/TASK-258/handoff.md`（零触碰生产 Java 代码/其余模块/配置/pom/web） |
| 核心实现 | R1 新增 MapMatchPostgisIT 真实 PostGIS 集成测试（环境感知守卫、GIST 索引断言、正负样本轨迹匹配通过率与半径封顶）；R2 新增纯 JVM MapMatchSqlContractTest 契约测试（surefire mapmatch 35→36 只增不减）；R3 新增自动化复验脚本 verify-postgis.sh |
| 门禁要求 | 七门禁全量逐位（surefire 63/78/221/321/203/110/35 只增不减，mapmatch 35→36 净增 1 契约测）+ 契约门（判据 B 机器比对）+ token 门 29 项逐位持平（期望值口径：C-02 树 = 2133 + 29 = 2162）+ 词面门四 locale ZERO_HIT + 探针阳性对照 + checkstyle delta=0 + diff --check 净；双提交 C-01/C-02 |
| 受限预登记 | spec §5 三项：MapMatchPostgisIT 依赖容器环境在缺省时 assume 优雅跳过；7780 条路网切片覆盖上海核心区域；生产代码 MapMatchService 零改动 |
| 实施与门禁 | C-01 `97ddde9`（fix(mapmatch)，恰 3 文件 +563 行：MapMatchPostgisIT 真实 PostGIS 集成测试 5 用例全绿 + MapMatchSqlContractTest 纯 JVM 契约测试 + verify-postgis.sh 100755 脚本）+ C-02 `68afd35`（台账 1 文件 +49 行）；七门禁全绿：offline 七模块 rc=0（63/78/221/321/203/110/36，mapmatch 35→36 净增 1 契约测全绿、其余六模块完全持平、零失败零跳过）、真机 PostGIS 四步核验通过且 MapMatchPostgisIT 5 用例实跑全绿（1.419s，0 失败 0 跳过，EXPLAIN 命中 idx_road_edge_geom 无 Seq Scan）、契约门纯净 worktree 判据 B 4 路径全等 rc=0、token 门基线 2133 逐位持平 + 终态 2162 精确吻合期望值、词面门四 locale ZERO_HIT + 探针阳性对照、checkstyle delta=0、diff --check 净（新文件 LF） |
| 指导亲核 | FULL 档亲核三块全 PASS：①Git 摘要（porcelain 空、恰 2 笔、diff 4 文件与 handoff §2 逐位一致、零推送）；②定向抽查（MapMatchPostgisIT 5 用例全绿、MapMatchSqlContractTest 常量反射锁定与列序对齐、verify-postgis.sh 脚本可执行权限 100755）；③一致性裁决（surefire 净增与契约测落位完全吻合，口令漂移与首轮假绿排查如实登记） |
| 独立复核 | 五块全 PASS 零阻断（块一 4 笔提交链全等；块二红线与零触碰 4 项全 PASS；块三双端门禁实测 7 项亲自复跑全 PASS：surefire 63/78/221/321/203/110/36、真机 PostGIS 5 用例真跑非跳过、契约门纯净 worktree rc=0、token 门 2162 精确持平、词面门四 locale ZERO_HIT+探针对照、checkstyle delta=0 且新文件不在扫描面、diff 净、ls-files 纯 LF；块四证据链核对 PASS，独立 JDBC 探针反向坐实口令漂移排查结论；块五只改清单三向全等 4=4=4）；非阻断 4 项集中登记封盘笔（N1 baseline 时点口径 / N2 提交信息措辞 / N3 机器负载耗时差异 / N4 脚本端口展示行模板） |
| 是否到达外部门槛 | **已到达本地封盘**：封盘批次（5 笔 = TASK-264 补记笔 `96caca4` + 立项笔 `4f3060f` + C-01 `97ddde9` + C-02 `68afd35` + 封盘笔）统一推送 origin/main，GitHub Actions CI 外部终验随后登记 |
| 状态 | **已终裁 ACCEPT（2026-10-18 指导侧）**：指导亲核三块 + 独立复核五块双 PASS 零阻断；4 文件白名单双向全等；E13 PostGIS 真实空间库复验端到端闭环；surefire 63/78/221/321/203/110/36 新基线确立；封盘批次统一推送 origin/main，待 CI 外部终验 |

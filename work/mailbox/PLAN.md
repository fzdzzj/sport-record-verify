# sports 项目优化计划（活动看板）

## 当前进度与外部基线

- **最新代码基线与分支**：`main` 分支，最新已封盘至 **TASK-232**（HEAD `ceec5c6` / `bf4c7b9`）。
- **会话无缝交接看板**：新主 Agent 接手状态与下一步动作见 [HANDOVER.md](HANDOVER.md)（原地滚动更新，免建新交接文档）。
- **CI 外部门槛状态**：GitHub Actions run `38586443704`（HEAD `aee5ca0`，web ✓31s / build ✓11m46s 全 job 绿）。包含全模块 clean verify、Checkstyle/SpotBugs/PMD 静态门、Docker 镜像构建与 Trivy 依赖扫描。
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





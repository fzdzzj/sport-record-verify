# TASK-238 handoff：运动轨迹全链路真实化与反作弊合理性——长轨迹全量突破、GPX完整导出、SVG等比投影与闭环判定合理性（MEDIUM 档）

## 1. 编号、基线与外部状态

- 任务编号：`TASK-238`（外约：`work/mailbox/tasks/TASK-238/spec.md`；本文件为回传）。
- 分级：**MEDIUM**（前后端协同：长轨迹分页拉取、GPX完整导出、SVG数学等比投影矫正、R4规则起征门槛）。
- 开工基线：`b1c0e3a`（main 分支，工作树干净；开工前仅本任务 `spec.md` 未跟踪）。
- C-01 实施笔：`b693fe9`（提交信息 `feat(web,verify): TASK-238 运动轨迹全链路真实化——全量长轨迹与GPX导出、SVG等比投影与折返起征保护（C-01 实施笔）`，恰 4 文件 = 白名单实施面全等）。
- C-02 台账笔：本 handoff 所在提交（`docs(mailbox)`，自指哈希沿 TASK-220/221/224/231/232/233/234/235/236/237 先例，实际以 `git log` 为准）。
- 两笔均**未 push、未建 PR**。

## 2. 只改清单（相对开工基线 b1c0e3a，恰 6 文件 = C-01 4 + C-02 2）

C-01（实施笔，恰 4 文件 = 白名单实施面全等）：

- web/src/pages/record-detail.page.vue
- verify-service/src/main/java/com/sportverify/verify/algorithm/rule/R4DistanceRule.java
- verify-service/src/main/java/com/sportverify/verify/config/VerifyProperties.java
- verify-service/src/test/java/com/sportverify/verify/algorithm/rule/R4DistanceRuleTest.java

C-02（`docs(mailbox)` 台账笔，恰 2 文件）：

- work/mailbox/tasks/TASK-238/spec.md
- work/mailbox/tasks/TASK-238/handoff.md

> 白名单外零触碰：生产后端六服务（`src/main` 与 `src/test`）、`api/`、`common/`、根 pom、`package.json`、`playwright.config.ts`、`work/mailbox/PLAN.md` 与 `work/mailbox/HANDOVER.md` 均零 diff；零新依赖。临时验证日志落 gitignored `.trae/tmp/`，不入库。

## 3. 一句话结论

长轨迹腰斩、SVG 拉伸变形与微短闭环误杀三个问题一次闭环：① `record-detail.page.vue` `load()` 首包 `size=500` 后若 `total > records.length` 自动循环分页按批追加拉取（每批 500，防死循环上限 20000 点对齐 DTO 契约），`points.value` 全量齐备，界面文案改为「共 {{ points.length }} 个轨迹点（完整加载）…」并移除旧 500 点截断警告，「导出 GPX」基于完整点集生成；② SVG 投影重构为局部等距等比投影：平均纬度余弦 `cosLat` 修正经度地面跨度 `spanX=(maxLng-minLng)*cosLat`，统一缩放 `scale=min(innerW/spanX, innerH/spanY)` 居中渲染（viewBox 800×400、PAD 16），正方形路线保持正方形、圆形操场保持正圆；③ `VerifyProperties.Rules.R4` 新增 `minCumulativeMeters=500.0` 起征门槛，`R4DistanceRule` 累计距离低于门槛直接返回 `null` 豁免微短闭环，超门槛后原比值判定逻辑一字未动，既有 `VerifyEngineTest.t5_returnTrackRejectedByR4`（2000m 折返）继续命中 R4 SOFT 拒绝；④ 新增 `R4DistanceRuleTest` 三用例（300m 短闭环豁免 / 2000m 折返命中 / 1000m-500m ratio=2.0 不命中）。全套门禁全绿：后端 BUILD SUCCESS 164 测 0 失败、前端 type-check 0 错误 / 32 单测 / build / e2e **12 passed**、契约门禁退出码 0。

## 4. 判别式与验收要点

- **R1 长轨迹全量拉取与 GPX 完整导出（绿）**：`load()` 以 `firstRes.data.total` 为全量基准，`while (allPoints.length < totalPoints && allPoints.length < 20000)` 循环 `curPage++` 拉取后续批次（每批 500），`nextBatch` 为空即 break 防死循环，`points.value = allPoints` 全量齐备；模板提示更新为 `共 {{ points.length }} 个轨迹点（完整加载）；起点绿、终点红，其余点为蓝，悬停查看 seq/speed/ts。`，旧 `<span v-if="points.length >= 500">` 截断警告已移除；「导出 GPX」`generateGpx(points.value)` 基于全量点集（`exportGpx` 门控与调用不变，点数完整即 GPX 完整）。
- **R2 SVG 局部等距等比投影（绿）**：`projected` 先过滤有效数值坐标（`validPoints`，悬停 title 对齐同源），`avgLat=(minLat+maxLat)/2` → `cosLat=cos(avgLat*π/180)` → `spanX=(maxLng-minLng)*cosLat`、`spanY=maxLat-minLat`；`innerW=800-32=768`、`innerH=400-32=368`；`scale=min(innerW/spanX, innerH/spanY)`（单轴退化时取非零轴缩放），`drawW/drawH` 居中偏移 `offsetX=16+(innerW-drawW)/2`、`offsetY=16+(innerH-drawH)/2`；点投影 `x=offsetX+(lng-minLng)*cosLat*scale`、`y=(H-offsetY)-(lat-minLat)*scale`（北在上）；全同坐标退化居中映射；正方形/圆形轨迹不再被压扁拉伸。
- **R3 R4 起征门槛与既有契约（绿）**：`VerifyProperties.Rules.R4` 新增 `minCumulativeMeters=500.0`（lombok `@Data` 自动 getter/setter，可配）；`R4DistanceRule.evaluate` 累加 `cumulative` 后 `if (cumulative < typeThreshold.getR4().getMinCumulativeMeters()) return null;`，`cumulative >= 500` 时原 `ratio = cumulative / straight`（`straight < 1e-6` 时 `Double.MAX_VALUE`）与 `ratio <= maxRatio(3.0)` 判定完全不变；`VerifyEngineTest.t5_returnTrackRejectedByR4`（累计≈2000m 折返）继续 R4 SOFT → REJECTED（score 60），`softOnlyHits_canBeConfiguredToPass` 等既有断言零回归。
- **R4 针对性单测（绿）**：`R4DistanceRuleTest` 三用例——① 短距离闭环 300m（<500m）且起终点严格重合 → `null`（起征保护生效，若无门槛其比值本为无穷大会误杀）；② 长距离折返 2000m（>500m）且起终点重合 → 命中 `R4_DISTANCE` SOFT，detail 含 `cumulative`/`straight 0 m`；③ 折线轨迹累计 1000m、直线 500m（ratio=2.0 ≤ 3.0）→ `null`。
- **R5 红线（绿）**：`git diff --name-only b1c0e3a` + untracked 恰 6 文件全等于 §2 清单（无多报无漏报）；零新依赖（复用既有 Vue 3 / Ant Design Vue / Spring Boot 配置体系）；白名单外文件零 diff；`PLAN.md`/`HANDOVER.md` 未触碰。

## 5. 验证命令与输出片段

1. **后端门禁**：`mvn -B -ntp -o -s .mvn-settings.xml clean test -pl verify-service -am`（`TMP/TEMP` 重定向 `.trae/tmp`，沿 TASK-224 起同款处置）→ 首跑红 1 例（见 D3），修正断言后重跑：`[INFO] Reactor Summary … 全部 SUCCESS`、`[INFO] Tests run: 164, Failures: 0, Errors: 0, Skipped: 0`、`[INFO] BUILD SUCCESS`；以 `*> log; exit $LASTEXITCODE` 捕获 `EXITCODE=0`（规避 `2>&1` 管道退出码伪象，见 D2/D3）。
2. **前端 type-check**：`pnpm --dir web type-check` → rc=0（`vue-tsc --noEmit -p tsconfig.app.json`，0 错误）。
3. **前端单测**：`pnpm --dir web test` → rc=0：`Test Files 4 passed (4)` / `Tests 32 passed (32)`（vitest）。
4. **前端构建**：`pnpm --dir web build`（同 TMP/TEMP 重定向）→ rc=0：`✓ built`（chunk >500 kB 为既有非阻断 advisory）。
5. **前端 E2E 全量**：`pnpm --dir web e2e`（同 TMP/TEMP 重定向）→ **`12 passed (45.9s)`，rc=0**（chromium 单 worker，vite preview 生产产物 + route-mock；`pnpm build` 由 webServer 命令内嵌执行）。
6. **契约门禁**：`bash scripts/verify/mailbox-contract.sh --open=TASK-238 --baseline=b1c0e3a`（`D:\git\Git\bin\bash.exe`，Git Bash 实跑）→ 退出码 0：判据 A 两件套齐（spec.md + handoff.md 均在）+ 判据 B 只改清单与实际改动集一致（`git diff --name-only b1c0e3a` 恰 6 文件全等）。

## 6. 未覆盖、遗留风险与不得推出的结论

- **长轨迹/GPX/投影未入 E2E 断言**：白名单内无对应 e2e 文件（`record-detail.page.vue` 交互由 type-check + build + 32 单测 + 代码评审承载；既有 12 条 E2E 全部保持全绿）；分页循环拉取的端到端行为（total>500 多批合并）后续如需可新开任务补 route-mock 长轨迹 E2E。
- **`--mode=online` / `--static`（Checkstyle/SpotBugs/PMD）CI 全流程未跑**（未 push、未建 PR），外部门禁待指导侧推动后补记。
- **GPX 完整性依赖 points.value 全量**：`generateGpx` 本身无改动（既有 `gpx.test.ts` 5 测覆盖结构），完整导出由 §R1 全量拉取保证；若后端分页接口返回的 `total` 与 `records` 口径不一致（罕见），循环以 `nextBatch` 空为终止兜底，不会死循环。
- **R4 起征门槛默认值可配**：`minCumulativeMeters=500.0` 为代码默认；Nacos/yml 可覆盖（`verify.rules.r4.min-cumulative-meters`），后续灰度如需调整门槛无需改代码。

## 7. 偏差登记

- **D1（行尾契约）**：本机 `core.autocrlf=true`，编辑器写入 bare LF，`git add` 时归一 CRLF（warning 属预期）；`git diff --numstat b1c0e3a` 复核全为行级小 diff（record-detail.page.vue 59+/28-、R4DistanceRule.java 8+/1-、VerifyProperties.java 2+/0-、R4DistanceRuleTest.java 新增 91 行），无整文件重写残迹。
- **D2（e2e 与 build 串行 + Maven 退出码捕获）**：沿 TASK-234 D3 / TASK-235 D4 / TASK-236 D2 / TASK-237 D2 先例，`pnpm --dir web build` 与 `pnpm --dir web e2e` 串行执行（CPU 抢占会导致 preview 未在 180s webServer 窗口内起服务）；Maven 经 `2>&1 | Tee-Object` 管道时进程退出码被 Nacos 连接失败的 stderr 噪音污染为 1（日志实为 BUILD SUCCESS），最终以 `*> 日志; exit $LASTEXITCODE` 重跑捕获 `EXITCODE=0` 为门禁结论。
- **D3（Maven 首跑红 1 例 = 新单测断言过严，非产品代码问题）**：`R4DistanceRuleTest.longReturnTrack_overMinCumulative_hitsR4` 首跑断言 `detail contains("2000")` 失败——haversine 弧长（R=6371km）与 `111320` 折算差异使累计实际为 1998m（`straight 0 m, ratio 999.99`，命中语义完全正确）；修正为语义断言（`rule=R4_DISTANCE`、`level=SOFT`、detail 含 `cumulative` 与 `straight 0 m`），`R4DistanceRule`/`VerifyProperties` 产品代码零改动，重跑全绿。
- **D4（投影 viewBox 常数按 spec §3.2 数学基准调整）**：spec §3.2 以内层 768×368（即 W=800、H=400、PAD=16）给出全部数学式，故 `W/H/PAD` 由旧 800/480/40 调整为 800/400/16；配速趋势图 `paceGeometry` 共用 `PAD` 随之 40→16（仅内边距变化，折线几何语义不变），轨迹 SVG `viewBox` 纵横比由 800:480 变 800:400——此为等比投影的必要参数，非功能回退。

## 8. 停止条件

白名单内实施与测试改动完成（C-01 恰 4 文件），验证命令全绿（后端 BUILD SUCCESS 164 测、前端 type-check 0 错误 + 32 单测 + build + e2e 12 passed），台账两件套落盘（C-02 恰 2 文件）并经外约门禁退出码 0；`PLAN.md` / `HANDOVER.md` 与白名单外文件零触碰。后续如需推进 CI push、长轨迹 E2E、门槛灰度调参，须新开任务，不在本任务扩大范围。

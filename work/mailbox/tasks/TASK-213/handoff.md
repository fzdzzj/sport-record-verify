# TASK-213 回传交接（记录轨迹查看页 SVG 投影，MEDIUM 档）

## 1. 编号、基线与外部状态

- 课题：add-track-view（记录轨迹查看页，纯前端 SVG 投影）。
- 分级：**MEDIUM**（零后端改动、零新依赖；指导侧亲核 + CI 外部终验收口，**免独立复核**）。
- 开工基线：`cc9ce4a`（派发笔，含提案三件套 proposal/spec-delta/tasks.json 与任务书）；开工前 `git status --porcelain` 为空，基线自洽。
- 离线基线（§6）：684 = 52/50/162/166/159/80/15。
- C-01 实施笔：`7e4be36`（`feat(web): 记录轨迹查看页SVG投影（TASK-213）`，恰 4 文件，+203 ／−0）；C-02 台账笔：本 handoff 所在提交（`docs(mailbox)`）。
- 两笔均**未 push、未建 PR**；CI 外部终验待指导侧推动推送后补记。

## 2. 只改清单（C-01 = 4 文件；C-02 = 恰 4 台账文件；两笔合计 8 文件 = 从 cc9ce4a 的改动集）

C-01（feat(web) 实施笔）：
- web/src/pages/record-detail.page.vue（新文件）
- web/src/pages/index.page.vue
- web/src/typed-router.d.ts
- README.md

C-02（docs(mailbox) 台账笔，本 handoff 所在提交）：
- spec/changes/add-track-view/tasks.json（勾选）
- work/mailbox/tasks/TASK-213/spec.md（§7 回填）
- work/mailbox/tasks/TASK-213/handoff.md（本新文件）
- work/mailbox/PLAN.md（纯追加）

> 六后端服务 / api / common / 父 pom / sql / ci.yml / mvn-verify.sh / mailbox-contract.sh / compose / web/package.json 全部零 diff（证据见 §5）。C-01 未落 client.ts：既有 `getRecordPoints(recordId,page,size)` 已满足 size=500 传参需求，无需新增或改动（偏差 D2）。

## 3. 一句话结论

Web 新增 `/record-detail` 页消费既有 `GET /record/api/records/{id}/points` 读取轨迹，用 min-max 归一化把 lat/lng 投影到固定 viewBox 的 SVG（polyline + 起点绿/终点红 + 逐点 circle 悬停 seq/speed/ts），摘要头经既有 `GET /{id}/verify-result`（失败降级为仅轨迹）；入口在首页「最近记录」行「查看轨迹」（recordId 经路由 query 传递，对齐 verdict 页范式）。归属由服务端 TASK-204 归属校验裁决（403/1002 → 权限错误态），前端零绕过、零本地身份判断。零后端、零新依赖、零 package.json 改动。全部 §2.5 判别式与 §6 门禁按 MEDIUM 档留证。

## 4. 判别式红绿（R1 / R2 / R3 / G1 / G2）

web 侧（`vue-tsc --noEmit` rc=0 + `pnpm build` 本机成功 + 代码审查）：

- **R1（绿）投影正确性**：`projected` computed 对 lat/lng 各自做 min-max 归一化——`fx=(lng-minLng)/lngRange`、`fy=(lat-minLat)/latRange`，`x=PAD+fx*innerW`、`y=H-PAD-fy*innerH`（翻转使北在上），固定留边 PAD=40、viewBox=800×480。**退化处理**：`lngRange<=0 || latRange<=0` 时取 0.5，落内层区正中——覆盖单点（latRange=lngRange=0）与所有点同坐标两种退化；单点时 polyline 不渲染（`v-if="points.length > 1"`），仅剩一个标记（i===0 首位 → 绿）。
- **R2（绿）归属链**：`load()` catch 分支 `if (e.status === 403 || e.code === 1002) permError.value = true` → 模板渲染权限错误态 `a-alert type=error`；前端**无任何本地 userId/role 判断**、无请求参数绕过路径（`getRecordPoints(recordId,1,500)` 仅传 recordId，userId 由网关注入），服务端为唯一权威。
- **R3（绿）分页单次拉取**：`getRecordPoints(recordId.value, 1, 500)` 单次调用（size=500，沿用 my-records 同口径）；`points.length >= 500` 时渲染「单次拉取上限 500 点，超出部分轨迹未显示（已知限制）」文案。
- **G1（绿）**：`pnpm --dir web run type-check`（vue-tsc --noEmit）rc=**0**；`pnpm --dir web run build`（vite build）本机成功（3283 modules transformed，16.52s）。
- **G2（绿）**：`git diff -- web/package.json` 空（零 diff）；`git diff --name-only cc9ce4a` 对六后端/api/common/父 pom/sql/ci.yml/mvn-verify.sh/mailbox-contract.sh/compose **全空**；`web/src/typed-router.d.ts` 含新路由 `/record-detail`（RouteNamedMap 与 `_RouteFileInfoMap` 双处）。

## 5. §6 门禁留证

1. **offline 全量**：唯一入口 `D:\git\Git\bin\bash.exe scripts/verify/mvn-verify.sh --mode=offline test` → **BUILD SUCCESS rc=0**，逐位 **684 = 52/50/162/166/159/80/15**（纯前端零后端改动，七模块计数与基线逐位不变）。
2. **契约门双态**：在途 `--open TASK-213 --baseline=cc9ce4a` rc=**0**（判据 A 两件套齐含 1 个待办进行中 = TASK-213）；收口无参 rc=**0**（见 §9）。
3. **token 29 项 2030**：见 §6。
4. **词面门**：CI-exact（`-n -I -iE`）以模式文件（显式 ERE、`|` 为交替）对改动 4 文件（C-01 集）`git grep` **ZERO_HIT rc=1**；合成探针阳性对照 `grep -n -I -iE -f <modefile> probe` **HIT rc=0**（命中探针第 2 行），证明 `-E/-i` 口径有效、无假阴性。
5. **`git diff --check`**（含 cached 与工作树）rc=**0**。
6. **行尾**：改文件保持基线各自身——`README.md` 保持 CRLF、`index.page.vue` / `typed-router.d.ts` 保持 LF；新文件 `record-detail.page.vue` 与 `handoff.md` 纯 LF + 末尾换行、无 BOM、无 CR（逐字节核验：CR 计数 0、末字节 LF）。
7. **web / 零依赖**：`vue-tsc --noEmit` rc=0 且本机 `pnpm build` 成功；`web/package.json` 零 diff 为 G2 证据。

## 6. token 时点读数

29 项受保护 token 以 tracked `git grep -cF` 全量计（SUM = 各 token 命中计数求和）：开工（cc9ce4a 基线）账单参照 = **2030**、post-C01 = **2030**、close = **2030**（只增不减；本课题新增文件对 29 项 token 命中 0）。

## 7. 实现要点

- **record-detail.page.vue**：`useRoute()` 取 `route.query.id`（对齐 verdict 页 `route.query.id` 范式），`onMounted` 有 id 时 `load()`。`load()` 先拉轨迹（size=500），成功后追加拉摘要（`getVerifyResult`，包在独立 try 内，失败仅置 `summaryDegraded` 不阻断轨迹）；轨迹请求 catch 区分 403/1002（权限态）与其它（普通错误态）。
- **投影**：`projected` 基于 `points` 派生 `{x,y}[]`，`polylinePoints` 拼接 `x,y` 串；circle `:r`/`:fill` 按首/末/中间三态（5/5/3、绿/红/蓝），`<title>` 输出 `seq/speed/ts`。
- **入口**：`index.page.vue` `recentColumns` 增 `{ title: '操作', key: 'actions' }`，`bodyCell` 增 `column.key === 'actions'` 分支渲染 `<a @click="goTrack(record.id)">查看轨迹</a>`；`goTrack(id)` → `router.push({ path: '/record-detail', query: { id: String(id) } })`；欢迎列表加一条轨迹页说明。verdict 页入口按任务书「可选」保持极简，未加。
- **typed-router.d.ts**：由 `pnpm build` 经 unplugin-vue-router 再生成，纯 13 行插入（`/record-detail` 路由 + `src/pages/record-detail.page.vue` 文件映射），其余零扰动。

## 8. 异常与偏差登记

- **D1（C-02 自指哈希）**：本 handoff 无法记录 C-02 自身提交哈希，沿 TASK-178/183/204/205/206/207/208/209/210/211 同型先例，以 C-02 实际提交哈希为准（`git log --oneline`）。
- **D2（client.ts 未改动）**：既有 `getRecordPoints` 提供 `size` 参数（默认 5），直接传 500 即可满足 R3，故 client.ts 无需新增调用；C-01 落 4 文件（落在 §4 白名单 4-5 目标下界内）。
- **D3（本机 build 成功）**：`pnpm --dir web run build` 本机成功（TMP/TEMP 重定向至仓内 `.trae/tmp` 规避 esbuild temp 清理问题），故 typed-router.d.ts 由真实构建再生成而非手改，CI web job 的 `git diff --exit-code -- web/src/typed-router.d.ts` 判据具备一致性前提。
- **D4（台账笔末尾换行）**：重写 `tasks.json` 时初次丢末尾换行，复补 LF 使 diff 仅含布尔翻转 7/7 行（无 "No newline at end of file"）。
- **停止条件（§3）**：无一条触发——改动未出白名单、无需服务端变更（points 端点与 size 参数既有）、路由约定沿用既有 Page/query 无基建冲突。

## 9. 收口门禁（C-02 后）

- 契约门**无参 rc=0**。
- token 收口态 repo 全量 `git grep -cF` SUM=**2030**。
- `git diff --check` rc=0。
- `git diff --name-only cc9ce4a` 对照恰 **8 文件**（C-01 4 + C-02 4）：对六后端/api/common/父 pom/sql/ci.yml/mvn-verify.sh/mailbox-contract.sh/compose 及 web/package.json **git diff 全空**。
- 工作树 `git status --porcelain` 干净（`.trae/` 排除、`.trae/tmp` 已清空）。

## 10. 未覆盖与不得推出的结论

- `--mode=online`、CI 全流程、真实网关注入 X-User-Id 的跨进程 403 链路、真实浏览器可视交互未跑；未 push、未建 PR，无 CI run；CI 外部终验待指导侧推动推送后补记（MEDIUM 档：指导亲核 + CI 外部终验收口）。
- 真实地图底图（Leaflet + OSM 瓦片）为设计性非目标（需外部瓦片源决策），本迭代以 SVG 归一化投影替代。
- 无性能/吞吐断言（任务书 §0 禁性能声明）。

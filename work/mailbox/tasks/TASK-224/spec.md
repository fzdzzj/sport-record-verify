# TASK-224: My-Records Filtering (P5, MEDIUM-small)

派发基线：TASK-223 已推送（origin=765b366，CI run 38497654651 结果以指导侧封盘笔回填为准——若红则本课题开工顺延至 D 修复落地）；封盘笔与派发笔随本课题批次推送，勿动。开工时工作树干净；offline 基线 52/50/173/217/159/80/26（收窄政策下 record 实测位=217，其余沿绿基线）；token 基线 SUM=2036。提案三件套=spec/changes/add-my-records-filter/。

## S0 hard constraints

- 红线零触碰：record-service/web 白名单外 src/main、六服务其他模块、api 模块（本课题**禁触**——过滤参数在 record-service 内绑定；recon 若认定必须动 api 即触发 S3 §4 停止）、pom、ci.yml、scripts、compose、sql（零 schema 零索引）、web/package.json、vite.config.ts；无新路由 → typed-router 零 diff。
- 既有语义零改动：无过滤时 /my 行为与基线逐字节等价（G1 既有用例零改动全绿承载）；identity 门/分页钳制/order 短语零弱化。
- 真实凭证零入库；命令行禁中文；MSYS sed -i 禁对 tracked 文件；D7 家法（TableInfoHelper 惯用法 + 新测试类隔离单跑）。

## S1 verified context（指导侧已知事实，执行侧核veri）

- /my 现状：GET /api/records/my，identity 与 /my 同构（auth 开 X-User-Id 缺头 1001；关 → userId 参数缺失 400）；pageMy 内 safePage/safeSize 钳制 + user_id eq + orderByDesc(createdAt)。
- SportType 整数枚举（RUNNING 默认等）；status 为校验态整数域（recon 列出合法集用于 400 校验）。
- web：index.page.vue 我的记录区（列表+分页+删除），client.ts getMyRecords。

## S2 preregistered design

- 过滤参数（全可选，缺省=不过滤，向后兼容）：sportType（整数枚举域校验）、status（校验态域校验）、startDate/endDate（record.startTime 上界/下界，start>end → 400）。非法枚举/越界 → 400（沿 @Min/@Max 严格先例）。
- wrapper 组合：sportType eq + status eq + startTime ge/le，null 跳过，与既有 user_id eq + orderByDesc(createdAt) + 钳制合成；不新增排序维度。
- web：过滤条（类型下拉+状态下拉+日期区间），任一变更 page 重置 1；client.ts 参数扩展。
- Non-Goals 见 proposal（排序/全文/持久化/admin/跨用户零涉及）。

## S2.5 discriminants

- R1 向后兼容：全参数缺省时 wrapper 与基线等价（既有用例零改全绿）。
- R2 各过滤维单启用 + 组合启用语义正确（单测断言 wrapper 条件集）。
- R3 非法值：枚举外/越界/start>end → 400；缺头 1001；auth 关缺 userId 400（identity 门不变）。
- R4 web：过滤条变更重置页码、参数入 query、空态/结果集缩减渲染；四件套承载。
- R5 api 模块零 diff、零 schema、零新端点。
- G1 record 实测只增、其余沿绿基线（政策首用，台账按政策口径登记）；G2 白名单全等；G3 门禁全套含 CI。

## S3 stop conditions

1. /my 现签名与 S1 记载不符（绑定风格/参数形态冲突）。
2. 收窄门禁下任何实测模块红。
3. 正确实现需触 api 模块或新 schema/索引。
4. 过滤与既有钳制/order 语义冲突需改基线行为。
5. 性能红旗（大用户量列表过滤超时）。

## S4 whitelist（recon 定稿锁死，预估 7-10）

record-service：RecordController、SportRecordService（或专用查询对象新文件）、测试 1-2；web：index.page.vue、client.ts、client.test.ts；README 1。台账 4 文件另计（C-02）。handoff §2 逐字符 git 全路径（D6 家法）。

## S6 gates（分级政策首用，2026-10-14 常备政策）

- **offline 收窄**：`mvn-verify.sh --mode=offline --pl record-service test` 实测 record 217→(217+n) 只增；其余六模块沿绿基线（origin CI 绿 + 白名单外零 diff 论证）登记。若 recon 触发全量条件（S3 §3）→ 升级全量逐位。
- 新测试类隔离单跑；--static=record-service（唯一声明三件套改动模块）；web 四件套（TMP 重定向 .trae/tmp；typed-router 零 diff）；契约双态（--open TASK-224 --baseline=<开工 HEAD> + 收口无参）；token 2036 持平；词面门 ZERO_HIT+探针正控；diff --check；行尾（新文件纯 LF 无 BOM，改文件保基线）。
- 独立复核四块（用户派发）；指导亲核后推送，CI 绿为收口前提。

## S7 backfill（C-02 后）

判别式红绿、门禁留证（含政策首用口径）、偏差 D1-Dn、未覆盖清单沿先例（真实浏览器过滤交互/真实 DB 过滤联调/--it/online）。

### 回填（2026-10-14，C-02 台账笔）

- **判别式红绿**：R1-R5 / G1-G3 逐条实证全绿，详细证据见 `handoff.md §4`。
- **门禁留证**：
  - offline 分级收窄门禁（政策首用，2026-10-14 常备政策）：`mvn-verify.sh --mode=offline --pl record-service test` 实测 `record-service` 由基线 217 增至 **226**（净增 9 测，0 Failures/Errors/Skipped，rc=0）；其余六模块按绿基线（origin CI 38497654651 全绿 + 白名单外零 diff 论证）登记为 `52/50/173/226/159/80/26`。
  - 新增单测隔离单跑：`RecordControllerTest`（33 测 rc=0，净增 4 测）、`SportRecordServiceTest`（72 测 rc=0，净增 5 测）。
  - 静态门禁：`--static=record-service` rc=0（0 Checkstyle violations / SpotBugs 0 错误 / PMD BUILD SUCCESS）。
  - web 四件套：`pnpm test` 4 套 28 测 rc=0（净增 2 测，TMP 重定向 `.trae/tmp`）、`pnpm type-check` 0 错误 rc=0、`pnpm build` 成功（3283 modules）rc=0、`git diff --exit-code -- web/src/typed-router.d.ts` 零 diff rc=0。
  - 契约门双态：在途 `--open TASK-224 --baseline=cdaa8b3` rc=0；收口无参 rc=0。
  - token 29 项：两点 2036/2036 持平（≥2034）。
  - 词面门：CI-exact ZERO_HIT（rc=1）+ 探针阳性对照 HIT（rc=0）。
  - 格式与行尾：`git diff --check` rc=0；改动文件保持基线 LF 与末尾换行无 BOM。
- **偏差**：D1（C-02 台账笔自指哈希沿先例）；D2（token/词面门/构建脚本等临时件落 gitignored `.trae/tmp`）；D3（Checkstyle ParameterNumber 8 参家法规约适配，Controller 保持恰好 8 参、Service 保持恰好 7 参）；D4/D5（offline 噪音与 build chunk 告警为既有非阻断）。明细见 `handoff.md §8`。
- **未覆盖清单**：真实浏览器过滤条交互与结果列表联动（沿 TASK-213/220/221/222/223 口径登记未覆盖）、真实 MySQL/Redis 跨服务过滤联调（--it 未执行）、--mode=online 与外部 CI。均沿先例口径登记，见 `handoff.md §10`。
- **停止条件**：S3 §1-§5 无一按字面触发（核对见 `handoff.md §8`）。

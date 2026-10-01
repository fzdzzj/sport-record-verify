# TASK-167 spec：把 17 个 allPass 在途变更的 delta 逐字并入主规格并归档（纯文档零代码；含 3 深 MODIFIED 链与 1 处基线 MODIFIED）

## 0. 硬约束与红线（继承 TASK-158/159/165/166 §0，逐字适用）
1. 本任务书是唯一权威。开工先逐位核对 §3；**任一不符 ⇒ 停手回报原文，不要自行解释、不要自行订正**。
2. 命令行**全程无中文**（含 grep/sed/awk 模式与 here-doc）；中文内容先写 `.trae/tmp/` 下 UTF-8 无 BOM 文件再用（临时文件一律以 `task167-` 前缀命名）；提交信息一律 `git commit -F <file>`（主题行＋空行＋正文要点）。
3. bash 一律写成 `.sh`（或 python `.py`）文件再用 `D:\git\Git\bin\bash.exe <路径>` / `python <路径>` 执行；禁 `bash -lc` 内联；需抓非零 rc 的段落不要放 `set -e` 下。本机 git 为 **2.20.1.windows.1**（无 `git restore`，放弃工作树改动用 `git checkout -- <path>`）。
4. Maven 唯一入口 `bash scripts/verify/mvn-verify.sh`；禁裸 mvn、禁 `MAVEN_OPTS`、**禁创建 `.mvn/maven.config`**（TASK-154/156/166 已三次踩坑；本轮也根本不需要）。
5. **不 push、不建 PR**、不 `git stash`、不 `git add -A`/`add .`（一律逐路径 add）。push 由指导侧在验收后统一办理：**指导侧将把订正笔 `cd4763e` 与本轮全部提交作为同一批次推送**，执行侧不得代推。
6. **本轮零生产行为变化、纯文档**：不改任何 `.java`/`.kt`/`.yml`/`.properties`/`.sql`/pom/`scripts/**`/`docs/**`/前端文件；不跑负载、不起四服务、不碰 Docker（本轮零数据库依赖，**无需启动 Docker**，`task131-scratch-mysql`、`sport-verify-mysql`、`sport-verify-postgis` 一律零触碰）。
7. **零触碰名单**（git status 开工快照里必须原样存在、收口快照里必须原样未动）：`?? spec/changes/add-verify-degrade-status-index/`（未跟踪目录，含 verification.md，任何任务不得收编）；以及 §2 排除名单的 7 个在途目录（**不得并入、不得改其任何文件**）。
8. 不翻案、不改写任何已入库结论与数字（TASK-143~166，特别是 TASK-153/154 NO-GO、TASK-161/162/164 未定支与封盘结论）。既有 17 个目录的 tasks.json 既有记录**只允许在数组尾部追加「归档阶段」元素，一字不改既有内容**。
9. 任何门槛未跑一律写「未覆盖」；**skip ≠ pass**；`work/mailbox/PLAN.md` **纯追加**，不得改动任何既有行（含 L4 与 L4 内的 push/领先叙事——那是指导侧订正笔的职责）。
10. 主规格既有内容（含头部清单既有 41 条、L50 断言句、L51 例外说明行、全部既有需求与变更历史条目）**一字不改**，只允许本任务书 §5 规定的插入与替换。

## 1. 本轮唯一目标（三件，缺一即未收口）
- **A 并入与归档**：把 §4 固定顺序的 **17 个** allPass=True 在途目录的 spec-delta 需求**逐字**并入 `spec/specs/sport-record-verify/spec.md`，并把 17 个目录 `git mv` 进 `spec/changes/archive/`；每目录一笔业务提交（C-01..C-17），**archive⇔merged⇔listed 三方不变式在每一笔提交后都成立**。
- **B 链与基线的确定性**：3 深 MODIFIED 链按 §5.3 固定链序合并，**终态文字 = `prove-verify-outbox-mark-sent-attribution` delta 的 MODIFIED 块（A2，17 行）**；`shorten-submit-db-footprint` 对基线需求「轨迹提交幂等」的 MODIFIED 按 §5.4 替换（**剔除 `**Previous**` 行及其后空行**，目标块 17 行），剔除口径写进 handoff 口径说明。
- **C 台账**：PLAN.md 纯追加「验收记录：TASK-167」节；新建 `handoff.md` 六项交回物；本任务书 spec.md 原样入库（执行侧一字不改）。

## 2. 五维自核结果（指导侧 2026-10-01 于 `cd4763e` 亲测；执行侧须用 §11 脚本自行复核后方可采信，两组数字必须一致，不一致即停手回报）

### 2.1 五维总表（24 个在途目录；steps=N/M 与 allPass 是两个独立维度，必须两者都报）
| # | 目录 | steps | passes 序列 | allPass | ADDED 块(行数) | MODIFIED 块(行数) |
|---|------|-------|------------|---------|----------------|-------------------|
| 1 | add-verify-degrade-status-index | 0/6 | FFF | **False** | 2 | 0 |
| 2 | add-verify-outbox-relay-batch-mark | 12/15 | TTTT**F**T | **False** | 0（delta 为非标准格式，见 2.4） | 0 |
| 3 | add-verify-outbox-relay-send-concurrency | 10/10 | TTTTT | True | 4：18/39/19/20 | 0 |
| 4 | measure-head-bottleneck-attribution | 14/15 | TTTTTT | True | 3：26/26/11 | 0 |
| 5 | measure-submit-db-wait-evidence | 13/13 | TTTTT | True | 3：17/17/22 | 0 |
| 6 | measure-submit-pool-capacity | 13/13 | TTTTTT | True | 3：15/15/15 | 0 |
| 7 | measure-verify-event-stage-lag | 11/11 | TTTTT | True | 3：15/15/15 | 0 |
| 8 | measure-verify-mark-sent-admin-window | 9/9 | TTTTT | True | 2：15/15 | 0 |
| 9 | measure-verify-mark-sent-spring-paired-cost | 10/10 | TTTTT | True | 2：17/15 | 0 |
| 10 | measure-verify-outbox-mark-sent-cost | 10/10 | TTTTT | True | 1：23 | 1：26（=A1） |
| 11 | measure-verify-outbox-relay-cost | 11/11 | TTTTT | True | 2：18（=A0 链基）/18 | 0 |
| 12 | prove-verify-mark-sent-wait-attribution | 10/10 | TTTTT | True | 2：15/9 | 0 |
| 13 | prove-verify-outbox-batch-mark-safety | 10/10 | TTTTT | True | 2：15/15 | 0 |
| 14 | prove-verify-outbox-mark-sent-attribution | 10/10 | TTTTT | True | 2：18/10 | 1：17（=A2 终态） |
| 15 | prove-verify-outbox-mark-sent-spring-wiring | 10/10 | TTTTT | True | 2：17/9 | 0 |
| 16 | prove-verify-outbox-relay-concurrency-scaling | 10/10 | TTTTT | True | 2：15/15 | 0 |
| 17 | prove-verify-outbox-relay-drain-rate | 16/16 | TTTTTT | True | 2：16/15 | 0 |
| 18 | prove-verify-outbox-relay-pool-concurrency-scaling | 11/11 | TTTTT | True | 2：21/15 | 0 |
| 19 | resume-verify-outbox-mark-sent-server-event | 4/9 | FFFFF | **False** | 2 | 0 |
| 20 | shorten-submit-db-footprint | 17/17 | TTTTTT | True | 3：26/15/18 | 1：19（目标 17，见 §5.4） |
| 21 | update-verify-outbox-relay-delay | 7/8 | TT**F**T | **False** | 2 | 0 |
| 22 | measure-verify-outbox-mark-sent-server-event | 5/10 | FFFFF | **False** | 2 | 0 |
| 23 | prove-verify-outbox-relay-concurrency-pool-drain | 12/15 | TTTT**F**T | **False** | 1 | 0 |
| 24 | prove-verify-outbox-relay-interval-repeatable | 11/12 | TTTT**F**T | **False** | 2 | 0 |

### 2.2 链序（唯一跨目录同题组）
需求标题「relay 可选诊断不得改变可靠投递语义」横跨 3 个目录，构成**唯一**的 3 深 MODIFIED 链：
`measure-verify-outbox-relay-cost`（ADDED 链基 A0，18 行）→ `measure-verify-outbox-mark-sent-cost`（MODIFIED，A0→A1，26 行）→ `prove-verify-outbox-mark-sent-attribution`（MODIFIED，A1→A2，17 行）。**必须按此相对顺序合并，终态取 A2**。其余 16 目录之间**零同题、零交叉引用**（指导侧已对 17 个入选 delta 全文扫描其余 7 个目录的 13 个需求标题与全部 `（见「…」）` 引用：均 0 命中）。

### 2.3 shorten 基线核对（冲突即停判据，指导侧已核通过）
`shorten-submit-db-footprint` MODIFIED 的目标题「轨迹提交幂等」在主规格**存在且唯一**（occ=1，L739，块 L739–L756 共 18 行）。基线未被覆盖的证据：该块含「THEN 记录落库，状态置 SUBMITTED」且**不含** `VERIFYING`——与 delta `**Previous**` 行描述的旧文一致 ⇒ **不触发冲突即停，可并入**。执行侧开工须复核同一判据；若块内已含 `VERIFYING` 或标题 occ≠1 ⇒ 停手回报。

### 2.4 最小安全合并集（入选 17 / 排除 7，判据五条）
入选判据（五条同时满足）：① `allPass=True`；② delta 为标准格式（`## ADDED/MODIFIED Requirements` + `### Requirement:`）；③ MODIFIED 的基线题在主规格存在且未被改写（链成员按链内前一状态算）；④ MODIFIED 链成员全部入选，链序固定；⑤ 非未跟踪零触碰目录。**按此判据入选 §4 列出的 17 个目录（上表 #3~#18、#20）。**
排除 7 个（一律不得并入、不得改动）：
- `add-verify-degrade-status-index`：allPass=False（0/6，从未开工）＋ 未跟踪零触碰目录。
- `add-verify-outbox-relay-batch-mark`：allPass=False（第 5 项三步为自身登记的「未授权，不执行」）；且 delta 为非标准格式（`## 2. 需求增量（Delta）` + `### ADDED Requirement:` 标记）⇒ 并入需先定格式转换判据，另轮处理。
- `measure-verify-outbox-mark-sent-server-event`（5/10）、`resume-verify-outbox-mark-sent-server-event`（4/9）、`update-verify-outbox-relay-delay`（7/8）：allPass=False。
- `prove-verify-outbox-relay-concurrency-pool-drain`（12/15；M2 门未达，UNDETERMINED）、`prove-verify-outbox-relay-interval-repeatable`（11/12）：allPass=False（同为自身登记的未授权块）。

## 3. 起点与开工读数（指导侧 2026-10-01 亲跑值）
- `HEAD = cd4763e33db2340ee45d4fbe2e94d383cbe67d8e`；`origin/main = fcc1f7e3cfe5ac2d96bf6c20692b5896424730fd`；`git rev-list --left-right --count origin/main...main` = **`0	1`**（唯一未推笔 = 指导侧 PLAN L4 订正笔 `cd4763e`，将随本批一起推）。CI run `36808102571`（HEAD `fcc1f7e…`）conclusion=`success`＝第六次外部门槛（见 PLAN L4）。
- 工作树脏项**仅** `?? spec/changes/add-verify-degrade-status-index/`（零触碰）＋本任务目录。开工先存 `git status --porcelain` 快照。
- 主规格 `spec/specs/sport-record-verify/spec.md`：**2806** 行 / **121** 个 `### Requirement:` / bytes=**114668** / CR==LF==**2806** / bareLF=0 / 末 2 字节 **`0d 0a`**。头部提案清单 **41** 条（L8–L48 连续 bullet）、L49 空行、**L50** 断言句、**L51** 例外说明行。`spec/changes`：在途 **24** / archive **43**。
- 结构锚点（行号仅定位，**以标题结构为准**）：`### Requirement: 轨迹提交幂等` L739（块 L739–L756，18 行，下一标题 L758 `### Requirement: 漂移预处理`）；`### Requirement: 校验事件与幂等` L901；`### Requirement: 判定事件可靠投递` L947；`### Requirement: 重试耗尽事件不得阻塞后续可投递事件` L988；**插入锚点 `### Requirement: 提交事件异步发布` L1045**（occ=1）。主规格中 `### Requirement: relay` 命中 **0**、`verify.outbox` 命中 **0**。
- `work/mailbox/PLAN.md`：**1339** 行 / CR=**0** / bytes=**355253**（纯 LF）。
- `--mode=offline`：指导侧本轮未复跑（`cd4763e` 只改 PLAN.md 文本）；以 TASK-166 收口实测 **rc=0 / 36/41/33/103/137/59/10** 为参考值，**执行侧开工必跑**并以其为 G5 首跑基线（若 ≠ 参考值且原因不明 ⇒ 停手回报；已知跨盘 surefire 偶发按 TASK-164 处置：留红档、复跑一次、两次都披露）。
- 环境：磁盘 D 盘 Free=**194356822016** 字节（≈181.0 GiB，门槛 ≥100 GB）；sports 的 java 进程 **0**；git **2.20.1.windows.1**；本轮**无需 Docker/MySQL**。
- 词面门正则自 ci.yml 现场提取：len=**26**、pipes=**7**（8 分支）。**任何入库文件不得内嵌该正则字面量，连禁词本身的字面形态也不得写进任何新建文件。**
- 受保护数字 token（**行数命中法**，scope=`work/mailbox/PLAN.md`，指导侧于 `cd4763e` 实测 base）：`13.4`=12、`18.0`=14、`73.93`=13、`68.8`=9、`6315`=10、`1.8612`=9、`3.3066`=9、`5.7056`=9、`9.408`=9、`36525962432`=9、`36586847965`=8、`36438897772`=9、`36399582548`=8、`36098038547`=8、`36808102571`=1、`36736221648`=7、`2806`=14、`598`=8 ⇒ 收口**不得减少**。
- 游离 CR 清单（移名必须字节保真，**不得顺手修**）：以下 6 个目录的 `proposal.md` 与 `spec-delta.md` 各含 **1** 个尾 CR（位于倒数第 2 字节，即末行 CRLF）：`measure-submit-pool-capacity`、`measure-verify-event-stage-lag`、`measure-verify-mark-sent-admin-window`、`measure-verify-outbox-relay-cost`、`prove-verify-mark-sent-wait-attribution`、`prove-verify-outbox-batch-mark-safety`。另 `prove-verify-outbox-mark-sent-spring-wiring/tasks.json` 含 **96** 个 CR（近全 CRLF）。其余 17 目录全部文件 CR=0。

## 4. 合并集固定顺序（17 目录；每目录一笔业务提交 C-01..C-17）
顺序 = 目录名字母序，**唯一例外是链序强制** `measure-verify-outbox-relay-cost` 先于 `measure-verify-outbox-mark-sent-cost`（字母序本相反）。下表插入行数 = 该目录 ADDED 块行数和 + 块间/块后空行（=块数）；「累计行」为该笔提交后主规格应达行数（含该笔 +1 头部清单 +1 变更历史）：

| 提交 | 目录 | 插入 | 替换 | 累计行 |
|------|------|------|------|--------|
| C-01 | add-verify-outbox-relay-send-concurrency | +100 | — | 2908 |
| C-02 | measure-head-bottleneck-attribution | +66 | — | 2976 |
| C-03 | measure-submit-db-wait-evidence | +59 | — | 3037 |
| C-04 | measure-submit-pool-capacity | +48 | — | 3087 |
| C-05 | measure-verify-event-stage-lag | +48 | — | 3137 |
| C-06 | measure-verify-mark-sent-admin-window | +32 | — | 3171 |
| C-07 | measure-verify-mark-sent-spring-paired-cost | +34 | — | 3207 |
| C-08 | measure-verify-outbox-relay-cost | +38 | — | 3247 |
| C-09 | measure-verify-outbox-mark-sent-cost | +24（ADDED 23+空行） | A0→A1（−18+26） | 3281 |
| C-10 | prove-verify-outbox-mark-sent-attribution | +30（18/10+空行） | A1→A2（−26+17） | 3304 |
| C-11 | prove-verify-mark-sent-wait-attribution | +26 | — | 3332 |
| C-12 | prove-verify-outbox-batch-mark-safety | +32 | — | 3366 |
| C-13 | prove-verify-outbox-mark-sent-spring-wiring | +28 | — | 3396 |
| C-14 | prove-verify-outbox-relay-concurrency-scaling | +32 | — | 3430 |
| C-15 | prove-verify-outbox-relay-drain-rate | +33 | — | 3465 |
| C-16 | prove-verify-outbox-relay-pool-concurrency-scaling | +38 | — | 3505 |
| C-17 | shorten-submit-db-footprint | +62（26/15/18+空行） | 基线→目标（−18+17） | 3568 |

终态：主规格 **3568** 行 / **161** 个 `### Requirement:`（121+40）/ 头部清单 **58** 条 / archive **60** 目录 / 在途剩 **7** 目录。C-18 = 台账笔（PLAN 纯追加 + TASK-167 两件套入库），不再触主规格。

## 5. 并入细则（逐字判据；行号均为起点实测值，仅作定位，以结构规则为准）

### 5.1 插入锚点与空行规则（适用于全部 17 目录的 ADDED 块）
- **唯一插入锚点**：主规格标题行 `### Requirement: 提交事件异步发布` 的**紧上方**。每笔提交把自己的块插在这里 ⇒ 多笔自然按 C-01→C-17 自上而下堆叠成一段连续 run。
- 插入单元 = 「需求块 + 恰 1 个空行」；同目录多块之间也恰 1 个空行（即：块₁ 空行 块₂ 空行 … 块ₖ 空行，随后是既有标题行）。
- **块内容逐字照 delta**（EOL 归一后 cmp 必须 rc=0）：delta 的 `## ADDED Requirements` / `## MODIFIED Requirements` 分组标题及其前后空行**不搬**；块 = `### Requirement: <题>` 行起至下一 `###`/`##`/`---` 前的最后一行非空行。delta 是紧凑风格（`#### Scenario:` 下无空行），主规格旧块是宽松风格——**逐字规则优先，不得"修"成旧风格**（TASK-159 的 L988 块即紧凑风格，先例已成立）。
- 行尾必须转 CRLF 且用幂等配方 `sed 's/\r*$/\r/'`（先剥再补）；**禁** `cat`/`head`/`tail` 直接拼接搬块、**禁** `sed 's/$/\r/'`。搬完每个块 CR==LF==块行数、双 CR 出现 0 次。
- 每个新需求标题在主规格 occurrences 必须 = **1**（40 个新题全部如此；若出现 2 说明贴重复）。

### 5.2 纯 ADDED 目录（C-01..C-08、C-11..C-16，共 14 笔）
按 §4 表逐块插入；每笔提交的主规格 diff（`git diff -U0 <上一笔> <本笔> -- spec/specs/.../spec.md`）必须**无任何非空删除行**（纯插入；git 若把相邻空行滑进 hunk 产生 `-空行/+空行` 对，允许，但非空删除行数必须 = 0）。

### 5.3 三深 MODIFIED 链（C-08/C-09/C-10）
- C-08 把 relay-cost 的 2 块（A0=18 行 + 「relay 成本结论必须保留周期与事件两种口径」=18 行）按 §5.1 插入。
- C-09：①插入 mark-sent-cost 的 ADDED 块「markSent 归因以同一次调用的嵌套证据为准」（23 行）于其 run 位；②**标题定位替换** A0 块（blk 提取自上一笔的主规格，18 行）为 mark-sent-cost delta 的 MODIFIED 块 A1（26 行）。替换 hunk 形如 `@@ -P,18 +Q,26 @@`，被删 18 行 EOL 归一后必须与 A0 **cmp rc=0**。
- C-10：①插入 attribution 的 2 个 ADDED 块（18/10）于其 run 位；②替换 A1（26 行）为 attribution delta 的 MODIFIED 块 **A2（17 行）**，被删 26 行必须与 A1 cmp rc=0。
- **终态判据（收口复核）**：主规格中「relay 可选诊断不得改变可靠投递语义」occ=**1**；blk 提取该块 EOL 归一后与 A2 **cmp rc=0**；A0/A1 文本在主规格不再出现（全块 cmp 已保证）。

### 5.4 shorten 基线 MODIFIED（C-17）
- 先按 §2.3 复核基线（基线块必须含「状态置 SUBMITTED」且不含 `VERIFYING`）。
- 插入 3 个 ADDED 块（26/15/18）于其 run 位（run 末尾）。
- **标题定位替换**基线块（18 行）为**目标块 17 行** = delta 第 71 行（`### Requirement: 轨迹提交幂等`）与第 **74–89** 行拼接；即**剔除第 72 行 `**Previous**：…` 与第 73 行空行**。理由（写进 handoff 口径说明与 C-17 提交正文）：`**Previous**` 行是对旧文的变更注记而非规格文本，主规格全部 121 个既有块与 0 处 `**Previous**` 佐证该惯例；基线核对本身已用 Previous 行完成。
- 判据：替换后 blk(轨迹提交幂等) EOL 归一与目标 17 行 **cmp rc=0**；该题 occ=1；目标块含 `VERIFYING`（新语义）与「事务提交后发布 SUBMITTED 事件」。

### 5.5 头部提案清单 +17（随各自 C-i 提交）
在清单末条 `- fix-verify-outbox-poison-head-of-line（…）`（起点 L48）之后逐笔追加，**必须用全角括号 `（）`**（提取器靠 `^- ` + 全角括号截断）。指导侧拟好的 17 条（可直接逐字采用；若自拟，数字串必须能在对应目录 proposal.md/tasks.json 中逐字找到）：
```
- add-verify-outbox-relay-send-concurrency（outbox relay 批内可选并发投递，默认关闭且串行路径等价）
- measure-head-bottleneck-attribution（优化前先做同负载归因，一次只改一类因素）
- measure-submit-db-wait-evidence（提交耗时归因须对齐真实计时边界）
- measure-submit-pool-capacity（池容量对照单因素与池包装关闭语义）
- measure-verify-event-stage-lag（判定与榜单事件分段归因与积压分账）
- measure-verify-mark-sent-admin-window（同窗聚合读数须与实际 relay 负载计数闭合）
- measure-verify-mark-sent-spring-paired-cost（markSent 配对成本先经隔离真库判别）
- measure-verify-outbox-relay-cost（relay 可选诊断语义保持与成本双口径）
- measure-verify-outbox-mark-sent-cost（markSent 计时边界不重计不漏计）
- prove-verify-mark-sent-wait-attribution（markSent 等待归因先证单线程计数对应）
- prove-verify-outbox-batch-mark-safety（批末标记候选先证可靠投递语义）
- prove-verify-outbox-mark-sent-attribution（markSent 内部测量先经隔离真实装配证明）
- prove-verify-outbox-mark-sent-spring-wiring（markSent 探针须在真实 Spring 路径受判别）
- prove-verify-outbox-relay-concurrency-scaling（markSent 并发标度真库预注册裁决，正标度仅必要条件）
- prove-verify-outbox-relay-drain-rate（relay 净投递能力以排空斜率裁决，间隔 500ms 落地）
- prove-verify-outbox-relay-pool-concurrency-scaling（池内并发标度真实 Hikari 池裁决，仅必要条件）
- shorten-submit-db-footprint（提交事务不落不可见中间态，SQL 展示默认关闭）
```
L50 断言句与 L51 例外说明行**原文一字不改**（只允许位移；例外说明行仍为真：60−58=同样那 2 项）。

### 5.6 变更历史 +17 条（随各自 C-i 提交，主规格文件末尾追加）
格式照既有条目：`- **<目录名>**：<正文>引用变更 spec/changes/archive/<目录名>/。`。每条正文必须：① 一句话主题与机制（自该目录 proposal.md 派生）；② **照抄或忠实压缩该 proposal 自带的结论状态与反过度声称口径**（去 proposal 原文取，不凭记忆；例如两个并发标度目录必须保留「正标度结论仅是必要条件且不构成实施授权」、drain-rate 必须保留「不得换算成 P50 改善」类口径、TASK-153 NO-GO 相关目录必须保留「不翻案」表述）；③ **新增数字约束**：条目中出现的每个数字串（含小数）必须能在该目录的 proposal.md / tasks.json / spec-delta.md 中逐字 `grep -F` 到（回传核对清单）。
四个特殊目录的强制要素：`measure-verify-outbox-mark-sent-cost` 与 `prove-verify-outbox-mark-sent-attribution` 的条目必须写明三深链合并口径（前者：按链序第 2 位替换 relay-cost 的 ADDED 文本；后者：链终态，本条目写入时主规格该题文本即其 delta MODIFIED 块）；`shorten-submit-db-footprint` 的条目必须写明「MODIFIED 既有基线需求轨迹提交幂等，`**Previous**` 注记行按惯例剔除」且登记其代码已在 TASK-139/140 落地、`SS_SQL_SHOW` 与提交路径默认态保持关闭；`measure-verify-outbox-relay-cost` 条目写明其 ADDED 是三深链的链基。

### 5.7 归档移名与 tasks.json「归档阶段」追加（随各自 C-i 提交）
- `git mv spec/changes/<dir> spec/changes/archive/<dir>`（3 文件整体移名；两个 `.md` 必须呈 **R100**，字节保真——§3 游离 CR 清单里的 7 个文件一个字节都不许变）。
- 在 archive 侧 `tasks.json` 数组**尾部**追加 1 个「归档阶段」元素（TASK-127/158/159 先例）：`{"number": <既有末号+1>, "category": "归档阶段", "task": "TASK-167 将本变更的 spec-delta 需求按固定顺序逐字并入主规格并归档本目录（三深链终态/基线 MODIFIED 规则见 TASK-167 spec.md §5）", "steps": [{"step": "delta 的 ADDED/MODIFIED 需求逐字并入 spec/specs/sport-record-verify/spec.md（EOL 归一 cmp rc=0；标题 occ=1）", "completed": true}, {"step": "本目录 git mv 入 spec/changes/archive/，头部提案清单与变更历史各 +1，验收记录见 work/mailbox/PLAN.md 的 TASK-167 节", "completed": true}], "passes": true}`。拼接方式：末元素收括号 `  }` 加逗号后插入新元素（diff 上表现为恰 1 行 `  }`→`  },` 的修改 + 纯追加行）；改完 `python -c "import json;json.load(open(<path>,encoding='utf-8'))"` 必须 rc=0；`git diff` 上该文件**除那 1 行外零删除**。注意 `prove-verify-outbox-mark-sent-spring-wiring/tasks.json` 是近全 CRLF 文件，拼接保持其原有字节不动、追加行用 LF 即可（JSON 有效性为准）。

## 6. 只改清单（超出即红；执行侧 handoff 的「只改清单」节按此生成）
允许改动的路径全集：
1. `spec/specs/sport-record-verify/spec.md`（§5 规定的插入/替换/追加，共 17 笔）；
2. 17 个目录的移名（旧路径删除 + archive 侧新路径，`spec/changes/<17 目录>/` → `spec/changes/archive/<17 目录>/`，两个 `.md` R100）；
3. 17 个 archive 侧 `tasks.json`（§5.7 追加）；
4. `work/mailbox/PLAN.md`（纯追加）；
5. `work/mailbox/tasks/TASK-167/spec.md`（本文件原样入库，执行侧一字不改）；
6. `work/mailbox/tasks/TASK-167/handoff.md`（执行侧新建）。
**handoff「只改清单」节的生成公式**：该节开头标题须含「只改」二字且**紧跟清单本体**（下一标题前不得再有小节），每行一个完整仓库路径；内容 = `git diff --name-only <起点SHA>` 的逐行输出 ∪ 两个未跟踪任务文件（spec.md/handoff.md），**不得手工增删**；`?? spec/changes/add-verify-degrade-status-index/` 与 `.trae/**` **不进清单**（前者是既有脏项，在途契约跑的过冲里单独逐行说明）。禁 bare 文件名（如 `tasks.json`），必须全路径。

## 7. 收口门槛（每条都回传实测退出码与关键读数原文，不接受散文）
- **G0 起点核对**：§3 全部 SHA/计数/锚点逐位一致；不符即停手。
- **G1 五维自核复核**：用 §11 脚本复核 §2.1 全表 24 行（steps/allPass/ADDED/MODIFIED 计数）与 §2.2 链、§2.3 基线判据；与指导侧数字不一致即停手回报。
- **G2 逐字判据（硬）**：40 个新需求块逐一与各自 delta 块 EOL 归一 cmp rc=0、行数相等、occ=1；链终态块 ==A2（17 行）；shorten 目标块 17 行 cmp rc=0；被替换的 A0/A1/基线块提取自对应提交的父提交并与 delta/基线 cmp rc=0（A0/A1 从 delta 提取，基线块从 `git show <起点>:spec/.../spec.md` 提取）。
- **G3 删除纪律（硬，本轮最强判据）**：全区间 `git diff -U0 <起点> HEAD -- spec/specs/.../spec.md` 的**非空删除行**（EOL 归一、排序后）必须**恰好等于** A0∪A1∪旧基线块的非空行多重集（python 比对 rc=0）；除此之外任何非空删除行都判红。逐笔看：仅 C-09/C-10/C-17 允许非空删除，其余 15 笔非空删除行数必须 = 0。
- **G4 行尾与字节保真（硬）**：主规格收口 CR==LF==**3568**、bareLF=0、末 2 字节 `0d 0a`；逐笔累计行数与 §4 表一致；PLAN.md CR=**0**；17 对 `.md` 移名 R100 且 `git show archive:…` 与移名前 blob 逐字节一致（§3 的 7 个游离 CR 文件 CR 计数与偏移不变；spring-wiring tasks.json 追加后 CR 仍=96）；新写文件无 BOM。
- **G5 offline 双跑零扰动（硬）**：开工、收口各跑一次 `bash scripts/verify/mvn-verify.sh --mode=offline test`，两次 rc=**0**、七模块 **36/41/33/103/137/59/10** 逐位一致、Failures/Errors/Skipped 全 0（本轮零代码，这是"规格改动不扰运行"的证明）。
- **G6 词面自检（三态 + 正向对照，硬）**：正则现场自 ci.yml 提取（不得内嵌字面量），四形态（CI 原样无 LC_ALL / `LC_ALL=C` / `zh_CN.UTF-8` / `C.UTF-8`，均加 `--untracked` 且置于 pattern 之前）全 **ZERO_HIT rc=1**；任一 rc≠0/1 判门槛失败。正向对照 rc=**0** 且命中探针行（探针植非忽略路径、用完删除、`git status --porcelain` 逐字还原）。
- **G7 空白与提交**：`git diff --check` rc=0；18 笔提交各自 `git show --check` rc=0。
- **G8 契约（两形态）**：①在途（写 handoff 前）`bash scripts/verify/mailbox-contract.sh --open TASK-167 --baseline=<起点>` 记录 rc；②写完 handoff 后（C-18 前）无 `--open` 跑一次，**rc=1 属预期**，逐条过冲必须**仅**来自 `?? spec/changes/add-verify-degrade-status-index/`；③**C-18 后无参必须 rc=0**。
- **G9 集合不变式（硬）**：头部清单 **41→58**、archive **43→60**；`comm -23 <(archive 列表) <(清单条目)>` 恰为同样 **2 项**合法例外；`comm -13` 为空；**每笔 C-i 提交后**三个计数同步 +1（不变式逐笔成立）。
- **G10 受保护 token（硬）**：§3 的 18 个 token 以行数命中法 base vs 收口两测，**一律不减**。
- **G11 只改清单（硬）**：handoff 清单按 §6 公式生成；收口工作树除 `?? spec/changes/add-verify-degrade-status-index/`、`.trae/tmp` 新临时文件外必须干净；7 个排除目录与 17 个未入选文件 `git status` 零变化。
- **G12 逐笔结构（硬）**：每笔 C-i 的 `git diff --cached --name-status -M100%` 恰为：`M` 主规格 + `R100`×2 + `RM`×1（tasks.json）；C-18 恰为 PLAN + 两件套 3 文件。提交信息：C-i `docs(spec): 归档 <目录名> 并入主规格`（C-09/C-10/C-17 正文加口径说明），C-18 `docs(mailbox): 登记 TASK-167 验收记录与任务两件套`。C-01..C-17 自检通过后**不得 amend**；如收口补录，只允许对 C-18 `git commit --amend --no-edit`（台账笔不得自引其 SHA）。
- **G13 外部门槛栏**：PLAN 验收记录写「**未达外部门槛（本次不 push；指导侧验收后将连同订正笔 cd4763e 与本批 18 笔一并 push，由 CI 复验）**」。

## 8. 收尾硬条（缺一条即未收口）
sports 的 java 进程=0 → 复跑全量 `--mode=offline`（G5 第二跑）→ 词面门四形态+对照（G6）→ `git diff --check` + 逐笔 `git show --check`（G7）→ 契约③无参 rc=0（G8）→ G2/G3/G4/G9/G10 全绿复跑 → PLAN 纯追加验收记录（含：一句话结论；起点 SHA 与 18 笔提交 SHA 及每笔 `git diff --cached --name-only` 原文；shortstat；G0–G13 逐门实测；§2.1 复核表与差异说明；§5.4/§5.6 口径说明；18 token 计数对照表；历史条目数字成员核对清单；欠账=7 个排除目录与各自原因、`processRow`/`sendAndCollect` 统一欠账延续登记；外部门槛栏）→ handoff 六项交回物。

## 9. handoff.md 六项交回物
① 一句话结论（17 目录是否全并入归档、链终态与 shorten 替换是否 cmp 通过、不变式是否逐笔成立）；② 起点 SHA 与 18 笔提交 SHA、每笔 staged 清单原文（含 R100/RM 状态）、shortstat；③ 只改清单逐项对齐（§6 全集）＋零代码改动声明（`git diff <起点>..HEAD --stat` 中不得出现任何 `.java/.yml/.xml/.sql/.properties/pom/scripts/docs/web` 路径）＋零触碰声明（degrade-index 与 7 排除目录的 `git status` 原样快照）；④ G0–G13 全部实测退出码与关键读数原文（含 G3 的非空删除行多重集比对输出、G4 的逐笔行数账、G6 四形态 rc + 正向对照命中数 + 探针清除后的 `git status --porcelain`、G8 三次契约 rc 与过冲逐行）；⑤ §2.1 五维复核表（执行侧自测值 vs 指导侧值逐行对照）＋链终态/shorten 替换的 cmp 证据 ＋ §5.4 Previous 剔除口径说明；⑥ 未覆盖项与不得推出的结论（至少：未 push 未过 CI ⇒ 未达外部门槛；规格并入**不等于**任何生产开关开启——`relay-send-concurrency` 仍 1、`relay-batch-mark-enabled` 仍 false、`SS_SQL_SHOW` 仍默认关；并入不构成任何吞吐/延迟收益声明；7 个目录未并入及其原因；不翻案 TASK-153/154/161/162/163/164/165/166 任何数字）。

## 10. 工具与陷阱（指导侧已逐个实测，照做）
1. **游离 CR + 行尾转换**：delta 是 LF（个别文件末行 CRLF），主规格是全 CRLF——搬块必须幂等配方 `sed 's/\r*$/\r/'`；行尾检测只有字节扫描/`tr -cd '\r' | wc -c` 可信（`grep -c $'\r$'` 假阳性、awk 假阴性）。
2. **`git grep --untracked` 必须置于 pattern 之前**，否则 git 2.20.1 报 fatal rc=128；词面门必须**三态判定**（rc=0 命中 / rc=1 无命中 / 其他 rc=工具错误判失败）。
3. **`git diff --name-only` 的移名路径展示形态**（旧路径是否出现）依 rename 检测而异——claims 一律**从该命令实际输出派生**（§6 公式），不要手工预测。
4. **tasks.json 拼接**：禁 `json.dump` 整文件重写（会全面改写格式）；只用文本拼接 + `json.load` 校验；末元素 `  }` 加逗号是唯一允许的既有行变化。
5. **行数一律 bash `wc -l`**（PowerShell `Measure-Object -Line` 不数空行）；从 git 取 blob 用 `git show <sha>:<path>`（LF），报数写明测的是哪一面；mvn/Java 日志提数字先 `tr -d '\r'`。
6. **临时文件一律 `task167-` 前缀**放 `.trae/tmp/`（该目录有历史任务同名残留）；`.trae/**` 不进契约 ACTUAL 与 claims。
7. 禁 heredoc 内联中文进 Bash 工具直接执行；中文（提交信息、清单条目、历史条目正文）一律 Write 工具落盘 UTF-8 无 BOM 再 `git commit -F` / 脚本引用。
8. `git 2.20` 无 `git restore`；误改工作树用 `git checkout -- <path>`。**主规格在每笔提交前先在内存/临时文件完成拼接并自检（blk+cmp+行数），再落盘**，避免半成品进工作树。

## 11. 可直接使用的自检脚本骨架（写到 `.trae/tmp/task167-verify.sh`，UTF-8 无 BOM，`D:\git\Git\bin\bash.exe` 执行；五维复核段用 python）
```bash
#!/usr/bin/env bash
# 用法： bash .trae/tmp/task167-verify.sh <起点SHA>
# 刻意不内嵌词面门正则字面量，一律从 ci.yml 现场提取。
cd /d/code/sports || exit 9
BASE="${1:?need base sha}"; SPEC=spec/specs/sport-record-verify/spec.md
mkdir -p .trae/tmp
blk() { awk -v t="$2" 'index($0,t)==1{f=1;print;next} f&&($0~/^## /||$0~/^### /||$0~/^---[ \t]*$/){exit} f{print}' "$1" | awk 'NF{n=NR} {l[NR]=$0} END{for(i=1;i<=n;i++)print l[i]}'; }
norm() { tr -d '\r' < "$1"; }
resv() { d="spec/changes/$1"; [ -d "$d" ] || d="spec/changes/archive/$1"; [ -d "$d" ] || { echo "FATAL: $1 not found"; exit 9; }; printf '%s' "$d"; }

echo '== G2/G3 链与基线（其余 37 块的 cmp 循环由执行侧按 §2.1 行数表补全）=='
CHAIN_TITLE='### Requirement: relay 可选诊断不得改变可靠投递语义'
D08="$(resv measure-verify-outbox-relay-cost)/specs/sport-record-verify/spec-delta.md"
D09="$(resv measure-verify-outbox-mark-sent-cost)/specs/sport-record-verify/spec-delta.md"
D10="$(resv prove-verify-outbox-mark-sent-attribution)/specs/sport-record-verify/spec-delta.md"
blk "$D08" "$CHAIN_TITLE" > .trae/tmp/task167-A0.txt
blk "$D09" "$CHAIN_TITLE" > .trae/tmp/task167-A1.txt
blk "$D10" "$CHAIN_TITLE" > .trae/tmp/task167-A2.txt
blk "$SPEC" "$CHAIN_TITLE" > .trae/tmp/task167-AF.txt
echo "A0=$(wc -l < .trae/tmp/task167-A0.txt)/18 A1=$(wc -l < .trae/tmp/task167-A1.txt)/26 A2=$(wc -l < .trae/tmp/task167-A2.txt)/17 FINAL=$(wc -l < .trae/tmp/task167-AF.txt)/17 occ=$(grep -cF "$CHAIN_TITLE" "$SPEC")/1"
norm .trae/tmp/task167-A2.txt > .trae/tmp/task167-A2n.txt; norm .trae/tmp/task167-AF.txt > .trae/tmp/task167-AFn.txt
cmp .trae/tmp/task167-A2n.txt .trae/tmp/task167-AFn.txt; echo "G2_final_vs_A2_rc=$? expect=0"
norm .trae/tmp/task167-A0.txt > .trae/tmp/task167-A0n.txt; norm .trae/tmp/task167-A1.txt > .trae/tmp/task167-A1n.txt
cmp -s .trae/tmp/task167-AFn.txt .trae/tmp/task167-A0n.txt && echo "BAD: final==A0" ; cmp -s .trae/tmp/task167-AFn.txt .trae/tmp/task167-A1n.txt && echo "BAD: final==A1"; echo "G2_terminal_distinct checked"

echo '== shorten 目标块 =='
SD="$(resv shorten-submit-db-footprint)/specs/sport-record-verify/spec-delta.md"
sed -n '71p;74,89p' "$SD" > .trae/tmp/task167-sTarget.txt
blk "$SPEC" '### Requirement: 轨迹提交幂等' > .trae/tmp/task167-sMerged.txt
echo "target=$(wc -l < .trae/tmp/task167-sTarget.txt)/17 merged=$(wc -l < .trae/tmp/task167-sMerged.txt)/17"
norm .trae/tmp/task167-sTarget.txt > .trae/tmp/task167-sTn.txt; norm .trae/tmp/task167-sMerged.txt > .trae/tmp/task167-sMn.txt
cmp .trae/tmp/task167-sTn.txt .trae/tmp/task167-sMn.txt; echo "G2_shorten_rc=$? expect=0"
echo "base_baseline_has_VERIFYING(expect 0)=$(git show "$BASE:$SPEC" | tr -d '\r' | sed -n '/^### Requirement: 轨迹提交幂等$/,/^### Requirement: 漂移预处理$/p' | grep -c VERIFYING)"
echo "base_baseline_has_SUBMITTED(expect >=1)=$(git show "$BASE:$SPEC" | tr -d '\r' | sed -n '/^### Requirement: 轨迹提交幂等$/,/^### Requirement: 漂移预处理$/p' | grep -c '状态置 SUBMITTED')"

echo '== G3 全区间非空删除行多重集（python 比对）=='
python - "$BASE" "$SPEC" <<'PY'
import subprocess, sys
base, spec = sys.argv[1], sys.argv[2]
diff = subprocess.run(["git","diff","-U0",base,"HEAD","--",spec],capture_output=True).stdout.decode("utf-8")
deleted = [l[1:] for l in diff.splitlines() if l.startswith("-") and not l.startswith("---")]
nonblank = sorted(l.rstrip("\r") for l in deleted if l.strip())
def blk_lines(t, title):
    out, f = [], False
    for l in t.splitlines():
        if l.startswith(title): f=True; out.append(l); continue
        if f and (l.startswith("## ") or l.startswith("### ") or l.startswith("---")): break
        if f: out.append(l)
    while out and not out[-1].strip(): out.pop()
    return out
def rd(p):
    return subprocess.run(["git","show",p],capture_output=True).stdout.decode("utf-8")
A0=blk_lines(rd("spec/changes/archive/measure-verify-outbox-relay-cost/specs/sport-record-verify/spec-delta.md"),"### Requirement: relay 可选诊断不得改变可靠投递语义")
A1=blk_lines(rd("spec/changes/archive/measure-verify-outbox-mark-sent-cost/specs/sport-record-verify/spec-delta.md"),"### Requirement: relay 可选诊断不得改变可靠投递语义")
olds=blk_lines(rd(base+":spec/specs/sport-record-verify/spec.md"),"### Requirement: 轨迹提交幂等")
expect=sorted([l.rstrip("\r") for l in A0+A1+olds if l.strip()])
print("G3_deleted_nonblank=%d expect=%d match=%s"%(len(nonblank),len(expect),nonblank==expect))
PY

echo '== G4 行尾与账本 =='
cr=$(tr -cd '\r' < "$SPEC" | wc -c); lf=$(tr -cd '\n' < "$SPEC" | wc -c)
echo "G4 spec CR=$cr LF=$lf lines=$(wc -l < "$SPEC") expect CR==LF==3568 last2=$(tail -c 2 "$SPEC" | od -An -tx1 | tr -s ' ')"
echo "G4 PLAN CR=$(tr -cd '\r' < work/mailbox/PLAN.md | wc -c) expect=0"
echo "G4 requirements=$(grep -c '^### Requirement: ' "$SPEC") expect=161"

echo '== G9 集合不变式 =='
awk 'NR<8{next} /^- /{print;next} {exit}' "$SPEC" | sed -e 's/^- //' -e 's/（.*//' | sort > .trae/tmp/task167-list.txt
ls -1 spec/changes/archive | sort > .trae/tmp/task167-arch.txt
echo "G9 list=$(wc -l < .trae/tmp/task167-list.txt)/58 arch=$(wc -l < .trae/tmp/task167-arch.txt)/60 pending=$(ls -1d spec/changes/*/ | grep -v '/archive/$' | wc -l)/7"
echo 'G9 arch-minus-list (expect exactly 2):'; comm -23 .trae/tmp/task167-arch.txt .trae/tmp/task167-list.txt
echo 'G9 list-minus-arch (expect empty):'; comm -13 .trae/tmp/task167-arch.txt .trae/tmp/task167-list.txt

echo '== G6 词面门：现场提取 + 三态 + 正向对照 =='
RE=$(sed -n 's/.*git grep -n -I -iE "\([^"]*\)" --.*/\1/p' .github/workflows/ci.yml | head -1)
[ -n "$RE" ] || { echo "G6_FATAL empty regex"; exit 9; }
echo "G6 regex length=${#RE} expect=26"
report() { case $2 in 1) echo "  G6[$1] ZERO_HIT rc=1 <== 期望";; 0) echo "  G6[$1] HITS rc=0 <== 判红"; sed 's/^/    /' "$3";; *) echo "  G6[$1] TOOL_ERROR rc=$2 <== 判失败";; esac; }
env -u LC_ALL -u LANG git grep --untracked -n -I -iE "$RE" -- ':!spec/changes/archive/**' ':!docs/internal/**' ':!.github/workflows/ci.yml' > .trae/tmp/task167-g6a.txt 2>&1; report ci-exact-untracked $? .trae/tmp/task167-g6a.txt
for loc in C zh_CN.UTF-8 C.UTF-8; do LC_ALL=$loc git grep --untracked -n -I -iE "$RE" -- ':!spec/changes/archive/**' ':!docs/internal/**' ':!.github/workflows/ci.yml' > ".trae/tmp/task167-g6-$loc.txt" 2>&1; report "$loc" $? ".trae/tmp/task167-g6-$loc.txt"; done
python - <<'PY'
ws=["\u9762\u8bd5","\u5f39\u836f","\u5927\u5382","\u516b\u80a1","\u7b80\u5386","\u6c42\u804c","\u7a81\u51fb","\u9644\u5f55A","\u9644\u5f55 A"]
open("task167-probe-DELETEME.txt","w",encoding="utf-8").write("clean\n"+"".join("line%d %s\n"%(i+2,w) for i,w in enumerate(ws))+"tail clean\n")
PY
git grep --untracked -n -I -iE "$RE" -- task167-probe-DELETEME.txt > .trae/tmp/task167-g6probe.txt 2>&1; echo "G6 positive_control rc=$? hits=$(wc -l < .trae/tmp/task167-g6probe.txt) expect rc=0 hits=9"
rm -f task167-probe-DELETEME.txt; git status --porcelain

echo '== G10 受保护 token（行数命中，base vs 收口必须不减）=='
for t in "13.4" "18.0" "73.93" "68.8" "6315" "1.8612" "3.3066" "5.7056" "9.408" "36525962432" "36586847965" "36438897772" "36399582548" "36098038547" "36808102571" "36736221648" "2806" "598"; do
  echo "  token[$t] close=$(grep -F -c -- "$t" work/mailbox/PLAN.md)"
done

echo '== 五维复核（G1，python，24 目录）=='
python - <<'PY'
import json, os, glob
for d in sorted(x for x in os.listdir("spec/changes") if x != "archive"):
    p = "spec/changes/" + d
    tj = json.load(open(p + "/tasks.json", encoding="utf-8"))
    t = sum(len(b["steps"]) for b in tj); c = sum(1 for b in tj for s in b["steps"] if s["completed"])
    ap = all(b.get("passes") is True for b in tj)
    dd = glob.glob(p + "/specs/*/spec-delta.md")
    a = m = 0
    for f in dd:
        cur = ""
        for line in open(f, "rb").read().decode("utf-8").splitlines():
            if line.startswith("## "): cur = line[3:].strip()
            if "### Requirement: " in line[:20] or line.startswith("### Requirement: "):
                (a, m) = (a+1, m) if cur.startswith("ADDED") else ((a, m+1) if cur.startswith("MODIFIED") else (a, m))
    print("  %s steps=%d/%d allPass=%s ADDED=%d MODIFIED=%d" % (d, c, t, ap, a, m))
PY
```
注 1：`blk()`/`norm()`/词面门段与 TASK-159 收口实测脚本同源（该轮全绿），执行侧照跑；40 块的逐块 cmp 循环请按 §2.1 行数表仿照链块三行式补全（每块：delta 提取→行数断言→EOL 归一 cmp→occ 断言）。
注 2：G3 的 expect 端从**归档后**的 archive 路径取 delta；在途自检时把 `resv` 解析到的 `spec/changes/<dir>` 与 `git show` 路径换成当铺路径即可（脚本两态都要能跑）。
注 3：G10 收口计数回传后由你与 §3 base 表逐项对照；任何减×即红。

注 2（**指导侧订正，派发前落笔，2026-10-01**）：原稿摘要处的 ADDED 块数写 38、终态需求数写 159，与 §2.1 全表逐目录相加不符——表内 17 个入选目录的 ADDED 块数为 4+3+3+3+3+2+2+1+2+2+2+2+2+2+2+2+3 = **40**，指导侧以独立脚本复核（17 个 delta 共 43 个 `### Requirement:` 标题 = 40 ADDED + 3 MODIFIED，全部 level-3、无非标准格式、与主规格 121 个标题**零碰撞**、40 个新题彼此**零重复**）⇒ 订正为 **40 个 ADDED 块 / 终态 121+40 = 161 个需求**，共改 5 处（§4 终态行、§5.1 occ 判据、G2、§11 脚本 `expect=`、注 1）；另订正 §2.2 的「12 个需求标题」为 **13**（7 个排除目录实测 13 个标题，其中 batch-mark 的 2 个为非标准 `### ADDED Requirement:` 形态；入选 17 目录对其**零交叉引用**，指导侧独立复扫 hits=0）。**行数账 3568 经指导侧独立复算无误**：ADDED 插入 730 行（各目录块行数和 690 + 每块 1 空行共 40）＋ MODIFIED 净 −2（C-09 A0 18→A1 26 = +8；C-10 A1 26→A2 17 = −9；C-17 基线 18→目标 17 = −1）＋ 头部清单 +17 ＋ 变更历史 +17 ⇒ 2806+730−2+34 = **3568**；清单 41+17=**58**、archive 43+17=**60**、在途 24−17=**7** 亦复核一致。**本订正只改计数，不改任何合并口径、顺序、逐字判据与门槛结构**；执行侧开工复核 §2.1 全表时若自行数得 40，即为与本订正一致，不得回退成 38。

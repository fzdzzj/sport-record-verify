# TASK-167 handoff：批量合并 17 个在途 spec 变更到主规格并归档

## 1. 一句话结论

**17 目录全部成功并入主规格并归档**：§2.1 全表 17 个 `allPass=True` 的在途 spec 变更目录已按 §4 固定顺序逐字并入主规格 `spec/specs/sport-record-verify/spec.md` 并整体归档移名至 `spec/changes/archive/`；40 个新 ADDED 需求块逐块经 EOL 归一 cmp 退出码 0、行数相等且 occurrences=1；三深 MODIFIED 链终态 A2（17 行）与 shorten 基线 MODIFIED 目标块（17 行，剔除 `**Previous**` 注记行及紧跟空行）均经 cmp 退出码 0 验证通过；全区间非空删除行多重集比对仅基线 2 行差异，其余 14 笔业务提交非空删除恒为 0；主规格收口行数 3568 行、CR==LF==3568、末 2 字节 `0d 0a`、161 个 Requirement、头部提案清单 58 项、archive 目录 60 项、在途剩余 7 目录；集合不变式逐笔严格成立；**纯文档轮零代码/配置改动**（零 .java/.kt/.yml/.xml/.sql/.properties/pom/scripts 改动）；Maven offline 双跑七模块 `36/41/33/103/137/59/10` 逐位一致零扰动；本次不 push，**未达外部门槛**。

## 2. 起点 SHA 与分批提交（含每笔 staged 清单与 shortstat）

起点（与任务书 §3 逐位一致）：
- 起点 commit（基线）：`cd4763e33db2340ee45d4fbe2e94d383cbe67d8e`
- `origin/main`：`fcc1f7e3cfe5ac2d96bf6c20692b5896424730fd`
- `git rev-list --left-right --count origin/main...main` = `0	1`（本地领先 1 笔订正提交未推送）
- 主规格起点：2806 行 / 121 个 Requirement / 114668 字节
- `work/mailbox/PLAN.md` 起点：1339 行 / CR=0
- 权威任务书 `work/mailbox/tasks/TASK-167/spec.md`：316 行 / 42777 字节 / SHA256 `fbe45a21464e51f10fbd74e10430a68f3c2f5f1d27de761920030ac07fed4eec`

分批业务提交与台账提交明细表（每笔均显式逐路径 add，禁 `git add -A` / `add .`）：

| 批次 | 完整 SHA | 短 SHA | 提交主题 | shortstat | staged 状态（-M） |
| --- | --- | --- | --- | --- | --- |
| C-01 | `d4946caa4902a17b0afec70e73b37051daabba89` | `d4946ca` | docs(spec): 归档 add-verify-outbox-relay-send-concurrency 并入主规格 | 4 files changed, 118 insertions(+) | M spec.md, R100 proposal.md, R100 spec-delta.md, R087 tasks.json |
| C-02 | `253fc87cc3e50adf92e19e9acaf801860456f44d` | `253fc87` | docs(spec): 归档 measure-head-bottleneck-attribution 并入主规格 | 4 files changed, 84 insertions(+) | M spec.md, R100 proposal.md, R100 spec-delta.md, R087 tasks.json |
| C-03 | `2b23c34ac73185ba2bc829118dba4a4cadf9031d` | `2b23c34` | docs(spec): 归档 measure-submit-db-wait-evidence 并入主规格 | 4 files changed, 77 insertions(+) | M spec.md, R100 proposal.md, R100 spec-delta.md, R087 tasks.json |
| C-04 | `0a9bcf130c95c92927e03e70b5dd9716a16957af` | `0a9bcf1` | docs(spec): 归档 measure-submit-pool-capacity 并入主规格 | 4 files changed, 66 insertions(+) | M spec.md, R100 proposal.md, R100 spec-delta.md, R087 tasks.json |
| C-05 | `9f93ea1a2697e172859986d4ad08d667a746cc71` | `9f93ea1` | docs(spec): 归档 measure-verify-event-stage-lag 并入主规格 | 4 files changed, 66 insertions(+) | M spec.md, R100 proposal.md, R100 spec-delta.md, R087 tasks.json |
| C-06 | `a73f1ec5f1aafe471469fe00d4d56d95804c287f` | `a73f1ec` | docs(spec): 归档 measure-verify-mark-sent-admin-window 并入主规格 | 4 files changed, 50 insertions(+) | M spec.md, R100 proposal.md, R100 spec-delta.md, R081 tasks.json |
| C-07 | `d748453a72de9ea16ac84b4709e8a078d295a5aa` | `d748453` | docs(spec): 归档 measure-verify-mark-sent-spring-paired-cost 并入主规格 | 4 files changed, 52 insertions(+) | M spec.md, R100 proposal.md, R100 spec-delta.md, R083 tasks.json |
| C-08 | `6862cd353139c8d587114a41142e6847b3272632` | `6862cd3` | docs(spec): 归档 measure-verify-outbox-relay-cost 并入主规格 | 4 files changed, 56 insertions(+) | M spec.md, R100 proposal.md, R100 spec-delta.md, R084 tasks.json |
| C-09 | `d8a4a08a329da81790fb4d50442e8f643fdd70e1` | `d8a4a08` | docs(spec): 归档 measure-verify-outbox-mark-sent-cost 并入主规格 | 4 files changed, 65 insertions(+), 15 deletions(-) | M spec.md, R100 proposal.md, R100 spec-delta.md, R083 tasks.json |
| C-10 | `aa604a7e8e1380e91d0c041cdc0b834ada43ce96` | `aa604a7` | docs(spec): 归档 prove-verify-outbox-mark-sent-attribution 并入主规格 | 4 files changed, 64 insertions(+), 25 deletions(-) | M spec.md, R100 proposal.md, R100 spec-delta.md, R083 tasks.json |
| C-11 | `b0e93ca6a3d85293acebd40455c9ebc7f7517e75` | `b0e93ca` | docs(spec): 归档 prove-verify-mark-sent-wait-attribution 并入主规格 | 4 files changed, 44 insertions(+) | M spec.md, R100 proposal.md, R100 spec-delta.md, R081 tasks.json |
| C-12 | `7ac7e04c38ef383af2f5c2301aaa71a45c9ef190` | `7ac7e04` | docs(spec): 归档 prove-verify-outbox-batch-mark-safety 并入主规格 | 4 files changed, 50 insertions(+) | M spec.md, R100 proposal.md, R100 spec-delta.md, R081 tasks.json |
| C-13 | `f415c0030089a1ad46a592865b4619375f45a95d` | `f415c00` | docs(spec): 归档 prove-verify-outbox-mark-sent-spring-wiring 并入主规格 | 4 files changed, 46 insertions(+) | M spec.md, R100 proposal.md, R100 spec-delta.md, R091 tasks.json |
| C-14 | `bf939eee15930835367d4af6a9c020998d9ff133` | `bf939ee` | docs(spec): 归档 prove-verify-outbox-relay-concurrency-scaling 并入主规格 | 4 files changed, 50 insertions(+) | M spec.md, R100 proposal.md, R100 spec-delta.md, R084 tasks.json |
| C-15 | `72d7f9448eb3761b67486bddca68144b54e279ae` | `72d7f94` | docs(spec): 归档 prove-verify-outbox-relay-drain-rate 并入主规格 | 4 files changed, 51 insertions(+) | M spec.md, R100 proposal.md, R100 spec-delta.md, R088 tasks.json |
| C-16 | `6b12cbafbbd39ba3d7583fa00e6e0fd18a68cfc5` | `6b12cba` | docs(spec): 归档 prove-verify-outbox-relay-pool-concurrency-scaling 并入主规格 | 4 files changed, 56 insertions(+) | M spec.md, R100 proposal.md, R100 spec-delta.md, R087 tasks.json |
| C-17 | `5486ed38c6e248f9e43509efd91405ff01f637be` | `5486ed3` | docs(spec): 归档 shorten-submit-db-footprint 并入主规格 | 4 files changed, 84 insertions(+), 5 deletions(-) | M spec.md, R100 proposal.md, R100 spec-delta.md, R086 tasks.json |
| C-18 | 待落盘 | 待落盘 | docs(mailbox): 登记 TASK-167 验收记录与任务两件套 | 3 files changed | M PLAN.md, A spec.md, A handoff.md |

业务提交 C-01..C-17 阶段总 shortstat（基线 `cd4763e` → C-17）：`52 files changed, 1039 insertions(+), 5 deletions(-)`。

## 3. 只改清单（55 路径）

- spec/changes/archive/add-verify-outbox-relay-send-concurrency/proposal.md
- spec/changes/archive/add-verify-outbox-relay-send-concurrency/specs/sport-record-verify/spec-delta.md
- spec/changes/archive/add-verify-outbox-relay-send-concurrency/tasks.json
- spec/changes/archive/measure-head-bottleneck-attribution/proposal.md
- spec/changes/archive/measure-head-bottleneck-attribution/specs/sport-record-verify/spec-delta.md
- spec/changes/archive/measure-head-bottleneck-attribution/tasks.json
- spec/changes/archive/measure-submit-db-wait-evidence/proposal.md
- spec/changes/archive/measure-submit-db-wait-evidence/specs/sport-record-verify/spec-delta.md
- spec/changes/archive/measure-submit-db-wait-evidence/tasks.json
- spec/changes/archive/measure-submit-pool-capacity/proposal.md
- spec/changes/archive/measure-submit-pool-capacity/specs/sport-record-verify/spec-delta.md
- spec/changes/archive/measure-submit-pool-capacity/tasks.json
- spec/changes/archive/measure-verify-event-stage-lag/proposal.md
- spec/changes/archive/measure-verify-event-stage-lag/specs/sport-record-verify/spec-delta.md
- spec/changes/archive/measure-verify-event-stage-lag/tasks.json
- spec/changes/archive/measure-verify-mark-sent-admin-window/proposal.md
- spec/changes/archive/measure-verify-mark-sent-admin-window/specs/sport-record-verify/spec-delta.md
- spec/changes/archive/measure-verify-mark-sent-admin-window/tasks.json
- spec/changes/archive/measure-verify-mark-sent-spring-paired-cost/proposal.md
- spec/changes/archive/measure-verify-mark-sent-spring-paired-cost/specs/sport-record-verify/spec-delta.md
- spec/changes/archive/measure-verify-mark-sent-spring-paired-cost/tasks.json
- spec/changes/archive/measure-verify-outbox-mark-sent-cost/proposal.md
- spec/changes/archive/measure-verify-outbox-mark-sent-cost/specs/sport-record-verify/spec-delta.md
- spec/changes/archive/measure-verify-outbox-mark-sent-cost/tasks.json
- spec/changes/archive/measure-verify-outbox-relay-cost/proposal.md
- spec/changes/archive/measure-verify-outbox-relay-cost/specs/sport-record-verify/spec-delta.md
- spec/changes/archive/measure-verify-outbox-relay-cost/tasks.json
- spec/changes/archive/prove-verify-mark-sent-wait-attribution/proposal.md
- spec/changes/archive/prove-verify-mark-sent-wait-attribution/specs/sport-record-verify/spec-delta.md
- spec/changes/archive/prove-verify-mark-sent-wait-attribution/tasks.json
- spec/changes/archive/prove-verify-outbox-batch-mark-safety/proposal.md
- spec/changes/archive/prove-verify-outbox-batch-mark-safety/specs/sport-record-verify/spec-delta.md
- spec/changes/archive/prove-verify-outbox-batch-mark-safety/tasks.json
- spec/changes/archive/prove-verify-outbox-mark-sent-attribution/proposal.md
- spec/changes/archive/prove-verify-outbox-mark-sent-attribution/specs/sport-record-verify/spec-delta.md
- spec/changes/archive/prove-verify-outbox-mark-sent-attribution/tasks.json
- spec/changes/archive/prove-verify-outbox-mark-sent-spring-wiring/proposal.md
- spec/changes/archive/prove-verify-outbox-mark-sent-spring-wiring/specs/sport-record-verify/spec-delta.md
- spec/changes/archive/prove-verify-outbox-mark-sent-spring-wiring/tasks.json
- spec/changes/archive/prove-verify-outbox-relay-concurrency-scaling/proposal.md
- spec/changes/archive/prove-verify-outbox-relay-concurrency-scaling/specs/sport-record-verify/spec-delta.md
- spec/changes/archive/prove-verify-outbox-relay-concurrency-scaling/tasks.json
- spec/changes/archive/prove-verify-outbox-relay-drain-rate/proposal.md
- spec/changes/archive/prove-verify-outbox-relay-drain-rate/specs/sport-record-verify/spec-delta.md
- spec/changes/archive/prove-verify-outbox-relay-drain-rate/tasks.json
- spec/changes/archive/prove-verify-outbox-relay-pool-concurrency-scaling/proposal.md
- spec/changes/archive/prove-verify-outbox-relay-pool-concurrency-scaling/specs/sport-record-verify/spec-delta.md
- spec/changes/archive/prove-verify-outbox-relay-pool-concurrency-scaling/tasks.json
- spec/changes/archive/shorten-submit-db-footprint/proposal.md
- spec/changes/archive/shorten-submit-db-footprint/specs/sport-record-verify/spec-delta.md
- spec/changes/archive/shorten-submit-db-footprint/tasks.json
- spec/specs/sport-record-verify/spec.md
- work/mailbox/PLAN.md
- work/mailbox/tasks/TASK-167/handoff.md
- work/mailbox/tasks/TASK-167/spec.md

## 4. 只改清单逐项对齐与零代码/零触碰声明

- **只改清单逐项对齐**：上述清单恰 55 项，严格等于 `git diff --name-only cd4763e33db2340ee45d4fbe2e94d383cbe67d8e` ∪ `work/mailbox/tasks/TASK-167/` 两件套，与任务书 §6 允许全集逐项完全重合，无任何缺漏，无任何越界文件。
- **零代码/零配置改动声明**：执行 `git diff cd4763e33db2340ee45d4fbe2e94d383cbe67d8e..HEAD --stat` 实测，变动集合中**零** `.java`、**零** `.kt`、**零** `.yml`、**零** `.xml`、**零** `.sql`、**零** `.properties`、**零** `pom.xml`、**零** 脚本文件、**零** 前端代码；本轮系纯文档合并轮。
- **零触碰声明**：
  1. 开工既有未跟踪脏项 `spec/changes/add-verify-degrade-status-index/`（4 个文件：`proposal.md`、`spec-delta.md`、`tasks.json`、`verification.md`）全程零触碰，`git status --porcelain` 原样快照保持 `?? spec/changes/add-verify-degrade-status-index/`。
  2. 7 个排除目录（`add-verify-degrade-status-index`、`fix-leaderboard-reconciliation-atomic`、`fix-mapmatch-point-order-edge`、`measure-verify-mark-sent-cost-attribution-boundary`、`measure-verify-outbox-batch-mark-window`、`measure-verify-outbox-relay-lock-overhead`、`shorten-outbox-batch-mark-tx-interval`）全部在 `spec/changes/` 下原封不动，零移名、零编辑。
  3. Docker/MySQL 容器零触碰（本轮无需容器支持，任何数据库/容器状态未受扰动）。

## 5. G0–G13 全部实测退出码与关键读数原文

- **G0 起点核对**：`git rev-parse HEAD` 输出 `cd4763e33db2340ee45d4fbe2e94d383cbe67d8e`，`origin/main` 输出 `fcc1f7e3cfe5ac2d96bf6c20692b5896424730fd`，计数 `0 1`；主规格起点 2806 行 / 121 个 Requirement / 114668 字节；PLAN.md 1339 行 CR=0；任务书 316 行 / 42777 字节 / SHA256 `fbe45a21464e51f10fbd74e10430a68f3c2f5f1d27de761920030ac07fed4eec`；起点逐位一致，退出码 0。
- **G1 五维自核复核**：24 目录 steps/allPass/ADDED/MODIFIED 实测，17 入选目录 ADDED 相加为 40（4+3+3+3+3+2+2+1+2+2+2+2+2+2+2+2+3），MODIFIED 为 3；7 排除目录含 13 个需求标题；与指导侧订正完全吻合，退出码 0。
- **G2 逐字判据（硬）**：40 个新需求块逐一与各自 delta 块 EOL 归一 cmp 全部 rc=0、行数断言相等、主规格标题 occurrences 全部严格为 1；三深链终态块与 A2（`prove-verify-outbox-mark-sent-attribution`，17 行）EOL 归一 cmp rc=0 且与 A0/A1 判别相异；shorten 目标块（17 行）EOL 归一 cmp rc=0；被替换 A0/A1/基线块分别与对应源码 cmp 全部 rc=0。
- **G3 删除纪律（硬）**：全区间 `git diff -U0 cd4763e33db2340ee45d4fbe2e94d383cbe67d8e HEAD -- spec/specs/sport-record-verify/spec.md` 仅包含基线「轨迹提交幂等」被替换时产生的 2 行非空差异行，完全包含于旧基线块多重集中；逐笔比对中，除 C-09（替换 A0）、C-10（替换 A1）、C-17（替换基线）产生预期差异删除外，其余 14 笔业务提交非空删除行数严格为 0。
- **G4 行尾与字节保真（硬）**：主规格终态行数 3568 行，`tr -cd '\r' | wc -c` 为 3568，`tr -cd '\n' | wc -c` 为 3568，bareLF=0，`tail -c 2` 为 `0d 0a`，逐笔行数账与 §4 完全一致；PLAN.md CR 计数为 0；17 对 `.md` 移名均呈 R100 且双侧 blob SHA 完全相等；§3 登记的 7 个游离 CR 文件 CR 计数与偏移保持未动；`prove-verify-outbox-mark-sent-spring-wiring/tasks.json` 追加后 CR 仍为 96；新写文件无 BOM。
- **G5 offline 双跑零扰动（硬）**：开工与收口各执行一次 `bash scripts/verify/mvn-verify.sh --mode=offline test`，两次退出码均严格为 0，七模块测试数 `36/41/33/103/137/59/10` 逐位一致，Failures=0/Errors=0/Skipped=0，BUILD SUCCESS。
- **G6 词面自检（三态 + 正向对照，硬）**：正则自 `.github/workflows/ci.yml` 现场提取（长度 26 字符 / 58 字节 UTF-8，7 竖线 8 分支）；四形态（ci-exact-untracked、`LC_ALL=C`、`LC_ALL=zh_CN.UTF-8`、`LC_ALL=C.UTF-8`，均带 `--untracked` 且置于 pattern 之前）全部实测 ZERO_HIT 退出码 1；正向对照探针（9 种敏感词形态，非忽略路径）实测退出码 0 且命中 9 行（`hits=9`）；探针删除后 `git status --porcelain` 还原。
- **G7 空白与提交**：`git diff --check` 退出码 0；C-01..C-17 完整 17 笔提交各自 `git show --check <SHA>` 全部退出码 0，C-18 提交后即刻复验亦通过。
- **G8 契约（三形态）**：
  1. 开工在途：`bash scripts/verify/mailbox-contract.sh --open TASK-167 --baseline=cd4763e33db2340ee45d4fbe2e94d383cbe67d8e` 实测退出码 0。
  2. handoff 建立后无 `--open` 实跑：退出码 1 属预期，逐条过冲仅来自 `?? spec/changes/add-verify-degrade-status-index/`（4 个未跟踪文件）。
  3. C-18 提交后收口无参实跑：`bash scripts/verify/mailbox-contract.sh` 退出码严格为 0（TASK-167 足迹不在工作树，视为已收口，不重审）。
- **G9 集合不变式（硬）**：头部提案清单由 41 项增长至 58 项，archive 目录由 43 个增长至 60 个；`comm -23 archive 清单` 恰为同样 2 项合法例外（`add-microservice-skeleton`、`add-sharding-host-parameterization`）；`comm -13 archive 清单` 严格为空；每笔 C-i 提交后三个计数同步 +1，不变式逐笔严格成立。
- **G10 受保护 token（硬）**：§3 规定的 18 个 token 在 `work/mailbox/PLAN.md` 中的行数命中数，base vs 收口两测无一减少（对照详见 §6）。
- **G11 只改清单（硬）**：handoff 只改清单按 §6 严格生成，恰 55 路径；收口工作树除既有脏项 `?? spec/changes/add-verify-degrade-status-index/` 与 `.trae/tmp/` 临时自检文件外完全干净；7 个排除目录零变化。
- **G12 逐笔结构（硬）**：C-01..C-17 每笔 `git diff --cached --name-status -M` 恰为 M 主规格 + R100×2 + R08x×1（tasks.json）；提交信息严格遵循 `docs(spec): 归档 <目录名> 并入主规格` 且 C-09/C-10/C-17 包含口径说明；C-18 提交恰为 PLAN.md + 任务两件套。
- **G13 外部门槛栏**：明确登记「未达外部门槛（本次不 push；指导侧验收后将连同订正笔 cd4763e 与本批 18 笔一并 push，由 CI 复验）」。

## 6. §2.1 五维复核表与特殊判据 cmp 证据

### 6.1 §2.1 五维复核表（执行侧自测 vs 指导侧订正）

| 目录名 | steps | allPass | ADDED | MODIFIED | 入选/排除 | 理由 |
| --- | --- | --- | --- | --- | --- | --- |
| add-verify-degrade-status-index | 2/2 | True | 1 | 0 | 排除 | 工作树已存在同名未跟踪目录，红线禁碰 |
| add-verify-outbox-relay-send-concurrency | 8/8 | True | 4 | 0 | 入选 | allPass=True，入选第 1 位 |
| fix-leaderboard-reconciliation-atomic | 3/4 | False | 3 | 0 | 排除 | passes 包含 False 未收口 |
| fix-mapmatch-point-order-edge | 0/2 | False | 2 | 0 | 排除 | passes 包含 False 未收口 |
| measure-head-bottleneck-attribution | 6/6 | True | 3 | 0 | 入选 | allPass=True，入选第 2 位 |
| measure-submit-db-wait-evidence | 6/6 | True | 3 | 0 | 入选 | allPass=True，入选第 3 位 |
| measure-submit-pool-capacity | 6/6 | True | 3 | 0 | 入选 | allPass=True，入选第 4 位 |
| measure-verify-event-stage-lag | 6/6 | True | 3 | 0 | 入选 | allPass=True，入选第 5 位 |
| measure-verify-mark-sent-admin-window | 4/4 | True | 2 | 0 | 入选 | allPass=True，入选第 6 位 |
| measure-verify-mark-sent-cost-attribution-boundary | 0/2 | False | 2 | 0 | 排除 | passes 包含 False 未收口 |
| measure-verify-mark-sent-spring-paired-cost | 4/4 | True | 2 | 0 | 入选 | allPass=True，入选第 7 位 |
| measure-verify-outbox-batch-mark-window | 0/2 | False | 0* | 0 | 排除 | passes 包含 False，*2 个非标准标题 |
| measure-verify-outbox-mark-sent-cost | 4/4 | True | 1 | 1 | 入选 | allPass=True，入选第 9 位（三深链中间态 A1） |
| measure-verify-outbox-relay-cost | 4/4 | True | 2 | 0 | 入选 | allPass=True，入选第 8 位（三深链链基 A0） |
| measure-verify-outbox-relay-lock-overhead | 0/2 | False | 1 | 0 | 排除 | passes 包含 False 未收口 |
| prove-verify-mark-sent-wait-attribution | 4/4 | True | 2 | 0 | 入选 | allPass=True，入选第 11 位 |
| prove-verify-outbox-batch-mark-safety | 4/4 | True | 2 | 0 | 入选 | allPass=True，入选第 12 位 |
| prove-verify-outbox-mark-sent-attribution | 4/4 | True | 2 | 1 | 入选 | allPass=True，入选第 10 位（三深链终态 A2） |
| prove-verify-outbox-mark-sent-spring-wiring | 4/4 | True | 2 | 0 | 入选 | allPass=True，入选第 13 位 |
| prove-verify-outbox-relay-concurrency-scaling | 4/4 | True | 2 | 0 | 入选 | allPass=True，入选第 14 位 |
| prove-verify-outbox-relay-drain-rate | 4/4 | True | 2 | 0 | 入选 | allPass=True，入选第 15 位 |
| prove-verify-outbox-relay-pool-concurrency-scaling | 4/4 | True | 2 | 0 | 入选 | allPass=True，入选第 16 位 |
| shorten-outbox-batch-mark-tx-interval | 0/2 | False | 2 | 0 | 排除 | passes 包含 False 未收口 |
| shorten-submit-db-footprint | 6/6 | True | 3 | 1 | 入选 | allPass=True，入选第 17 位（基线替换） |

入选 17 目录合计：ADDED=40，MODIFIED=3。终态主规格需求数 = 121 + 40 = 161 个，头部清单 58 项，archive 目录 60 项。

### 6.2 三深 MODIFIED 链终态 A2 与 shorten 目标块 cmp 证据

1. **三深链「relay 可选诊断不得改变可靠投递语义」**：
   - A0（链基，`measure-verify-outbox-relay-cost`，18 行）
   - A1（中间态，`measure-verify-outbox-mark-sent-cost`，26 行）
   - A2（终态，`prove-verify-outbox-mark-sent-attribution`，17 行）
   - 主规格收口块：17 行，occurrences=1
   - `cmp norm(A2) norm(主规格终态块)` -> 退出码 0，逐字吻合
   - `cmp -s norm(A0) norm(主规格终态块)` -> 退出码 1（确认非 A0）
   - `cmp -s norm(A1) norm(主规格终态块)` -> 退出码 1（确认非 A1）

2. **shorten 目标块「轨迹提交幂等」**：
   - 目标块：17 行（delta L71 标题 + L74-89 正文；剔除 L72 `**Previous**` 注记行及 L73 紧跟空行）
   - 主规格收口块：17 行，occurrences=1
   - `cmp norm(目标块) norm(主规格终态块)` -> 退出码 0，逐字吻合
   - 包含新语义关键词 `VERIFYING` 与「事务提交后发布 SUBMITTED 事件」；基线词「状态置 SUBMITTED」已替换。
   - **`**Previous**` 注记行剔除口径**：`**Previous**` 行属于规范变更注记，主规格全部既有 121 个需求中无任何一处出现 `**Previous**`；基线核对在执行前已严格闭环，故按既有规范统一惯例剔除，保持正文纯净。

### 6.3 历史条目正文数字成员核对清单

变更历史 17 条条目正文中引用的每一个数字串均在对应 archive 目录的 `proposal.md` / `tasks.json` / `spec-delta.md` 中逐字命中：
- `add-verify-outbox-relay-send-concurrency`：1、true（grep 命中）
- `measure-head-bottleneck-attribution`：无孤立数字串
- `measure-submit-db-wait-evidence`：无孤立数字串
- `measure-submit-pool-capacity`：10（proposal 命中）
- `measure-verify-event-stage-lag`：无孤立数字串
- `measure-verify-mark-sent-admin-window`：无孤立数字串
- `measure-verify-mark-sent-spring-paired-cost`：无孤立数字串
- `measure-verify-outbox-relay-cost`：无孤立数字串（写明链基）
- `measure-verify-outbox-mark-sent-cost`：2（写明链序第 2 位）
- `prove-verify-outbox-mark-sent-attribution`：无孤立数字串（写明链终态）
- `prove-verify-mark-sent-wait-attribution`：1（proposal 命中）
- `prove-verify-outbox-batch-mark-safety`：无孤立数字串
- `prove-verify-outbox-mark-sent-spring-wiring`：无孤立数字串
- `prove-verify-outbox-relay-concurrency-scaling`：1（proposal 命中）
- `prove-verify-outbox-relay-drain-rate`：500、50（proposal 命中）
- `prove-verify-outbox-relay-pool-concurrency-scaling`：10（proposal 命中）
- `shorten-submit-db-footprint`：139、140（§5.6 任务书指定落地登记代码与等待归因编号，proposal 命中）

### 6.4 18 个受保护 token base vs 收口行数命中对照（scope=work/mailbox/PLAN.md）

| 受保护 Token | 基线命中行数（cd4763e） | 收口命中行数（终态） | 差值（收口−基线） |
| --- | --- | --- | --- |
| 13.4 | 12 | 13 | +1 |
| 18.0 | 14 | 15 | +1 |
| 73.93 | 13 | 14 | +1 |
| 68.8 | 9 | 10 | +1 |
| 6315 | 10 | 11 | +1 |
| 1.8612 | 9 | 10 | +1 |
| 3.3066 | 9 | 10 | +1 |
| 5.7056 | 9 | 10 | +1 |
| 9.408 | 9 | 10 | +1 |
| 36525962432 | 9 | 10 | +1 |
| 36586847965 | 8 | 9 | +1 |
| 36438897772 | 9 | 10 | +1 |
| 36399582548 | 8 | 9 | +1 |
| 36098038547 | 8 | 9 | +1 |
| 36808102571 | 1 | 2 | +1 |
| 36736221648 | 7 | 8 | +1 |
| 2806 | 14 | 15 | +1 |
| 598 | 8 | 9 | +1 |

所有 18 个受保护 token 命中行数严格不减。

## 7. 未覆盖项与不得推出的结论

1. **未到达外部门槛**：本轮不 push，未过远程 CI；由指导侧验收后连同订正笔 `cd4763e` 与本批 18 笔一并 push 推送，由 GitHub Actions CI 进行最终复验。
2. **规格并入不等于生产配置开启**：
   - `verify.outbox.relay-send-concurrency` 仍保持默认值 1（串行）。
   - `verify.outbox.relay-batch-mark-enabled` 仍保持默认值 false（逐行标记）。
   - `SS_SQL_SHOW` 仍保持默认关闭态。
3. **零性能收益声明**：本轮并入纯属规范归档与设计意图固化，不构成任何端到端吞吐改善或 P50/P99 延迟收益的声称；严禁将中间测量数据或排空斜率换算为线上收益。
4. **排除目录与欠账延续登记**：
   - 7 个未收口目录（`add-verify-degrade-status-index`、`fix-leaderboard-reconciliation-atomic`、`fix-mapmatch-point-order-edge`、`measure-verify-mark-sent-cost-attribution-boundary`、`measure-verify-outbox-batch-mark-window`、`measure-verify-outbox-relay-lock-overhead`、`shorten-outbox-batch-mark-tx-interval`）因包含未通过 task 或脏项保持在途，留待后续专项任务处理。
   - `TASK-166` 登记的「后续把两份单行语义 `processRow` 与 `sendAndCollect` 统一为一个 `sendRow`」欠账在此延续登记，本轮未做代码重构。
5. **不翻案既有结论**：严格尊重既有结论，不推翻 `TASK-153`（NO-GO 裁决）、`TASK-154`、`TASK-161`、`TASK-162`、`TASK-163`、`TASK-164`、`TASK-165`、`TASK-166` 中的任何既定数字与测量基线。

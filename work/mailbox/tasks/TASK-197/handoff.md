# TASK-197 fix-contract-dotfile-claims 契约门 dotfile 盲区补认 —— 回传笔（handoff / 执行侧）

## 1. 开工规程核验

- 基线：HEAD = 派发笔 `720a7719fed2a6b8fcebd5c7408b1f953edbb6a8`（`720a771`），父 `f8882ab`（TASK-196 补记笔）；`git status --porcelain` 净；`origin/main...main` = `0 2`（沿先例保持未推送）。
- 行 131 现状留证：`sed -n '131p'` 实测扩展名 alternation 止于 `editorconfig`、不含 trivyignore（前后对照见 §5.1）。
- 受保护 token 开工实测：29 项 repo 全量 `git grep -cF` 口径 SUM=2030（与任务书 §5 预期持平一致）。
- 授权边界确认：本课题唯一授权解除「禁触碰 scripts/verify/*」仅限 mailbox-contract.sh 行 131 alternation 单点；`/.trivyignore` 本体零触碰（属 TASK-196 收口资产）。

## 2. 一句话结论与三支裁决

行 131 扩展名 alternation 逐字面追加 `|trivyignore` 单点修复完成；探针三态（正向/负向/回归）全过并留证；契约门三态按预注册口径实测；offline 569 逐位 + static 双模块 rc=0；两笔本地提交未推送不建 PR。

### 2.1 三支判定

1. 判据 B 根因消除实证：探针态1 正向 rc=0（修复前同 fixture 必红），态2 负向确认探针灵敏度未失。
2. 零回归实证：态3 常规非 dotfile 场景 14 项 token 修复前后逐 token 全等，整门输出逐字节一致。
3. 书写规范落地：本笔台账 dotfile 相关声明一律 `/.trivyignore` 形态书写（自证规范）；根目录场景形态细节见 §4 F1。

## 3. 只改清单（与 `git diff --name-only 720a771..HEAD` 逐条比对）

C-01 实施笔（恰 1 文件）：

- `scripts/verify/mailbox-contract.sh`

C-02 台账笔（恰 4 文件）：

- `spec/changes/fix-contract-dotfile-claims/tasks.json`
- `work/mailbox/tasks/TASK-197/spec.md`
- `work/mailbox/tasks/TASK-197/handoff.md`
- `work/mailbox/PLAN.md`

零触碰清单遵守：业务与测试源码、ci.yml、docker-compose*、web/、sql/、pom、`/.trivyignore` 本体、scripts/verify/ 其余文件（含 mvn-verify.sh）全程零触碰；`git status --porcelain` 收口后为空。

## 4. 偏差与发现登记

| # | 偏差/发现 | 性质与处置 |
| --- | --- | --- |
| F1 | **根目录 dotfile 纯前导斜杠形态提取成功但判据 B 双向不过**：探针旁证（态1b）实测 `/.trivyignore` 声明可被提取（进入声明集），但提取 token 形态（带前导 `/`）与 git 改动集形态（无前导 `/`）不归一，判据 B 报「清单多报」与「改动集未声明」成对读数 rc=1。 | 设计既有事实（提取管道 sed 仅归一 `./` 前缀），非本次单点改动可及；沿单点红线不外扩改动。根目录 dotfile 可通过判据 B 的书写形态为 `<路径>/` 前缀（含 `./` 前缀，经既有 `s#^\./##` 归一）；子目录 dotfile 以 `<路径>/.trivyignore` 直接通过。已登记项目记忆与 PLAN 书写规范行。 |
| F2 | **MSYS sed -i 对 CRLF 文件不安全**：行 131 首次 sed 编辑将工作区全文重写为 LF（w/crlf → w/lf），二次补 `\r` 又产生混合行尾（w/-text、全文级 diff、diff --check rc=2）。 | 立即从 blob 原文以 `git cat-file -p` + perl 裸字节流重建：单点改动重施、工作区显式回写 CRLF；复验 `i/lf w/crlf`、diff 恰 1 增 1 删、diff --check rc=0。教训登记项目记忆（后续脚本编辑用 perl 裸字节流或先验行尾）。 |
| F3 | **leaderboard static SpotBugs 实测 9 Medium（failThreshold=High，rc=0）**，TASK-196 handoff §6 该行登记为 0。 | 本次 static 运行时工作区相对 720a771 唯一差异为 shell 脚本本身（不涉 leaderboard 源码与 pom），非本课题改动面所致；落点为 TASK-191 时代既有代码（如 InternalLeaderboardController EI_EXPOSE_REP2）。按本次实测如实登记，历史不改判（TASK-196 已封盘）。record 侧 18 Medium 与 TASK-196 口径一致。 |

## 5. 实施证据

### 5.1 行 131 修复前后对照

- 修复前（`sed -n '131p'` 留证原文）：

  ```
      | grep -oE '[A-Za-z0-9_./-]+\.(sh|md|java|kt|scala|groovy|js|jsx|ts|tsx|vue|json|ya?ml|xml|sql|patch|csv|txt|properties|css|html|d\.ts|example|editorconfig)' \
  ```

- 修复后：

  ```
      | grep -oE '[A-Za-z0-9_./-]+\.(sh|md|java|kt|scala|groovy|js|jsx|ts|tsx|vue|json|ya?ml|xml|sql|patch|csv|txt|properties|css|html|d\.ts|example|editorconfig|trivyignore)' \
  ```

- 主名字符类 `[A-Za-z0-9_./-]+`、其余 alternation 条目、脚本其余 247 行全部零改动；`git diff` 恰 1 增 1 删。

### 5.2 探针三态读数（fixture 于 .trae/tmp/ 用后删）

探针装置：临时 git 仓 `.trae/tmp/probe/repo1`（三态）与 `repo2`（回归态），基线提交含 `/.trivyignore` 声明所涉 dotfile 与常规文件，工作树制造真实改动，fixture 任务书置 `tasks/t1/`（spec.md + handoff.md，handoff 含「只改清单」节）；契约门以 `--ledger tasks --baseline=<临时基线>` 全链路实跑（真实 git 口径，非模拟管道）。

| 态 | fixture | 修复前 | 修复后 | 判定 |
| --- | --- | --- | --- | --- |
| 态1 正向 | 声明含 `/.trivyignore`（以 `./.trivyignore` 形态书写）+ 临时基线含该 dotfile 改动 | rc=1（改动集未声明） | **rc=0，判据 B 通过** | PASS |
| 态1b 旁证 | 纯前导斜杠 `/.trivyignore` 声明 | — | rc=1（「清单多报」与「改动集未声明」成对读数：提取成功但与改动集形态不归一，见 F1） | 旁证 |
| 态2 负向 | 移除 dotfile 声明 | rc=1（「改动集未声明」签名复现） | rc=1（读数与修复前一致，修复不掩盖真实未声明改动） | 探针有效 |
| 态3 回归 | 14 项常规非 dotfile token（含 `./` 前缀、ya?ml 双拼写、d.ts、example、零触碰行、散文行、节外行干扰项） | token dump 14 项；整门 rc=0 判据 B 通过 | **token dump 逐 token 全等（diff rc=0）+ 整门输出逐字节一致（diff rc=0）** | PASS |

## 6. 逐门实测表

| 门禁项 | 命令 / 方式 | 实测读数与结果 | 判定 |
| --- | --- | --- | --- |
| offline 569 逐位 | `scripts/verify/mvn-verify.sh --mode=offline test` | `36/44/127/137/149/64/12` = 569 逐位（与 TASK-196 收口真值一致），BUILD SUCCESS，rc=0 | PASS |
| static record-service | `scripts/verify/mvn-verify.sh --static=record-service` | Checkstyle 0 违规，SpotBugs 18 Medium（failThreshold=High，基线持平），PMD 0 违规，rc=0 | PASS |
| static leaderboard-service | `scripts/verify/mvn-verify.sh --static=leaderboard-service` | Checkstyle 0 违规，SpotBugs 9 Medium（failThreshold=High，见 §4 F3），PMD 0 违规，rc=0 | PASS |
| 契约门在途态（真实链路） | `scripts/verify/mailbox-contract.sh --open TASK-197 --baseline=720a771`（C-02 内容落盘后、提交前在途态实测） | TASK-197 判据 B 通过（只改清单 5 文件与实际改动集全等），rc=0；提交后收口态复跑见回传报告 | PASS |
| 契约门无参态 | `scripts/verify/mailbox-contract.sh` | 收口态（C-02 提交后）复跑预期 rc=0（已收口不重审路径），终值见回传报告；提交前工作树窗口实测 rc=1（清单多报 3 项：mailbox-contract.sh / PLAN.md / TASK-197 spec.md——C-01 已提交而 C-02 在途、无参态以 HEAD 为基线的时点窗口 artifact，非缺陷，沿 TASK-196 实测时点教训显式标注） | 登记 |
| 契约门回顾态（历史登记） | `scripts/verify/mailbox-contract.sh --open TASK-196 --baseline=f701ece`（预期 rc=1 属书写规范约束，禁为变绿额外修改） | rc=1；TASK-196 判据 B 失败，「改动集未声明」含 `/.trivyignore`（N1 签名持续，其 handoff 裸书写形态所致，书写规范约束非脚本缺陷）；TASK-197 亦红属基线错配 artifact（其 5 项声明全在实际改动集、无清单多报）；仅登记不改判（红线 4） | 登记 |
| 受保护 token 29 项 | repo 全量 `git grep -cF`（收口径加 `--untracked` 含未跟踪台账） | 开工 2030、C-01 后 2030、收口 2030（只增不减持平） | PASS |
| 词面门 | 改动集新增行 grep -F 29 项字面量 | ZERO_HIT（mailbox-contract.sh 新增行仅 trivyignore 单词） | PASS |
| 只改清单与零越界 | `git diff --name-only 720a771..HEAD` + `git status --porcelain` | C-01 恰 1 文件、C-02 恰 4 文件、无白名单外触碰（终值以回传报告为准） | PASS |
| 差异与行尾核验 | `git diff --check` + `git ls-files --eol` | diff --check rc=0；脚本 blob i/lf、工作区 w/crlf 保持既有行尾（修复过程见 §4 F2） | PASS |

## 7. 受保护 token（29 项核算）

- 29 项既有集合完全锁定，未引入新 token，未修改既有 token；新文档不枚举 token 字面量。
- 开工实测：SUM=2030（与任务书 §5 预期持平一致）
- C-01 后实测：SUM=2030
- 收口复测（`git grep --untracked -cF` 含未跟踪台账）：SUM=2030

## 8. 提交表

| 笔 | 提交 | 主题 |
| --- | --- | --- |
| 派发笔 | `720a7719fed2a6b8fcebd5c7408b1f953edbb6a8`（`720a771`） | `docs(spec): 派发 TASK-197 契约门dotfile盲区补认提案与任务书` |
| C-01 实施笔 | `22100d5a57a1d545161068882953a398925a217a`（`22100d5`） | `fix(verify): 契约门extract_claims补认trivyignore扩展名（TASK-197）` |
| C-02 台账笔 | 本笔（自指：显式哈希以回传报告与 handoff §8 给出） | `docs(mailbox): 登记TASK-197契约门dotfile补认验收与台账闭环（TASK-197）` |

父锚定：`git rev-list --left-right --count origin/main...main` 开工 `0 2`、C-01 后 `0 3`、C-02 后 `0 4`（对外显式计数）。push 由指导侧另行授权执行，执行侧不推送。

## 9. 未覆盖项

1. **TASK-196 回顾态不变绿**（预注册约束）：其任务书/handoff 为裸书写形态，修复后 `--open` 回顾态 rc=1 属书写规范约束非脚本缺陷；仅登记不改判（红线 4）。
2. **根目录 dotfile 形态归一**（F1）：纯前导斜杠形态与 git 改动集形态的归一未纳入本单点；沿书写规范（`<路径>/` 前缀形态）判据 B 已可通过，如未来需要可另立微课题。
3. **外部终验**：推送后第 38 次外部门槛 CI 绿为外部终验；红则按签名归因禁重试刷绿。

## 10. 外部终验

推送后下一次外部门槛（GitHub Actions，第 38 次）CI 全绿为外部终验；新 run 号以文本登记，不扩受保护 token 集合。

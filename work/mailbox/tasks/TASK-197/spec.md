# 任务书：契约门 dotfile 盲区补认（TASK-197 / fix-contract-dotfile-claims）

唯一指令源：本任务书 + spec/changes/fix-contract-dotfile-claims/。冲突以本任务书为准。全程 bash 经 `D:\git\Git\bin\bash.exe`；命令行参数禁携中文；唯一验证入口 scripts/verify/mvn-verify.sh。本课题授权单点解除「禁触碰 scripts/verify/*」约束，仅限 mailbox-contract.sh 行 131 alternation 追加。

## §0 红线（十条，违反即 FAILED）

1. **白名单制改动面**：C-01 恰 1 文件 `scripts/verify/mailbox-contract.sh`（行 131 扩展名 alternation 逐字面追加 `|trivyignore`，单点）；C-02 恰 4 台账文件（§4）。其余零触碰。
2. **最小改动**：主名字符类 `[A-Za-z0-9_./-]+` 禁改为 `*`；alternation 其余条目零改动；脚本其余函数与逻辑零改动。
3. **探针实证**：三态探针（正向/负向/回归）全跑并留证；fixture 置于 `.trae/tmp/` 用后删。
4. **历史不改判**：TASK-196 `--open` 回顾态复测结果仅登记（预期 rc=1 属书写规范约束），禁为使其变绿做任何额外修改。
5. **offline 569 逐位不变** + static 双模块（record + leaderboard）rc=0（脚本改动不涉 Java，仍按 FULL 口径留证）。
6. **token 29 项只增不减**（开工实测）；新文档禁枚举字面量；台账纯追加。
7. **零收益百分比 claim**。
8. **提交纪律**：两笔 `type(scope): description (TASK-197)`，未推送不建 PR；CI 红禁重试刷绿；修改脚本保持既有行尾，`git diff --check` 干净。
9. **词面门**：改动文件集 ZERO_HIT（mailbox-contract.sh 新增行仅 `trivyignore` 单词）。
10. **dotfile 书写规范落地**：本任务书 §4 与 handoff 中 `.trivyignore` 相关声明一律 `/.trivyignore` 形态书写（自证规范可被提取）。

## §1 现状与根因（复核实证，不必重查）

- 行 131 正则 `[A-Za-z0-9_./-]+\.(sh|md|java|...|example|editorconfig)`：主名 `+` 非空 + 扩展名白名单；裸 `.trivyignore`（前导反引号/空格）不匹配 → 在途态判据 B 误报（TASK-195 D3 / TASK-196 N1，两次复核实测 rc=1）。
- `/.trivyignore` 形态可被现有结构提取（前导 `/` 充当主名字符）。

## §2 预注册设计

- **§2.1 修复**：行 131 alternation 末尾追加 `|trivyignore`。
- **§2.2 探针三态**：态1 正向（fixture 任务书声明含 `/.trivyignore` + 临时基线含 `.trivyignore` 改动 → `--open` 判据 B 通过）；态2 负向（声明移除 → 误报「改动集未声明」复现，证明探针有效）；态3 回归（常规非 dotfile fixture → 提取 token 集与修复前逐 token 一致）。
- **§2.3 真实链路**：`--open TASK-197 --baseline=<派发笔哈希>` 在途态 rc=0（本任务书 §4 含 `/.trivyignore` 声明示例）；无参态 rc=0。
- **§2.4 历史登记**：`--open TASK-196 --baseline=f701ece` 回顾态复测登记（预期 rc=1，书写规范约束非缺陷）。

## §3 停止条件

1. 探针态1 无法通过（正则追加后 `/.trivyignore` 仍不提取）；
2. 探针态3 出现提取差异（常规场景回归）；
3. 需改动行 131 之外的任何脚本内容或第二文件（实施笔）；
4. offline/static/token 任一破防；
5. 需触碰白名单外文件。

## §4 白名单（只改清单）

- **C-01 实施笔**（恰 1 文件）：scripts/verify/mailbox-contract.sh
- **C-02 台账笔**（恰 4 文件）：spec/changes/fix-contract-dotfile-claims/tasks.json、work/mailbox/tasks/TASK-197/spec.md（§7 回填）、work/mailbox/tasks/TASK-197/handoff.md（新建）、work/mailbox/PLAN.md（纯追加）
- 提交主题：C-01 `fix(verify): 契约门extract_claims补认trivyignore扩展名（TASK-197）`；C-02 `docs(mailbox): 登记TASK-197契约门dotfile补认验收与台账闭环（TASK-197）`
- 书写规范示例：本课题不触碰 `/.trivyignore` 本体（.trivyignore 文件零改动，属 TASK-196 收口资产）

## §5 受保护 token（29 项）

开工实测 SUM（预期 2030 持平）→ 收口复测只增不减；清单见 TASK-187 handoff §7；字面量禁写入新文档。

## §6 门禁结构

- **开工规程**：基线 HEAD=派发笔、工作区净、token 开工实测、正则行现状留证（sed -n '131p'）
- **预提交门禁**：探针三态读数齐；`git diff --check` 干净；行尾核验；词面门 ZERO_HIT
- **收口门禁**：offline 569 + static 双模块（mvn-verify.sh 亲跑留证）；契约门三态（无参 / `--open TASK-197 --baseline=<派发笔>` / 历史 TASK-196 回顾登记）；token 复测；只改清单全等；零越界；台账纯追加
- **外部终验**：push 后第 38 次 CI 全绿，红则按签名归因禁重试

## §7 收口记录（执行侧 C-02 纯追加回填）

### §7.1 提交记录

- 派发笔：`720a7719fed2a6b8fcebd5c7408b1f953edbb6a8`（`720a771`）
- C-01 实施笔：`22100d5a57a1d545161068882953a398925a217a`（`22100d5`）
- C-02 台账笔：`（本笔自指：显式哈希以回传报告与 handoff §8 给出）`

### §7.2 实施读数（回填：行 131 修复前后对照 / 探针三态读数 / 契约门三态读数）

- 行 131 对照：修复前 alternation 止于 `editorconfig`（`sed -n '131p'` 留证）；修复后逐字面追加 `|trivyignore`；`git diff` 恰 1 增 1 删，主名字符类与其余条目、脚本其余逻辑零改动（全量对照见 handoff §5.1）。
- 探针三态（fixture 置 .trae/tmp/ 用后删，临时 git 仓全链路实跑）：态1 正向 rc=0 判据 B 通过（声明含 `/.trivyignore` 的 `<路径>/` 形态 + 临时基线含该 dotfile 改动）；态2 负向 rc=1 移除声明后「改动集未声明」签名复现（修复前后读数一致，探针有效）；态3 回归 14 项常规 token 修复前后逐 token 全等 + 整门输出逐字节一致（零回归）。旁证（handoff §4 F1）：纯前导斜杠 `/.trivyignore` 提取成功但与 git 改动集形态不归一、判据 B 双向不过；根目录 dotfile 可通过判据 B 的书写形态为 `<路径>/` 前缀（含 `./` 前缀，经既有 sed 归一）。
- 契约门三态（读数时点显式登记，沿 TASK-196 实测时点教训）：
  - 在途态 `--open TASK-197 --baseline=720a771`：rc=0，TASK-197 判据 B 通过（只改清单 5 文件与实际改动集全等；C-02 内容落盘后、提交前在途态实测；提交后收口态复跑见回传报告）。
  - 回顾态 `--open TASK-196 --baseline=f701ece`：rc=1，TASK-196 判据 B 失败且「改动集未声明」含 `/.trivyignore`（N1 签名持续，裸书写形态所致，书写规范约束非脚本缺陷）；TASK-197 亦红属基线错配 artifact（其 5 项声明全在实际改动集、无清单多报）；仅登记不改判（红线 4）。
  - 无参态：收口态（C-02 提交后）复跑预期 rc=0（已收口不重审路径），终值以回传报告为准；提交前工作树窗口实测 rc=1 属时点窗口 artifact（C-01 已提交而 C-02 在途，无参态以 HEAD 为基线报 C-01 项清单多报），非缺陷。

### §7.3 门禁读数（回填：offline / static 双模块 / token / 只改清单 / diff --check / 行尾）

- offline：`--mode=offline test` 分模块 `36/44/127/137/149/64/12` = 569 逐位（TASK-196 收口真值一致），rc=0。
- static 双模块：record-service rc=0（Checkstyle 0 / SpotBugs 18 Medium 基线持平 / PMD 0）；leaderboard-service rc=0（Checkstyle 0 / SpotBugs 9 Medium 属 failThreshold=High 不拦截、非本课题改动面所致，按实测登记见 handoff §4 F3 / PMD 0）。
- token：29 项 SUM 开工 2030 / C-01 后 2030 / 收口 2030（收口径用 `--untracked` 含未跟踪台账），只增不减；词面门 ZERO_HIT（mailbox-contract.sh 新增行仅 trivyignore 单词）。
- 只改清单：C-01 恰 1 文件、C-02 恰 4 文件，与 `git diff --name-only 720a771..HEAD` 全等，零越界。
- `git diff --check` rc=0；脚本 blob i/lf、工作区 w/crlf 保持既有行尾（F2 重建后复验）。

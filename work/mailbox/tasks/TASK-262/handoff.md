# TASK-262 执行交接（handoff）

> 状态：**已立项待实施**（2026-10-17 指导侧立项；spec 定稿，开工基线 = TASK-262 立项笔哈希，执行侧开工实测回填本档）

## 1. 开工基线

- 开工基线：main @ `<TASK-262 立项笔哈希>`（执行侧开工时 `git rev-parse HEAD` 实测回填，开工时 `git status --porcelain` 实测为空）
- 领先笔数：执行侧开工实测 `git rev-list --count origin/main..HEAD` 回填（预期含 TASK-261 补记笔 + TASK-262 立项笔等本地保留笔；禁推送约束不变，推送由指导侧批次统一执行）
- 任务书：`work/mailbox/tasks/TASK-262/spec.md`（白名单/修法/门禁/受限声明以 spec 为准）

## 2. 改动集清单（C-01/C-02 落库后逐行回填，与 `git diff --name-only <开工基线>` 双向全等）

- 改动清单：（执行侧回填，逐行路径 token 供判据 B 机器比对；**叙述行避免出现文件名带扩展名形态的字符串**——契约门提取陷阱，TASK-261 先例）
- 未跟踪文件核验：（C-01 前 `git ls-files -o --exclude-standard` 实测回填）
- spec §2 白名单双向全等自证：（回填；两处执行侧选定落点 = user 控制器测试适配落点、leaderboard 测试落点，C-01 前登记）

## 3. 门禁读数（实测回填）

| # | 门禁 | 命令 | rc | 关键读数 |
| --- | --- | --- | --- | --- |
| 1 | 后端 offline 全量七模块 | `mvn-verify.sh --mode=offline test`（经 D:\git\Git\usr\bin\bash.exe --login） | 待填 | BUILD SUCCESS；surefire 逐位回填（基线 63/78/218/318/202/109/35 只增不减；**record 侧删用例等量补位须对账**：R6 删除数 vs R1/R3/R6 新增数） |
| 2 | 契约门 | `mailbox-contract.sh --open=TASK-262 --baseline=<开工基线>` | 待填 | C-01 前 / C-01 后（台账在途态）/ C-02 后三时点实测 |
| 3 | token 门 29 项 | `git grep -cF` 逐项（清单沿 TASK-181 handoff §6，全仓匹配行累加口径沿 TASK-257） | 待填 | 期望值口径：**C-02 树 = 基线 + 29**（handoff 自枚举效应，TASK-261 教训直写；逐位明细回填） |
| 4 | 词面门 | 模式文件法 + 探针阳性对照 | 待填 | 四 locale 形态 ZERO_HIT + 探针活性坐实（阳性探针文件非命令行中文，含三排除路径口径） |
| 5 | checkstyle delta=0 | `mvn-verify.sh --static=<模块>` + 纯净 worktree 双向对照 | 待填 | 有 diff 模块逐个双向实测（record/user/leaderboard/verify/api 预期均有 diff） |
| 6 | diff 净与行尾 | `git diff --check` / `git ls-files --eol` | 待填 | 净 + 修改文件保持既有行尾（编辑器整文件转 LF 需 perl raw-IO 归一回，TASK-261 先例） |
| 7 | mapmatch 照跑 | offline 全量内含 | 待填 | 35 持平预期（本笔零 mapmatch 文件，照跑） |

## 4. 条件项与非阻断登记（如实回填）

- R2 jitter 实现形态（TtlFunction 或等价）与幅度定档：C-01 前登记
- R4 前缀常量与 outbox 状态常量落点（同包零新增文件优先）：C-01 前登记
- R5 序列化器选定与缓存名/键前缀升版形态：C-01 前登记
- R6 测试处置对账（删除清单 vs 补位清单，record surefire 只增不减红线）：C-01 前登记
- R1 两类契约 delta 用例落点（record 3001→404 / Auth 1002→403）：C-01 前登记
- spec §5 受限五项沿 spec 口径如实登记

## 5. 收口自证

- `git status --porcelain` 输出为空（C-02 后实测回填）；`.trae/tmp` 临时件收口清理（模式文件/探针/消息文件/基线 worktree remove）。
- 未推送：`origin/main..HEAD` 收口后笔数回填——推送由指导侧批次统一执行，本笔零推送。
- spec/handoff 状态行回填「已实施待复核」；契约门 C-02 后终态复跑 rc 读数回填。

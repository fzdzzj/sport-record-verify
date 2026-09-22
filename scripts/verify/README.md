# 统一验收入口

本目录承载本仓**唯一**的 Maven 验收命令定义。判定一次改动是否通过，只从这里取命令：
根 `README.md` 与 `.github/workflows/ci.yml` 均引用 `mvn-verify.sh`，不再各自复述参数组合。

| 文件 | 用途 |
| --- | --- |
| `mvn-verify.sh` | 统一验收入口（命令拼写的唯一定义处） |
| `mailbox-contract.sh` | 派发—回传契约校验（任务目录两件套 + 只改清单与工作树比对），与 Maven 入口职责正交 |
| `env.example` | 本机环境变量样例：复制为仓库根 `.env` 后按机器改（端口覆盖、中间件占位口令、真库端到端测试三个变量）；`.env` 本身不入库 |
| `README.md` | 本文件：用法、退出码语义与依赖来源判据 |

## 用法

```bash
bash scripts/verify/mvn-verify.sh [--mode=auto|offline|online] [--pl <模块>] [--it] [test|verify|package]
```

| 参数 | 含义 |
| --- | --- |
| `--mode=offline` | 拼 `-o -s .mvn-settings.xml`，并在调用 Maven **之前**校验该 settings 声明的 `localRepository` 目录真实存在 |
| `--mode=online` | 剥离 `-s` 与 `-o`，依赖来源交由 Maven 自身解析。CI 固定用这一档，作为对外权威结论 |
| `--mode=auto`（默认） | settings 文件与其 `localRepository` 目录都在位 → offline；否则 → online |
| `--pl <模块>` | 只构建该模块及其上游，自动补 `-am` |
| `--it` | 定向执行需要真实 MySQL 的端到端测试，见下节 |
| 阶段参数 | `test`（默认）\| `verify` \| `package` |

固定行为：

- 请求的阶段**前总是先执行 `clean`**。不带 clean 的构建会复用上一轮 `target/` 产物，
  上一轮残留足以让一条已经失效的断言继续给出结论——入口不接受这种"绿"。
- 调用 Maven 前先打印四行来源信息：生效模式、settings 路径、`localRepository`、命令全文。
- 不吞 Maven 的原始输出，原样透传其退出码。

`localRepository` 由脚本对 settings 文件做纯文本解析得到（跳过 XML 注释后取首个
`<localRepository>` 值），不依赖任何 Maven 插件。

## 退出码语义

| 码 | 含义 | 记账口径 |
| --- | --- | --- |
| 0 | 构建与用例全绿 | 通过 |
| 1 | Maven 自身失败：编译或用例红 | 用例/构建失败 |
| 3 | **依赖来源不可判定**：offline 模式下 settings 缺失、解析不出 `localRepository`，或该目录不存在 | 环境/依赖面失败，**不得记为"用例红"，也不得记为通过** |
| 2 | 参数用法错误 | 未执行 |

`3` 与 `1` 必须分开记：一次因为离线仓缺件而没跑起来的验收，不是一次红测试。

## 真库端到端测试

```bash
bash scripts/verify/mvn-verify.sh --it
```

展开为 `-pl leaderboard-service -am test -Dtest=LeaderboardDailySummaryMapperMysqlIT`
（`-am` 会连带 `common`/`api` 等没有该测试的模块，故同时带上
`-Dsurefire.failIfNoSpecifiedTests=false`，否则在无匹配的模块上直接失败）。

前提：三个环境变量任一缺失，该测试即被 `assume` 跳过——

| 变量 | 用途 |
| --- | --- |
| `TASK108_IT_URL` | JDBC URL，指向 scratch 准备库 |
| `TASK108_IT_USER` / `TASK108_IT_PASSWORD` | 该库账号 |

**被跳过不得计入通过**：脚本在缺变量时会往 stderr 打印一条"按口径记为未覆盖"的提示，
Maven 自身仍以 0 退出（surefire 不把 assume 跳过当失败），所以这条提示必须进台账。

## 与两种依赖来源的关系

`.mvn-settings.xml` 与其 `localRepository` 指向的仓内离线仓库**都不入库**，且 settings 内含
机器绝对路径——新 clone 里它根本不存在。因此：

- offline 模式只在已经备好离线仓的本机可用，用于贴近日常开发的重跑；
- **online 模式是唯一对外权威口径**（CI 用的就是它）；
- 两模式依赖集不同、结论不一致时，以 online 结论为准，并把差异与缺失构件记进台账，
  不要把差异写成"测试变红"。

---

## 派发—回传契约校验（`mailbox-contract.sh`）

与 `mvn-verify.sh` 并列的第二个入口，职责正交：Maven 入口验依赖与用例，本脚本验台账契约，
把任务包的"只改白名单"从提示词文字落成可机械执行的校验。不改 `mvn-verify.sh` 本体。

```bash
bash scripts/verify/mailbox-contract.sh [--ledger=<dir>] [--open=<task,...>] [--baseline=<ref>] [--diff-file=<path>]
```

| 参数 | 含义 |
| --- | --- |
| `--ledger=<dir>` | 任务台账目录（默认 `<仓库根>/work/mailbox/tasks`） |
| `--open=<task,...>` | 判据 A 放行的"仅 `spec.md` 无 `handoff.md`"进行中任务目录名列表（逗号分隔） |
| `--baseline=<ref>` | 判据 B 的 diff 基线（默认 `HEAD`，视为开工基线） |
| `--diff-file=<path>` | 判据 B 的"实际改动集"来源：逐行路径文件；缺省用 git 工作树计算（排除 `.trae/` 与本机残留） |

**判据 A（两件套完备）**：任务目录须同时含 `spec.md` 与 `handoff.md`。
仅含 `spec.md` 判定为进行中任务：声明于 `--open` 则列待办放行，否则判据 A 失败；
仅含 `handoff.md`（有回传无契约）或两者皆无但目录非空（异常态）→ 判据 A 失败；空目录忽略。

**判据 B（清单比对）**：把回传「改动清单」节声明的只改文件集与工作树相对基线的实际改动集比对。
二者有交叠（回传在途）→ 要求完全一致，清单多报或改动集未声明任多出一文件都判据 B 失败；
二者无交叠 → 视为已收口，不重审既有台账。只改清单只从「改动清单」节解析，正文引用不算改动。
清单提取的扩展名白名单含 `.example`（如 `env.example`），避免声明了却判法上无法提取而误记为未声明。

**退出码**：

| 码 | 含义 |
| --- | --- |
| 0 | 通过（判据 A 两件套齐，含声明的待办进行中；判据 B 清单一致） |
| 1 | 契约或结构不符：判据 A 失败或有在途回传判据 B 失败 |
| 2 | 参数使用错误 |
| 3 | 差异来源不可判定：非 git 上下文且未给 `--diff-file`，或基线/命令无法解析——不是契约红，也不得记为通过 |

本仓当前进行中任务（仅 `spec.md`）：`--open=TASK-018`。

# 统一验收入口

本目录承载本仓**唯一**的 Maven 验收命令定义。判定一次改动是否通过，只从这里取命令：
根 `README.md` 与 `.github/workflows/ci.yml` 均引用 `mvn-verify.sh`，不再各自复述参数组合。

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

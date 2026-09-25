# TASK-140：提交链路 DB 等待归因与重复负载核验（只测量不优化）

## 目标

在 TASK-139 单次前后对比的证据等级问题上，按 `spec/changes/measure-submit-db-wait-evidence/`
三件套的门槛执行：订正历史归因措辞、只读实测指标可得性、视门槛决定最小插桩、
有限重复同负载记录波动、一次作业收口。**不实施任何性能优化。**

规范来源：`spec/changes/measure-submit-db-wait-evidence/`（proposal.md / tasks.json /
spec-delta.md）。本任务是一次完整作业，不拆多轮回传。

## 开工基线

- HEAD 应为 `eb624131674c2c0bff7bcd9257ffd4ce83c707fe`。实际核对一致后开工。
- 既有脏项不触碰：archive 移名（wire-verify-outbox / adopt-native-mq-retry）、
  `.codex/`、`.trae/`、`spec/changes/add-verify-degrade-status-index/`。
- TASK-138 原始 `head-c100-*`、TASK-139 原始 `dbfoot-c100-*` 与 `split-c100-*` 不得覆盖。

## 门槛与停止条件（要点）

1. 冻结订正：TASK-139 复测报告/摘要 JSON/handoff 追加更正（原始数字不动）；
   必要时给 TASK-138 归因文档补交叉引用。
2. 指标门槛：先只读检查 `/actuator/metrics` 与 Prometheus 是否实际暴露内层 Hikari
   计量项；留脱敏证据。拿不到就不冒称。
3. 插桩门槛：仅当指标缺失且位置可证时，加默认关闭、有界、不改业务的最小计时；
   不扩大依赖、不碰 ShardingSphere 私有 API；无法可靠分离的份额如实记未覆盖。
4. 容量与负载门槛：确认磁盘与库容量（每轮 2010×300 点）；计时关重复与 TASK-139
   dbfoot 组成三轮，新增负载总数 ≤3（计时开跑次计入且不混组）；独立 label；
   任一失败只记退出码，不反复试错。
5. 收口：新测量报告 + 可解析摘要 + 台账/契约 + 本地提交；不 push、不建 PR。

## 负载与环境

- Shell：`D:\git\Git\bin\bash.exe`；命令行禁止中文；提交用 `git commit -F` UTF-8 文件。
- MySQL 容器 3307；起栈 `bash scripts/perf/run-perf.sh start-services`。
- label：rpt1 / rpt2 / connwait；禁止复用 head/dbfoot/split。
- Maven 唯一入口 `bash scripts/verify/mvn-verify.sh --mode=offline ...`。

## 判别式

1. 指标门槛证据：actuator/prometheus 实测输出留档于报告。
2. 插桩判别式：关闭态零样本、回滚不计成功、开启态驻留封顶 cap、人群仅提交线程
   （SubmitTxTimingTest + SPI 接线判别 + submit 接线判别 + 主 yaml 池类判别）。
3. Maven 门槛 rc=0，record-service 用例只增不减。
4. 三轮负载各自留 raw；报告逐轮 QPS/P50/P95/P99/错误率 + 范围 + 新鲜度，不算因果百分比。
5. 收口提交后 `bash scripts/verify/mailbox-contract.sh` 无参数 rc=0。

## 回传必须包含

实际 HEAD 与提交哈希、指标门槛实测结果、三轮负载指标与范围、connWait 直接测量值、
Maven/契约退出码、只改清单、未覆盖项。

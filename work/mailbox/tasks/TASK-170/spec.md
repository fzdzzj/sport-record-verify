# TASK-170 spec：单行投递逻辑重构（统一 processRow 与 sendAndCollect 为单一 sendRow）

## 1. 唯一问题与背景
在 TASK-165 引入分块批量标记并在 TASK-169 正式默认落地（`relay-batch-mark-enabled: true`）的过程中，`VerifyOutboxRelay.java` 内部并存了两份高度重复的单行处理逻辑：
1. `processRow`（逐行标记路径，L329-386）：用于关闭态下的串行与并发投递；
2. `sendAndCollect`（分块标记路径，L491-530）：用于分块标记态下的串行与并发投递。

两份实现包含完全重复的防御性耗尽行拦截（`row.getRetryCount() >= maxRetry` 告警并跳过）、消息同步发送（`verifyEventProducer.syncSend(row)`）、发送失败单行重试递增补偿（`outboxMapper.incrRetry(row.getId())`）以及对应的纳秒耗时诊断统计（`sendNanos` 与 `incrRetryNanos`）。

TASK-166 在 PLAN.md 中正式登记了该技术债（「后续把两份单行语义 `processRow` 与 `sendAndCollect` 统一为一个 `sendRow`，消除单行语义分叉风险」）。
本任务核心问题：如何通过纯结构重构，将两份单行处理体完全统一为单一的 `sendRow` 方法，在**零生产行为变化、零配置变化、测试全绿、Checkstyle 违规不增加（<=867）**的前提下彻底闭环此技术债？

## 2. 重构设计规范

### 2.1 方法签名与便捷重载
定义统一的 `sendRow` 核心方法：
```java
private void sendRow(final VerifyEventOutbox row,
                     final int chunkSize,
                     final List<Long> pendingIds,
                     final boolean diagEnabled,
                     final RelayTotals totals)
```
以及逐行标记路径专用的便捷重载方法（避免在逐行路径调用点传递无意义的 0 与 null）：
```java
private void sendRow(final VerifyEventOutbox row,
                     final boolean diagEnabled,
                     final RelayTotals totals) {
    sendRow(row, 0, null, diagEnabled, totals);
}
```

### 2.2 核心方法内部逻辑（三大段结构）
1. **耗尽行防御性拦截**（逐字保持既有逻辑）：
   检查 `row.getRetryCount() != null && row.getRetryCount() >= maxRetry`，命中则累加 `totals.exhausted++`，输出原 ERROR 日志并直接返回。
2. **发送段**（逐字保持既有 `syncSend`、异常补偿与纳秒计时）：
   - `verifyEventProducer.syncSend(row)`，记录 `sendNanos`；
   - 抛出异常时记录 `sendNanos`，调用 `outboxMapper.incrRetry(row.getId())` 并记录 `incrRetryNanos`，累加 `totals.failed++`，输出原 WARN 日志并返回。
3. **标记段**（依据 `pendingIds` 是否为 null 进行模式分流）：
   - **逐行标记模式（`pendingIds == null`）**：
     执行单行 `outboxMapper.markSent(row.getId())`，成功则记录 `markNanos`、累加 `totals.success++` 并输出原逐行 INFO 日志；抛出异常则记录 `markNanos`、调用 `outboxMapper.incrRetry(row.getId())` 补偿并记录 `incrRetryNanos`、累加 `totals.failed++` 并输出原 WARN 日志。
   - **分块批量标记模式（`pendingIds != null`）**：
     将当前行 ID 加入 `pendingIds`（`pendingIds.add(row.getId())`）；若 `pendingIds.size() >= chunkSize`，调用现有的 `flushBatchMark(pendingIds, diagEnabled, totals)`。

### 2.3 调用点与死代码消除
- 彻底删除原私有方法 `processRow` 与 `sendAndCollect`。
- 将 `relayMessages` 串行逐行标记处的 `processRow(row, diagEnabled, totals);` 替换为 `sendRow(row, diagEnabled, totals);`。
- 将 `runShard` 并发逐行标记循环处的 `processRow(row, diagEnabled, totals);` 替换为 `sendRow(row, diagEnabled, totals);`。
- 将 `deliverBatchMarkSerial` 分块标记循环处的 `sendAndCollect(...)` 替换为 `sendRow(row, chunkSize, pendingIds, diagEnabled, totals);`。
- 将 `runBatchMarkShard` 并发分块标记循环处的 `sendAndCollect(...)` 替换为 `sendRow(row, chunkSize, pendingIds, diagEnabled, totals);`。
- 订正 Javadoc 注释：消除 TASK-166 留存的“两者的发送/耗尽/incrRetry 语义必须同步维护，存在漂移风险”临时告警，恢复为统一的单行语义说明。

## 3. 硬性红线与约束
1. **只改白名单**：
   - 生产代码仅允许修改 `verify-service/src/main/java/com/sportverify/verify/mq/VerifyOutboxRelay.java`；
   - 台账仅允许修改 `work/mailbox/PLAN.md` 以及新增 `work/mailbox/tasks/TASK-170/` 两件套；
   - 严禁触碰 `application.yml`、pom.xml、构建脚本、主规格 `spec.md` 或其他服务代码。
2. **测试与静态检查零回退**：
   - 全量 offline 测试必须保持 **36/41/33/103/140/59/10** 全绿，Skipped 全 0；
   - `verify-service` 模块必须保持 140 个测试用例全部通过；
   - Checkstyle violations 严格保持 `<= 867`（绝不增加任何新的 Checkstyle 违规）；
   - 代码行长严格遵守不超过 120 字符，参数加 `final`，Javadoc 格式规范。
3. **词面门与契约门**：
   - 四形态（CI 原样 / `LC_ALL=C` / `zh_CN.UTF-8` / `C.UTF-8`）词面门必须全部 `ZERO_HIT rc=1`；
   - 在途契约门 `--open TASK-170 --baseline=037d39ab7a981383e113172d29c6a04ee50ce6d3` 必须 rc=0；
   - 收口后无参契约门必须 rc=0。
4. **PLAN 21 个受保护 token 数量不得减少**。
5. **Git 纪律**：
   - 严禁 `git add -A` 或 `git add .`（逐路径 add）；
   - 严禁私自 push、严禁建 PR、严禁 stash；
   - 严禁触碰既有脏项 `spec/changes/add-verify-degrade-status-index/`。

## 4. 门禁定义（G0–G8）
- **G0 起点核对**：§8 全 SHA 逐位一致，工作树仅含既有脏项与本任务。
- **G1 单行语义统一确证**：
  `VerifyOutboxRelay.java` 中不再存在 `processRow` 与 `sendAndCollect` 标识符；
  存在且仅存在统一的 `sendRow` 处理体；
  耗尽检查与 `syncSend` 逻辑在整个文件中恰好出现 1 次。
- **G2 保护件零改动**：
  `VerifyOutboxRelayTest`、`VerifyOutboxRelayConcurrencyTest`、`VerifyOutboxRelayBatchMarkTest` 等测试文件零改动且全绿。
- **G3 生产配置零改动**：
  `git diff --exit-code HEAD -- verify-service/src/main/resources/application.yml` 退出码为 0。
- **G4 全量 offline 测试门**：
  `bash scripts/verify/mvn-verify.sh --mode=offline test` 退出码 0，七模块 **36/41/33/103/140/59/10**，Skipped 全 0。
- **G5 静态代码门**：
  `--mode=offline --static=verify-service` 退出码 1，Checkstyle violations 严格 `<= 867`。
- **G6 词面门**：
  从 `.github/workflows/ci.yml` 提取正则（PAT_LEN=26），四形态全 `ZERO_HIT rc=1`；正向对照探针 rc=0。
- **G7 空白检查门**：
  `git diff --check` 退出码 0，零尾随空格，文件末尾保留单个 LF。
- **G8 契约门与台账**：
  在途 `--open TASK-170 --baseline=037d39ab7a981383e113172d29c6a04ee50ce6d3` 退出码 0；
  收口后无参 `bash scripts/verify/mailbox-contract.sh` 退出码 0；
  PLAN.md 追加验收记录并闭环 TASK-166 欠账，21 个受保护 token 数量无一减少。

## 5. 开工读数（指导侧亲跑基线；任一不符停手回报原文）
- HEAD = `037d39ab7a981383e113172d29c6a04ee50ce6d3`
- origin/main = `9050964699c8b802ae9da22980939d78b2d31bdc`
- `git rev-list --left-right --count origin/main...main` = `0 1`
- 工作树脏项仅 `?? spec/changes/add-verify-degrade-status-index/`（既有，零触碰）＋ 本任务目录
- 全量 offline 测试：`bash scripts/verify/mvn-verify.sh --mode=offline test` → rc=0，七模块 `36/41/33/103/140/59/10`，Skipped 全 0
- 静态门：`--mode=offline --static=verify-service` → rc=1，Checkstyle violations 严格为 `867`
- 契约门：在途无参 rc=1（预期），`--open TASK-170 --baseline=037d39ab7a981383e113172d29c6a04ee50ce6d3` rc=0；收口后无参必须 rc=0
- `VerifyOutboxRelay.java`：785 行（wc -l）；L114 `relay-batch-mark-enabled`、L123 `relay-batch-mark-chunk-size`、L189 `processRow`、L329 `processRow` 方法定义、L452 `processRow`、L475 `sendAndCollect`、L491 `sendAndCollect` 方法定义、L655 `sendAndCollect`
- `application.yml`：200 行，CR=6（工作树视角）/ CR=0（blob 视角），`relay-batch-mark-enabled: true`
- 主规格 `spec/specs/sport-record-verify/spec.md`：3568 行 / 161 个 `### Requirement:`，头部清单 58，归档 60，在途 7
- `work/mailbox/PLAN.md`：1429 行，CR=0
- 词面门：从 `.github/workflows/ci.yml` 提取（PAT_LEN=26），四形态全 ZERO_HIT rc=1
- 21 个受保护 tokens 基线（在 PLAN.md 中行命中数）：
  `13.4=14`、`18.0=16`、`73.93=15`、`68.8=11`、`6315=12`、`1.8612=11`、`3.3066=11`、`5.7056=11`、`9.408=11`、`36525962432=11`、`36586847965=10`、`36438897772=11`、`36399582548=10`、`36098038547=10`、`2806=17`、`598=10`、`36736221648=9`、`36808102571=4`、`36821040708=2`、`36845152965=1`、`36871294588=1`

## 6. 只改清单（超出即红）
- 允许改动与新增路径：
  - `work/mailbox/tasks/TASK-170/spec.md`（本任务书）
  - `work/mailbox/tasks/TASK-170/handoff.md`（交付报告）
  - `verify-service/src/main/java/com/sportverify/verify/mq/VerifyOutboxRelay.java`（重构目标）
  - `work/mailbox/PLAN.md`（台账更新与欠账闭环）

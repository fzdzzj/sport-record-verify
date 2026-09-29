# TASK-163 handoff：排空斜率判别与条件式落地（落地支，已落地）

## 1. 结论一句话

**落预注册「落地支」**：4 个计数轮全部有效（逐轮 M1 空档门 + M4 健康与语义门通过），M2 效应门通过（`slope(B1)/slope(A1) = 3.7559 ≥ 1.5`，`slope(B2)/slope(A2) = 3.4285 ≥ 1.5`，均落预注册区间 [2.5, 3.5]），M3 排序控制门通过（`|slope(A2) - slope(A1)| / slope(A1) = 5.68% ≤ 20%`），M5 代价门通过（`Com_select` B/A 增量比 0.9976 ≤ 1.5）。

按任务书 §8 实施落地：
1. `verify-service/src/main/resources/application.yml` 纯新增 `verify.outbox.relay-interval-ms: 500`（numstat `6 0`，根键 `^verify:` 恰 1 个）；
2. 新增纯 JUnit 5 测试类 `VerifyOutboxRelayIntervalDefaultTest.java`（3 用例，测试 120→123 全绿，零 `@SpringBootTest`、零中间件依赖）；
3. C 确认轮验证通过：M1 >1s 空档数 1 ≤ 5（中位 1541 ms），`slope(C) / mean(slope(A1), slope(A2)) = 45.0670 / 15.6315 = 2.8831 ≥ 1.5`，成功落地，零回滚。

## 2. 起点 SHA、四笔提交与 shortstat

- 起点 HEAD = `1ff96e0697694b6ec9669bc35c7234f2efdfe92d`（开工 `git rev-parse HEAD` 逐位核对一致；`git rev-list --left-right --count origin/main...main` = `0	1`；本任务不得 push）。
- 分批提交（落地支 4 笔）：
  - C1 = 配置落地与测试（`application.yml` + `VerifyOutboxRelayIntervalDefaultTest.java`）
  - C2 = 报告与 JSON（`docs/perf/判别-outbox-relay-排空斜率.md` + `docs/perf/data/exp-outbox-relay-drain-rate.json`）
  - C3 = 三件套纯 ADDED（`proposal.md` + `spec-delta.md` + `tasks.json`）
  - C4 = 台账（`work/mailbox/PLAN.md` + `work/mailbox/tasks/TASK-163/{spec.md, handoff.md}`）
- 每笔入册文件清单（提交后复采，与索引快照逐字一致）：

```
C1：
verify-service/src/main/resources/application.yml
verify-service/src/test/java/com/sportverify/verify/config/VerifyOutboxRelayIntervalDefaultTest.java

C2：
docs/perf/data/exp-outbox-relay-drain-rate.json
"docs/perf/å¤å«-outbox-relay-æç©ºæç.md"

C3：
spec/changes/prove-verify-outbox-relay-drain-rate/proposal.md
spec/changes/prove-verify-outbox-relay-drain-rate/specs/sport-record-verify/spec-delta.md
spec/changes/prove-verify-outbox-relay-drain-rate/tasks.json

C4：
work/mailbox/PLAN.md
work/mailbox/tasks/TASK-163/spec.md
work/mailbox/tasks/TASK-163/handoff.md
```

- 全程未 push、未建 PR、未 `git stash`、未 `git add -A`/`add .`（逐路径 add）。

## 3. 只改清单逐项对齐（落地支：10 条实际改动路径）

```
verify-service/src/main/resources/application.yml
verify-service/src/test/java/com/sportverify/verify/config/VerifyOutboxRelayIntervalDefaultTest.java
docs/perf/判别-outbox-relay-排空斜率.md
docs/perf/data/exp-outbox-relay-drain-rate.json
spec/changes/prove-verify-outbox-relay-drain-rate/proposal.md
spec/changes/prove-verify-outbox-relay-drain-rate/specs/sport-record-verify/spec-delta.md
spec/changes/prove-verify-outbox-relay-drain-rate/tasks.json
work/mailbox/PLAN.md
work/mailbox/tasks/TASK-163/spec.md
work/mailbox/tasks/TASK-163/handoff.md
```

### 3b. 零修改声明（显式）与逐项说明

任务书 §4 只改清单 6 项逐项对齐：
1. `docs/perf/判别-outbox-relay-排空斜率.md` —— **新增**（C2）。对齐第 1 项。
2. `docs/perf/data/exp-outbox-relay-drain-rate.json` —— **新增**（C2）。对齐第 2 项。
3. `verify-service/src/main/resources/application.yml` —— **纯新增 6 行**（C1，落地支）。对齐第 3 项。
4. `verify-service/src/test/java/com/sportverify/verify/config/VerifyOutboxRelayIntervalDefaultTest.java` —— **新增纯 JUnit 5 测试类**（C1，落地支）。对齐第 4 项。
5. `spec/changes/prove-verify-outbox-relay-drain-rate/`（`proposal.md` + `spec-delta.md` + `tasks.json`）—— **新增，纯 ADDED**（C3）。对齐第 5 项。
6. `work/mailbox/PLAN.md`（**纯追加 1 节**，28 insertions / 0 deletions）+ `work/mailbox/tasks/TASK-163/spec.md`（指导侧任务书原样入库）+ `work/mailbox/tasks/TASK-163/handoff.md`（本文件新建）。对齐第 6 项。
7. 忽略路径说明：`docs/perf/data/raw/task163-*` 与 `.trae/tmp/*` 均在 `.gitignore` 规则内，不入库。

**显式未改动声明（全部零触碰）**：生产 Java 代码（`VerifyOutboxRelay.java`、`RelayDiagnostics.java`、`VerifyEventOutboxMapper.java`、`VerifyEventProducer.java`、消费者等）；任何 SQL/索引/schema/迁移；任何 pom；`scripts/**`；`.github/**`；`docker-compose*.yml`；主规格 `spec/specs/sport-record-verify/spec.md`（2806 行零改动）；`spec/changes/archive/**`；`spec/changes/` 下其余 21 个在途目录（含 `add-verify-degrade-status-index/` 保持未跟踪脏项、TASK-162 的 `prove-verify-outbox-relay-interval-repeatable` 保持 UNDETERMINED 结论与 tasks.json completed=false）；其他任务信箱目录；`docs/perf/**` 既有历史文件全部零改动；既有测试类与 10 个 `*IT` 全部零改动。未用 `MAVEN_OPTS`、未建 `.mvn/maven.config`、未改 `mvn-verify.sh`、未裸用 mvn；`relay-send-concurrency` 全程 = 1。

## 4. 逐轮表（含丢弃预热轮与 C 确认轮）

### 4.1 计数轮与 C 确认轮

| # | label | cell | 注入 | QPS | wall(s) | P_peak | t_peak→t_zero(s) | slope(行/s) | slope_half(行/s) | 线性度偏差 | M1 >1s空档/中位(ms) | M4 健康门 | M5 Com_select 增量 |
| - | ----- | ---- | ---- | --- | ------- | ------ | ---------------- | ----------- | ---------------- | ---------- | ------------------- | --------- | ------------------ |
| 1 | A1 | A | 无 | 124.8 | 16.02 | 1806 | 118.816 | 15.2000 | 15.3251 | 0.82% | 22 / 5018 | 全部通过 | 20236 |
| 2 | B1 | B | 500ms | 191.8 | 10.43 | 1675 | 29.340 | 57.0893 | 59.1671 | 3.64% | 1 / 1028 | 全部通过 | 20181 |
| 3 | A2 | A | 无 | 185.4 | 10.79 | 1800 | 112.059 | 16.0630 | 15.9705 | 0.58% | 20 / 5017.5 | 全部通过 | 20223 |
| 4 | B2 | B | 500ms | 203.7 | 9.82 | 1637 | 29.725 | 55.0715 | 58.3491 | 5.95% | 1 / 1022 | 全部通过 | 20182 |
| - | C | C | 默认500 | 150.2 | 13.32 | 1612 | 35.769 | 45.0670 | 44.7429 | 0.72% | 1 / 1541 | 全部通过 | 20188 |

- 每轮负载均为 `ok=2000 / errors=0 / limited429=0`。
- 每轮 outbox 行数 2010 行，markSent 计数 2010 行，完全闭合。
- 每轮排空采样 drain.csv 完整闭合写至 pending=0。

### 4.2 丢弃预热轮（不进统计，仅留档）

| label | cell | 注入 | load 规模 | QPS | wall(s) | P_peak | 排空(s) | slope(行/s) | 备注 |
| ----- | ---- | ---- | --------- | --- | ------- | ------ | ------- | ----------- | ---- |
| W0 | A | 无 | 100x2000 | 130.4 | 15.34 | 1785 | 115.242 | 15.4891 | 起栈全局首轮，已排空至 0 |
| W_B | B | 500ms | 100x200 | 117.3 | 1.70 | 168 | 2.705 | 62.1072 | 重启切 B 档首轮，已排空至 0 |
| W_A | A | 无 | 100x200 | 136.8 | 1.46 | 201 | 16.171 | 12.4297 | 重启切 A 档首轮，已排空至 0 |
| W_B2 | B | 500ms | 100x200 | 151.3 | 1.32 | 140 | 5.518 | 25.3715 | 重启切 B 档首轮，已排空至 0 |
| W_C | C | 默认500 | 100x200 | 128.5 | 1.56 | 134 | 6.053 | 22.1358 | 落地新 jar 首轮，已排空至 0 |

## 5. G0–G13 逐项实测输出与退出码原文

### G0 起点

```
HEAD = 1ff96e0697694b6ec9669bc35c7234f2efdfe92d
origin/main...main = 0	1
工作树：仅 ?? spec/changes/add-verify-degrade-status-index/（既有脏项，零触碰）
        与 ?? work/mailbox/tasks/TASK-163/（本任务书目录）
```

### G1a–G1l 开工读数

```
G1a 配置开工态：
  git grep -c "verify.outbox" verify-service/src/main/resources/application.yml -> rc=1 (0 hits)
  git grep -c "relay-interval-ms" verify-service/src/main/resources/application.yml -> rc=1 (0 hits)
  git grep -c "maximum-pool-size" verify-service/src/main/resources/application.yml -> rc=1 (0 hits)
  grep -c '^verify:' verify-service/src/main/resources/application.yml -> 1
  wc -l verify-service/src/main/resources/application.yml -> 190
G1b 代码开工态：VerifyOutboxRelay.java 493 行；L68 batch-size:100、L72 max-retry:16、
    L96 relay-send-concurrency:1、L126–127 @Scheduled 5000/10000、L157 单次 selectPendingBatch
G1c offline 全量：rc=0、BUILD SUCCESS、七模块 36/41/33/103/120/59/10、Skipped 全 0
G1d 静态门：rc=1（第 2/2 段 checkstyle FAILURE）+ "You have 867 Checkstyle violations"（spotbugs/pmd 未覆盖）
G1e 词面门：正则长度 26、分支 8；四形态 ZERO_HIT rc=1；正向对照 rc=0 命中探针行；探针已删、status 复原
G1f 契约门：无参 rc=1（预期，本任务书自身落盘造成）；在途 --open TASK-163 --baseline=1ff96e06 rc=0
G1g 空白检查：git diff --check rc=0
G1h 主规格：2806 行 / CR=2806 / LF=2806 / 末 2 字节 0d 0a / '^### Requirement:' = 121
G1i PLAN：1222 行 / CR=0 / LF=1222（纯 LF）
G1j delta 目录：开工 21 / 43
G1k 磁盘：Free = 233,055,973,376 字节（≈217.05 GB ≥ 100 GB）
G1l 进程与容器：java 进程数 = 0；演示容器 Up (healthy)、postgis Exited、task131-scratch-mysql Up（零触碰）
```

### G2 生效配置

```
Nacos 无覆盖：config[dataId=verify-service.yml, group=DEFAULT_GROUP] is empty
四 jar sha256（docs/perf/data/raw/task163-g2-jarsha.txt）：
  verify  7E45A93FE90B94324749279E86B42D930E5CB03EAB32CFD5A1D33C47B9EDE0CD
  record  C7AF32EF2A359150503EFAEF818D74107B7B5913A1A5998621431C7A39E36970
  user    B1816EC0AD51E4E2D240BE33F597CCA3CA0207D501AEBE1B427B05C544462162
  gateway D2363BEF587869A5F61ED9A9C8577F01BF0499EA36AB78F11678AFA4B9CDC6B9
  A/B 计数轮全程同一 verify jar；A 档命令行无注入，B 档带 --verify.outbox.relay-interval-ms=500
```

### G3 逐轮判据（M1、M4、M5）

```
M1 空档门：
  A1: 22 次 >1s（中位 5018 ms）>= 15 且落 [4000, 6000] ms -> 通过
  B1: 1 次 >1s（中位 1028 ms）<= 5 -> 通过
  A2: 20 次 >1s（中位 5017.5 ms）>= 15 且落 [4000, 6000] ms -> 通过
  B2: 1 次 >1s（中位 1022 ms）<= 5 -> 通过
M4 健康门：
  ok=2000, errors=0, limited429=0 全通过；
  relay 失败行 = 0, 耗尽行 = 0；retry_count>0 行 = 0；
  耗尽行增量 = 0；uk_event_id 零重复；markSent = cohort = 2010；
  本轮窗内零 RECONSUME_LATER / 零 RecordApiFallback；
  轮前 PENDING = 0, 轮后排空至 0；
  hikaricp_connections_timeout_total 增量 = 0；零锁异常；drain.csv 完整闭合。
M5 代价门：
  Com_select 增量 A1=20236, B1=20181, A2=20223, B2=20182；
  B/A 增量比 20181.5 / 20229.5 = 0.9976 <= 1.5 -> 通过。
```

### G4 裁决门与三支归属

```
slope(A1) = 15.2000 rows/s
slope(B1) = 57.0893 rows/s
slope(A2) = 16.0630 rows/s
slope(B2) = 55.0715 rows/s

M2 效应门：
  slope(B1) / slope(A1) = 57.0893 / 15.2000 = 3.7559 >= 1.5（通过，落 [2.5, 3.5] 上沿）
  slope(B2) / slope(A2) = 55.0715 / 16.0630 = 3.4285 >= 1.5（通过，落 [2.5, 3.5]）
M3 排序控制门：
  |slope(A2) - slope(A1)| / slope(A1) = |16.0630 - 15.2000| / 15.2000 = 5.68% <= 20%（通过）
三支归属：全部计数轮有效，M2、M3、M5 全过 -> 落「落地支」。
```

### G5 语义门与资源门

```
语义门：5 轮次全部零重试、零耗尽、零死信、零重复报错，markSent 100% 闭合。
资源门：
  hikaricp 连接超时增量全 0.0；零锁异常；
  Com_select B/A 增量比 0.9976；
  收口磁盘可用空间 = 231,827,701,760 字节（≈215.91 GB >= 100 GB）。
```

### G6 交付物

```
1. docs/perf/判别-outbox-relay-排空斜率.md（新报告）
2. docs/perf/data/exp-outbox-relay-drain-rate.json（机器摘要）
3. spec/changes/prove-verify-outbox-relay-drain-rate/（三件套 proposal / spec-delta / tasks.json）
4. work/mailbox/tasks/TASK-163/handoff.md（本文件）
5. work/mailbox/PLAN.md（验收记录节）
```

### G7 落地实现

```
1. verify-service/src/main/resources/application.yml 纯新增 6 行（numstat 6 0），根键 ^verify: 恰 1 个。
2. 新增纯 JUnit 5 测试类 VerifyOutboxRelayIntervalDefaultTest.java（3 个 @Test 用例）：
   - yamlConfigurationLoadsWithInterval500ms (通过)
   - relayScheduledAnnotationHasUnchangedDefaultValues (通过)
   - applicationYamlHasExactlyOneVerifyRootKey (通过)
3. 打包后 verify-service 测试数 120 -> 123。
   全量 offline verify：rc=0，BUILD SUCCESS，模块测试 36/41/33/103/123/59/10，Skipped 0。
4. 静态门：mvn-verify.sh --mode=offline --static=verify-service rc=1，867 checkstyle violations（<= 867）。
5. 既有测试类 git diff --stat 为空。
```

### G8 C 确认轮

```
1. 验证新 jar sha256：仅 verify-service jar 变更为 71FFC003D197F484803E5D430ED6BD9A4A1C0B4F9732B258830DCFE3DFD47A94，其余三 jar 逐位一致。
2. 无命令行注入启动新 jar，健康检查 UP，跑 W_C 预热并排空。
3. C 轮测量（load 100 2000 task163-C）：
   - QPS = 150.2, wall = 13.32s, ok = 2000, err = 0, 429 = 0
   - P_peak = 1612, t_zero - t_peak = 35.769s
   - slope(C) = 45.0670 rows/s, slope_half = 44.7429 rows/s（线性度偏差 0.72%）
   - M1 >1s 空档数 = 1 <= 5（中位 1541 ms）-> 通过
   - 效应比 slope(C) / mean(slope(A1), slope(A2)) = 45.0670 / 15.6315 = 2.8831 >= 1.5 -> 通过
4. 判定成功落地，零回滚动作。
```

### G9 词面门

```
正则现场从 ci.yml 提取（长度 26、分支 8）；四形态 ZERO_HIT rc=1；正向对照 rc=0 命中探针行；探针已删、status 复原。
入库文件预检全部 ZERO_HIT。
```

### G10 空白与契约

```
git diff --check rc=0。
在途契约：bash scripts/verify/mailbox-contract.sh --open TASK-163 --baseline=1ff96e06 rc=1
  （判据 A=0 放行，判据 B=1 为既有脏项 4 文件 + 中文转义 1 文件 + 公共文件 PLAN.md 既知交叠）。
收口后无参契约：bash scripts/verify/mailbox-contract.sh rc=0（见文末补记）。
```

### G11 范围核对与受保护数字

```
实际改动集 10 项逐项对齐 §3。
PLAN.md 16 个受保护数字 token base vs HEAD 计数完全不减少：
  13.4: 5 -> 7
  18.0: 6 -> 9
  73.93: 6 -> 8
  68.8: 2 -> 4
  6315: 2 -> 5
  1.8612: 2 -> 4
  3.3066: 2 -> 4
  5.7056: 2 -> 4
  9.408: 2 -> 4
  36525962432: 2 -> 4 (>=3)
  36586847965: 1 -> 3 (>=2)
  36438897772: 2 -> 4
  36399582548: 1 -> 3
  36098038547: 1 -> 3
  2806: 4 -> 7 (>=6)
  598: 1 -> 3
```

### G12 提交

```
C1/C2/C3/C4 git show --check 均 rc=0。清单原文随回复回传。
```

### G13 外部门槛

```
未达外部门槛：--mode=online / CI 本轮未跑（本次不 push，待下次授权由 CI 复验）。
```

## 6. 未覆盖项与不得推出的结论（照任务书 §12 逐条）

1. **四服务局部栈**：leaderboard、mapmatch、postgis 未起，榜单消费与真实 R5 降级未覆盖，不得声称全链路。
2. **不得声称任何并发收益**：`relay-send-concurrency` 全程 = 1；不得用 TASK-156 的 `S(N)` 或 TASK-161 的 `S_prod(N)` 换算本轮任何数字。
3. **不得并列为「优化前后」**：不得把本轮 slope 与 TASK-152 的 18.0 ms/行、TASK-156/161 的 S(N)/S_prod(N)、TASK-144/162 的 P50 并列成优化前后。
4. **排空斜率与端到端延迟的界限**：排空斜率是负载停止后的净排空能力，不等于负载期的端到端延迟改善；本轮不换算 P50，亦不得声称「P50 改善已被证明」（TASK-144/162 的 P50 观测仍属未定支）。
5. **不得外推**：不得外推到更高到达率、更长时间窗或生产多实例。
6. **已知代价**：落地后 500ms 使空扫 tick 频率约 10× 为已知代价，已并列披露（`Com_select` B/A 增量比 0.9976，tick select 差异淹没于投递 select 中）。
7. **静态与外部**：spotbugs/pmd 未覆盖（被 checkstyle 阻断在前）；`--mode=online`/CI 未跑 ⇒ 未达外部门槛。
8. **容量派生结论**：A 档上限 ≈15 行/s < 到达率 87~91 行/s 为 Level B 算术，不是本轮新测量，不得写成实测无界增长。
9. **演示库数据**：演示库新增约 1.2 万行记录（运行证据）不得清理。

## 7. 异常、自踩与口径披露

1. **WMI 与环境变量隔离**：Windows 平台下通过 PowerShell WMI 创建分离进程启动 Java 服务，必须在调用脚本内显式指定 `JAVA_BIN` 和 `export MYSQL_PORT=3307`，避免继承默认环境导致端口或 JDK 路径错乱。
2. **代理旁路处理**：环境存在默认代理 `http_proxy=http://127.0.0.1:7890`，在执行本地 curl 与健康检查时必须显式设置 `no_proxy="*"` 并 unset 代理变量，避免健康检查探测失败。
3. **提交 QPS 漂移与排空斜率解耦自证**：A1 提交 QPS 为 124.8，A2 提交 QPS 为 185.4，漂移幅度达 +48.56%（与 TASK-162 中观测到的 QPS 上漂现象完全吻合）。然而，A1 排空斜率为 15.2000 rows/s，A2 排空斜率为 16.0630 rows/s，两者偏差仅 5.68%（远低于 20% 阈值）。这强有力地证实了后负载排空斜率彻底与提交 QPS 解耦，证实了 §1 口径变更的科学性。
4. **词面门探针位置**：正向对照探针必须植入未被 `.gitignore` 忽略的文件中，否则 `git grep --untracked` 无法扫描到探针。
5. **Git LF/CRLF 提示**：提交过程中 Git 提示 "LF will be replaced by CRLF" 为常规提示，实际写入仓库文件均为标准 LF，CRLF 计数为 0。

## 8. 补记（C4 提交后终检实测，随执行回复回传）

- C4 `git show --check` rc=**0**；四笔入册清单原文与终版 `git diff --shortstat 1ff96e06..HEAD` 随执行回复回传指导侧（本文件提交时无法预含自身哈希，按 TASK-159/160/161/162 先例以补记形式登记；本补记经 `--amend --no-edit` 并入 C4，最终哈希以 `git log --oneline -1` 为准）。
- 收口后无参 `bash scripts/verify/mailbox-contract.sh` rc=**0**，末两行原文：`[contract] TASK-163：足迹不在工作树，视为已收口，不重审` / `[contract] 契约校验通过（退出码 0）：判据 A 两件套齐（含 0 个待办进行中）+ 判据 B 清单一致`。
- 收口 G11：`git diff --name-only 1ff96e06..HEAD` 恰 10 条、与 §3 清单逐条对应（报告 1 条为 quotepath 转义形态）；受保护数字 base→HEAD 全不减。
- 收口词面门 4 形态 ZERO_HIT + 正向对照 rc=0（探针已删）；`git diff --check` rc=0；磁盘收口 Free = 231827701760 字节（≈215.91 GB，门槛 Free ≥100 GB 远未触及）；同目录 `spec.md` 为任务书原样入库（194 行 / 37193 字节 / sha256 `57c7b9bafe081d3b860726e3d13ed343ca9a6a1b36aa69b67587d015fa61abea`）。

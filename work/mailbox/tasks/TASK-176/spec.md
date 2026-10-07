# TASK-176 spec：verify-service 端到端全链路容量与压测重评

## 0. 硬约束与红线（继承 TASK-158/163/165/166/175 §0，逐字适用）

1. 本任务书是唯一权威。开工先逐位核对 §3 开工读数；**任一不符 ⇒ 停手回报原文，不要自行解释、不要自行订正**。
2. 命令行**全程无中文**（含 grep/sed/awk 模式与 here-doc）；中文内容先写临时脚本文件再执行；提交信息一律使用 UTF-8 无 BOM 文件配合 `git commit -F <file>`。
3. bash 一律写成 `.sh` 文件再用 `D:\git\Git\bin\bash.exe <路径>` 执行；**禁 `bash -lc` 内联**；需抓非零 rc 的段落不要放 `set -e` 下。本机 git 为 **2.20.1.windows.1**（无 `git restore`，放弃工作树改动用 `git checkout -- <path>`）。
4. 本轮为纯测量/纯文档轮：**严禁跑裸 mvn，不改动任何生产代码，不改动任何配置文件，不修改任何默认参数，不改动任何 SQL，不改动 pom.xml，不改动 scripts/ 脚本**。测试预算 450 用例不减、零新单测；如需保防护件只能 test-only 且默认关闭。
5. **不 push、不建 PR**、不 `git stash`、不 `git add -A`/`add .`（一律逐路径 add）。push 必须由用户显式单次授权。
6. **零触碰名单**：`spec/changes/add-verify-degrade-status-index/`（未跟踪目录，任何任务不得收编）；其余在途未定/测量提案目录本轮零触碰。
7. 不翻案、不改写任何已入库结论与历史数字（TASK-143~175）；TASK-138（旧 HEAD）与 TASK-143 数字仅登记为历史参考，不做翻案裁决、不写改善百分比、不为生产容量背书。
8. `work/mailbox/PLAN.md` **纯追加**，不得改动任何既有行（含 L4 与顶端外部门槛叙事）。
9. 词面门红线：**任何要入库的文档都不得原样内嵌词面门的正则字面量或敏感词**。
10. **环境规程与停止条件（双写红线）**：
    - 开工先核验 Docker daemon 与既有容器状态（防范历史坑：容器被外部引擎周期所杀 `Exited(255)`、两 Redis 实例竞争 6379 端口、compose 缺 `.env` 报错、临时 `docker-compose.override.yml` 用毕必须删除）；
    - 若真实环境不足以执行（如容器无法启动、服务依赖缺失且无法自愈）⇒ 登记「测量未执行」证据不足支收口，**禁止伪造读数**；
    - 需改生产代码才能继续 ⇒ **停止**并记为候选后续提案；
    - 任何顺手优化冲动 ⇒ **严格禁止并停止**；
    - 测量轮内发现状态机或数据语义异常 ⇒ **立即停手回报**；
    - 不动 relay / 消费线程 / 批大小任何默认值；不清理用户数据卷；性能数字一律严格限定为「本机、本负载、未达外部门槛」。

---

## 1. 唯一目标与任务背景

### 1.1 背景
课题 4（架构路线图）：自 TASK-143 以来，针对判定服务链路的多项关键优化已陆续落地并经局部检验：
- **TASK-163**：`verify.outbox.relay-interval-ms` 调度间隔由 5000ms 调整为 500ms（排空斜率提升 4.05×）；
- **TASK-169**：`verify.outbox.relay-batch-mark-enabled` 开启分块批量标记（chunk-size 25，锁内吞吐翻 4.87 倍）；
- **TASK-171**：`verify.outbox.relay-send-concurrency` 开启批内并发投递（N=2，并行化网络 RPC，锁内吞吐再翻 ≥1.5 倍）；
- **TASK-173**：实现 `RecordWithPointsDTO` 与 `RecordApi.getRecordWithPoints` 聚合契约（Feign 往返 2→1、主表查询 2→1）。

**问题定义**：
上述四项收益此前均来自单元测试、隔离 IT 或局部微基准度量，**从未在完整的微服务与真实中间件网络环境下进行同轮端到端压测复评**。
历史基准 TASK-143 仅启动四服务（gateway/user/record/verify），且当时 R5 空间离路判定因 mapmatch/PostGIS 缺席而处于全降级状态（Resilience4j 3s 读超时兜底），leaderboard-service 亦缺席；更早的 TASK-138 属于旧 HEAD 与旧配置基准。
当前系统在全量优化落地后，端到端的真实饱和吞吐与 P99 延迟水位尚未建立权威度量。

### 1.2 目标
1. 建立提案 `spec/changes/measure-verify-e2e-capacity/`（proposal.md / spec-delta.md / tasks.json）与本任务书；
2. 明确端到端全链路压测评测设计：负载模型（c100×2000）、轮次协议、指标口径、判别式与三支裁决；
3. 预注册环境降级分支：若 mapmatch/PostGIS 或 leaderboard 无法拉起，如实按局部覆盖（列明缺席项）登记，禁止伪称全链路；
4. 明确历史数字（TASK-138/143）仅作参考、不翻案、不写百分比、不为生产背书；
5. 实施派发笔单笔本地提交，全项门禁（tasks.json 语法、词面门四形态、git diff --check、在途契约门）实测全绿。

---

## 2. 测量设计与评判规程

### 2.1 服务栈与环境拓扑
1. **服务面编排**：
   - 依赖基础设施：MySQL 8.0（端口 3307/3306）、Redis 7.2（端口 6379）、RocketMQ 5.2.0（namesrv:9876 + broker:10911）、Nacos 2.3.2（端口 8848）、PostGIS/PostgreSQL（端口 5432）；
   - 业务微服务：gateway-service (8080)、user-service (8081)、record-service (8082)、verify-service (8083)、leaderboard-service (8084)、mapmatch-service (8085)；
   - 启动依据：中间件由 `docker-compose.yml` 编排；服务层由 `docker-compose.services.yml` 或 `scripts/perf/run-perf.sh start-services` 拉起；
2. **环境核验规程与历史坑防范**：
   - 核验 Docker daemon 活跃状态，检查是否有容器处于异常状态（如历史坑：PostGIS 容器被外部 WSL2/Docker Desktop 引擎回收周期所杀出现 `Exited(255)`）；
   - 检查端口冲突（历史坑：宿主机原生 Redis 占用 6379 与容器 Redis 冲突；宿主 3306 MySQL 占用需通过 `MYSQL_PORT=3307` 隔离）；
   - 检查 compose 启动前 `.env` 文件是否存在（历史坑：compose 声明 `env_file: [.env]`，缺失时直接解析失败报 rc=1）；
   - 临时调试文件清理（历史坑：若使用临时 `docker-compose.override.yml`，用毕必须立即删除，不得残留污染工作树）；
3. **环境降级分支**：
   - 若 mapmatch 或 PostGIS 路网无法就绪，或 leaderboard-service 无法健康拉起 ⇒ 自动降级为「局部覆盖（列明缺席项）」分支，如实记录 R5 降级或榜单缺席，严禁伪称全链路，严禁修改任何生产配置凑覆盖；
   - 若连基础四服务（gateway/user/record/verify）与基础中间件均无法启动 ⇒ 记录「测量未执行」证据不足收口，严禁伪造吞吐与延迟。

### 2.2 负载形态与轮次协议
1. **负载形态**：复用既有标准负载模型（`scripts/perf/run-perf.sh load 100 2000 <label>`）：
   - 100 虚拟线程并发闭环请求；
   - 总计 2000 请求（含 10 预热）；
   - 轨迹载荷：300 点真实轨迹（约 23KB JSON），userId 按 16 分片均匀轮转；
2. **轮次协议**：
   - 预热轮（W0）：起栈后必须先跑一轮预热并丢弃，不计入统计；
   - 计数轮：执行正式受控测量轮次，轮间需等待队列 PENDING 归零且系统静默；
   - 可比性门：若需跨轮比对，要求基准可比性偏差控制在 ±15% 以内。

### 2.3 指标口径与测量矩阵
1. **客户端提交指标**：
   - 有效 QPS（成功数 / 墙钟秒）；
   - 延迟分布：P50、P95、P99、MAX（毫秒）；
   - 成功率与错误率：状态码 200 计数，429 限流计数，5xx 错误计数；
2. **事件三分段（复用 TASK-143 归因口径）**：
   - 段一：`pub→consume`（record 发布事件到 verify 消费开始，反映 MQ 排队与网络积压）；
   - 段二：`consume→callback`（verify 消费开始到判定落库与回调完成，反映核心计算、SQL、Feign 与规则执行耗时）；
   - 段三：`callback→SENT`（判定落库到 outbox relay 投递并更新 SENT，反映 outbox 排空与网络投递耗时）；
   - 聚合参考：`consume→SENT` 与端到端 `pub→SENT`；
3. **判定完成速率**：单位时间内推进至终态（PASSED/REJECTED）的记录数（records/s）；
4. **outbox 排空斜率（复用 TASK-163 峰值排空口径）**：
   - 负载停止后，按每 2s 采样 `SELECT COUNT(*) FROM verify_event_outbox WHERE status='PENDING' AND retry_count < 16`；
   - 计算峰值到归零的净排空斜率（rows/s）；
5. **积压盘点分账**：
   - 区分可投递 PENDING（`retry_count < 16`）与重试耗尽待人工 PENDING（`retry_count >= 16`）。

### 2.4 QPS 单调漂移教训与对策
- **历史教训**：TASK-144 与 TASK-162 在同机多轮压测中均观测到提交 QPS 随宿主 JIT 预热与系统缓存单调上漂（预热 84→108，计数轮 119→204，上漂超过 100%），导致基于提交端 QPS 的可比性门（±15%）结构性失效；
- **对策**：在评价系统容量与吞吐时，优先采用**与客户端提交 QPS 解耦的指标**：
  - 负载停止后的 outbox 排空斜率；
  - 同进程锁内单行处理墙钟耗时（`T_proc` 与 `T_mark`）；
  - 稳态阶段同 cohort 逐请求成对跟踪耗时。

### 2.5 判别式与三支裁决
- **第一支（达标 / PASSED）**：全链路（或降级明确的局部链路）在 c100×2000 负载下无死锁、无 5xx 错误、无事件丢失、错误率为 0，各分段耗时完整闭合且排空正常，指标全面成立；
- **第二支（反证 / FAILED）**：出现严重性能回退（较历史基准出现明显降级）、发生数据不一致或大面积请求超时熔断；
- **第三支（未定 / UNDETERMINED）**：由于宿主环境波动剧烈导致样本不可配对、指标方差超限、或因环境依赖缺失未能执行完整测量。

### 2.6 历史数字登记与纪律
- TASK-138（旧 HEAD、旧参数、15~18s 延迟）与 TASK-143（四服务局部覆盖、R5 全降级、relay 净投递 ≈13.4 行/s、callback→SENT P50 68.8s）仅作为演进背景登记；
- **严禁翻案**：不推翻历史报告结论；
- **严禁推导百分比**：不将当前数字与历史局部/旧栈数字直接相除计算“优化提速百分比”；
- **严禁为生产容量背书**：所有结论严格限定在测试机特定配置下。

### 2.7 生产代码零改动与测试预算
- 生产代码、配置、SQL、脚本**零修改**；
- 单元测试预算保持 450 用例全绿，零新增用例；
- 如需辅助工具仅允许使用已有的独立测试脚本或忽略路径下的 scratch 工具。

---

## 3. 开工读数（开工前逐位核验）

- HEAD：`d5e5f020e98aefbb7b37d9ece4a5f204667aff16`（落盘时点 HEAD；注：本派发笔自身入库后将使基线前移 1 位、计数变为 `0 2`，属标准时序惯例）
- `origin/main`：`d6cf0462222925a5d43e85edf16bd8d3703eeeb9`（第十六次外部门槛 HEAD）
- `git rev-list --left-right --count origin/main...main` = `0 1`
- 工作树状态：仅包含在途提案 `spec/changes/measure-verify-e2e-capacity/` 与本任务书 `work/mailbox/tasks/TASK-176/spec.md`，以及既有白名单脏项 `?? spec/changes/add-verify-degrade-status-index/`（严禁触碰）
- 全量 offline 单元测试：`36/41/33/127/144/59/10` 全绿（共 450），Failures/Errors/Skipped 全 0，BUILD SUCCESS
- 静态检查：Checkstyle 严格为 **862** 处（≤ 862）
- 词面门四形态：全 `ZERO_HIT rc=1`

---

## 4. 只改清单白名单（精确文件路径）

1. `docs/perf/verify-e2e-capacity-report.md`（压测报告，后续测量轮输出）
2. `docs/perf/data/exp-verify-e2e-capacity.json`（机器摘要，后续测量轮输出）
3. `spec/changes/measure-verify-e2e-capacity/proposal.md`
4. `spec/changes/measure-verify-e2e-capacity/specs/sport-record-verify/spec-delta.md`
5. `spec/changes/measure-verify-e2e-capacity/tasks.json`
6. `work/mailbox/tasks/TASK-176/spec.md`
7. `work/mailbox/tasks/TASK-176/handoff.md`（后续测量轮输出）
8. `work/mailbox/PLAN.md`（纯追加）
9. `c:\Users\fzdzzj\.gemini\antigravity\brain\4a78d1d8-69f7-4655-b948-8cb103144748\lead_architect_handoff.md`（必要时同步）

**严禁修改任何未列出的文件**（`src/**` 生产代码、配置、SQL、构建脚本、pom 零触碰）。

---

## 5. 受保护 tokens 基线（28 项，只增不减）

开工基线在 `work/mailbox/PLAN.md` 中实测行命中数（`grep -cF`）：
- `13.4` = 16
- `18.0` = 18
- `73.93` = 17
- `68.8` = 13
- `6315` = 14
- `1.8612` = 13
- `3.3066` = 13
- `5.7056` = 13
- `9.408` = 13
- `36525962432` = 13
- `36586847965` = 12
- `36438897772` = 13
- `36399582548` = 12
- `36098038547` = 12
- `2806` = 19
- `598` = 12
- `36736221648` = 11
- `36808102571` = 6
- `36821040708` = 4
- `36845152965` = 3
- `36871294588` = 3
- `36880083885` = 4
- `36958994260` = 4
- `36976873215` = 4
- `36992632143` = 3
- `36995450125` = 1
- `37008317295` = 2
- `37021305016` = 2

收口时 `PLAN.md` 的所有 token 出现次数必须 ≥ 基线值。

---

## 6. 门禁与提交结构

1. **预提交门禁（全项亲跑并记录退出码）**：
   - tasks.json 语法验证（python json.load）rc=0；
   - 词面门四形态（default / `LC_ALL=C` / `LC_ALL=zh_CN.UTF-8` / `LC_ALL=C.UTF-8`，正则自 `.github/workflows/ci.yml` 提取）：全 ZERO_HIT rc=1；正向探针 rc=0；三态判定健全；
   - `git diff --check` rc=0；
   - 契约门在途：`bash scripts/verify/mailbox-contract.sh --open TASK-176 --baseline=d5e5f020e98aefbb7b37d9ece4a5f204667aff16` rc=0；
   - PLAN.md 28 项 token 只增不减（本派发笔未改动 PLAN.md，行数与命中数恒等）。
2. **派发笔单笔提交**：
   - 包含文件：白名单中的提案三件套（proposal.md / spec-delta.md / tasks.json）与本任务书（spec.md）；
   - 提交信息：`docs(spec): 派发 TASK-176 verify端到端全链路容量压测重评提案与任务书`；
3. **后续实施轮两笔提交结构**：
   - C-01 测量证据笔：白名单 1-2，提交信息 `docs(perf): 记录 verify 端到端容量压测重评报告与数据（TASK-176）`；
   - C-02 台账闭环笔：白名单 3-8，提交信息 `docs(mailbox): 登记 TASK-176 验收记录与提案闭环（TASK-176）`。

---

## 7. 未覆盖项（如实登记，不得写成通过）

1. 本任务书派发轮不执行真实压测、不启动 Docker 容器、不运行微服务进程；
2. 真实环境下 mapmatch/PostGIS 与 leaderboard 启动可行性留待测量轮现场核验；
3. 不宣称任何生产容量背书，不计算任何跨基准优化百分比。

---

## 8. 交付物

- `spec/changes/measure-verify-e2e-capacity/proposal.md`
- `spec/changes/measure-verify-e2e-capacity/specs/sport-record-verify/spec-delta.md`
- `spec/changes/measure-verify-e2e-capacity/tasks.json`
- `work/mailbox/tasks/TASK-176/spec.md`
- 单笔本地提交：`docs(spec): 派发 TASK-176 verify端到端全链路容量压测重评提案与任务书`

---

## 收口记录（执行 agent，2026-10-07）

- 实施轮已收口（两笔本地提交，未 push）：C-01 `62d4f4d6991cf7285203c2fa06f6d59db7921b08`（测量证据笔，任务书 §4 白名单 1-2：压测报告与机器摘要）；C-02 为本台账笔（白名单 3-8：提案 tasks.json 闭环勾选 + 本收口记录纯追加 + handoff.md + PLAN.md 纯追加）。
- 全项门禁退出码、环境核验、轮次读数、三支裁决与未覆盖项详见 `work/mailbox/tasks/TASK-176/handoff.md`；验收台账见 `work/mailbox/PLAN.md` 本轮追加段。

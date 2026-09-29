## ADDED Requirements

### Requirement: relay 池内并发标度须在真实 10 连接 Hikari 池上以预注册阈值裁决
WHEN relay 容量决策需要知道「逐行语义不变的前提下，N 路并发 `markSent` 经过生产生效的 Hikari 池（无 `maximum-pool-size` 配置 ⇒ 默认 10）后标度还剩多少」,
判别作业 SHALL 用真实 `HikariDataSource`（`setMaximumPoolSize(10)`、其余 Hikari 默认）经真实 MyBatis `SqlSessionFactory` 与生产 `VerifyEventOutboxMapper#markSent` 逐字 SQL（每次调用 `openSession(true)`）在**同一个池**上跑 A 组（N ∈ {1,2,4,8}，无占用）与 B 组（N=4 且额外持有 8 条池连接），并以预注册的 `S_prod(N)` 三支阈值裁决，不得事后放宽。

#### Scenario: 受控池内并发实验
GIVEN scratch schema `task161_pool_scratch` 已由仓库 DDL 机械改名生成且每条连接硬校验 `SELECT DATABASE()`
WHEN A 组 N ∈ {1,2,4,8} 各 3 轮、M=2000 行、已取列表下标 `i % N` 互不相交分片、每 worker 从同一池取连接；B 组 N=4 全程额外持有 J=8 条池连接（可用 2 < 4）
THEN 作业 SHALL 交叉校验每轮 `Com_update` 增量精确等于 M、窗口内 `Com_insert`/`Com_delete` 为 0、收尾 SENT=M 且 PENDING=0、会话门（池连接 ≤ 10 + 1 条控制连接，排除 `event_scheduler`/`compress_gtid_table` 两条系统 Daemon）干净
AND SHALL 逐轮记录 `HikariPoolMXBean` 四读数轮内最大值，且 B 组每轮 `getThreadsAwaitingConnection() > 0`（自证门），否则该臂作废重做

#### Scenario: 池占用代理不得冒充消费者模型
GIVEN B 组以 J=8 条常驻池连接制造占用压力
WHEN 形成裁决与报告措辞
THEN 作业 SHALL 把 J 标注为「池占用代理（occupancy proxy）」而非 32~40 个消费线程的行为模型
AND SHALL NOT 用池级聚合指标拆解单行 `markSent` 内部构成，SHALL NOT 据此命名 fsync/锁/纯 SQL 占比

#### Scenario: 变异与跳过不得冒充测量
GIVEN 测量 harness 可能空过或环境变量缺失
WHEN 注入漏标一行的变异或缺省 `TASK161_IT_*` 环境变量
THEN 变异运行 SHALL 以既有断言翻红（`Com_update` ≠ M）、字节还原后复绿，缺变量跳过 SHALL 记为未覆盖而不计入真库通过
AND 三档退出码 SHALL 实测记录

### Requirement: 池内标度结论仅是必要条件且不构成实施授权
WHEN 判别得出 `S_prod(N)` 的裁决,
作业 SHALL 把结论限定为该 scratch 实例、该池装配（`maximumPoolSize=10` 与其余 Hikari 默认值）与该 test-only 装配（无 Redisson 全局锁、无 `syncSend`、无四服务）下**可用连接层面**的可摊薄性，不得将其当作生产吞吐收益或 relay 改造授权。

#### Scenario: 裁决分支的边界
GIVEN 裁决落入任一预注册分支
WHEN 形成裁决
THEN 作业 SHALL 同时记录「未测并发 `syncSend`/RocketMQ、未测 Redisson 全局 `tryLock`、未测多实例竞争、未起四服务、未跑负载、spotbugs/pmd 未覆盖、未 push 未过 CI」的边界
AND SHALL NOT 修改 relay-interval/batch/并发/锁/SQL/索引/事务/池/JVM/MQ 任何默认值，SHALL NOT 翻案 TASK-153/154，SHALL NOT 改写 TASK-152 的 18.0 ms/行 或 TASK-156 的 S(N)

#### Scenario: 不同装配数字不可比
GIVEN TASK-152 的 18.0 ms/行（演示实例 3307）与 TASK-156 的 S(N)（同 scratch 实例但无池 `DriverManager` 装配）
WHEN 形成报告措辞
THEN 判别量 SHALL 只有同实例同装配内的 `S_prod(N)`
AND SHALL NOT 把跨实例（13318 scratch vs 3307 演示）或跨装配（DriverManager vs Hikari）的数字并列成「优化前后」

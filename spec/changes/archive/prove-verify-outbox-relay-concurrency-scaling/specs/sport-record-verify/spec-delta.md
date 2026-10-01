## ADDED Requirements

### Requirement: markSent 并发标度判别须在真库上以预注册阈值裁决
WHEN relay 容量决策需要知道「逐行语义不变的前提下，N 路并发 `markSent` 的聚合吞吐是否随 N 显著上升」,
测量作业 SHALL 在专用 scratch 真库上以生产逐字 SQL、每线程独立自动提交连接和互不相交行分片进行受控实验，并以预注册的 S(N) 三支阈值裁决，不得事后放宽。

#### Scenario: 受控并发标度实验
GIVEN 专用 scratch schema 已由仓库 DDL 机械改名生成且每条连接硬校验落库正确
WHEN N ∈ {1,2,4,8} 各臂按 `id % N` 互不相交分片、M=2000 行、每行一次自动提交条件 UPDATE 并重复 3 轮
THEN 作业 SHALL 交叉校验每轮 `Com_update` 增量精确等于 M、窗口内 `Com_insert`/`Com_delete` 为 0、收尾 SENT=M 且 PENDING=0
AND SHALL 在起跑前记录在跑会话并要求除本臂自有 N+1 条连接外无外来前台会话，任一判据不满足即判 harness 缺陷停并回传

#### Scenario: 变异与跳过不得冒充测量
GIVEN 测量 harness 可能空过或环境变量缺失
WHEN 注入漏标/重复标记一行的变异或缺省 `TASK156_IT_*` 环境变量
THEN 慢跑 SHALL 以既有断言翻红（`Com_update` ≠ M 或 SENT 计数 ≠ M）、还原后复绿，缺变量跳过 SHALL 记为未覆盖而不计入真库通过
AND 三档退出码 SHALL 实测记录

### Requirement: 正标度结论仅是必要条件且不构成实施授权
WHEN 实验得出 S(N) 的裁决,
作业 SHALL 把结论限定为该 scratch 实例、该持久配置与该连接语义下的提交层可摊薄性，不得将其当作生产吞吐收益或 relay 改造授权。

#### Scenario: 并发标度成立的边界
GIVEN S(8) ≥ 2.0 且 S 随 N 单调不减
WHEN 形成裁决
THEN 作业 SHALL 同时记录「未测并发 syncSend/RocketMQ、未测 relay 锁改造、未测多实例竞争」的边界
AND SHALL NOT 修改 relay-interval/batch/并发/锁/SQL/索引/事务/池/JVM/MQ/`innodb_flush_log_at_trx_commit` 任何默认值，SHALL NOT 翻案 TASK-153 或改写 TASK-152 数字

#### Scenario: 分区 relay 的保序约束
GIVEN 消费端对同 recordId 事件的顺序依赖已被只读核查
WHEN 核查发现消费者依赖同 recordId 事件顺序（或未来设计引入顺序假设）
THEN 后续分区 relay 设计 SHALL 按 recordId 分区而非 round-robin / `id % N` 以保序
AND 消费端 SETNX/锚点幂等 SHALL NOT 被当作改 relay 的授权依据

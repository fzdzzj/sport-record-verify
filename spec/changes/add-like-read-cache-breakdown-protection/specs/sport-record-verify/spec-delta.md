# 变更增量规格：点赞读路径防击穿治理与 pending 队列可观测

## 1. 变更背景与架构依据

- **F17 防击穿**：`RecordLikeService.readCount` 在 Redis 计数键缺失时并发回源 DB COUNT（findings-summary F17，TASK-130 复核仍在；TASK-103 的 SETNX 修复登记属台账虚报）。本 delta 以 per-record Redisson 互斥锁收敛并发回源，方案拍板取「跨实例锁」而非「进程内 Caffeine 单飞」，避免与 F13「Caffeine 单层多实例不一致窗口」既有结论口径冲突。
- **空值哨兵**：既有回填无 TTL，冷记录计数键长驻 Redis。记录不存在已由 `requireRecord` 3001 前置拦截，故哨兵针对「记录存在但无赞」的 0 计数，短 TTL 60s。
- **队列可观测**：`like:pending:ops` 异步落库队列此前零度量（TASK-130 证据：全类零 Micrometer 引用）。本 delta 补堆积量与队头消费延迟两个 Gauge，不加背压与上限（须以真实到达率证据另立提案）。
- **不变式继承**：点赞幂等双保险（成员集 SADD + 联合主键 INSERT IGNORE）、写路径无本地事务（ADR-0009）、flush 批量落库与 LTRIM 时序、对账以 DB 行为权威源——全部保持不动。

---

## 2. 需求增量（Delta）

### ADDED Requirement: 点赞计数读路径互斥重建与防击穿

WHEN 点赞计数读路径在 Redis 计数键缺失时需要回源数据库，
系统 SHALL 以记录粒度互斥锁（`lock:like:count-init:{recordId}`）保证同一记录同一时刻至多一个线程执行数据库 COUNT 回源与回填，
其余并发请求 SHALL 通过短暂重读缓存共享重建结果，重试耗尽后 SHALL 兜底直读数据库返回且不回填（不与持锁者竞争写），
锁服务异常时 SHALL 降级为既有直读回填路径，读可用性 SHALL 不因互斥机制而降低。

#### Scenario: 并发缓存 miss 单线程回源
GIVEN 某热点记录的 Redis 计数键缺失，且有多个并发请求同时到达 `readCount`
WHEN 各请求尝试获取该记录的重建锁
THEN 至多一个请求获锁执行数据库 COUNT 与回填
AND 未获锁请求不产生额外数据库 COUNT（共享重建结果或兜底路径除外）

#### Scenario: 获锁者双重检查
GIVEN 某请求已获取重建锁
WHEN 等锁期间计数键已被其他路径回填（如对账任务覆盖写）
THEN 该请求重读缓存命中后直接返回，不再执行数据库 COUNT

#### Scenario: 等待者共享重建结果
GIVEN 重建锁被其他线程持有且回填尚未完成
WHEN 当前请求多次重读缓存后命中重建结果
THEN 直接返回缓存值，全程不触发数据库 COUNT

#### Scenario: 等待者兜底直读不回填
GIVEN 重建锁被持续持有且重试重读均未命中
WHEN 等待者重试次数耗尽
THEN 兜底直读数据库 COUNT 并返回
AND 不执行缓存回填（避免与持锁者竞争写）

#### Scenario: 锁服务异常降级
GIVEN Redisson 锁服务在重建路径上抛出异常
WHEN `readCount` 执行互斥重建
THEN 降级为既有直读回填行为（数据库 COUNT + 回填），不向上抛出锁异常

### ADDED Requirement: 点赞计数空值哨兵与 TTL 持久化配套

WHEN 点赞计数回源回填时数据库计数为零，
系统 SHALL 以短 TTL（60 秒）空值哨兵回填该计数键，
非零计数 SHALL 维持既有无 TTL 持久回填；
AND 计数键被 INCR / DECR 原子写后 SHALL 立即清除其 TTL（persist），
防止哨兵键在点赞后被 TTL 过期引发计数幽灵回退。

#### Scenario: 零计数短 TTL 哨兵回填
GIVEN 记录存在且数据库点赞计数为 0
WHEN `readCount` 回源回填
THEN 计数键以 60 秒 TTL 写入哨兵值 "0"

#### Scenario: 非零计数持久回填不变
GIVEN 数据库点赞计数大于 0
WHEN `readCount` 回源回填
THEN 计数键以既有无 TTL 方式写入，行为与既有实现一致

#### Scenario: 计数写入后清除哨兵 TTL
GIVEN 计数键当前携带哨兵 TTL
WHEN `like` 首次点赞执行 INCR 或 `unlike` 确认取消执行 DECR
THEN 立即对该键执行 persist 清除 TTL，计数不会因哨兵过期而回退

### ADDED Requirement: 点赞 pending 队列堆积与消费延迟可观测

WHEN 点赞异步落库的 pending 队列被写入或被 flush 消费，
系统 SHALL 在每轮 flush 中采集队列堆积量（LLEN）与队头元素年龄（now − enqueuedAt），
并以 Micrometer Gauge（`like.pending.queue.size` / `like.pending.head.age.ms`）暴露，
堆积量超过阈值（1000）SHALL 记 WARN 日志；
本需求 SHALL NOT 引入队列长度上限、背压或拒写（须以真实到达率证据另立提案）。

#### Scenario: pending 操作携带入队时间戳
WHEN 点赞或取消点赞产生 pending 操作入队
THEN 队列元素 JSON 携带 `enqueuedAt`（epoch millis）

#### Scenario: flush 每轮发布队列度量
GIVEN pending 队列存在积压
WHEN flush 定时任务执行
THEN `like.pending.queue.size` 更新为当前 LLEN
AND `like.pending.head.age.ms` 更新为队头元素年龄
AND 度量值经构造注入的 MeterRegistry 注册的 Gauge 暴露

#### Scenario: 旧格式元素年龄不可计算
GIVEN 队头元素为不含 `enqueuedAt` 的旧格式（历史遗留）
WHEN flush 采集队头年龄
THEN `like.pending.head.age.ms` 记 -1，不抛异常、不阻断消费

#### Scenario: 空队列度量归零
GIVEN pending 队列为空
WHEN flush 定时任务执行
THEN `like.pending.queue.size` 记 0、`like.pending.head.age.ms` 记 0，本轮不触发落库

#### Scenario: 堆积超阈值告警日志
GIVEN pending 队列堆积量超过 1000
WHEN flush 定时任务执行
THEN 记录 WARN 级别堆积告警日志，且不拒写、不背压、不改变消费行为

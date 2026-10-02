# 变更提案：点赞读路径防击穿治理与 pending 队列可观测（add-like-read-cache-breakdown-protection）

## 1. Why

`record-service` 的点赞计数读路径（`RecordLikeService.readCount`，`record-service/src/main/java/com/sportverify/record/service/RecordLikeService.java:341-351`）当前语义为「Redis 优先，键缺失兜底 DB COUNT(*) 并回填」。findings-summary F17（TASK-130 于 2026-09-23 复核仍在，`work/mailbox/findings-summary.md` L102）确认其存在三类缺口：

1. **缓存击穿（F17 本体）**：计数键 `like:count:{recordId}` 在冷启动、Redis 清空或键被淘汰后，热点记录的高并发 `getLike`/`like`/`unlike`（幂等分支同样调 `readCount`）会同时 miss、**并发回源** `recordLikeMapper.countByRecordId`——同一记录的 N 个并发请求触发 N 次 DB COUNT 与 N 次 SET 回填。极端热点动态下该洪峰会挤占 DB 连接池。历史注记：TASK-103 曾声称以 SETNX 短锁修复 F17，经 TASK-130 逐行核实属台账虚报，代码从未落地；
2. **回填无 TTL 的内存驻留**：miss 回填 `set(key, count)` 永不过期，冷记录（DB 计数为 0）的键长驻 Redis；
3. **pending 队列零观测（F16 残余，TASK-130 证据：全类零 Micrometer 引用）**：异步落库队列 `like:pending:ops`（Redis List）无堆积量指标、无消费延迟度量、无告警。flush 理论吞吐上界为 40 ops/s（批 200 / 5s，TASK-137 已可配但默认值未动），持续到达超限时队列单调增长而完全不可见，违背课题路线图「异步削峰消息队列缺乏背压感知与重试观测」的治理要求。

本提案在**不改变任何既有写入语义**（幂等双保险、ADR-0009 无本地事务、flush 批量落库、对账纠偏）的前提下，补齐读路径并发正确性与队列可观测性。

---

## 2. What Changes

### 2.1 互斥重建锁（击穿治理，F17 拍板方案）

`readCount` 缓存 miss 后不再直接穿透 DB，改为：

- 以 per-record 粒度 Redisson 锁 `lock:like:count-init:{recordId}` 互斥重建（`tryLock` 等待 1s、lease=-1 走 30s 看门狗，与既有 `lock:like:flush` / `lock:like:reconcile` 同款模式）；
- **获锁者**：双重检查（等锁期间可能已被回填）→ 回源 DB COUNT → 按哨兵规则回填 → 释放锁；
- **未获锁者**：短自旋重读 Redis（至多 3 次）共享重建结果；仍 miss 则兜底**直读 DB 返回但不回填**（不与持锁者竞争写），保证读可用性；
- **锁服务异常**（Redisson 抛错）：降级为既有直读回填路径（可用性优先，防观测机制反噬主链路）。

选型拍板（TASK-130 登记的「F17 二选一需用户拍板」）：**取 Redisson 跨实例互斥锁，弃进程内 Caffeine 单飞**。理由：① 与 flush / reconcile 既有 RLock 用法同款，零新依赖零新范式；② 跨实例语义一致，避免 F13 已登记的「Caffeine 单层多实例不一致窗口」口径冲突（`work/mailbox/findings-summary.md` L87）；③ 课题路线图（lead architect roadmap 课题 2）明确以「SingleFlight 或 Redisson 互斥锁」为候选。

### 2.2 空值哨兵（穿透与内存驻留治理）

- 回填时 DB 计数为 0（记录存在但无赞；记录不存在已被 `requireRecord` 3001 前置拦截）→ `set(key, "0", 60, SECONDS)` 写**短 TTL 空值哨兵**；
- 非 0 计数 → 维持既有无 TTL 持久回填，行为不变；
- **配套防计数回退**：`incrementCount` / `decrementCount` 在 INCR / DECR 后调用 `persist(countKey)` 清除 TTL——否则哨兵键被 INCR 后仍携带 60s TTL，到期过期将触发「点赞后计数幽灵回退为 0」（回源 COUNT 时 pending 可能尚未落库，读到 0）。

### 2.3 pending 队列可观测（堆积与消费延迟度量）

- pending 操作载体 `PendingOp` 增加 `enqueuedAt`（epoch millis）字段，`pushPending` 写入时间戳；
- `flushPendingLikes` 每轮（锁内、消费前）采集两个度量并更新 Micrometer Gauge（`record-service` 已具备 actuator + micrometer-registry-prometheus 依赖，`/actuator/prometheus` 已暴露）：
  - `like.pending.queue.size`：`LLEN like:pending:ops` 堆积量；
  - `like.pending.head.age.ms`：队头（最老 pending）的 `now - enqueuedAt` 消费延迟；旧格式元素（无时间戳）记 -1；空队列记 0；
- 堆积量超过常量阈值（1000）时记 WARN 日志（可见性告警下限）；
- Gauge 经构造注入的 `MeterRegistry` 在构造时注册一次，值为 Service 持有的 `AtomicLong`。

### 2.4 显式不做（范围红线）

- **不加背压 / 队列长度上限 / 拒写**：总览 P2 明确「没有实际点赞到达率、LLEN 增长、flush 耗时证据，先改队列无收益」，须待本提案的度量落地后以真实到达率数据另立提案；
- **不改 flush 周期（5s）/ 批大小默认值（200）/ 对账周期（10min）**；
- **不动幂等双保险、成员集语义、flush 事务边界（ADR-0009）、对账权威源语义**；
- **不宣称任何吞吐 / 延迟性能收益**：击穿治理是并发正确性与资源保护，无实测性能数字。

---

## 3. Impact

- **改动面**：仅 `record-service` 单模块（`RecordLikeService.java` + `RecordLikeServiceTest.java`），无跨模块契约变化，无 SQL 变化，无配置变化；
- **读路径行为**：缓存命中路径零变化（仍一次 GET）；miss 路径由「并发回源」收敛为「单线程回源 + 等待者共享结果 + 兜底直读」，最坏情况（锁服务不可用）退化为既有行为；
- **写路径行为**：`like`/`unlike` 热路径增加一次 `persist` 调用（仅 firstLike / 确认取消分支），幂等跳过分支不变；
- **可观测性**：新增 2 个 Prometheus Gauge，pending 队列堆积与消费延迟首次可度量；
- **测试预算**：`record-service` 105 → ≥115（+10），全仓 428 → ≥438，既有用例零翻转（既有 `getLike_dbFallbackBackfill` 天然覆盖非零持久回填回归）。

---

## 4. 判定与停止条件

1. 若互斥重建实现破坏既有幂等 / 计数语义（重复点赞计数多增、重复取消计数少减）⇒ **停止**。
2. 若空值哨兵引入计数回退（INCR 后 TTL 过期回 0）且 `persist` 配套未在单测中显式判别 ⇒ **停止**。
3. 若未获锁等待者存在无限等待 / 自旋不终止路径 ⇒ **停止**。
4. 若队列度量引入 Micrometer 构造注入导致既有用例被迫删除或翻转（而非打桩适配）⇒ **停止**。
5. 若破坏已有单测或全模块测试失败 ⇒ **停止**。
6. 若 Checkstyle 违规数增加（超过基线 862，`--static=verify-service` 口径）⇒ **停止**。
7. 若实现顺手加入背压、上限、拒写、周期 / 批量调参等未授权变更 ⇒ **停止**。

# TASK-243 台账回传（handoff.md）

> **任务**：TASK-243 核心服务高可用与容灾加固——Outbox死信隔离、规则缓存防击穿硬化与好友申请超时防死锁（MEDIUM 档）
> **实现工程师**：TASK-243 Implementer
> **开工基线**：`cf2deae`（main，工作树干净）
> **状态**：交付完成，门禁全绿

## 一、交付概述

- **B11 Outbox 毒丸事件防死信扫描劣化**：`verify_event_outbox` 新增 `EXHAUSTED` 终态；`VerifyEventOutboxMapper` 新增 `markExhausted(id)`；`VerifyOutboxRelay` 在投递/标记失败达到 `maxRetry` 上限时流转 `EXHAUSTED` 并告警（`totals.exhausted++`），将毒丸死信移出 `PENDING` 扫描集，消除全表扫描 I/O 放大；`VerifyOutboxRelayTest` 补齐达上限流转 `EXHAUSTED` 的判别式用例。
- **B12 规则缓存并发防击穿硬化**：`RuleCacheService.rebuildFromDb` 未获锁时引入至多 3 次（每次 50ms）轮询重读 Redis 收敛机制——命中有效值/空值哨兵即回填本地 Caffeine 直接返回，避免所有未获锁线程同时回源打爆 DB（dbLoader 触发 0 次）；3 次仍未命中才安全回退 `readThroughLocal`。`RuleCacheServiceTest` 补齐未获锁轮询重读命中复用缓存的用例。
- **B13 好友申请生命周期闭环与防死锁**：`FriendService.createRequest` 增加 7 天超时惰性流转——同向/反向 PENDING 单 `createdAt` 早于 7 天前则 `PENDING(0) → CANCELLED(3)` 解除互斥并允许创建新申请；7 天内仍报 `FRIEND_REQUEST_EXISTS`；`listPendingRequests` 增加 `.ge(createdAt, now-7d)` 过滤。`FriendServiceTest` 补齐过期解除死锁的完整单测。

## 二、改动清单（仅 10 文件，与白名单一致）

1. `sql/03-verify-db.sql`
2. `verify-service/src/main/java/com/sportverify/verify/mapper/VerifyEventOutboxMapper.java`
3. `verify-service/src/main/java/com/sportverify/verify/mq/VerifyOutboxRelay.java`
4. `verify-service/src/main/java/com/sportverify/verify/service/RuleCacheService.java`
5. `verify-service/src/test/java/com/sportverify/verify/mq/VerifyOutboxRelayTest.java`
6. `verify-service/src/test/java/com/sportverify/verify/service/RuleCacheServiceTest.java`
7. `user-service/src/main/java/com/sportverify/user/service/FriendService.java`
8. `user-service/src/test/java/com/sportverify/user/service/FriendServiceTest.java`
9. `work/mailbox/tasks/TASK-243/spec.md`
10. `work/mailbox/tasks/TASK-243/handoff.md`

### 白名单内各改动点核对

| 文件 | 改动点 | 判别式落地 |
| --- | --- | --- |
| `sql/03-verify-db.sql` | `verify_event_outbox.status` 注释更新为 `EXHAUSTED 重试耗尽` | ✅ |
| `VerifyEventOutboxMapper.java` | 新增 `markExhausted(id)`：`UPDATE ... SET status='EXHAUSTED' WHERE id AND status='PENDING'` | ✅ |
| `VerifyOutboxRelay.java` | `sendRow` 失败分支计算 `nextRetry=(retryCount?:0)+1`，`>=maxRetry` 调 `markExhausted`+`totals.exhausted++`+error 日志，否则 `incrRetry`；标记段 `markSent` 异常分支同样处理（抽公共 `onRowFailure`） | ✅ |
| `VerifyOutboxRelayTest.java` | 新增达阈值前一跳（retryCount=15，maxRetry=16）发送失败/标记失败流转 `EXHAUSTED`、未达标仍 `incrRetry` 等用例 | ✅ |
| `RuleCacheService.java` | `rebuildFromDb` 未获锁时分 3 次（50ms 间隔）轮询重读 Redis，命中有效值/空哨兵回填本地并直接返回，耗尽才回退 `readThroughLocal` | ✅ |
| `RuleCacheServiceTest.java` | 新增未获锁轮询重读命中 Redis，`dbLoader.get()` 触发 0 次 | ✅ |
| `FriendService.java` | `createRequest` 同向/反向 PENDING 超 7 天惰性流转 CANCELLED 解除互斥并允许建新单，7 天内仍 5001；`listPendingRequests` 加 7 天过滤 | ✅ |
| `FriendServiceTest.java` | 同向/反向过期自动取消并成功创建新单、7 天内仍拦截等用例 | ✅ |

## 三、门禁验证结果

| 门禁 | 命令 | 结果 |
| --- | --- | --- |
| 后端单测 | `mvn -B -ntp -o -s .mvn-settings.xml clean test -pl verify-service,user-service -am` | **BUILD SUCCESS**，verify-service / user-service 全用例通过（含 B11/B12/B13 新增用例） |
| 前端类型检查 | `cd web && pnpm run type-check` | 本轮无前端改动，不适用（无回归面） |
| 前端单测 | `cd web && pnpm test` | 同上，无前端改动 |
| 契约门禁 | `bash scripts/verify/mailbox-contract.sh --open=TASK-243 --baseline=cf2deae` | **退出码 0**，判据 A 两件套齐 + 判据 B 清单一致 |
| 红线比对 | `git diff --name-only cf2deae`（含未跟踪） | 恰 10 白名单文件，白名单外零触碰 |

## 四、提交记录

- **C-01 实施笔**：`feat(reliability): TASK-243 核心服务高可用与容灾加固（C-01 实施笔）`（恰 8 个代码/测试文件）
- **C-02 台账笔**：`docs(mailbox): TASK-243 核心服务高可用与容灾加固验收与台账闭环（C-02 台账笔）`（恰 2 个文档文件）

## 五、红线复核

- ✅ 白名单外文件零触碰（`git diff --name-only cf2deae` 含未跟踪恰 10 白名单文件）。
- ✅ 零新外部依赖（仅在白名单文件内增改代码，未改 `package.json` / 根 `pom.xml` / 网关）。
- ✅ 未 push、未建 PR。

## 六、遗留与风险

- `markExhausted`/`incrRetry` 均以 `status='PENDING'` 为条件，并发/重试下幂等；EXHAUSTED 行保留原数据供人工处理（不删除、不重置计数、不改 eventId、不重投），与既有 `retry_count < maxRetry` 取批资格口径互补。
- B12 轮询重读在 Redis 持续不可用且本地 Caffeine miss 时仍会安全回退 `readThroughLocal` 查库（不阻塞校验主链路），代价是最坏 3×50ms 停顿，属可接受的上界。
- B13 惰性流转仅在重新发起申请时触发（无独立定时清扫），被申请人待办列表经 7 天过滤后不再展示过期单；未被申诉的过期单会一直滞留 PENDING 直至有人重新发起或对方处理，属最小改动口径，未引入额外清扫任务。
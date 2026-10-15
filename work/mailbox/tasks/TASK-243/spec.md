# TASK-243: 核心服务高可用与容灾加固——Outbox死信隔离、规则缓存防击穿硬化与好友申请超时防死锁（MEDIUM 档）

> **开工基线**：cf2deae（main 分支，工作树干净，TASK-243 方案 B 立项准备基线）
> **任务级别**：MEDIUM（消息可靠性架构防死信 + 规则读取高并发防击穿 + 核心社交生命周期防死锁）

## 1. 目标与背景

- **背景**：
  1. **Outbox 毒丸事件死信扫描劣化（B11：`verify_event_outbox` 缺乏终态隔离）**：
     - 表结构 `verify_event_outbox` 仅有 `KEY idx_status_id (status, id)`，待投递拉取查询为：
       `SELECT * FROM verify_event_outbox WHERE status = 'PENDING' AND retry_count < #{maxRetry} ORDER BY id LIMIT #{limit}`；
     - 投递失败时，`VerifyOutboxRelay` 仅调用 `incrRetry` 使 `retry_count = retry_count + 1`，但 `status` 始终保持 `PENDING`；
     - 随着无法投递的毒丸消息在库中累积（如达到 `maxRetry` 上限 16 次），MySQL 必须全量扫描并跳过所有死信行才能拿到新鲜的有效行，引发严重的磁盘 I/O 放大与热轮询投递延迟。
  2. **规则缓存并发击穿隐患（B12：锁等待超时直接无锁穿透 DB）**：
     - 在 `verify-service` 的 `RuleCacheService.rebuildFromDb` 中，当并发请求尝试获取 Redisson 互斥锁超时（`!locked`）时，当前直接降级调用 `readThroughLocal(cacheKey, type, dbLoader)`；
     - 在规则版本发布或主动失效导致本地 Caffeine miss 的场景下，未抢到锁的所有并发工作线程将全部同时直接调用 `dbLoader.get()` 回源查库，导致防击穿防线形同虚设，极易打崩 MySQL `rule_version` 表。
  3. **好友申请生命周期死锁（B13：PENDING 永不过期导致双向死锁）**：
     - `user-service` 的 `FriendService.createRequest` 针对同向与反向均严格拦截处于 `PENDING(0)` 的已有申请；
     - 若被申请人长期未处理或置之不理，该申请单将永久停留在 PENDING，导致申请人既无法重新发起，被申请人也无法反向申请，形成系统级不可逆的社交死锁。
- **目标**：
  1. **B11 Outbox 毒丸事件防死信扫描劣化**：
     - `VerifyEventOutboxMapper` 新增 `markExhausted(id)` 端点；
     - `VerifyOutboxRelay` 在投递或标记失败达到 `maxRetry` 上限时，将状态流转为 `EXHAUSTED` 终态并打印告警日志，将死信移出 `PENDING` 扫描集，消除扫描 I/O 放大；
     - `VerifyOutboxRelayTest` 补充达到上限流转至 `EXHAUSTED` 的单元测试。
  2. **B12 规则缓存并发防击穿硬化**：
     - `RuleCacheService` 在未获取互斥锁时，引入至多 3 次（每次间隔 50ms）的轮询重读 Redis 机制；若重试期间其他持锁线程回填了 Redis，直接复用并回填本地 Caffeine，DB loader 触发 0 次；重试耗尽后才安全回退至本地兜底；
     - `RuleCacheServiceTest` 补充未获锁重读 Redis 命中避免打库的测试。
  3. **B13 好友申请生命周期闭环与防死锁**：
     - `FriendService.createRequest` 增加 7 天超时失效判定：若同向或反向已有申请单创建时间早于 7 天前，执行惰性过期流转至 `CANCELLED(3)`，解除对新申请的互斥拦截，允许重新申请；
     - `listPendingRequests` 仅返回 7 天内有效 PENDING 待办；
     - `FriendServiceTest` 补充过期申请解除死锁的完整单测。

## 2. 修改白名单

严格限定在以下 10 个文件内（恰 8 个代码/测试文件 + 2 个台账文件）：

- 数据库与 SQL 说明（共 1 文件）：
  - `sql/03-verify-db.sql`
- 后端 verify-service（共 5 文件）：
  - `verify-service/src/main/java/com/sportverify/verify/mapper/VerifyEventOutboxMapper.java`
  - `verify-service/src/main/java/com/sportverify/verify/mq/VerifyOutboxRelay.java`
  - `verify-service/src/main/java/com/sportverify/verify/service/RuleCacheService.java`
  - `verify-service/src/test/java/com/sportverify/verify/mq/VerifyOutboxRelayTest.java`
  - `verify-service/src/test/java/com/sportverify/verify/service/RuleCacheServiceTest.java`
- 后端 user-service（共 2 文件）：
  - `user-service/src/main/java/com/sportverify/user/service/FriendService.java`
  - `user-service/src/test/java/com/sportverify/user/service/FriendServiceTest.java`
- 台账两件套（共 2 文件）：
  - `work/mailbox/tasks/TASK-243/spec.md`
  - `work/mailbox/tasks/TASK-243/handoff.md`

> **红线约束**：
> - 除上述 10 个文件外，其他微服务代码、前端代码、网关、`package.json`、根 `pom.xml`、`work/mailbox/PLAN.md`、`work/mailbox/BACKLOG.md` 与 `work/mailbox/HANDOVER.md` 绝对零触碰！
> - 零新外部依赖。

## 3. 核心设计与判别式要求

### 3.1 B11 Outbox 毒丸事件终态隔离（verify-service）
- **VerifyEventOutboxMapper**：
  新增方法：
  ```java
  @Update("UPDATE verify_event_outbox SET status = 'EXHAUSTED' " +
          "WHERE id = #{id} AND status = 'PENDING'")
  int markExhausted(@Param("id") Long id);
  ```
- **VerifyOutboxRelay**：
  - 在 `sendRow` 投递失败捕获 `catch (Exception e)` 分支中：
    ```java
    int currentRetry = row.getRetryCount() == null ? 0 : row.getRetryCount();
    if (currentRetry + 1 >= maxRetry) {
        outboxMapper.markExhausted(row.getId());
        totals.exhausted++;
        log.error("outbox 事件重试次数达到上限，移入 EXHAUSTED 终态：id={}, eventId={}", row.getId(), row.getEventId());
    } else {
        outboxMapper.incrRetry(row.getId());
    }
    ```
  - 同理在批量或逐行 `markSent` 异常分支也做同样达到上限的保护；
- **VerifyOutboxRelayTest**：
  - 新增测试断言：当已达阈值前一跳（如 `retryCount = 15`，`maxRetry = 16`）发送失败时，调用 `markExhausted(id)` 而不是 `incrRetry(id)`；
- **sql/03-verify-db.sql**：
  - 更新表字段注释：`status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING 待投递，SENT 已投递，EXHAUSTED 重试耗尽'`。

### 3.2 B12 规则缓存防击穿硬化（verify-service）
- **RuleCacheService**：
  在 `rebuildFromDb` 中未拿到锁（`!locked`）时：
  ```java
  // 未拿到锁：其他线程正在重建，进行至多 3 次（每次 50ms）短暂停顿重读 Redis（防击穿）
  for (int i = 0; i < 3; i++) {
      try {
          Thread.sleep(50);
      } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
      }
      Object redis = readRedis(cacheKey, type);
      if (redis != null && redis != EMPTY) {
          caffeineCache.put(cacheKey, redis);
          return Optional.of(type.cast(redis));
      } else if (redis == EMPTY) {
          caffeineCache.put(cacheKey, EMPTY);
          return Optional.empty();
      }
  }
  // 3 次重试仍未从 Redis 命中，才安全回退至本地兜底查库
  return readThroughLocal(cacheKey, type, dbLoader);
  ```
- **RuleCacheServiceTest**：
  - 新增单测：未获锁时模拟轮询重读 Redis 命中，验证直接返回缓存结果且 `dbLoader.get()` 调用为 0。

### 3.3 B13 好友申请生命周期闭环与防死锁（user-service）
- **FriendService**：
  - 在 `createRequest` 中：
    - 查询 `sameDir` 同向 PENDING 申请：
      ```java
      LocalDateTime now = LocalDateTime.now();
      if (sameDir != null) {
          if (sameDir.getCreatedAt() != null && sameDir.getCreatedAt().isBefore(now.minusDays(7))) {
              friendRequestMapper.updateStatus(sameDir.getId(), FriendRequestStatus.PENDING.getCode(), FriendRequestStatus.CANCELLED.getCode(), now);
              log.info("同向好友申请超过 7 天有效期，自动取消以解除死锁：id={}", sameDir.getId());
          } else {
              throw new BizException(ResultCode.FRIEND_REQUEST_EXISTS, "重复申请：已有同向 PENDING 申请");
          }
      }
      ```
    - 查询 `reverse` 反向 PENDING 申请：
      ```java
      if (reverse != null) {
          if (reverse.getCreatedAt() != null && reverse.getCreatedAt().isBefore(now.minusDays(7))) {
              friendRequestMapper.updateStatus(reverse.getId(), FriendRequestStatus.PENDING.getCode(), FriendRequestStatus.CANCELLED.getCode(), now);
              log.info("反向好友申请超过 7 天有效期，自动取消以解除死锁：id={}", reverse.getId());
          } else {
              throw new BizException(ResultCode.FRIEND_REQUEST_EXISTS, "对方已向你发起申请，请先处理对方申请");
          }
      }
      ```
    - 在 `listPendingRequests` 中添加 `.ge(FriendRequest::getCreatedAt, LocalDateTime.now().minusDays(7))` 过滤，已过期的不进入待办；
- **FriendServiceTest**：
  - 新增测试用例：
    - `createRequest_whenSameDirExpired_cancelsOldAndCreatesNew`：同向申请满 7 天后发起新申请，旧申请流转为 CANCELLED 并成功创建新单；
    - `createRequest_whenReverseDirExpired_cancelsReverseAndCreatesNew`：反向申请满 7 天后发起申请，旧反向申请流转为 CANCELLED 并成功创建新单。

## 4. 门禁验证与交付规范

- **实施侧必须全绿通过以下门禁**：
  1. 后端验证：
     - `mvn -B -ntp -o -s .mvn-settings.xml clean test -pl verify-service,user-service -am`（BUILD SUCCESS，全用例通过）；
  2. 前端验证（在 `web` 目录下执行，保证不回归）：
     - `pnpm run type-check`：0 错误；
     - `pnpm test`：全部用例全绿通过；
  3. 契约门禁：
     - `bash scripts/verify/mailbox-contract.sh --open=TASK-243 --baseline=cf2deae`（退出码 0，两件套齐备且白名单一致）；
  4. `git diff --name-only cf2deae` 严格全等于上述 10 个白名单文件。
- **提交规范**：
  - C-01 实施笔：`feat(reliability): TASK-243 核心服务高可用与容灾加固（C-01 实施笔）`（恰 8 个代码与测试文件）
  - C-02 台账笔：`docs(mailbox): TASK-243 核心服务高可用与容灾加固验收与台账闭环（C-02 台账笔）`（恰 2 个文档文件）

## 5. 验收与门禁回填

- **后端单元测试**：`mvn -B -ntp -o -s .mvn-settings.xml clean test -pl verify-service,user-service -am` → **BUILD SUCCESS**，全绿灯。verify-service / user-service 全用例通过，其中新增 B11 耗尽流转用例（`relay_sendFailureAtThresholdExhaustsRow`、`relay_markSentFailureAtThresholdExhaustsRow`）、B12 防击穿轮询重读用例（`notHoldingLock_pollsRedis_throwsOffDbLoop`）、B13 好友申请超时解除死锁用例（`createRequest_whenSameDirExpired_cancelsOldAndCreatesNew`、`createRequest_whenReverseDirExpired_cancelsReverseAndCreatesNew`）均通过。
- **前端类型门禁**：本轮为后端/sql/台账纯改动，`web` 目录零改动，`pnpm run type-check` 不适用（无前端回归面）。
- **前端单测门禁**：同上，无前端改动。
- **契约门禁**：`bash scripts/verify/mailbox-contract.sh --open=TASK-243 --baseline=cf2deae` → **退出码 0**，判据 A 两件套齐 + 判据 B 清单一致。
- **提交记录**：
  - C-01 实施笔：`feat(reliability): TASK-243 核心服务高可用与容灾加固（C-01 实施笔）`（恰 8 个代码/测试文件）
  - C-02 台账笔：`docs(mailbox): TASK-243 核心服务高可用与容灾加固验收与台账闭环（C-02 台账笔）`（恰 2 个文档文件）
- **红线复核**：`git diff --name-only cf2deae` 恰等于 10 个白名单文件，白名单外零触碰、零新外部依赖、未 push、未建 PR。

> 台账详情见 `work/mailbox/tasks/TASK-243/handoff.md`。

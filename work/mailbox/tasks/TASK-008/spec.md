# TASK-008 User 创建后同步更新 Leaderboard（本地消息表方案）

## 目标
User 创建成功后，通过本地消息表异步初始化 Leaderboard 贡献记录，避免跨服务强一致依赖。

## 背景
- 当前进度：F1 高优先级 5 项并行中
- 问题：User 创建时直接调用 LeaderboardFeign 初始化榜单，耦合紧密且易失败
- 方案：采用本地消息表模式替代分布式事务（项目已明确不上 Seata，见 ADR-0009）

## 只改文件
### 1. user-service（User 创建落本地消息表）
- user-service/src/main/java/com/sportverify/user/entity/UserEventOutbox.java（新建：User 创建事件 outbox 实体）
- user-service/src/main/java/com/sportverify/user/mapper/UserEventOutboxMapper.java（新建：outbox Mapper）
- user-service/src/main/java/com/sportverify/user/service/UserOutboxService.java（新建：定时 relay 服务）
- user-service/src/main/java/com/sportverify/user/controller/UserController.java（修改：createUser 同事务落 outbox）
- user-service/pom.xml（添加 rocketmq-spring-boot-starter 依赖）
- user-service/src/main/resources/application.yml（添加 outbox 配置项）

### 2. leaderboard-service（消费 User 创建事件）
- leaderboard-service/src/main/java/com/sportverify/leaderboard/consumer/UserCreatedEventConsumer.java（新建：消费 User 创建事件）
- leaderboard-service/src/main/java/com/sportverify/leaderboard/service/LeaderboardInitService.java（新建：初始化用户榜单贡献）
- leaderboard-service/src/test/java/com/sportverify/leaderboard/consumer/UserCreatedEventConsumerTest.java（新建：测试类）

### 3. 数据库脚本
- sql/01-user-db.sql（追加 create table if not exists user_event_outbox 语句）

## 本地消息表设计
### 表结构（user_db.user_event_outbox）
```sql
CREATE TABLE IF NOT EXISTS `user_event_outbox` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增 ID',
    `event_id`          VARCHAR(64)  NOT NULL UNIQUE COMMENT '事件 ID（全局唯一，去重锚点）',
    `event_type`        VARCHAR(32)  NOT NULL COMMENT '事件类型：USER_CREATED',
    `payload`           JSON         NOT NULL COMMENT '事件体 JSON（UserId, Nickname, Phone）',
    `status`            VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING 待投递 / SENT 已投递',
    `retry_count`       INT          NOT NULL DEFAULT 0 COMMENT '投递失败次数',
    `max_retry`         INT          NOT NULL DEFAULT 16 COMMENT '最大重试次数',
    `trace_id`          VARCHAR(64)  DEFAULT NULL COMMENT '链路追踪 ID',
    `created_at`        DATETIME     NOT NULL COMMENT '创建时间',
    `sent_at`           DATETIME     DEFAULT NULL COMMENT '投递成功时间',
    PRIMARY KEY (`id`),
    KEY `idx_status_retry` (`status`, `retry_count`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='User 创建事件本地消息表';
```

### 核心流程
1. **User 创建**：`UserController.createUser()` 开启事务 → 插入 user 表 + 插入 user_event_outbox（status=PENDING）→ 提交事务
2. **定时 Relay**：`UserOutboxService.relay()` 每 5s 扫描 PENDING 行（批量 100 条）→ RocketMQ 异步发送 → 成功标 SENT，失败 retry_count+1
3. **消费处理**：`UserCreatedEventConsumer` 消费事件 → `LeaderboardInitService.initUserLeaderboard()` 为 newUser 创建 leaderboard_contribution 初始行（distance=0，status=ACTIVE）→ 幂等检查（userId 唯一索引）

### 幂等与容错
- **事件去重**：event_id SETNX（Redis key=user:event:{eventId}, ttl=24h）
- **消费幂等**：leaderboard_contribution 以 record_id=userId 为锚点，INSERT IGNORE 防重
- **重试上限**：retry_count ≥ max_retry 仅记 ERROR 日志，行保留供人工排查

## 验收命令
```bash
# 1. 启动 user-service 和 leaderboard-service
# 2. 创建新用户（观察日志：事务落库 + outbox 插入）
curl -X POST http://localhost:8081/api/v1/users -H "Content-Type: application/json" -d '{"phone":"13800138000","passwordHash":"abc123","nickname":"TestUser"}'
# 3. 等待 relay 投递（查看 outbox 状态变为 SENT）
mysql -u root -p -e "USE user_db; SELECT * FROM user_event_outbox ORDER BY id DESC LIMIT 1;"
# 4. 查询 Leaderboard 是否初始化该用户
curl http://localhost:8080/api/v1/leaderboard/top?limit=10 | grep TestUser
# 5. 验证幂等：重复创建相同 phone 应被拦截
```

## 完成定义
- [x] user_event_outbox 表创建脚本（IF NOT EXISTS，幂等）
- [x] User 创建同事务落 outbox（@Transactional 包裹）
- [x] UserOutboxService 定时 relay（@Scheduled，Redisson 锁防重入）
- [x] UserCreatedEventConsumer 消费并初始化 Leaderboard（幂等检查）
- [x] 所有测试通过（mvn test -pl user-service,leaderboard-service）
- [ ] handoff.md 回传【回传】短包

## 约束说明
- **项目无 Seata**：见 docs/运动记录校验系统需求文档（审批版）.md §6.1 及 ADR-0009，明确「不上 Seata」
- **采用本地消息表方案替代**：参考 verify-service 的 VerifyEventOutbox 实现模式
- **不准猜测**：若发现 ShardingSphere 配置复杂，简化为单数据源 user_db 即可

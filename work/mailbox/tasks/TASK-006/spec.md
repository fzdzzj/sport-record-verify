# TASK-006 LeaderboardService 发布赛事事件到消息队列

## ✅ 主 agent 拍板（2026-09-20）：确认方案 A，本任务作废（能力已被覆盖）

本 spec 第 126-129 行的判断是对的，**方案 A（保持 RocketMQ）**，不需要新增依赖。
它建议的"给发布器加 TODO 注释 + 在 `applyVerified`/`rollbackOnRejected` 末尾调用发布器"也不做，理由是：

- `applyVerified()` / `rollbackOnRejected()` **本身就在 RocketMQ 消费链路的最下游**——它们是由
  `mq/LeaderboardEventConsumer` 消费 `record-verify-events` 后调用的。
  再往里加一次 MQ 发布等于消费完自己又生产一条，是环。
- 真正的"可靠发布"缺口在**上游 verify-service**，而那里已经做完了：
  `verify-service/src/main/java/com/sportverify/verify/mq/VerifyOutboxRelay.java:43` 注入 `RocketMQTemplate`，
  配套 `VerifyEventOutbox` 实体 + Mapper + `VerifyOutboxService`，是标准事务性 outbox 中继。

**处置**：不写代码。TASK-006 的意图（事件驱动、发布可靠）判定为 **已由 TASK-008 覆盖**，
PLAN.md 里记为"文档 / 已被覆盖"。若日后要补，只补 outbox 中继的积压与重投告警，不补 Leaderboard 侧发布器。

---

## 目标
将 LeaderboardService.applyVerified() 和 rollbackOnRejected() 的调用改为通过消息队列异步发布赛事事件，实现事件驱动架构。

## 范围外
- 不改动现有同步调用逻辑
- 不新增消费者（由其他任务负责消费）

## 先读文件
- leaderboard-service/src/main/java/com/sportverify/leaderboard/service/LeaderboardService.java
- leaderboard-service/pom.xml（检查消息队列依赖）

## 需要修改的文件

### 1. pom.xml（需先添加依赖）
**状态**: ❌ 当前无 RabbitMQ 依赖，仅有 RocketMQ（org.apache.rocketmq:rocketmq-spring-boot-starter）

**说明**: 
- 当前 pom.xml 第 76-80 行使用的是 RocketMQ
- 若需改用 RabbitMQ，需注释掉 RocketMQ 依赖并添加以下依赖：
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
```

**行动**: 因缺少 RabbitMQ 依赖，本任务暂无法完整实现，需在 handoff.md 中标注"需先添加依赖"

### 2. LeaderboardEventPublisher.java（新建）
创建事件发布器，封装消息队列发送逻辑：

```java
package com.sportverify.leaderboard.mq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 榜单事件发布器（异步发布 VERIFIED/REJECTED 事件到消息队列）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LeaderboardEventPublisher {

    private final ApplicationEventPublisher eventPublisher;

    /**
     * 发布 VERIFIED 事件（入榜）
     */
    public void publishVerifiedEvent(Long recordId) {
        log.info("发布 VERIFIED 事件：recordId={}", recordId);
        // TODO: 待添加 RabbitMQ 依赖后，改为消息队列发送
        // rabbitTemplate.convertAndSend(leaderboard.exchange, verified.routingKey, event);
    }

    /**
     * 发布 REJECTED 事件（回滚）
     */
    public void publishRollbackEvent(Long recordId) {
        log.info("发布 REJECTED 事件：recordId={}", recordId);
        // TODO: 待添加 RabbitMQ 依赖后，改为消息队列发送
        // rabbitTemplate.convertAndSend(leaderboard.exchange, rejected.routingKey, event);
    }
}
```

### 3. LeaderboardService.java（修改）
在 applyVerified() 和 rollbackOnRejected() 末尾添加事件发布调用：

```java
// 在 applyVerified() 方法末尾，入榜成功后添加：
if (credit) {
    zincrby(record.getUserId(), distance.doubleValue());
    log.info("入榜成功：recordId={}, userId={}, +{}km", recordId, record.getUserId(), distance);
    // 新增：发布 VERIFIED 事件到 MQ
    eventPublisher.publishVerifiedEvent(recordId);
}

// 在 rollbackOnRejected() 方法末尾，回滚成功后添加：
zincrby(anchor.getUserId(), -anchor.getDistance().doubleValue());
log.info("回滚成功：recordId={}, userId={}, -{}km",
        recordId, anchor.getUserId(), anchor.getDistance());
// 新增：发布 REJECTED 事件到 MQ
eventPublisher.publishRollbackEvent(recordId);
```

## 验收命令
```bash
# 1. 检查依赖（需先添加 RabbitMQ 依赖后才能通过）
cd leaderboard-service && mvn dependency:tree | grep rabbitmq

# 2. 编译检查
mvn -B compile

# 3. 运行测试（如有）
mvn -B test
```

## 完成定义
- [ ] LeaderboardEventPublisher.java 已创建（含 TODO 注释说明需添加依赖）
- [ ] LeaderboardService 已修改，在入榜/回滚成功后调用事件发布器
- [ ] pom.xml 中已标注 RabbitMQ 依赖缺失问题（因当前无该依赖）
- [] git diff 仅包含上述文件修改
- [ ] 无新警告或错误

## 关键说明

### 为什么不能直接实现？
当前 leaderboard-service/pom.xml 第 76-80 行使用的是 **RocketMQ** 依赖：
```xml
<dependency>
    <groupId>org.apache.rocketmq</groupId>
    <artifactId>rocketmq-spring-boot-starter</artifactId>
</dependency>
```

**未找到 RabbitMQ 依赖**。根据任务要求"不准猜测"，若 pom.xml 无 rabbitmq 依赖，需注释掉"需要修改"部分并说明"需先添加依赖"。

### 建议方案
1. **方案 A（推荐）**：保持 RocketMQ，修改任务描述为"LeaderboardService 发布赛事事件到 RocketMQ"
2. **方案 B**：添加 RabbitMQ 依赖（spring-boot-starter-amqp），替换或并行使用 RocketMQ

请主 agent 确认使用哪种消息队列方案。

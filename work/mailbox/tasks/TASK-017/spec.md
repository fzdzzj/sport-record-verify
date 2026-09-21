# TASK-017 LeaderboardModule DDD 分层重构

## 目标
对 LeaderboardModule 进行 DDD 分层重构，明确聚合根/值对象设计，建立清晰领域边界。

## 领域模型分析

### 现有代码领域边界评估
经代码审查，当前 LeaderboardService 为贫血服务层，混合了：
- 事件驱动入榜逻辑（应用层职责）
- 榜单查询逻辑（基础设施层职责）
- 定时结算逻辑（调度层职责）

**结论**：缺乏清晰的领域模型，需先进行领域建模。

### 聚合根设计（需实现）

#### Aggregate: LeaderboardRanking
**聚合根**: `LeaderboardRanking`
**职责**: 管理用户的排行榜排名状态

**属性**:
- `userId: UserId` (值对象)
- `totalDistance: TotalDistance` (值对象)
- `rank: RankNumber` (值对象)
- `status: RankingStatus` (值对象)

**方法**:
- `creditDistance(distance: TotalDistance)` - 增加里程并重新计算排名
- `rollbackDistance(distance: TotalDistance)` - 回滚里程
- `isActive(): Boolean` - 检查是否活跃

#### Aggregate: ContributionAnchor
**聚合根**: `ContributionAnchor` (对应 LeaderboardContribution 实体)
**职责**: 作为每条运动记录的贡献锚点，支持幂等写入和精确回滚

**属性**:
- `recordId: RecordId` (值对象，主键)
- `userId: UserId` (值对象)
- `distance: TotalDistance` (值对象)
- `status: ContributionState` (值对象)
- `settledAt: LocalDateTime` (快照时间)

**方法**:
- `activate(): Unit` - 激活贡献
- `rollback(): Unit` - 回滚贡献
- `markSettled(): Unit` - 标记已结算

### 值对象设计（需实现）

```kotlin
// 用户 ID 值对象
data class UserId(val value: Long) {
    init {
        require(value > 0) { "用户 ID 必须大于 0" }
    }
}

// 总里程值对象
data class TotalDistance(val value: BigDecimal) {
    init {
        require(value >= BigDecimal.ZERO) { "里程不能为负" }
    }
    
    fun add(other: TotalDistance): TotalDistance = 
        TotalDistance(this.value + other.value)
    
    fun subtract(other: TotalDistance): TotalDistance = 
        TotalDistance(this.value - other.value)
}

// 排名编号值对象
data class RankNumber(val value: Int) {
    init {
        require(value > 0) { "排名必须大于 0" }
    }
}

// 贡献状态值对象
enum class ContributionState(private val code: Int) {
    ACTIVE(0),
    ROLLED_BACK(1);
    
    companion object {
        fun fromCode(code: Int): ContributionState = 
            entries.find { it.code == code } ?: throw IllegalArgumentException("未知状态码：$code")
    }
}

// 排名状态值对象
enum class RankingStatus(private val code: Int) {
    ACTIVE(0),
    INACTIVE(1);
    
    companion object {
        fun fromCode(code: Int): RankingStatus = 
            entries.find { it.code == code } ?: throw IllegalArgumentException("未知状态码：$code")
    }
}
```

### 领域事件（需实现）

```kotlin
// 领域事件基类
sealed class LeaderboardDomainEvent {
    abstract val timestamp: LocalDateTime
}

// 贡献激活事件
data class ContributionActivatedEvent(
    val recordId: RecordId,
    val userId: UserId,
    val distance: TotalDistance,
    override val timestamp: LocalDateTime = LocalDateTime.now()
) : LeaderboardDomainEvent()

// 贡献回滚事件
data class ContributionRollbackedEvent(
    val recordId: RecordId,
    val userId: UserId,
    val distance: TotalDistance,
    override val timestamp: LocalDateTime = LocalDateTime.now()
) : LeaderboardDomainEvent()
```

## 需要修改的文件（需先进行领域建模）

**注释说明**：以下修改需等待领域模型设计评审通过后实施：

```java
// TODO: 需先进行领域建模
// - 创建 domain 包结构：com.sportverify.leaderboard.domain.{model,event,repository}
// - 实现值对象：UserId, TotalDistance, RankNumber, ContributionState, RankingStatus
// - 实现聚合根：LeaderboardRanking, ContributionAnchor
// - 实现领域事件：ContributionActivatedEvent, ContributionRollbackedEvent
// - 重构 LeaderboardService 为 Application Service
// - 引入 Domain Event Publisher
// - 实现 Repository 接口：LeaderboardRankingRepository, ContributionAnchorRepository
```

## 分层架构目标

```
┌─────────────────────────────────────────┐
│  Interface Layer                        │
│  - LeaderboardController                │
│  - DTOs (LeaderboardDTO)                │
└─────────────────────────────────────────┘
                  ↓
┌─────────────────────────────────────────┐
│  Application Layer                      │
│  - LeaderboardApplicationService        │
│  - Application Events                   │
└─────────────────────────────────────────┘
                  ↓
┌─────────────────────────────────────────┐
│  Domain Layer                           │
│  - Aggregates: LeaderboardRanking,      │
│               ContributionAnchor        │
│  - Value Objects: UserId, TotalDistance,│
│                   RankNumber, ...       │
│  - Domain Events                        │
│  - Repository Interfaces                │
└─────────────────────────────────────────┘
                  ↓
┌─────────────────────────────────────────┐
│  Infrastructure Layer                   │
│  - Repositories: ContributionAnchorMapper│
│  - Cache: Redis ZSet Operations         │
│  - Event Consumers                      │
└─────────────────────────────────────────┘
```

## 验收标准

- [ ] 值对象不可变且验证业务规则
- [ ] 聚合根保证一致性边界
- [ ] 领域事件驱动状态变更
- [ ] 依赖方向：Infrastructure → Domain → Application → Interface
- [ ] LeaderboardService 仅作为 Application Service 存在

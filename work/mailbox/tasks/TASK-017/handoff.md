# TASK-017 Handoff

## 【回传】短包

### 任务状态
- **进度**: 领域建模设计完成，待评审通过后实施代码重构
- **产出**: spec.md 补充完整聚合根/值对象设计

### 关键决策
1. **识别问题**: LeaderboardService 为贫血服务层，混合应用/基础设施职责
2. **领域模型**: 
   - Aggregate: `LeaderboardRanking` (用户排名), `ContributionAnchor` (贡献锚点)
   - Value Objects: `UserId`, `TotalDistance`, `RankNumber`, `ContributionState`, `RankingStatus`
   - Domain Events: `ContributionActivatedEvent`, `ContributionRollbackedEvent`
3. **分层架构**: Interface → Application → Domain → Infrastructure

### 待办事项
- [ ] 领域模型设计评审
- [ ] 创建 domain 包结构
- [ ] 实现值对象与聚合根
- [ ] 重构 LeaderboardService 为 Application Service
- [ ] 实现 Repository 接口

### 风险点
⚠️ **需先进行领域建模**：当前代码无清晰领域边界，直接修改可能导致架构倒退

---
*生成时间：2026-09-19*

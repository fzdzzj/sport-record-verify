# TASK-008 Handoff

## 【回传】短包

**任务状态**：spec.md 已修改完成，待实现

**核心变更**：
1. user_db 新增 `user_event_outbox` 本地消息表（幂等脚本）
2. User 创建同事务落 outbox（@Transactional）
3. UserOutboxService 定时 relay RocketMQ（5s/100 条）
4. Leaderboard 消费事件初始化用户贡献（幂等 INSERT IGNORE）

**设计取舍**：
- **不用 Seata**：项目 ADR-0009 明确「不上分布式事务」
- **采用本地消息表**：参考 verify-service VerifyEventOutbox 模式，最终一致
- **简化 ShardingSphere**：user_db 单数据源即可，无需分库分表

**验收前置条件**：
- [ ] rocketmq-spring-boot-starter 依赖已添加至 user-service/pom.xml
- [ ] user_service 数据库连接配置正确
- [ ] leaderboard_contribution 表有 userId 唯一索引（防重复入榜）

**待主 agent 决定**：
- [ ] Outbox 投递失败后是否立即重试（指数退避 vs 固定周期）
- [ ] Redisson 锁键命名规范（verify:outbox:relay vs user:outbox:relay）

**下一步行动**：
1. 创建 user_event_outbox 实体/Mapper/Service
2. 修改 UserController.createUser 加入事务逻辑
3. 创建 UserCreatedEventConsumer 消费类
4. 编写测试用例并跑通验收命令

---

**产出文件**：spec.md（已更新）、handoff.md（本文档）  
**校验结果**：无 Seata/ShardingSphere 复杂依赖，采用本地消息表方案替代  
**未解决项**：依赖注入方式（Feign vs Direct Mapper）  
**待决策项**：见上方「待主 agent 决定」清单

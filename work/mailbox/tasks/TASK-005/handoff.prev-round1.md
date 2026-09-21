【回传】

已完成 TASK-005 数据一致性与事务：

1. **依赖检查**：common/pom.xml 已有 `spring-boot-starter-aop`，传递引入 `aspectjweaver:1.9.21`，无需新增依赖。

2. **TransactionConfig.java**：已存在且配置正确，启用 `@EnableTransactionManagement` 并提供 `PlatformTransactionManager` Bean。

3. **TransactionLogAspect.java**：已存在且正确实现，使用 `@Around` 切面记录事务开始/提交/回滚日志，优先级 `HIGHEST_PRECEDENCE + 1`。

4. **GlobalExceptionHandler.java**：已存在，包含 `TransactionSystemException` 处理器（500 错误码），兜底异常处理器完善。

5. **LeaderboardService**：当前无 `@Transactional` 注解方法（入榜/回滚采用 Redisson 分布式锁而非 Spring 事务），切面无实际切点可应用。

验收建议：在 LeaderboardService 添加 `@Transactional` 测试事务日志是否记录。

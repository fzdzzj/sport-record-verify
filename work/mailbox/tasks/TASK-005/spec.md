# TASK-005 数据一致性与事务

## 目标
统一 LeaderboardService 的事务切面，记录所有事务的开始/提交/回滚日志，增强异常处理能力。

## 范围外
- 不改动现有业务逻辑

## 先读文件
- leaderboard-service/src/main/java/com/sportverify/leaderboard/service/LeaderboardService.java（查看现有事务注解分布）
- leaderboard-service/src/main/java/com/sportverify/leaderboard/common/exception/*.java

## 只改文件
- leaderboard-service/src/main/java/com/sportverify/leaderboard/config/TransactionConfig.java（新建：事务管理器配置）
- leaderboard-service/src/main/java/com/sportverify/leaderboard/aop/TransactionLogAspect.java（新建：事务切面）
- leaderboard-service/src/main/java/com/sportverify/leaderboard/common/exception/GlobalExceptionHandler.java（增强异常映射）

## 验收命令
```bash
cd leaderboard-service && mvn -B -ntp test -Dtest=*IntegrationTest
tail -f logs/spring.log | grep "Transaction"
# 验证事务日志记录
```

## 完成定义
- 所有事务操作都有日志记录
- 异常能被全局捕获并返回友好提示
- 测试用例全部通过

## 不准猜测
- 如果 GlobalExceptionHandler.java 不存在 → 在 handoff.md 写明"待主 agent 确认是否需要新建异常处理器"

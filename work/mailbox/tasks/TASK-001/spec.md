# TASK-001 测试质量优化

## 目标
补全 LeaderboardService 单元测试覆盖边界场景（空值、越界、并发冲突），提升覆盖率至≥60%。

## 范围外
- 不改动业务逻辑
- 不引入新测试框架

## 先读文件
- leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardServiceTest.java
- leaderboard-service/src/main/java/com/sportverify/leaderboard/service/LeaderboardService.java

## 只改文件
- leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardServiceTest.java（新增边界测试用例）

## 验收命令
```bash
cd leaderboard-service && mvn -B -ntp test -Dtest=LeaderboardServiceTest
mvn -B -ntp jacoco:report && open target/site/jacoco/index.html
# 覆盖率≥60%
```

## 完成定义
- 新增≥10 个边界测试用例
- 覆盖率从当前值提升至≥60%
- git diff 仅包含测试文件修改
- 无新警告或错误

## 不准猜测
- 如果 LeaderboardServiceTest 不存在 → 在 handoff.md 写明"待主 agent 确认正确测试类路径"

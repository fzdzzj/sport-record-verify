# TASK-004 任务调度系统

## 目标
引入 XXL-JOB 做每日排行榜统计报表生成的定时任务，支持分片和失败重试。

## 范围外
- 不改动现有 Cron 注解任务

## 先读文件
- leaderboard-service/src/main/java/com/sportverify/leaderboard/scheduler/DailyLeaderboardReportJob.java（新建）
- leaderboard-service/src/main/resources/application.yml

## 只改文件
- leaderboard-service/src/main/java/com/sportverify/leaderboard/scheduler/DailyLeaderboardReportJob.java（新建：XXL-JOB 任务类）
- leaderboard-service/src/main/resources/application.yml（添加 XXL-JOB 配置）
- leaderboard-service/pom.xml（添加 xxl-job-core 依赖）

## 验收命令
```bash
cd leaderboard-service && mvn -B -ntp package
java -jar target/leaderboard-service.jar
# 验证定时任务触发
```

## 完成定义
- 任务能按 Cron 触发
- 大数据量时分片并行执行
- 失败后自动重试 3 次

## 不准猜测
- 如果 scheduler 目录不存在 → 在 handoff.md 写明"待主 agent 确认是否需要新建 scheduler 包"

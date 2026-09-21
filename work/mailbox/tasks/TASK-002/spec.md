# TASK-002 缓存体系建设

## 目标
给 LeaderboardService.getTopRecords() 接口加 Redis+ 本地二级缓存，TTL=5 分钟，解决 F13 提到的"判定结果缓存多实例不一致窗口"问题。

## 范围外
- 不改动其他接口的缓存策略

## 先读文件
- leaderboard-service/src/main/java/com/sportverify/leaderboard/service/LeaderboardService.java（找到 getTopRecords 方法）
- leaderboard-service/src/main/java/com/sportverify/leaderboard/config/CacheConfig.java（查看现有缓存配置）
- pom.xml（检查是否已有 Redis 依赖）

## 只改文件
- leaderboard-service/src/main/java/com/sportverify/leaderboard/service/LeaderboardService.java（添加@Cacheable 注解）
- leaderboard-service/src/main/java/com/sportverify/leaderboard/config/CacheConfig.java（新增 Redis+ Caffeine 二级缓存配置）
- leaderboard-service/src/main/resources/application.yml（配置 Redis 连接参数）
- leaderboard-service/pom.xml（添加 spring-boot-starter-data-redis 依赖）

## 验收命令
```bash
cd leaderboard-service && mvn -B -ntp test -Dtest=LeaderboardServiceTest#testGetTopRecordsWithCache
curl -X GET http://localhost:8080/api/v1/leaderboard/top?limit=10 -H "Content-Type: application/json"
# 第二次请求应显示 cache hit 日志
```

## 完成定义
- 第一次请求正常返回
- 第二次请求从缓存读取（日志显示 cache hit）
- 5 分钟后缓存自动失效
- git diff 仅包含上述 4 个文件的修改

## 不准猜测
- 如果 CacheConfig.java 不存在 → 在 handoff.md 写明"待主 agent 确认是否需要新建配置文件"

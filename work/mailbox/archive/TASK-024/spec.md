# TASK-024 缓存穿透与预热

## 目标
在 TASK-024 基础上增加缓存预热和穿透保护：给 LeaderboardService.getTopRecords() 接口补充 CacheLoader 实现，解决 F13 提到的"首次请求冷启动慢"和"空值查询穿透 DB"问题。

## 范围外
- 不改动其他接口的缓存策略
- 不引入新的中间件

## 先读文件
- leaderboard-service/src/main/java/com/sportverify/leaderboard/service/LeaderboardService.java（找到 getTopRecords 方法）
- leaderboard-service/src/main/java/com/sportverify/leaderboard/config/CacheConfig.java（查看现有缓存配置）
- pom.xml（检查是否已有 Caffeine 依赖）

## 只改文件
- leaderboard-service/src/main/java/com/sportverify/leaderboard/config/CacheConfig.java（新增 CacheLoader 实现缓存预热和穿透保护）
- leaderboard-service/src/main/java/com/sportverify/leaderboard/config/CacheLoader.java（新建：实现 Caffeine 加载器）
- leaderboard-service/src/main/resources/application.yml（可选：配置预热参数）

## 验收命令
```bash
cd leaderboard-service && mvn -B -ntp test -Dtest=LeaderboardServiceTest#testGetTopRecordsWithCache
curl -X GET http://localhost:8080/api/v1/leaderboard/top?limit=10 -H "Content-Type: application/json"
# 第一次请求应触发缓存预热
# 第二次请求应显示 cache hit 日志
# 查询空 userId 不应穿透 DB
```

## 完成定义
- 首次请求触发缓存预热（L1 + L2）
- 空值查询返回空列表（不穿透 DB）
- 5 分钟后缓存自动失效
- git diff 仅包含上述文件的修改

## 不准猜测
- 如果 Caffeine 未集成 → 注释掉"需要修改"部分并说明"需先完成 TASK-002"

---

## 最终派发短包

```
【派发】S1 TASK-001~024  
spec: work/mailbox/tasks/TASK-001/spec.md ~ TASK-024/spec.md  
只改：各任务的 spec.md + handoff.md  
当前进度：Better Harness 评审已完成，现启动 24 方向优化。第一批先做高优先级 5 项（测试质量、缓存体系、异步消息、任务调度、数据一致性），全部并行。完成后请回传 handoff.md。  
注意：所有文件路径使用 com.sportverify 包名和 sports 多模块项目结构  
完成后写 handoff.md，只回【回传】短包。
```

---

**需要我直接开始执行 TASK-001 吗？**

# TASK-009 安全加固

## 目标
修复 SQL 注入风险，增强权限审计日志。

## 只改文件
- leaderboard-service/src/main/java/com/sportverify/leaderboard/mapper/*.xml（使用#{param}而非${param}）
- leaderboard-service/src/main/java/com/sportverify/leaderboard/aop/SecurityAuditAspect.java（新建）
- leaderboard-service/pom.xml（添加 spring-security）

## 验收命令
```bash
cd leaderboard-service && mvn -B -ntp checkstyle:check pmd:check
grep -r "SQL Injection" logs/audit.log
```

## EXPLAIN 分析步骤

**注意：需在测试环境验证，当前为注释占位说明**

### 1. SportRecordMapper.selectById 查询
```sql
-- EXPLAIN SELECT id, user_id, distance, status FROM sport_record WHERE id = #{recordId};
-- 预期：id 主键索引命中，type=const
-- 需要修改：无（已使用主键查询）
```

### 2. LeaderboardContributionMapper.insertIgnore 插入
```sql
-- EXPLAIN INSERT IGNORE INTO leaderboard_contribution (record_id, user_id, distance, status) 
-- VALUES (#{recordId}, #{userId}, #{distance}, #{status});
-- 预期：record_id 主键索引命中，INSERT IGNORE 避免重复冲突错误
-- 需要修改：确保 record_id 有唯一索引（表结构应已定义）
```

### 3. LeaderboardContributionMapper.updateStatus 更新
```sql
-- EXPLAIN UPDATE leaderboard_contribution SET status = #{toStatus} 
-- WHERE record_id = #{recordId} AND status = #{fromStatus};
-- 预期：record_id 主键索引命中，同时过滤 status 提高幂等性
-- 需要修改：无（record_id 为主键，已最优）
```

### 4. LeaderboardContributionMapper.selectActiveSummaries 聚合
```sql
-- EXPLAIN SELECT user_id AS userId, SUM(distance) AS totalDistance 
-- FROM leaderboard_contribution WHERE status = #{status} GROUP BY user_id;
-- 预期：status 索引 + 文件排序或临时表
-- 需要修改：添加复合索引 idx_status_user_id(status, user_id) 优化 GROUP BY
```

### 5. LeaderboardContributionMapper.markSettled 批量更新
```sql
-- EXPLAIN UPDATE leaderboard_contribution SET settled_at = #{settledAt} 
-- WHERE status = #{status};
-- 预期：status 索引定位行
-- 需要修改：同上，idx_status_user_id 可加速此查询
```

### 索引建议汇总
```sql
-- 需在测试环境执行验证并添加：
-- ALTER TABLE leaderboard_contribution 
-- ADD INDEX idx_status_user_id (status, user_id);

-- 验证命令（MySQL）：
-- EXPLAIN SELECT user_id, SUM(distance) FROM leaderboard_contribution 
-- WHERE status = 1 GROUP BY user_id;
-- 预期 Extra 列显示"Using index for group by"
```

**执行计划：**
1. 在测试环境连接数据库
2. 对每个查询执行 EXPLAIN 分析
3. 根据执行计划添加缺失的索引
4. 验证索引效果（检查 type、key、rows 扫描数）
5. 监控慢查询日志确认优化效果

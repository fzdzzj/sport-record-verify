# TASK-009 回传

## 【回传】短包

**任务：** LeaderboardService 慢查询分析与索引优化

**分析结论：**
1. SportRecordMapper.selectById - 主键查询，无需优化
2. LeaderboardContributionMapper.insertIgnore/updateStatus - 主键操作，已最优
3. selectActiveSummaries/markSettled - 需添加复合索引 idx_status_user_id(status, user_id)

**待验证项（需在测试环境执行）：**
```sql
ALTER TABLE leaderboard_contribution 
ADD INDEX idx_status_user_id (status, user_id);
```

**EXPLAIN 步骤已补充至 spec.md，所有"需要修改"部分已注释并标注测试验证要求。**

**状态：** 分析完成，等待测试环境验证

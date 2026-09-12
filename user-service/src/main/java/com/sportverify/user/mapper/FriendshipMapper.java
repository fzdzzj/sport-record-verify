package com.sportverify.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sportverify.user.entity.Friendship;

/**
 * 好友关系 Mapper（user_db.friendship，规范化存储）。
 *
 * <p>主键 (user_low,user_high) 唯一 + CHECK 约束（user_low &lt; user_high）：
 * 逆序重复插入被数据库拒绝，作为并发互加的最终兜底（规范差异「好友关系规范化存储」）。
 * 重复插入捕获 DuplicateKeyException 后按幂等成功处理。</p>
 */
public interface FriendshipMapper extends BaseMapper<Friendship> {
}

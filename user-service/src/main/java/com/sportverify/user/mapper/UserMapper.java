package com.sportverify.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sportverify.user.entity.User;

/**
 * 用户 Mapper（user_db.user）。
 *
 * <p>好友域用途：目标用户存在性校验（selectById）、列表反规范化回表取昵称（selectBatchIds）。</p>
 */
public interface UserMapper extends BaseMapper<User> {
}

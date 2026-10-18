package com.sportverify.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sportverify.user.entity.ClubMember;

/**
 * 跑团成员 Mapper（user_db.club_member，TASK-275）。
 *
 * <p>跑团域用途：一人一团存在性校验（selectOne）、成员列表（selectList）与
 * 退团删除（deleteById）；唯一键 uk_user_club (user_id) 兜底并发加入。</p>
 */
public interface ClubMemberMapper extends BaseMapper<ClubMember> {
}

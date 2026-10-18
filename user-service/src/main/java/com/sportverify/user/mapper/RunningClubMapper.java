package com.sportverify.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sportverify.user.entity.RunningClub;

/**
 * 跑团 Mapper（user_db.running_club，TASK-275）。
 *
 * <p>跑团域用途：名称查重（selectCount）、广场分页（selectPage）、
 * 成员数原子累加/递减与解散（update + LambdaUpdateWrapper setSql）。</p>
 */
public interface RunningClubMapper extends BaseMapper<RunningClub> {
}

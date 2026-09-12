package com.sportverify.leaderboard.mapper;

import com.sportverify.leaderboard.entity.SportRecordSnapshot;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 运动记录只读 Mapper（record_db.sport_record，服务拆分后的跨域数据面）。
 *
 * <p><b>只读依赖</b>（服务数 4→5 拆分的边界声明，见 ADR-0005）：接口层刻意不继承
 * MyBatis-Plus BaseMapper、不提供任何写方法——榜单只查询记录状态/里程做入榜闸门，
 * 记录的写入（提交/状态迁移）全部留在 record-service，从结构上杜绝双写。
 * 两表均未分片（{@code !SINGLE} 单表），本服务以普通数据源直查即可，无需 ShardingSphere。</p>
 */
public interface SportRecordMapper {

    /**
     * 主键单行只读查询：返回榜单视角快照（id/user_id/distance/status，
     * 列名下划线经 map-underscore-to-camel-case 映射到快照属性）。
     */
    @Select("SELECT id, user_id, distance, status FROM sport_record WHERE id = #{recordId}")
    SportRecordSnapshot selectById(@Param("recordId") Long recordId);
}

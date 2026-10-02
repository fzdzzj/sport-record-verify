package com.sportverify.record.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sportverify.record.entity.TrackPointArchive;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 轨迹点归档 Mapper（逻辑表 track_point_archive，物理分片 track_point_archive_0..15）。
 *
 * <p>查询/写入走 MyBatis-Plus 内置方法：分片路由由 ShardingSphere 代理 DataSource
 * 在 SQL 层完成（WHERE 需携带 user_id 分片键）。</p>
 */
public interface TrackPointArchiveMapper extends BaseMapper<TrackPointArchive> {

    /**
     * 归档批量写入（照 TrackPointMapper.insertBatch 先例，仅表名换 track_point_archive）。
     *
     * <p>迁移写入用，id 为热表原值（调用方预置），批插不重算：
     * 多值 INSERT 不经过 MP 的 ASSIGN_ID 回填。</p>
     */
    @Insert("<script>"
            + "INSERT INTO track_point_archive (id, record_id, user_id, seq, lat, lng, ts, speed) VALUES "
            + "<foreach collection='list' item='p' separator=','>"
            + "(#{p.id}, #{p.recordId}, #{p.userId}, #{p.seq}, #{p.lat}, #{p.lng}, #{p.ts}, #{p.speed})"
            + "</foreach>"
            + "</script>")
    int insertBatch(@Param("list") List<TrackPointArchive> points);
}

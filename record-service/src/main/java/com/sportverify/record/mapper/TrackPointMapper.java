package com.sportverify.record.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sportverify.record.entity.TrackPoint;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 轨迹点 Mapper（逻辑表 track_point，物理分片 track_point_0..15）。
 *
 * <p>查询/写入走 MyBatis-Plus 内置方法即可：分片路由由 ShardingSphere 代理
 * DataSource 在 SQL 层完成（WHERE 需携带 user_id 分片键）。</p>
 */
public interface TrackPointMapper extends BaseMapper<TrackPoint> {

    /**
     * 轨迹批量写入（压测变更「连接池调优案例」优化侧）。
     *
     * <p>单条多值 INSERT 替代逐条 INSERT：同一记录的分片键相同（user_id 一致），
     * ShardingSphere 路由到单一物理表，一条语句完成 N 点写入——
     * 连接占用时间从 N×RTT 降到 1×RTT，高并发下连接池不再被打满（基线逐条路径
     * 保留在 {@code SportRecordService.submit}，经
     * {@code record.track.batch-insert-enabled} 开关切换，供优化前后对比复现）。</p>
     *
     * <p>注意：id 必须由调用方预生成（MP 雪花 {@code IdWorker.getId()}），
     * 多值 INSERT 不经过 MP 的 ASSIGN_ID 回填。</p>
     */
    @Insert("<script>"
            + "INSERT INTO track_point (id, record_id, user_id, seq, lat, lng, ts, speed) VALUES "
            + "<foreach collection='list' item='p' separator=','>"
            + "(#{p.id}, #{p.recordId}, #{p.userId}, #{p.seq}, #{p.lat}, #{p.lng}, #{p.ts}, #{p.speed})"
            + "</foreach>"
            + "</script>")
    int insertBatch(@Param("list") List<TrackPoint> points);
}

package com.sportverify.record.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 轨迹点实体（逻辑表 track_point，规范「轨迹分片存储」）。
 *
 * <p>ShardingSphere 按 {@code user_id % 16} 路由到物理表 track_point_0..15
 * （见 sharding.yaml 与 sql/04-track-point-shards.sql）；userId 为冗余分片键，
 * 查询/写入必须携带，否则路由失败或广播全分片。</p>
 */
@Data
@TableName("track_point")
public class TrackPoint {

    /** 轨迹点ID（物理分片表非自增，由 MyBatis-Plus 雪花算法生成） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属记录 */
    private Long recordId;

    /** 分片键（路由必须，冗余自 sport_record.user_id） */
    private Long userId;

    /** 点序号 */
    private Integer seq;

    /** 纬度 */
    private BigDecimal lat;

    /** 经度 */
    private BigDecimal lng;

    /** 时间戳（毫秒） */
    private Long ts;

    /** 客户端上报瞬时速度（m/s，仅存证；判定时以 haversine/Δt 重算为准） */
    private BigDecimal speed;
}

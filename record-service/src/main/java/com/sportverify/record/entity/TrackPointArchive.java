package com.sportverify.record.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 轨迹点归档实体（逻辑表 track_point_archive，课题 3 冷热分离）。
 *
 * <p>归档分片表（课题 3 冷热分离），迁移保留原雪花 id，不重新生成；
 * 分片键与算法与热表一致（{@code user_id % 16}，见 sharding.yaml 与
 * sql/05-track-point-archive-shards.sql），userId 为冗余分片键，
 * 查询/写入必须携带。</p>
 */
@Data
@TableName("track_point_archive")
public class TrackPointArchive {

    /** 轨迹点ID（迁移保留热表原值，批插不重算） */
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

    /** 客户端上报瞬时速度（m/s，仅存证） */
    private BigDecimal speed;
}

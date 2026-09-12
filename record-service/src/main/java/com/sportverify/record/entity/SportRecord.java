package com.sportverify.record.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 运动记录主表实体（record_db.sport_record）。
 *
 * <p>不分片（规范「记录本身不分片」）：ShardingSphere 中未声明分片规则的表
 * 经 {@code !SINGLE} 声明后落到默认数据源单表；轨迹查询先查本表得到 user_id 再路由分片。</p>
 */
@Data
@TableName("sport_record")
public class SportRecord {

    /** 记录ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 客户端幂等键（uk_request_id 唯一） */
    private String requestId;

    /** 所属用户（轨迹分片路由键来源） */
    private Long userId;

    /** 运动类型：1 RUNNING，2 CYCLING ... */
    private Integer sportType;

    /** 开始时间 */
    private LocalDateTime startTime;

    /** 结束时间 */
    private LocalDateTime endTime;

    /** 距离（公里） */
    private BigDecimal distance;

    /** 时长（秒） */
    private Integer duration;

    /** 审核状态（RecordStatus.code，状态机见审批版 §5.1） */
    private Integer status;

    /** 乐观锁版本号（状态迁移 WHERE version 条件） */
    private Integer version;

    /** 创建时间 */
    private LocalDateTime createdAt;
}

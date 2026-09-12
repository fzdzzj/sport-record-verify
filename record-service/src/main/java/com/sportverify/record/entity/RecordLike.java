package com.sportverify.record.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 记录点赞实体（record_db.record_like，审批版 §6.2）。
 *
 * <p><b>联合主键 (record_id, user_id)</b>：从数据库层面杜绝同一用户重复赞
 * （异步落库 INSERT IGNORE 命中主键冲突即跳过，幂等）。本实体无单列主键，
 * 因此不声明 {@code @TableId}——只走 insert / 自定义 SQL，不用 selectById 系方法。</p>
 *
 * <p>写入路径：点赞/取消先写 Redis（计数 + 成员集 + pending 队列），
 * 由 {@code @Scheduled} flush 任务批量落本表；本表行是计数的<b>权威源</b>，
 * 对账任务以它为准纠偏 Redis 计数。</p>
 */
@Data
@TableName("record_like")
public class RecordLike {

    /** 被赞记录 */
    private Long recordId;

    /** 点赞用户 */
    private Long userId;

    /** 点赞时间 */
    private LocalDateTime createdAt;
}

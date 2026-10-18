package com.sportverify.record.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户勋章与成就实体（record_db.user_badge，TASK-269）。
 *
 * <p>唯一键 uk_user_badge (user_id, badge_code)：每用户每勋章至多一行，
 * 读时结算并发解锁以 {@code DuplicateKeyException} 捕获兜底；佩戴态
 * {@code is_equipped} 同一用户至多一行为 1（由服务层单事务先清后置保证）。</p>
 */
@Data
@TableName("user_badge")
public class UserBadge {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属用户ID */
    private Long userId;

    /** 勋章编码（{@code BadgeType} 枚举名） */
    private String badgeCode;

    /** 是否佩戴：0 否，1 是（同一用户至多一行=1） */
    private Integer isEquipped;

    /** 解锁时间 */
    private LocalDateTime unlockedAt;

    /** 创建时间（缺省由列 DEFAULT CURRENT_TIMESTAMP 兜底） */
    private LocalDateTime createdAt;
}

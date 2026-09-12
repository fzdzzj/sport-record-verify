package com.sportverify.user.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 好友关系实体（user_db.friendship，规范化存储）。
 *
 * <p>强制 {@code user_low < user_high}：主键 (user_low,user_high) + CHECK 约束
 * 从根上消除 A-B / B-A 重复行（规范差异「好友关系规范化存储」，面试弹药 G）。
 * 双向语义：关系一旦 ACCEPTED，双方互见，列表查询按「本人是 low 端还是 high 端」还原对方。</p>
 */
@Data
@TableName("friendship")
public class Friendship {

    /** 较小用户ID（恒小于 userHigh，无主键自增） */
    private Long userLow;

    /** 较大用户ID */
    private Long userHigh;

    /** 关系建立时间 */
    private LocalDateTime createdAt;
}

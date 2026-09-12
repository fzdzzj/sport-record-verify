package com.sportverify.api.user.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 好友列表项 DTO（user-api 契约）。
 *
 * <p>由 friendship 的 {@code (user_low,user_high)} 反规范化得到：对查询用户而言，
 * 对方即「非本人那一端」，再回表 user 取昵称。供 record-service 后续好友榜过滤使用。</p>
 */
@Data
public class FriendDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 好友用户ID（反规范化后） */
    private Long userId;

    /** 好友昵称 */
    private String nickname;

    /** 关系建立时间 */
    private LocalDateTime createdAt;
}

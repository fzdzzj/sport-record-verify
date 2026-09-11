package com.sportverify.api.user.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户 DTO（user-api 契约）。
 *
 * <p>对应 user_db.user，供其他服务获取用户概要信息（不包含密码哈希）。</p>
 */
@Data
public class UserDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户ID */
    private Long id;

    /** 手机号（脱敏后下发） */
    private String phone;

    /** 昵称 */
    private String nickname;

    /** 状态：0 禁用，1 正常 */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createdAt;
}

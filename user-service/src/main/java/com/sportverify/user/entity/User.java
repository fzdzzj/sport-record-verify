package com.sportverify.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体（user_db.user）。
 *
 * <p>好友域仅使用概要字段（id/nickname）做目标用户存在性校验与列表反规范化；
 * password_hash 一并映射以保持实体与表结构一致（不对外下发）。</p>
 */
@Data
@TableName("`user`")
public class User {

    /** 用户ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 手机号（登录账号，唯一） */
    private String phone;

    /** BCrypt 密码哈希（仅内部使用，不对外下发） */
    private String passwordHash;

    /** 昵称 */
    private String nickname;

    /** 状态：0 禁用，1 正常 */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createdAt;
}

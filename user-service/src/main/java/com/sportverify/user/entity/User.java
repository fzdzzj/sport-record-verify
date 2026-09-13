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

    /** 角色：普通用户（默认，最小权限，add-admin-rbac 见 ADR-0007） */
    public static final String ROLE_USER = "USER";
    /** 角色：管理员（治理面接口准入，仅经内部授予接口取得） */
    public static final String ROLE_ADMIN = "ADMIN";

    /** 用户ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 手机号（登录账号，唯一） */
    private String phone;

    /** BCrypt 密码哈希（仅内部使用，不对外下发） */
    private String passwordHash;

    /** 昵称 */
    private String nickname;

    /**
     * 角色（最小模型，二态）：USER 普通用户 / ADMIN 管理员（add-admin-rbac，见 ADR-0007）。
     *
     * <p>默认 USER，最小权限：新用户一律非管理员，ADMIN 仅经内部接口显式授予
     * （治理面 /admin/** 与规则版本接口要求 ADMIN，防普通用户翻案/改规则）。</p>
     */
    private String role = ROLE_USER;

    /** 状态：0 禁用，1 正常 */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createdAt;
}

package com.sportverify.api.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 创建跑团请求 DTO（user-api 契约，TASK-275）。
 *
 * <p>服务层校验 caller 未加入任何跑团（一人一团）且名称未被占用后落库，
 * creator_id 取网关注入身份，调用方不携带。</p>
 */
@Data
public class ClubCreateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 跑团名称（唯一，≤64 字符） */
    @NotBlank(message = "跑团名称不能为空")
    private String name;

    /** 跑团简介（≤255 字符） */
    private String description;

    /** 团徽 URL（≤255 字符） */
    private String badgeUrl;
}

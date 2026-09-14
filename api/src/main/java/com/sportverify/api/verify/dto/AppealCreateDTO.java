package com.sportverify.api.verify.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 创建申诉单 DTO（verify-api 契约）。
 *
 * <p>record-service 在 REJECTED → APPEALING 迁移前经 verify-api 调用；
 * record_id 唯一，重复创建幂等返回既有申诉单。userId 由 record 侧服务端权威填充
 * （不信任调用方入参），故不做必填约束。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AppealCreateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 申诉的记录（必填） */
    @NotNull(message = "recordId 不能为空")
    private Long recordId;

    /** 申诉用户（服务端权威填充，非入参） */
    private Long userId;

    /** 申诉理由（必填，上限 500） */
    @NotBlank(message = "申诉理由不能为空")
    @Size(max = 500, message = "申诉理由长度不能超过 500")
    private String reason;
}

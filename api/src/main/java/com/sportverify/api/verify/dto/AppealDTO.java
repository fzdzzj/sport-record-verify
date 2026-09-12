package com.sportverify.api.verify.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 申诉单 DTO（verify-api 契约）。
 *
 * <p>对应 verify_db.appeal；record_id 唯一（uk_record），
 * 同一记录最多一张申诉单（规范「申诉提交」）。</p>
 */
@Data
public class AppealDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 申诉ID */
    private Long id;

    /** 申诉的记录 */
    private Long recordId;

    /** 申诉用户 */
    private Long userId;

    /** 申诉理由 */
    private String reason;

    /** 状态：0 PENDING，1 RE_PASSED，2 RE_CONFIRMED（AppealStatus.code） */
    private Integer status;

    /** 复核操作人 */
    private String operator;

    /** 复核结论（详细） */
    private String recheckResult;

    /** 创建时间 */
    private LocalDateTime createdAt;
}

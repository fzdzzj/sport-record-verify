package com.sportverify.api.verify.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 创建申诉单 DTO（verify-api 契约）。
 *
 * <p>record-service 在 REJECTED → APPEALING 迁移前经 verify-api 调用；
 * record_id 唯一，重复创建幂等返回既有申诉单。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AppealCreateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 申诉的记录 */
    private Long recordId;

    /** 申诉用户 */
    private Long userId;

    /** 申诉理由 */
    private String reason;
}

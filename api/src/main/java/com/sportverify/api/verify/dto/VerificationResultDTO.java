package com.sportverify.api.verify.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 校验结果 DTO（verify-api 契约）。
 *
 * <p>对应 verify_db.verification_result，供消费方（record-service）读取
 * 判定结论与命中规则证据明细。</p>
 */
@Data
public class VerificationResultDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 记录ID */
    private Long recordId;

    /** 判定：0 VERIFYING，1 PASSED，2 REJECTED */
    private Integer verdict;

    /** 综合得分（0-100） */
    private Integer score;

    /** 命中规则证据明细（JSON 字符串） */
    private String ruleHits;

    /** 判定时间 */
    private LocalDateTime checkedAt;
}

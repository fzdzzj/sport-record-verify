package com.sportverify.api.record.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 记录提交结果 DTO（record-api 契约，规范「轨迹提交幂等」）。
 *
 * <p>重复提交时随 3004 一并返回原结果（记录ID + 当前状态），
 * 让客户端可据此对齐本地状态，无需重新查询。</p>
 */
@Data
public class RecordSubmitResultDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 记录ID */
    private Long recordId;

    /** 幂等键 */
    private String requestId;

    /** 当前审核状态（RecordStatus.code） */
    private Integer status;

    /** 是否为重复提交（true → 接口返回 3004） */
    private boolean duplicated;

    /** 提示信息 */
    private String message;

    public static RecordSubmitResultDTO of(Long recordId, String requestId, Integer status,
                                           boolean duplicated, String message) {
        RecordSubmitResultDTO dto = new RecordSubmitResultDTO();
        dto.setRecordId(recordId);
        dto.setRequestId(requestId);
        dto.setStatus(status);
        dto.setDuplicated(duplicated);
        dto.setMessage(message);
        return dto;
    }
}

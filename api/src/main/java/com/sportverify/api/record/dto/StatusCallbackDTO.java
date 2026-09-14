package com.sportverify.api.record.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 状态回调 DTO（record-api 契约，规范「校验状态机」）。
 *
 * <p>verify-service 判定/终判后回调 record-service 驱动状态迁移，
 * 携带 fromStatus + version 构成乐观锁条件（冲突返回 3003）。全字段必填。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StatusCallbackDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 记录ID（必填） */
    @NotNull(message = "recordId 不能为空")
    private Long recordId;

    /** 期望的当前状态（乐观锁条件一，必填） */
    @NotNull(message = "fromStatus 不能为空")
    private Integer fromStatus;

    /** 目标状态（PASSED/REJECTED/RE_PASSED/RE_CONFIRMED，必填） */
    @NotNull(message = "toStatus 不能为空")
    private Integer toStatus;

    /** 期望的版本号（乐观锁条件二，必填） */
    @NotNull(message = "version 不能为空")
    private Integer version;
}

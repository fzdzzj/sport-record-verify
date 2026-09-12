package com.sportverify.api.record.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 状态回调 DTO（record-api 契约，规范「校验状态机」）。
 *
 * <p>verify-service 判定/终判后回调 record-service 驱动状态迁移，
 * 携带 fromStatus + version 构成乐观锁条件（冲突返回 3003）。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StatusCallbackDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 记录ID */
    private Long recordId;

    /** 期望的当前状态（乐观锁条件一） */
    private Integer fromStatus;

    /** 目标状态（PASSED/REJECTED/RE_PASSED/RE_CONFIRMED） */
    private Integer toStatus;

    /** 期望的版本号（乐观锁条件二） */
    private Integer version;
}

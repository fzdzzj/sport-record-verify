package com.sportverify.api.verify.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 管理员终判 DTO（规范「终判改判/终判维持拒绝」）。
 *
 * <p>{@code pass=true} → appeal.RE_PASSED + record.RE_PASSED（发 VERIFIED 事件）；
 * {@code pass=false} → appeal.RE_CONFIRMED + record.RE_CONFIRMED（发 REJECTED 事件）。</p>
 *
 * <p>{@code pass} 为包装类型 {@link Boolean} 且 {@code @NotNull}（E22 TASK-256）：
 * 漏传不再静默默认 false 走「维持拒绝」终判（终判回写 + outbox 推送不可逆且无参数错误提示），
 * 而是经 {@code @Valid} 通道返回 400。</p>
 */
@Data
public class AppealReviewDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 复核操作人（必填，上限 64） */
    @NotBlank(message = "复核操作人不能为空")
    @Size(max = 64, message = "复核操作人长度不能超过 64")
    private String operator;

    /** 是否改判通过（必填；漏传 400 而非静默走维持拒绝） */
    @NotNull(message = "终判结论 pass 不能为空")
    private Boolean pass;

    /** 复核结论（详细，可选，上限 500） */
    @Size(max = 500, message = "复核结论长度不能超过 500")
    private String recheckResult;
}

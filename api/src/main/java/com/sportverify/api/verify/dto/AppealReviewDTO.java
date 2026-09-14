package com.sportverify.api.verify.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 管理员终判 DTO（规范「终判改判/终判维持拒绝」）。
 *
 * <p>{@code pass=true} → appeal.RE_PASSED + record.RE_PASSED（发 VERIFIED 事件）；
 * {@code pass=false} → appeal.RE_CONFIRMED + record.RE_CONFIRMED（发 REJECTED 事件）。</p>
 */
@Data
public class AppealReviewDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 复核操作人（必填，上限 64） */
    @NotBlank(message = "复核操作人不能为空")
    @Size(max = 64, message = "复核操作人长度不能超过 64")
    private String operator;

    /** 是否改判通过 */
    private boolean pass;

    /** 复核结论（详细，可选，上限 500） */
    @Size(max = 500, message = "复核结论长度不能超过 500")
    private String recheckResult;
}

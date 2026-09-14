package com.sportverify.verify.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 调整灰度比例请求（管理端 PATCH /verify/rules/versions/{id}/gray，规范「秒级回滚」）。
 */
@Data
public class RuleVersionGrayRequest {

    /** 目标灰度比例 0-100（必填）；0 = 秒级回滚（新版本不再被采样，基线不受影响） */
    @NotNull(message = "灰度比例不能为空")
    @Min(value = 0, message = "灰度比例须在 0-100")
    @Max(value = 100, message = "灰度比例须在 0-100")
    private Integer grayRatio;
}

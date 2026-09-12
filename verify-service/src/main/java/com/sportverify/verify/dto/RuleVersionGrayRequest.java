package com.sportverify.verify.dto;

import lombok.Data;

/**
 * 调整灰度比例请求（管理端 PATCH /verify/rules/versions/{id}/gray，规范「秒级回滚」）。
 */
@Data
public class RuleVersionGrayRequest {

    /** 目标灰度比例 0-100；0 = 秒级回滚（新版本不再被采样，基线不受影响） */
    private Integer grayRatio;
}

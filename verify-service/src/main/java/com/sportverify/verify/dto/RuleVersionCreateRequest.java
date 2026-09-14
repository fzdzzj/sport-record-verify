package com.sportverify.verify.dto;

import com.sportverify.verify.config.VerifyProperties;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建规则版本请求（管理端 POST /verify/rules/versions，规范「创建灰度版本」）。
 */
@Data
public class RuleVersionCreateRequest {

    /** 版本号（如 v20260912，uk_version 唯一）；不填自动生成 vyyyyMMddHHmmss，上限 64 */
    @Size(max = 64, message = "版本号长度不能超过 64")
    private String version;

    /** 初始灰度比例 0-100（缺省 0 = 只建版本不采样，创建后再调灰度） */
    @Min(value = 0, message = "灰度比例须在 0-100")
    @Max(value = 100, message = "灰度比例须在 0-100")
    private Integer grayRatio;

    /**
     * 规则+阈值快照（结构与 verify.rules.* 一致）；不填则快照当前基线
     * （ACTIVE 版本快照 / Nacos 实时值）。灰度试验新阈值时应显式携带，
     * 否则灰度与基线同参、采样无行为差异。
     */
    private VerifyProperties rules;
}

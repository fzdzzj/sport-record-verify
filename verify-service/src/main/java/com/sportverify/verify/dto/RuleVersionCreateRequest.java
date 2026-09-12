package com.sportverify.verify.dto;

import com.sportverify.verify.config.VerifyProperties;
import lombok.Data;

/**
 * 创建规则版本请求（管理端 POST /verify/rules/versions，规范「创建灰度版本」）。
 */
@Data
public class RuleVersionCreateRequest {

    /** 版本号（如 v20260912，uk_version 唯一）；不填自动生成 vyyyyMMddHHmmss */
    private String version;

    /** 初始灰度比例 0-100（缺省 0 = 只建版本不采样，创建后再调灰度） */
    private Integer grayRatio;

    /**
     * 规则+阈值快照（结构与 verify.rules.* 一致）；不填则快照当前基线
     * （ACTIVE 版本快照 / Nacos 实时值）。灰度试验新阈值时应显式携带，
     * 否则灰度与基线同参、采样无行为差异。
     */
    private VerifyProperties rules;
}

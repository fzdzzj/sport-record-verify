package com.sportverify.verify.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 规则版本实体（verify_db.rule_version，规范「规则版本化」，审批版 §7.4）。
 *
 * <p>规则灰度发布的核心载体：创建版本时把规则+阈值序列化为 rules_json 快照落库，
 * 灰度期间读快照执行而不读 Nacos 实时配置——消除「灰度观察期配置又被改」的竞态
 * （快照一旦落库不再变更，回滚/调比例只动 gray_ratio 与 status）。</p>
 */
@Data
@TableName("rule_version")
public class RuleVersion {

    /** 版本ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 版本号（唯一 uk_version，如 v20260912） */
    private String version;

    /** 状态：0 GRAY，1 ACTIVE，2 RETIRED（RuleVersionStatus.code） */
    private Integer status;

    /**
     * 灰度比例 0-100：userId%100 &lt; gray_ratio 的用户采样到本版本快照；
     * 0 = 不采样（回滚），100 = 全量。同一时刻至多一个 GRAY 版本在采样（服务层约束）。
     */
    private Integer grayRatio;

    /** 规则+阈值快照 JSON（RulesSnapshotCodec 与 VerifyProperties 互转，结构与 verify.rules.* 一致） */
    private String rulesJson;

    /** 创建时间 */
    private LocalDateTime createdAt;
}

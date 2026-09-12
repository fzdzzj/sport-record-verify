package com.sportverify.verify.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 校验结果实体（verify_db.verification_result，规范「判定聚合」）。
 *
 * <p>主键即 record_id（一条记录一个判定）：既是「校验幂等不重算」的落点，
 * 也是事件重复消费的 DB 级兜底（重复 upsert 只覆盖不新增）。</p>
 */
@Data
@TableName("verification_result")
public class VerificationResult {

    /** 记录ID（主键 = 幂等锚点） */
    @TableId(value = "record_id", type = IdType.INPUT)
    private Long recordId;

    /** 判定：0 VERIFYING，1 PASSED，2 REJECTED（Verdict.code） */
    private Integer verdict;

    /** 综合得分（0-100）：50 + 20×HARD + 10×SOFT */
    private Integer score;

    /** 证据 JSON（verdict/score/hits[rule+level+detail]/preprocess，规范「证据 JSON 完整」） */
    private String ruleHits;

    /** 判定时间 */
    private LocalDateTime checkedAt;
}

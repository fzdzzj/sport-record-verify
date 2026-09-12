package com.sportverify.verify.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sportverify.verify.entity.VerificationResult;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * 校验结果 Mapper（verify_db.verification_result）。
 */
public interface VerificationResultMapper extends BaseMapper<VerificationResult> {

    /**
     * 初始化校验中占位（INSERT IGNORE：record_id 主键幂等，重复触发不报错）。
     * 对应状态机 SUBMITTED→VERIFYING 副作用「写 verification_result(VERIFYING)」。
     */
    @Insert("INSERT IGNORE INTO verification_result (record_id, verdict, checked_at) " +
            "VALUES (#{recordId}, 0, NOW())")
    int initVerifying(@Param("recordId") Long recordId);

    /**
     * 判定结果幂等 upsert（主键冲突覆盖，不产生第二行——「校验按 recordId 不重算」落点）。
     */
    @Insert("INSERT INTO verification_result (record_id, verdict, score, rule_hits, checked_at) " +
            "VALUES (#{recordId}, #{verdict}, #{score}, #{ruleHits}, #{checkedAt}) " +
            "ON DUPLICATE KEY UPDATE verdict = VALUES(verdict), score = VALUES(score), " +
            "rule_hits = VALUES(rule_hits), checked_at = VALUES(checked_at)")
    int upsert(VerificationResult result);
}

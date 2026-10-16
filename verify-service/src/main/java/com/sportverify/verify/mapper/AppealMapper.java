package com.sportverify.verify.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sportverify.verify.entity.Appeal;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 申诉单 Mapper（verify_db.appeal）。
 */
public interface AppealMapper extends BaseMapper<Appeal> {

    /**
     * 管理员终判（乐观锁：仅 PENDING 可流转，影响 0 行 → 调用方报 3006）。
     */
    @Update("UPDATE appeal SET status = #{toStatus}, operator = #{operator}, " +
            "recheck_result = #{recheckResult} " +
            "WHERE id = #{id} AND status = #{fromStatus}")
    int updateStatus(@Param("id") Long id,
                     @Param("fromStatus") Integer fromStatus,
                     @Param("toStatus") Integer toStatus,
                     @Param("operator") String operator,
                     @Param("recheckResult") String recheckResult);

    /**
     * 终判申诉批扫（TASK-253 R2/E6 兜底对账数据源）：取终判（RE_PASSED=1 / RE_CONFIRMED=2）
     * 行按 id 倒序一批，走既有 {@code idx_status_id (status, id)} 索引。
     *
     * <p>终判为低频人工流，全量终判行倒序批扫即可；appeal 表无终判时间字段故不用时间窗口，
     * 兜底对账逐行核对 record 是否仍卡 APPEALING，已迁移者天然跳过（幂等闭环）。</p>
     *
     * @param limit 单批上限
     * @return 终判申诉单（id 倒序，至多 limit 条）
     */
    @Select("SELECT * FROM appeal WHERE status IN (1, 2) ORDER BY id DESC LIMIT #{limit}")
    List<Appeal> selectFinalizedBatch(@Param("limit") int limit);
}

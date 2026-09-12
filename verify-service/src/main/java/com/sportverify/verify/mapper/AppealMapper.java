package com.sportverify.verify.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sportverify.verify.entity.Appeal;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

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
}

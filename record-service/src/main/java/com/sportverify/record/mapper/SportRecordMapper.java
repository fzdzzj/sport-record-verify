package com.sportverify.record.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sportverify.record.entity.SportRecord;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 运动记录 Mapper（record_db.sport_record）。
 */
public interface SportRecordMapper extends BaseMapper<SportRecord> {

    /**
     * 乐观锁状态迁移（审批版 §5.1 并发控制唯一依据，规范「校验状态机」）：
     * {@code UPDATE sport_record SET status=?, version=version+1 WHERE id=? AND status=? AND version=?}
     *
     * @return 影响行数；0 表示并发冲突（状态或版本已变更），调用方报 3003 或重试
     */
    @Update("UPDATE sport_record SET status = #{toStatus}, version = version + 1 " +
            "WHERE id = #{id} AND status = #{fromStatus} AND version = #{version}")
    int updateStatus(@Param("id") Long id,
                     @Param("fromStatus") Integer fromStatus,
                     @Param("toStatus") Integer toStatus,
                     @Param("version") Integer version);

    /**
     * 按幂等键查询（uk_request_id；重复提交时返回原记录）。
     */
    @Select("SELECT * FROM sport_record WHERE request_id = #{requestId}")
    SportRecord selectByRequestId(@Param("requestId") String requestId);
}

package com.sportverify.record.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sportverify.record.dto.RecordAggDTO;
import com.sportverify.record.entity.SportRecord;
import java.util.List;
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


    /**
     * 个人全量指标单行聚合（TASK-232 统计下推）。
     *
     * <p>把 {@code MyStatsDTO} 的全量口径（总数 / 总里程 / 通过数）从「拉全量行到 JVM 汇总」
     * 下推到数据库单行返回：{@code COUNT(*)}、{@code COALESCE(SUM(distance), 0)}、
     * {@code COUNT(CASE WHEN status IN (通过终态) THEN 1 END)}。命中 {@code idx_user_time}
     * 前缀列 {@code user_id}，不做 select *、不拉回 record 行。</p>
     *
     * <p>通过终态集合由调用方以 {@link com.sportverify.api.record.RecordStatus} 枚举值传入
     * （PASSED/RE_PASSED），避免在 SQL 里硬编码状态码而与枚举漂移。</p>
     *
     * @param userId     归属用户（归属即身份，唯一过滤条件）
     * @param passStatus 计入「通过」的状态码集合（非空，2=PASSED，5=RE_PASSED）
     * @return 单行聚合结果（无记录时 totalCount=0、totalDistance=0、passCount=0，恒非 null）
     */
    @Select("<script>"
            + "SELECT COUNT(*) AS total_count,"
            + " COALESCE(SUM(distance), 0) AS total_distance,"
            + " COUNT(CASE WHEN status IN "
            + "<foreach item='st' collection='passStatus' open='(' separator=',' close=')'>#{st}</foreach>"
            + " THEN 1 END) AS pass_count"
            + " FROM sport_record WHERE user_id = #{userId}"
            + "</script>")
    RecordAggDTO selectAggByUserId(@Param("userId") Long userId,
                                   @Param("passStatus") List<Integer> passStatus);
}

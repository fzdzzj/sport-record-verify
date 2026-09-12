package com.sportverify.record.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sportverify.record.entity.RecordLike;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 记录点赞 Mapper（record_db.record_like，联合主键 (record_id,user_id)）。
 *
 * <p>点赞行的<b>唯一写入方</b>是 flush 定时任务（批量 INSERT IGNORE / DELETE）；
 * 业务侧只经 Redis 计数，不直接写本表。对账/兜底读取走 COUNT(*) 与 DISTINCT 扫描。</p>
 */
public interface RecordLikeMapper extends BaseMapper<RecordLike> {

    /**
     * 单条幂等插入（INSERT IGNORE）：联合主键冲突时静默跳过 → 不产生重复行
     * （规范差异「联合主键防重」，flush 逐条兜底用）。
     */
    @Insert("INSERT IGNORE INTO record_like (record_id, user_id, created_at) " +
            "VALUES (#{recordId}, #{userId}, #{createdAt})")
    int insertIgnore(@Param("recordId") Long recordId,
                     @Param("userId") Long userId,
                     @Param("createdAt") java.time.LocalDateTime createdAt);

    /**
     * 批量幂等插入（flush 主路径）：一条 SQL 写多行，主键冲突行自动跳过。
     */
    @Insert("<script>" +
            "INSERT IGNORE INTO record_like (record_id, user_id, created_at) VALUES " +
            "<foreach collection='list' item='l' separator=','>" +
            "(#{l.recordId}, #{l.userId}, #{l.createdAt})" +
            "</foreach>" +
            "</script>")
    int batchInsertIgnore(@Param("list") List<RecordLike> list);

    /**
     * 批量删除（flush 主路径）：按联合主键 (record_id,user_id) 行构造 IN 匹配，
     * 不存在的行天然无影响（取消幂等）。
     */
    @Delete("<script>" +
            "DELETE FROM record_like WHERE (record_id, user_id) IN " +
            "<foreach collection='list' item='l' open='(' separator=',' close=')'>" +
            "(#{l.recordId}, #{l.userId})" +
            "</foreach>" +
            "</script>")
    int batchDelete(@Param("list") List<RecordLike> list);

    /**
     * 计数兜底/对账：某记录的点赞行数（Redis 计数键缺失时回填的权威值）。
     */
    @Select("SELECT COUNT(*) FROM record_like WHERE record_id = #{recordId}")
    Long countByRecordId(@Param("recordId") Long recordId);

    /**
     * 对账：某记录的点赞用户集合（用于重建 Redis 成员集，恢复幂等防重）。
     */
    @Select("SELECT user_id FROM record_like WHERE record_id = #{recordId}")
    List<Long> selectUserIdsByRecordId(@Param("recordId") Long recordId);

    /**
     * 对账：全表去重记录 ID 扫描（演示规模可直接全扫；生产可改增量游标/位图）。
     */
    @Select("SELECT DISTINCT record_id FROM record_like")
    List<Long> selectDistinctRecordIds();
}

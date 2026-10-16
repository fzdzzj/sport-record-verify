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
 * 业务侧只经 Redis 计数，不直接写本表。对账读取走
 * {@link #selectDistinctRecordIdsAfter(Long, int)} + {@link #selectPairsByRecordIds(List)}
 * 键集分页（{@link #selectRecordLikePairs()} 保留供 IT 直读），兜底读取走 COUNT(*)。</p>
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
     *
     * <p>对账已改走键集分页游标（{@link #selectDistinctRecordIdsAfter(Long, int)} +
     * {@link #selectPairsByRecordIds(List)}，见类头注释现行口径）；本方法保留，
     * 对账主路径对其调用须保持 0 次（判别式测试约束）。</p>
     */
    @Select("SELECT user_id FROM record_like WHERE record_id = #{recordId}")
    List<Long> selectUserIdsByRecordId(@Param("recordId") Long recordId);

    /**
     * 对账：全表去重记录 ID 扫描（演示规模可直接全扫；生产可改增量游标/位图）。
     *
     * <p>对账已改走键集分页游标（{@link #selectDistinctRecordIdsAfter(Long, int)} +
     * {@link #selectPairsByRecordIds(List)}，见类头注释现行口径）；本方法保留，
     * 对账主路径对其调用须保持 0 次（判别式测试约束）。</p>
     */
    @Select("SELECT DISTINCT record_id FROM record_like")
    List<Long> selectDistinctRecordIds();

    /**
     * 对账键集分页（第一段）：取严格大于 {@code lastRecordId} 的去重记录 id，升序返回至多
     * {@code limit} 个。PK {@code (record_id, user_id)} 前缀索引覆盖，游标严格递增无跨页劈裂
     * （配合 {@link #selectPairsByRecordIds(List)} 按批取完整 pairs）。
     *
     * @param lastRecordId 上一批最大 record_id（首批传 0）
     * @param limit 单批上限
     */
    @Select("SELECT DISTINCT record_id FROM record_like WHERE record_id > #{lastRecordId} "
            + "ORDER BY record_id LIMIT #{limit}")
    List<Long> selectDistinctRecordIdsAfter(@Param("lastRecordId") Long lastRecordId,
                                            @Param("limit") int limit);

    /**
     * 对账键集分页（第二段）：取指定记录 id 集合的完整 (record_id, user_id) 对，内存按
     * record_id 分组后重建 Redis 计数与成员集。IN 构造沿仓内既有
     * {@code <script>}/{@code foreach} 注解模式。
     *
     * @param recordIds 本批去重记录 id（非空）
     */
    @Select("<script>" +
            "SELECT record_id, user_id FROM record_like WHERE record_id IN " +
            "<foreach collection='list' item='id' open='(' separator=',' close=')'>" +
            "#{id}" +
            "</foreach>" +
            "</script>")
    List<RecordLike> selectPairsByRecordIds(@Param("list") List<Long> recordIds);

    /**
     * 对账：一次取全表 (record_id, user_id) 对，内存按 record_id 分组后写 Redis
     * （替代 DISTINCT record_id + 逐 record 查成员的 N+1 扫描；
     * 演示规模可直接全扫，生产可改增量游标/位图）。
     *
     * <p>对账已改走 {@link #selectDistinctRecordIdsAfter(Long, int)} +
     * {@link #selectPairsByRecordIds(List)} 键集分页；本方法保留供 IT 断言直读。</p>
     */
    @Select("SELECT record_id, user_id FROM record_like")
    List<RecordLike> selectRecordLikePairs();

    /**
     * 删除记录关联的所有点赞行（记录删除级联清理，TASK-223）。
     *
     * @param recordId 记录 id
     * @return 影响行数
     */
    @Delete("DELETE FROM record_like WHERE record_id = #{recordId}")
    int deleteByRecordId(@Param("recordId") Long recordId);
}

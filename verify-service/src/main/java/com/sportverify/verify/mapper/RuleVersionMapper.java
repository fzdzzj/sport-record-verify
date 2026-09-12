package com.sportverify.verify.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sportverify.verify.entity.RuleVersion;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 规则版本 Mapper（verify_db.rule_version）。
 *
 * <p>除 BaseMapper 通用 CRUD 外，提供两类专用语句：</p>
 * <ul>
 *   <li>路由热路径查询（采样中灰度版本 / 全量 ACTIVE 版本）：固定条件写死在 SQL，
 *       调用方免拼 Wrapper，且便于缓存失效后单行重查；</li>
 *   <li>状态乐观迁移：全部带 WHERE 状态前置条件，影响 0 行即并发冲突——
 *       「同一时刻至多一个 ACTIVE」靠 FOR UPDATE 行锁串行化 + 退役旧版本 + 乐观晋升三步保证。</li>
 * </ul>
 */
public interface RuleVersionMapper extends BaseMapper<RuleVersion> {

    /**
     * 查询当前采样中的灰度版本（GRAY 且 gray_ratio &gt; 0）。
     *
     * <p>服务层约束同一时刻至多一个版本在采样；ORDER BY id DESC 兜底保证
     * 即使出现多条也确定取最新，路由不因数据态产生抖动。</p>
     */
    @Select("SELECT * FROM rule_version WHERE status = 0 AND gray_ratio > 0 ORDER BY id DESC LIMIT 1")
    RuleVersion selectSamplingGray();

    /** 查询全量生效的基线版本（ACTIVE），无则回退 Nacos 实时配置 */
    @Select("SELECT * FROM rule_version WHERE status = 1 LIMIT 1")
    RuleVersion selectActive();

    /**
     * 统计其他仍在采样的灰度版本数（用于「至多一个采样中版本」约束）。
     *
     * @param excludeId 排除的版本 id（调比例场景传自身）；可传 null 表示不排除（创建场景）
     */
    @Select("SELECT COUNT(*) FROM rule_version WHERE status = 0 AND gray_ratio > 0 " +
            "AND (#{excludeId} IS NULL OR id != #{excludeId})")
    long countOtherSampling(@Param("excludeId") Long excludeId);

    /**
     * 锁定全部未退役版本行（FOR UPDATE 读最新已提交数据）。
     *
     * <p>必须在事务内调用：并发全量发布在此串行化，是「同一时刻至多一个 ACTIVE」
     * 的保证点——后到的事务会看到先到者已晋升的新 ACTIVE 并将其退役，不会出现双 ACTIVE。</p>
     */
    @Select("SELECT id FROM rule_version WHERE status != 2 FOR UPDATE")
    List<Long> lockNonRetiredForUpdate();

    /**
     * 调整灰度比例（含回滚置 0）：仅 GRAY 版本可调（WHERE status=0 乐观条件），
     * 与并发全量发布冲突时影响 0 行，由调用方报冲突。
     */
    @Update("UPDATE rule_version SET gray_ratio = #{grayRatio} WHERE id = #{id} AND status = 0")
    int updateGrayRatio(@Param("id") Long id, @Param("grayRatio") Integer grayRatio);

    /**
     * 退役其他全部未退役版本（旧 ACTIVE + 遗留 GRAY）。
     *
     * <p>全量发布时调用：只退役他人、不动自己（id != 条件），保证晋升后
     * 全部用户无歧义地路由到唯一 ACTIVE 基线。</p>
     */
    @Update("UPDATE rule_version SET status = 2 WHERE id != #{id} AND status != 2")
    int retireOthers(@Param("id") Long id);

    /**
     * 晋升为 ACTIVE 并置 gray_ratio=100（全量发布）：仅 GRAY 可晋升（乐观条件防并发双 ACTIVE）。
     */
    @Update("UPDATE rule_version SET status = 1, gray_ratio = 100 WHERE id = #{id} AND status = 0")
    int promoteToActive(@Param("id") Long id);
}

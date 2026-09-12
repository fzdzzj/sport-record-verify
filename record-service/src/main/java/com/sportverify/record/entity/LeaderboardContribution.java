package com.sportverify.record.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 榜单贡献实体（record_db.leaderboard_contribution，审批版 §6.2）。
 *
 * <p><b>record_id 单列主键 = 回滚锚点</b>：每条 pass 记录至多一行贡献，
 * 改判驳回时按本行 {@code distance} 精确扣回 ZSet（以锚点值为准，不信任事件体重放值）；
 * INSERT IGNORE 命中主键即「该记录已入过榜」，天然幂等。</p>
 *
 * <p>写入路径：VERIFIED 事件消费者（入榜）；读取路径：定时结算任务
 * 以本表 ACTIVE 汇总为权威源纠偏 ZSet（最终一致）。</p>
 */
@Data
@TableName("leaderboard_contribution")
public class LeaderboardContribution {

    /** 记录ID（主键，回滚锚点；由业务侧传入，非自增） */
    @TableId(type = IdType.INPUT)
    private Long recordId;

    /** 所属用户（ZSet member） */
    private Long userId;

    /** 贡献里程（公里，仅 PASSED/RE_PASSED 记录；回滚按此值精确扣回） */
    private BigDecimal distance;

    /** 贡献状态（ContributionStatus.code：0 ACTIVE / 1 ROLLED_BACK） */
    private Integer status;

    /** 结算时间（定时结算对账后标记，快照口径） */
    private LocalDateTime settledAt;
}

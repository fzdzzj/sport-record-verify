package com.sportverify.leaderboard.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 跑团里程锚点只读映射实体（record_db.club_distance_contribution，TASK-278 R3）。
 *
 * <p><b>只读边界</b>：本表由 record-service 的跑团里程旁路写入（入账/回退）；本服务只做
 * 定时对账汇总（{@code GROUP BY} 三组口径），<b>对锚点表零写入</b>——与
 * {@link SportRecordSnapshot} 经只读 Mapper 读 {@code sport_record} 的边界同构
 * （跨服务同库只读，ADR-0005 先例）。因此对应 Mapper 刻意不继承 MyBatis-Plus
 * {@code BaseMapper}，从结构上杜绝双写。</p>
 *
 * <p><b>汇总列说明</b>：{@link #totalDistance} 不是本表列，仅承载三组汇总查询的
 * {@code SUM(distance_meters)} 别名（按 club / (club, week) / (club, user) 分组时使用），
 * 其余列在对账路径只作可读快照。</p>
 */
@Data
@TableName("club_distance_contribution")
public class ClubDistanceContribution {

    /** 锚点状态：该记录里程已计入跑团榜（对账唯一计入口径） */
    public static final int STATUS_CREDITED = 1;
    /** 锚点状态：该记录里程已从跑团榜回退 */
    public static final int STATUS_REVERSED = 0;

    /** 记录ID（主键，入账/回退锚点） */
    @TableId(type = IdType.INPUT)
    private Long recordId;

    /** 入账时点所在跑团 */
    private Long clubId;

    /** 所属用户（成员贡献 ZSet member） */
    private Long userId;

    /** 入账时点里程（米，恒正快照） */
    private BigDecimal distanceMeters;

    /** 入账时点周锚点（东八口径周一，ISO 日期） */
    private LocalDate weekStart;

    /** 锚点状态（{@link #STATUS_CREDITED} / {@link #STATUS_REVERSED}） */
    private Integer status;

    /** 入账时间 */
    private LocalDateTime createdAt;

    /** 回退时间 */
    private LocalDateTime reversedAt;

    /** 对账汇总列（非本表列：{@code SUM(distance_meters)} 别名映射，仅汇总查询使用） */
    @TableField(exist = false)
    private BigDecimal totalDistance;
}

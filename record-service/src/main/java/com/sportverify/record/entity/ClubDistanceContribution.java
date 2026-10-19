package com.sportverify.record.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 跑团里程入账锚点实体（record_db.club_distance_contribution，TASK-278 R1）。
 *
 * <p><b>record_id 单列主键 = 回退锚点</b>：每条被跑团榜计入的记录至多一行锚点，
 * 行内保存<b>入账时点快照</b>（{@code club_id} / {@code week_start} / {@code distance_meters}）。
 * 回退时按本行快照精确扣回，不再按「当前所在团」与「调用时点周」重算——换团（N5）与
 * 跨周改判（跨周错扣）两条时点漂移缺陷由此根治。</p>
 *
 * <p><b>状态语义</b>：{@code 1 CREDITED}（里程已在跑团三组 ZSet 上）/ {@code 0 REVERSED}
 * （已回退）。状态迁移一律走乐观 UPDATE（影响行数 = 操作是否真正生效），
 * 与 {@code leaderboard_contribution} 的入榜/回滚范式同构；同一方向恒只生效一次。
 * 本表取代 TASK-276 的 24h TTL Redis 幂等键，成为跑团里程唯一幂等源（治键过期静默跳过）。</p>
 *
 * <p><b>写入方</b>：仅 record-service 的跑团里程旁路；leaderboard-service 以只读 Mapper
 * 做汇总对账（跨服务同库只读，ADR-0005 先例）。</p>
 */
@Data
@TableName("club_distance_contribution")
public class ClubDistanceContribution {

    /** 锚点状态：该记录里程已计入跑团榜 */
    public static final int STATUS_CREDITED = 1;
    /** 锚点状态：该记录里程已从跑团榜回退 */
    public static final int STATUS_REVERSED = 0;

    /** 记录ID（主键，入账/回退锚点；由业务侧传入，非自增） */
    @TableId(type = IdType.INPUT)
    private Long recordId;

    /** 入账时点所在跑团（回退按此列扣减，不重查当前所在团） */
    private Long clubId;

    /** 所属用户（成员贡献 ZSet member） */
    private Long userId;

    /** 入账时点里程（米，恒正快照；回退按此值取负） */
    private BigDecimal distanceMeters;

    /** 入账时点周锚点（东八口径周一，ISO 日期） */
    private LocalDate weekStart;

    /** 锚点状态（{@link #STATUS_CREDITED} / {@link #STATUS_REVERSED}） */
    private Integer status;

    /** 入账时间 */
    private LocalDateTime createdAt;

    /** 回退时间（CREDITED 时恒 NULL；re-credit 时清空） */
    private LocalDateTime reversedAt;
}

package com.sportverify.leaderboard.entity;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 运动记录只读快照（record_db.sport_record 的榜单视角投影，仅 4 字段）。
 *
 * <p>服务拆分后的数据边界：记录实体的完整字段与写路径（提交/状态机/乐观锁）
 * 仍归 record-service；榜单侧对 {@code sport_record} <b>零写入</b>，
 * 仅以本快照读取入榜闸门所需的状态与里程——
 * {@code status} 做「仅 pass」闸门（PASSED/RE_PASSED 才入榜），
 * {@code distance} 为入榜增量（以库内记录为权威，防事件体重放伪造），
 * {@code userId} 为 ZSet member。</p>
 */
@Data
public class SportRecordSnapshot {

    /** 记录ID */
    private Long id;

    /** 所属用户（ZSet member） */
    private Long userId;

    /** 距离（公里，入榜增量） */
    private BigDecimal distance;

    /** 审核状态（RecordStatus.code） */
    private Integer status;
}

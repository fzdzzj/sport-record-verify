package com.sportverify.api.mapmatch.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 赛段信息 DTO（mapmatch-api 契约，TASK-272）。
 *
 * <p>一个挑战赛段的对外描述：数据库 {@code segment} 表一行或预置兜底赛段。
 * {@code distanceMeters} 为标称长度（米），是完赛判定容差（±25%）的分母基准；
 * {@code createdAt} 为建段时间字符串（预置兜底赛段无建段时间，为 null）。</p>
 */
@Data
public class SegmentDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 赛段 ID（数据库代理主键；预置兜底赛段使用固定稳定 ID） */
    private Long id;

    /** 赛段名（如「世纪公园内圈 1.2km 冲刺段」） */
    private String name;

    /** 运动类型：1 RUNNING, 2 CYCLING */
    private Integer sportType;

    /** 标称长度（米） */
    private Double distanceMeters;

    /** 建段时间（字符串；预置兜底赛段为 null） */
    private String createdAt;
}

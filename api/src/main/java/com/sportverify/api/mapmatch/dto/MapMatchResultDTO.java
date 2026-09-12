package com.sportverify.api.mapmatch.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 匹配结果（mapmatch-api 契约）。
 *
 * <p>指标定义（规范「匹配接口返回」）：</p>
 * <ul>
 *   <li>matchedRatio：吸附成功比例 = 垂距 ≤ 吸附阈值（默认 25m）的点数 / 采样点数；</li>
 *   <li>offRoadRatio：离路比例 = 垂距 &gt; 吸附阈值的点数 / 采样点数（R5 判定主指标）；</li>
 *   <li>avgOffRoadDistance：全部采样点到最近道路边的垂距均值（米）；</li>
 *   <li>maxOffRoadDistance：最大垂距（米），无候选边时按查询半径封顶（默认 300m）。</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MapMatchResultDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 吸附成功比例 0-1 */
    private double matchedRatio;

    /** 离路比例 0-1（R5 判定主指标） */
    private double offRoadRatio;

    /** 点到最近道路边垂距均值（米） */
    private double avgOffRoadDistance;

    /** 点到最近道路边最大垂距（米） */
    private double maxOffRoadDistance;

    /** 实际参与匹配的采样点数（超长轨迹抽稀后的值） */
    private int sampledPoints;

    /** 请求原始点数 */
    private int totalPoints;
}

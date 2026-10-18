package com.sportverify.api.record.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 运动记录公里分段全景 DTO（record-api 契约，TASK-273 公里分段配速与海拔剖面）。
 *
 * <p>轨迹点为空时优雅返回空 splits 列表（totalElevationGain/Loss 为 0.0、
 * fastestKm/slowestKm 为 null、hasElevationData 为 false）。</p>
 */
@Data
public class RecordSplitsDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 运动记录 id */
    private Long recordId;

    /** 轨迹 Haversine 累计总里程（米） */
    private Double totalDistanceMeters;

    /** 总耗时（秒，首末点时间差；点缺失 ts 时为 null） */
    private Integer totalElapsedSeconds;

    /** 公里分段列表（按公里序号升序，末尾余程为最后一段） */
    private List<KilometerSplitDTO> splits;

    /** 最快满公里序号（无满 1 公里时为 null） */
    private Integer fastestKm;

    /** 最慢满公里序号（无满 1 公里时为 null） */
    private Integer slowestKm;

    /** 全程累计爬升（米，无海拔数据时 0.0） */
    private Double totalElevationGain;

    /** 全程累计下降（米，无海拔数据时 0.0） */
    private Double totalElevationLoss;

    /** 是否含海拔数据（轨迹点携带 ele 时 true） */
    private Boolean hasElevationData;
}

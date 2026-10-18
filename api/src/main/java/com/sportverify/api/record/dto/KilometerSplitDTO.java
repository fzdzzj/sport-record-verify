package com.sportverify.api.record.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 单公里切片 DTO（record-api 契约，TASK-273 公里分段配速）。
 *
 * <p>由轨迹点 Haversine 累计路程跨过整公里阈值时线性插值越线时刻切出；
 * 末尾不足 1 公里的余程段（≥10m）也以本形态独立列出，km 为顺延序号。</p>
 */
@Data
public class KilometerSplitDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 分段序号：1, 2, 3...（末尾余程顺延编号） */
    private Integer km;

    /** 分段标称距离（米）：整公里 1000.0，末尾余程为实际剩余米数 */
    private Double distanceMeters;

    /** 分段净耗时（秒） */
    private Integer elapsedSeconds;

    /** 分段配速格式化串（m'ss"，如 5'24"；非正耗时或异常回退 --） */
    private String paceFormatted;

    /** 分段平均速度（m/s，保留 2 位小数） */
    private Double avgSpeed;

    /** 是否整公里中最快公里（fastestKm 对应段） */
    private Boolean isFastest;

    /** 是否整公里中最慢公里（slowestKm 对应段） */
    private Boolean isSlowest;

    /** 分段累计爬升（米，无海拔数据时 0.0） */
    private Double elevationGain;

    /** 分段累计下降（米，无海拔数据时 0.0） */
    private Double elevationLoss;
}

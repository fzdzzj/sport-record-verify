package com.sportverify.api.record.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 运动记录与轨迹点聚合 DTO（record-api 契约）。
 *
 * <p>供 verify-service 校验判定前单次拉取记录元数据与轨迹点列表，
 * 消除分开调用 getRecord 与 listPoints 造成的重复 HTTP 往返与两次 selectById 查询。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecordWithPointsDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 记录详情 */
    private SportRecordDTO record;

    /** 轨迹点列表（按 seq 升序） */
    private List<TrackPointDTO> points;
}

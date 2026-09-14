package com.sportverify.api.mapmatch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 匹配请求（mapmatch-api 契约）。
 *
 * <p>R5 离路规则把预处理后的有效轨迹点整段提交，服务侧负责抽稀与匹配。
 * 轨迹点列表必填且上限 20000 个（防超大请求体）。</p>
 */
@Data
public class MapMatchRequestDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 轨迹点序列（按时间升序；必填，上限 20000） */
    @NotEmpty(message = "轨迹点不能为空")
    @Size(max = 20000, message = "轨迹点数量超出上限（20000）")
    @Valid
    private List<MapMatchPointDTO> points;
}

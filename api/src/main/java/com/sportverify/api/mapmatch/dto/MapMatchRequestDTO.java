package com.sportverify.api.mapmatch.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 匹配请求（mapmatch-api 契约）。
 *
 * <p>R5 离路规则把预处理后的有效轨迹点整段提交，服务侧负责抽稀与匹配。</p>
 */
@Data
public class MapMatchRequestDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 轨迹点序列（按时间升序） */
    private List<MapMatchPointDTO> points;
}

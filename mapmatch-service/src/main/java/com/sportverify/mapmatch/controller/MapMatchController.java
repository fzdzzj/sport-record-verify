package com.sportverify.mapmatch.controller;

import com.sportverify.api.mapmatch.dto.MapMatchRequestDTO;
import com.sportverify.api.mapmatch.dto.MapMatchResultDTO;
import com.sportverify.common.result.Result;
import com.sportverify.mapmatch.service.MapMatchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 匹配接口（规范「匹配接口返回」）。
 *
 * <p>路径双入口同源：verify-service 经 MapMatchApi(Feign) 直调 {@code POST /match}；
 * 外部/管理端经网关 {@code POST /mapmatch/match}（StripPrefix=1 后同为 /match）。
 * 匹配结果统一 {@code Result<T>} 包装，异常由 common 全局处理器兜底。</p>
 */
@RestController
@RequiredArgsConstructor
public class MapMatchController {

    private final MapMatchService mapMatchService;

    /** 提交轨迹点 → 返回偏离路网指标（matchedRatio/offRoadRatio/avg/max） */
    @PostMapping("/match")
    public Result<MapMatchResultDTO> match(@Valid @RequestBody MapMatchRequestDTO request) {
        return Result.success(mapMatchService.match(request));
    }
}

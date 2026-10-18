package com.sportverify.mapmatch.controller;

import com.sportverify.api.mapmatch.dto.SegmentDTO;
import com.sportverify.api.mapmatch.dto.SegmentEffortDTO;
import com.sportverify.api.mapmatch.dto.SegmentMatchRequestDTO;
import com.sportverify.common.result.Result;
import com.sportverify.mapmatch.service.SegmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 赛段接口（TASK-272 路段挑战与赛段刷榜）。
 *
 * <p>网关 /mapmatch/** StripPrefix=1 后映射本控制器（同 MapMatchController 口径）：
 * 外部/前端经网关 {@code GET /mapmatch/segments}、{@code POST /mapmatch/segments/match}；
 * 校验链路内如有内部调用走 Feign 直连不经网关。结果统一 {@code Result<T>} 包装，
 * 异常由 common 全局处理器兜底。</p>
 */
@RestController
@RequiredArgsConstructor
public class SegmentController {

    private final SegmentService segmentService;

    /** 赛段列表（可选 sportType 过滤：1 RUNNING, 2 CYCLING；库空/库失联回退预置兜底赛段） */
    @GetMapping("/segments")
    public Result<List<SegmentDTO>> segments(
            @RequestParam(value = "sportType", required = false) Integer sportType) {
        return Result.success(segmentService.listSegments(sportType));
    }

    /** 赛段完赛匹配（两段式：GIST 粗筛 + 两锚精匹），返回命中的冲刺成果列表（可为空） */
    @PostMapping("/segments/match")
    public Result<List<SegmentEffortDTO>> match(@Valid @RequestBody SegmentMatchRequestDTO request) {
        return Result.success(segmentService.matchSegments(request));
    }
}

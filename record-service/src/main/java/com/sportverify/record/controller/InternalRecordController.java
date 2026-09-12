package com.sportverify.record.controller;

import com.sportverify.api.record.dto.LikeDTO;
import com.sportverify.api.record.dto.LikeRequestDTO;
import com.sportverify.api.record.dto.SportRecordDTO;
import com.sportverify.api.record.dto.StatusCallbackDTO;
import com.sportverify.api.record.dto.TrackPointDTO;
import com.sportverify.common.result.Result;
import com.sportverify.record.service.RecordLikeService;
import com.sportverify.record.service.SportRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * record-api Feign 契约实现（接口与实现分离，规范「服务间 Feign 契约」）。
 *
 * <p>消费方 verify-service 经 RecordApi 调用：拉记录/拉轨迹/状态回调；
 * 点赞三接口（点赞/取消/查询）供后续变更跨服务取数。
 * 榜单查询契约已随榜单职责迁至 leaderboard-service（LeaderboardApi，
 * 服务数 4→5，见 ADR-0005），本服务不再暴露榜单接口。</p>
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalRecordController {

    private final SportRecordService sportRecordService;
    private final RecordLikeService recordLikeService;

    /** 记录详情（含乐观锁版本，供回调携带） */
    @GetMapping("/records/{recordId}")
    public Result<SportRecordDTO> getRecord(@PathVariable("recordId") Long recordId) {
        return Result.success(sportRecordService.getDto(recordId));
    }

    /** 轨迹点列表（verify 判定输入；内部先解析 user_id 再路由分片） */
    @GetMapping("/records/{recordId}/points")
    public Result<List<TrackPointDTO>> listPoints(@PathVariable("recordId") Long recordId) {
        return Result.success(sportRecordService.listPoints(recordId));
    }

    /** 状态回调（乐观锁迁移，冲突 3003） */
    @PostMapping("/records/{recordId}/status-callback")
    public Result<Void> statusCallback(@PathVariable("recordId") Long recordId,
                                       @RequestBody StatusCallbackDTO dto) {
        sportRecordService.statusCallback(dto);
        return Result.success();
    }

    /** 点赞（Feign 契约实现，审批版 §4.6；仅 PASSED / RE_PASSED 可赞，否则 6001） */
    @PostMapping("/records/{recordId}/like")
    public Result<LikeDTO> likeRecord(@PathVariable("recordId") Long recordId,
                                      @RequestBody LikeRequestDTO dto) {
        return Result.success(recordLikeService.like(recordId, dto.getUserId()));
    }

    /** 取消点赞（Feign 契约实现；重复取消幂等） */
    @DeleteMapping("/records/{recordId}/like")
    public Result<LikeDTO> unlikeRecord(@PathVariable("recordId") Long recordId,
                                        @RequestParam("userId") Long userId) {
        return Result.success(recordLikeService.unlike(recordId, userId));
    }

    /** 查询点赞状态（Feign 契约实现；计数读热写冷） */
    @GetMapping("/records/{recordId}/like")
    public Result<LikeDTO> getRecordLike(@PathVariable("recordId") Long recordId,
                                         @RequestParam("userId") Long userId) {
        return Result.success(recordLikeService.getLike(recordId, userId));
    }
}

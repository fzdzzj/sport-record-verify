package com.sportverify.record.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sportverify.api.record.dto.RecordSubmitDTO;
import com.sportverify.api.record.dto.RecordSubmitResultDTO;
import com.sportverify.api.record.dto.TrackPointDTO;
import com.sportverify.api.verify.dto.AppealCreateDTO;
import com.sportverify.api.verify.dto.AppealDTO;
import com.sportverify.api.verify.dto.VerificationResultDTO;
import com.sportverify.common.result.Result;
import com.sportverify.common.result.ResultCode;
import com.sportverify.record.service.SportRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 记录域对外接口（网关 /record/** → StripPrefix → 本控制器）。
 *
 * <p>外部访问路径形如 {@code /record/api/records...}（与骨架网关约定一致）。</p>
 */
@RestController
@RequestMapping("/api/records")
@RequiredArgsConstructor
public class RecordController {

    private final SportRecordService sportRecordService;

    /**
     * 提交运动记录（规范「轨迹提交幂等」）。
     * 重复提交返回 3004 并携带原结果（记录ID + 当前状态）。
     */
    @PostMapping
    public Result<RecordSubmitResultDTO> submit(@RequestBody RecordSubmitDTO dto) {
        RecordSubmitResultDTO result = sportRecordService.submit(dto);
        if (result.isDuplicated()) {
            return new Result<>(ResultCode.IDEMPOTENT_CONFLICT.getCode(), "重复提交，返回原结果", result);
        }
        return Result.success(result);
    }

    /**
     * 查询判定结果（verify_db，经 verify-api 拉取；未终判时 verdict=VERIFYING）。
     */
    @GetMapping("/{id}/verify-result")
    public Result<VerificationResultDTO> verifyResult(@PathVariable("id") Long id) {
        return Result.success(sportRecordService.getVerifyResult(id));
    }

    /**
     * 提交申诉（仅 REJECTED 记录可申诉，规范「申诉提交」场景）。
     */
    @PostMapping("/{id}/appeal")
    public Result<AppealDTO> appeal(@PathVariable("id") Long id, @RequestBody AppealCreateDTO dto) {
        return Result.success(sportRecordService.appeal(id, dto.getUserId(), dto.getReason()));
    }

    /**
     * 轨迹分页查询（分片分页：验证 MyBatis-Plus 分页插件 × ShardingSphere 代理数据源）。
     */
    @GetMapping("/{id}/points")
    public Result<Page<TrackPointDTO>> points(@PathVariable("id") Long id,
                                              @RequestParam(defaultValue = "1") long page,
                                              @RequestParam(defaultValue = "20") long size) {
        return Result.success(sportRecordService.pagePoints(id, page, size));
    }
}

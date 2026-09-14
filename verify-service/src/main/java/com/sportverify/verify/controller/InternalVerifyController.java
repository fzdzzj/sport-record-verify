package com.sportverify.verify.controller;

import com.sportverify.api.verify.dto.AppealCreateDTO;
import com.sportverify.api.verify.dto.AppealDTO;
import com.sportverify.api.verify.dto.VerificationResultDTO;
import com.sportverify.common.result.Result;
import com.sportverify.verify.service.VerifyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * verify-api Feign 契约实现（接口与实现分离，规范「服务间 Feign 契约」）。
 *
 * <p>消费方 record-service 经 VerifyApi 调用：查询判定结果 / 创建申诉单。</p>
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalVerifyController {

    private final VerifyService verifyService;

    /** 查询校验结果（未终判返回 VERIFYING 占位） */
    @GetMapping("/verify-results/{recordId}")
    public Result<VerificationResultDTO> getVerificationResult(@PathVariable("recordId") Long recordId) {
        return Result.success(verifyService.getVerificationResult(recordId));
    }

    /** 创建申诉单（record_id 唯一，重复创建幂等返回既有申诉单） */
    @PostMapping("/appeals")
    public Result<AppealDTO> createAppeal(@Valid @RequestBody AppealCreateDTO dto) {
        return Result.success(verifyService.createAppeal(dto));
    }

    /** 触发校验（MQ 降级兜底 / 人工重放，幂等：按 recordId 不重算） */
    @PostMapping("/records/{recordId}/verify")
    public Result<Void> triggerVerify(@PathVariable("recordId") Long recordId) {
        verifyService.verify(recordId);
        return Result.success();
    }
}

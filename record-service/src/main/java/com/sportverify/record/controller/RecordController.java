package com.sportverify.record.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sportverify.api.record.dto.MyStreakDTO;
import com.sportverify.api.record.dto.RecordSubmitDTO;
import com.sportverify.api.record.dto.RecordSubmitResultDTO;
import com.sportverify.api.record.dto.RecordSummaryDTO;
import com.sportverify.api.record.dto.TrackPointDTO;
import com.sportverify.api.verify.dto.AppealCreateDTO;
import com.sportverify.api.verify.dto.AppealDTO;
import com.sportverify.api.verify.dto.VerificationResultDTO;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.Result;
import com.sportverify.common.result.ResultCode;
import com.sportverify.record.service.SportRecordService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
 * <p>身份认定（add-jwt-auth，见 ADR-0007）：<b>已从网关注入的 X-User-Id 读取</b>——
 * 提交/申诉的 userId 不再信任请求体显式携带值；{@code app.auth.enabled=true} 时以
 * X-User-Id 为准（显式值不一致 → 403 越权），false（默认）时降级旧行为（显式携带）。
 * 外部访问路径形如 {@code /record/api/records...}（与骨架网关约定一致）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/records")
@RequiredArgsConstructor
public class RecordController {

    private final SportRecordService sportRecordService;

    /** 鉴权降级开关（false=旧行为显式携带 userId；true=以网关注入的 X-User-Id 为准） */
    @Value("${app.auth.enabled:false}")
    private boolean authEnabled;

    /** 网关注入的 userId 请求头（唯一可信来源，外部伪造同名头被网关覆盖，见 ADR-0007） */
    private static final String HEADER_USER_ID = "X-User-Id";

    /**
     * 提交运动记录（规范「轨迹提交幂等」）。
     * 重复提交返回 3004 并携带原结果（记录ID + 当前状态）。
     */
    @PostMapping
    public Result<RecordSubmitResultDTO> submit(@Valid @RequestBody RecordSubmitDTO dto,
                                                HttpServletRequest request) {
        // 身份认定：true 时以 X-User-Id 覆盖 dto.userId（越权不一致 → 403）
        dto.setUserId(resolveUserId(request, dto.getUserId()));
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
     * 我的记录分页查询（TASK-211 个人统计面板数据面，归属即身份）。
     *
     * <p>身份语义（与 points/appeal 的 resolveUserId 口径一致，见 ADR-0007）：
     * {@code app.auth.enabled=true} 时从网关注入的 {@code X-User-Id} 读取归属人，
     * 缺失或空白 → 1001（HTTP 401）；false 时降级为显式 {@code userId} 查询参数，
     * 必填缺失 → 400 PARAM。记录严格按解析出的归属人过滤
     * （{@code WHERE user_id=?}），过滤即身份，构造上无越权。</p>
     *
     * <p>分页：{@code page>=1}，{@code size} 默认 20、上限 500（前端个人统计
     * 单次拉取即契约上限，超出部分为已知限制）；响应形态对齐 points 端点的
     * MyBatis-Plus {@code Page} 分页约定。</p>
     */
    @GetMapping("/my")
    public Result<Page<RecordSummaryDTO>> myRecords(
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            HttpServletRequest request) {
        Long owner = authEnabled ? resolveUserId(request, null) : userId;
        if (!authEnabled && owner == null) {
            throw new IllegalArgumentException("缺少必须的 userId 查询参数");
        }
        return Result.success(sportRecordService.pageMyRecords(owner, page, size));
    }

    /**
     * 我的连续打卡统计（TASK-220 个人仪表盘数据面，归属即身份）。
     *
     * <p>身份语义与 {@code /my} 完全一致（见 myRecords javadoc）：true 时从网关注入的
     * {@code X-User-Id} 读取归属人，缺失或空白 → 1001（HTTP 401）；false 时降级为显式
     * {@code userId} 查询参数，必填缺失 → 400 PARAM。统计口径见
     * {@link com.sportverify.api.record.dto.MyStreakDTO}：仅计入通过终态
     * （PASSED / RE_PASSED），同一天多条只算一天，今日无记录从昨日续算。</p>
     */
    @GetMapping("/my/streak")
    public Result<MyStreakDTO> myStreak(
            @RequestParam(value = "userId", required = false) Long userId,
            HttpServletRequest request) {
        Long owner = authEnabled ? resolveUserId(request, null) : userId;
        if (!authEnabled && owner == null) {
            throw new IllegalArgumentException("缺少必须的 userId 查询参数");
        }
        return Result.success(sportRecordService.myStreak(owner));
    }

    /**
     * 提交申诉（仅 REJECTED 记录可申诉，规范「申诉提交」场景）。
     * 归属校验（TASK-204）：true 时以网关 X-User-Id 为调用者，服务层断言调用者==记录归属人；
     * false 时 caller=null 降级旧行为。
     */
    @PostMapping("/{id}/appeal")
    public Result<AppealDTO> appeal(@PathVariable("id") Long id,
                                    @Valid @RequestBody AppealCreateDTO dto,
                                    HttpServletRequest request) {
        Long caller = authEnabled ? resolveUserId(request, dto.getUserId()) : null;
        return Result.success(sportRecordService.appeal(id, caller, dto.getReason()));
    }

    /**
     * 轨迹分页查询（分片分页：验证 MyBatis-Plus 分页插件 × ShardingSphere 代理数据源）。
     * 归属校验（TASK-204）：true 时以网关 X-User-Id 为调用者，服务层断言调用者==记录归属人；
     * false 时 caller=null 降级旧行为。
     */
    @GetMapping("/{id}/points")
    public Result<Page<TrackPointDTO>> points(@PathVariable("id") Long id,
                                              @RequestParam(defaultValue = "1") long page,
                                              @RequestParam(defaultValue = "20") long size,
                                              HttpServletRequest request) {
        Long caller = authEnabled ? resolveUserId(request, null) : null;
        return Result.success(sportRecordService.pagePoints(id, caller, page, size));
    }

    /**
     * 身份认定（数据隔离核心，见 ADR-0007）：true 时以网关 X-User-Id 为准，
     * 显式携带值不一致 → 403（1002，越权）；false 时降级为显式携带值（旧行为）。
     */
    private Long resolveUserId(HttpServletRequest request, Long claimed) {
        if (authEnabled) {
            String header = request.getHeader(HEADER_USER_ID);
            if (header == null || header.isBlank()) {
                throw new BizException(ResultCode.UNAUTHORIZED, "缺少网关注入的 X-User-Id");
            }
            Long tokenUserId = Long.parseLong(header);
            if (claimed != null && !claimed.equals(tokenUserId)) {
                // 调用方试图以他人身份提交/申诉：不信任自报家门，越权 → 403
                log.warn("越权访问被拒：tokenUserId={}, claimed={}", tokenUserId, claimed);
                throw new BizException(ResultCode.FORBIDDEN);
            }
            return tokenUserId;
        }
        return claimed;
    }

    /**
     * 本控制器局部异常处理：1001 → HTTP 401、1002 → HTTP 403（规范以 HTTP 状态为准）；
     * 其余业务码保持全局口径（HTTP 200 + body code）。
     */
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBizException(BizException e, HttpServletResponse response) {
        log.warn("业务异常：code={}, message={}", e.getCode(), e.getMessage());
        if (e.getCode() == ResultCode.UNAUTHORIZED.getCode()) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
        } else if (e.getCode() == ResultCode.FORBIDDEN.getCode()) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
        }
        return Result.failure(e.getCode(), e.getMessage());
    }
}

package com.sportverify.record.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sportverify.api.record.dto.MyStreakDTO;
import com.sportverify.api.record.dto.RecordSubmitDTO;
import com.sportverify.api.record.dto.RecordSubmitResultDTO;
import com.sportverify.api.record.dto.RecordSummaryDTO;
import com.sportverify.api.record.dto.FeedItemDTO;
import com.sportverify.api.record.dto.TrackPointDTO;
import com.sportverify.api.verify.dto.AppealCreateDTO;
import com.sportverify.api.verify.dto.AppealDTO;
import com.sportverify.api.verify.dto.VerificationResultDTO;
import com.sportverify.api.record.RecordStatus;
import com.sportverify.api.record.SportType;
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
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import org.springframework.web.bind.annotation.DeleteMapping;
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
     *
     * <p>读取面授权（TASK-221）：caller 经服务层 {@code assertCanView} 门判断——true 时以
     * 网关 X-User-Id 为读取者（缺失 → 1001），false 时 caller=null（降级态放行，行为与今日
     * 逐字节一致）。关闭了此前「任何人可读任意判定」的开放暴露。</p>
     */
    @GetMapping("/{id}/verify-result")
    public Result<VerificationResultDTO> verifyResult(@PathVariable("id") Long id,
                                                      HttpServletRequest request) {
        Long caller = authEnabled ? resolveUserId(request, null) : null;
        return Result.success(sportRecordService.getVerifyResult(id, caller));
    }

    /**
     * 我的记录分页查询（TASK-211 个人统计面板数据面，归属即身份）。
     *
     * <p>身份语义（与 points/appeal 的 resolveUserId 口径一致，见 ADR-0007）：
     * {@code app.auth.enabled=true} 时从网关注入的 {@code X-User-Id} 读取归属人，
     * 缺失或空白 → 1001（HTTP 401）；false 时降级为显式 {@code userId} 查询参数，
     * 必填缺失 → 400 PARAM。记录严格按解析出的归属人过滤
     * （{@code WHERE user_id=?}），过滤即身份，构造上无越权。</p>
     * <p>可选过滤（TASK-224）：sportType（运动类型枚举域校验）、status（校验状态域校验）、
     * startDate/endDate（开始日期范围过滤，start<=end 校验）。非法参数抛 400（IllegalArgumentException）。
     * 全部缺省时不过滤，保持向后兼容。</p>
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
            @RequestParam(value = "sportType", required = false) Integer sportType,
            @RequestParam(value = "status", required = false) Integer status,
            @RequestParam(value = "startDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(value = "endDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            HttpServletRequest request) {
        Long owner = authEnabled ? resolveUserId(request, null) : userId;
        if (!authEnabled && owner == null) {
            throw new IllegalArgumentException("缺少必须的 userId 查询参数");
        }
        validateFilterParams(sportType, status, startDate, endDate);
        return Result.success(sportRecordService.pageMyRecords(owner, page, size, sportType, status, startDate, endDate));
    }

    /**
     * 好友动态 feed（TASK-222 add-friend-feed，跨服务聚合读路径）。
     *
     * <p>身份语义（与 /my 同构，见 ADR-0007）：{@code app.auth.enabled=true} 时以网关注入的
     * {@code X-User-Id} 为读取者 caller，缺失或空白 → 1001（HTTP 401）；false 时 caller=null
     * （降级态）→ PUBLIC(0) 全站时间线（不区分归属，TASK-204 零扰动先例）。feed 的过滤即身份
     * 在服务层 {@code pageFeed}：auth on 时仅返回本人（任意可见性）+ 好友（PUBLIC/FRIENDS）记录，
     * 陌生人记录不入 feed——预过滤与 {@code assertCanView} 在 feed 场景下等价（见判别式 R6）。</p>
     */
    @GetMapping("/feed")
    public Result<Page<FeedItemDTO>> feed(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            HttpServletRequest request) {
        Long caller = authEnabled ? resolveUserId(request, null) : null;
        return Result.success(sportRecordService.pageFeed(caller, page, size));
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
     * 删除运动记录（TASK-223，硬删除级联恰四表 + Redis 缓存清理）。
     *
     * <p>身份语义与归属门（R1，与 /my 同构）：
     * {@code app.auth.enabled=true} 时从网关注入的 {@code X-User-Id} 读取调用者
     * （缺失或空白 → 1001 / HTTP 401）；false 时降级为显式 {@code userId} 查询参数
     * （缺失 → 400 PARAM）。服务层严格断言调用者为记录归属人，非本人删除 → 1002 / HTTP 403。</p>
     */
    @DeleteMapping("/{id}")
    public Result<Void> deleteRecord(@PathVariable("id") Long id,
                                     @RequestParam(value = "userId", required = false) Long userId,
                                     HttpServletRequest request) {
        Long caller = authEnabled ? resolveUserId(request, null) : userId;
        if (!authEnabled && caller == null) {
            throw new IllegalArgumentException("缺少必须的 userId 查询参数");
        }
        sportRecordService.deleteRecord(caller, id);
        return Result.success();
    }

    /**
     * 校验我的记录过滤参数（TASK-224）：sportType 枚举外、status 状态域外、start>end 抛 400（IllegalArgumentException）。
     */
    private void validateFilterParams(Integer sportType, Integer status, LocalDate startDate, LocalDate endDate) {
        if (sportType != null && SportType.fromCode(sportType) == null) {
            throw new IllegalArgumentException("非法运动类型：" + sportType);
        }
        if (status != null && !isValidStatus(status)) {
            throw new IllegalArgumentException("非法记录状态：" + status);
        }
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("开始日期不能晚于结束日期");
        }
    }

    private static boolean isValidStatus(Integer status) {
        if (status == null) {
            return false;
        }
        for (RecordStatus s : RecordStatus.values()) {
            if (s.getCode() == status) {
                return true;
            }
        }
        return false;
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

package com.sportverify.record.controller;

import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.Result;
import com.sportverify.common.result.ResultCode;
import com.sportverify.record.dto.GoalDTO;
import com.sportverify.record.dto.GoalUpsertDTO;
import com.sportverify.record.entity.Goal;
import com.sportverify.record.service.GoalService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 运动目标控制器（TASK-225，前缀 /api/records/my/goals）。
 *
 * <p>提供周/月目标的列表+进度查询、创建/更新（upsert）与删除接口。
 * 身份门与 /my 同构：authEnabled 时从 X-User-Id 读取调用者，缺头 1001；
 * 关闭时从 userId 参数读取，缺失 400；删除非 owner 403 1002，目标不存在 404。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/records/my/goals")
@RequiredArgsConstructor
public class GoalController {

    private final GoalService goalService;

    /** 鉴权降级开关（false=旧行为显式携带 userId；true=以网关注入的 X-User-Id 为准） */
    @Value("${app.auth.enabled:false}")
    private boolean authEnabled;

    /** 网关注入的 userId 请求头 */
    private static final String HEADER_USER_ID = "X-User-Id";

    /**
     * 查询我的运动目标及当前进度列表。
     *
     * @param userId  显式 userId（auth 关时必填）
     * @param request HTTP 请求对象
     * @return 目标与进度列表
     */
    @GetMapping
    public Result<List<GoalDTO>> listGoals(
            @RequestParam(value = "userId", required = false) Long userId,
            HttpServletRequest request) {
        Long owner = authEnabled ? resolveUserId(request, null) : userId;
        if (!authEnabled && owner == null) {
            throw new IllegalArgumentException("缺少必须的 userId 查询参数");
        }
        return Result.success(goalService.listGoalsWithProgress(owner));
    }

    /**
     * 创建或更新目标（upsert 语义）。
     *
     * @param dto         目标参数
     * @param userIdParam 显式 userId（auth 关且 dto 未携带时使用）
     * @param request     HTTP 请求对象
     * @return 保存后的 Goal 实体
     */
    @PostMapping
    public Result<Goal> upsertGoal(
            @Valid @RequestBody GoalUpsertDTO dto,
            @RequestParam(value = "userId", required = false) Long userIdParam,
            HttpServletRequest request) {
        Long claimed = dto.getUserId() != null ? dto.getUserId() : userIdParam;
        Long caller = authEnabled ? resolveUserId(request, claimed) : claimed;
        if (!authEnabled && caller == null) {
            throw new IllegalArgumentException("缺少必须的 userId 参数");
        }
        return Result.success(goalService.upsertGoal(caller, dto));
    }

    /**
     * 删除运动目标（校验 owner）。
     *
     * @param id      目标 ID
     * @param userId  显式 userId（auth 关时必填）
     * @param request HTTP 请求对象
     * @return 成功响应
     */
    @DeleteMapping("/{id}")
    public Result<Void> deleteGoal(
            @PathVariable("id") Long id,
            @RequestParam(value = "userId", required = false) Long userId,
            HttpServletRequest request) {
        Long caller = authEnabled ? resolveUserId(request, null) : userId;
        if (!authEnabled && caller == null) {
            throw new IllegalArgumentException("缺少必须的 userId 查询参数");
        }
        goalService.deleteGoal(caller, id);
        return Result.success();
    }

    private Long resolveUserId(HttpServletRequest request, Long claimed) {
        if (authEnabled) {
            String header = request.getHeader(HEADER_USER_ID);
            if (header == null || header.isBlank()) {
                throw new BizException(ResultCode.UNAUTHORIZED, "缺少网关注入的 X-User-Id");
            }
            Long tokenUserId = Long.parseLong(header);
            if (claimed != null && !claimed.equals(tokenUserId)) {
                log.warn("越权访问被拒：tokenUserId={}, claimed={}", tokenUserId, claimed);
                throw new BizException(ResultCode.FORBIDDEN);
            }
            return tokenUserId;
        }
        return claimed;
    }

    /**
     * 控制器局部业务异常处理：1001 → 401，1002 → 403，3001 → 404。
     */
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBizException(BizException e, HttpServletResponse response) {
        log.warn("业务异常：code={}, message={}", e.getCode(), e.getMessage());
        if (e.getCode() == ResultCode.UNAUTHORIZED.getCode()) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
        } else if (e.getCode() == ResultCode.FORBIDDEN.getCode()) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
        } else if (e.getCode() == ResultCode.RECORD_NOT_FOUND.getCode()) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
        }
        return Result.failure(e.getCode(), e.getMessage());
    }

    /**
     * 非法参数异常处理：映射 400。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("非法参数：{}", e.getMessage());
        return Result.failure(400, e.getMessage());
    }
}

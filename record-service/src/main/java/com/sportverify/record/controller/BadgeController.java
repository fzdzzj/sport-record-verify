package com.sportverify.record.controller;

import com.sportverify.common.result.Result;
import com.sportverify.record.dto.BadgeDTO;
import com.sportverify.record.service.BadgeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 运动徽章与成就控制器（TASK-269，前缀 /api/records，承载两组路由组）。
 *
 * <p>路由组一 /api/records/my/badges（归属即身份）：勋章墙列表（读时结算自动解锁）、
 * 佩戴与卸下；身份以网关注入的 {@code X-User-Id} 请求头为准，缺失由公共层
 * {@code MissingRequestHeaderException} 映射 400。路由组二 /api/records/users：
 * 指定用户的佩戴中勋章查询（社交外显读路径，纯只读）。任务书两组
 * {@code @RequestMapping} 在单控制器内以类级前缀 + 方法级子路径统一承载，
 * URL 契约逐字不变。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/records")
@RequiredArgsConstructor
public class BadgeController {

    /** 网关注入的 userId 请求头 */
    private static final String HEADER_USER_ID = "X-User-Id";

    private final BadgeService badgeService;

    /**
     * 我的勋章墙（全量 9 枚，读时结算自动解锁并追溯历史记录）。
     *
     * @param userId 网关注入的调用者 ID
     * @return 勋章与进度列表（枚举声明序）
     */
    @GetMapping("/my/badges")
    public Result<List<BadgeDTO>> listMyBadges(@RequestHeader(HEADER_USER_ID) Long userId) {
        return Result.success(badgeService.listMyBadges(userId));
    }

    /**
     * 佩戴勋章（未解锁 400）。
     *
     * @param userId    网关注入的调用者 ID
     * @param badgeCode 勋章编码（路径变量）
     * @return 成功响应
     */
    @PutMapping("/my/badges/{badgeCode}/wear")
    public Result<Void> wearBadge(@RequestHeader(HEADER_USER_ID) Long userId,
                                  @PathVariable("badgeCode") String badgeCode) {
        badgeService.wearBadge(userId, badgeCode);
        return Result.success();
    }

    /**
     * 卸下勋章（幂等）。
     *
     * @param userId 网关注入的调用者 ID
     * @return 成功响应
     */
    @PutMapping("/my/badges/unwear")
    public Result<Void> unwearBadge(@RequestHeader(HEADER_USER_ID) Long userId) {
        badgeService.unwearBadge(userId);
        return Result.success();
    }

    /**
     * 指定用户当前佩戴的勋章（社交外显，未佩戴 data 为 null）。
     *
     * @param userId 目标用户 ID（路径变量，公开外显读路径）
     * @return 佩戴中的勋章视图或 null
     */
    @GetMapping("/users/{userId}/equipped-badge")
    public Result<BadgeDTO> getEquippedBadge(@PathVariable("userId") Long userId) {
        return Result.success(badgeService.getEquippedBadge(userId));
    }

    /**
     * 控制器局部参数/业务校验异常处理：映射 400（GoalController 局部 handler 同款先例；
     * 未解锁佩戴的 BADGE_NOT_UNLOCKED 标识串经此返回调用方，公共层 BizException 映射不适用）。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("勋章操作非法参数/未解锁：{}", e.getMessage());
        return Result.failure(400, e.getMessage());
    }
}

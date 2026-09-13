package com.sportverify.leaderboard.controller;

import com.sportverify.api.record.dto.LeaderboardDTO;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.Result;
import com.sportverify.common.result.ResultCode;
import com.sportverify.leaderboard.service.LeaderboardService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 榜单域对外接口（网关 /leaderboard/api/leaderboard → StripPrefix → 本控制器，
 * 对应审批版 §4.5 榜单：总榜 / 好友榜 / 快照结算）。
 *
 * <p>路由前缀随服务拆分变更：原 /record/api/leaderboard（record-service）已下线，
 * 现走独立 /leaderboard/** 路由指向本服务（服务数 4→5，见 ADR-0005）。
 * 数据源为 Redis ZSet（读多写少，秒级）；写入由 VERIFIED/REJECTED 事件消费驱动，
 * 定时结算任务对账纠偏。好友榜查询人 userId <b>已从网关注入的 X-User-Id 读取</b>
 * （add-jwt-auth，见 ADR-0007）：auth.enabled=true 时以 X-User-Id 为准（显式值不一致
 * → 403 越权），false（默认）时降级为显式携带 userId 的旧行为。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/leaderboard")
@RequiredArgsConstructor
public class LeaderboardController {

    private final LeaderboardService leaderboardService;

    /** 鉴权降级开关（false=旧行为显式携带 userId；true=以网关注入的 X-User-Id 为准） */
    @Value("${app.auth.enabled:false}")
    private boolean authEnabled;

    /** 网关注入的 userId 请求头（唯一可信来源，外部伪造同名头被网关覆盖，见 ADR-0007） */
    private static final String HEADER_USER_ID = "X-User-Id";

    /**
     * 榜单查询：{@code type=overall} 总榜（按累计 pass 里程降序取前 N）；
     * {@code type=friend} 好友榜（Feign 拉好友列表后过滤 ZSet，只显示好友）。
     * 无好友/好友未上榜返回空榜；非法 type 或 friend 缺 userId 返回 400。
     */
    @GetMapping
    public Result<List<LeaderboardDTO>> leaderboard(
            @RequestParam("type") String type,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "size", defaultValue = "50") int size,
            HttpServletRequest request) {
        return Result.success(leaderboardService.top(type, resolveUserId(request, userId), size));
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
                // 调用方试图以他人身份查好友榜：不信任自报家门，越权 → 403
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

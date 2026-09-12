package com.sportverify.record.controller;

import com.sportverify.api.record.dto.LikeDTO;
import com.sportverify.api.record.dto.LikeRequestDTO;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.Result;
import com.sportverify.common.result.ResultCode;
import com.sportverify.record.service.RecordLikeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * 点赞域对外接口（网关 /record/api/records/{id}/like → StripPrefix → 本控制器，
 * 对应审批版 §4.6 点赞/取消/计数查询）。
 *
 * <p>身份认定（add-jwt-auth，见 ADR-0007）：<b>已从网关注入的 X-User-Id 读取</b>，
 * 不再信任请求体/query 显式携带的 userId——{@code app.auth.enabled=true} 时以 X-User-Id 为准，
 * 显式携带值与 token 身份不一致 → 403（1002，越权防护）；false（默认）时降级为旧行为
 * （显式携带 userId，兼容压测脚本）。错误码：记录不存在 3001、未通过校验 6001。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/records")
@RequiredArgsConstructor
public class LikeController {

    private final RecordLikeService recordLikeService;

    /** 鉴权降级开关（false=旧行为显式携带 userId；true=以网关注入的 X-User-Id 为准） */
    @Value("${app.auth.enabled:false}")
    private boolean authEnabled;

    /** 网关注入的 userId 请求头（唯一可信来源，外部伪造同名头被网关覆盖，见 ADR-0007） */
    private static final String HEADER_USER_ID = "X-User-Id";

    /**
     * 点赞（仅 PASSED / RE_PASSED 记录；重复点赞幂等，计数不变）。
     */
    @PostMapping("/{id}/like")
    public Result<LikeDTO> like(@PathVariable("id") Long id,
                                @RequestBody LikeRequestDTO dto,
                                HttpServletRequest request) {
        return Result.success(recordLikeService.like(id, resolveUserId(request, dto.getUserId())));
    }

    /**
     * 取消点赞（重复取消幂等；计数 DECR 下限 0，异步删行）。
     */
    @DeleteMapping("/{id}/like")
    public Result<LikeDTO> unlike(@PathVariable("id") Long id,
                                  @RequestParam(value = "userId", required = false) Long userId,
                                  HttpServletRequest request) {
        return Result.success(recordLikeService.unlike(id, resolveUserId(request, userId)));
    }

    /**
     * 查询点赞状态（计数读热写冷：Redis 优先、DB 兜底回填；liked 走成员集）。
     */
    @GetMapping("/{id}/like")
    public Result<LikeDTO> getLike(@PathVariable("id") Long id,
                                   @RequestParam(value = "userId", required = false) Long userId,
                                   HttpServletRequest request) {
        return Result.success(recordLikeService.getLike(id, resolveUserId(request, userId)));
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
                // 调用方试图以他人身份操作：不信任自报家门，越权 → 403
                log.warn("越权访问被拒：tokenUserId={}, claimed={}", tokenUserId, claimed);
                throw new BizException(ResultCode.FORBIDDEN);
            }
            return tokenUserId;
        }
        return claimed;
    }

    /**
     * 本控制器局部异常处理：1001 → HTTP 401、1002 → HTTP 403（规范以 HTTP 状态为准）；
     * 其余业务码（3001/6001 等）保持全局口径（HTTP 200 + body code）。
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

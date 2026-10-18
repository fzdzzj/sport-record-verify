package com.sportverify.user.controller;

import com.sportverify.api.common.PageResult;
import com.sportverify.api.user.dto.ClubCreateDTO;
import com.sportverify.api.user.dto.ClubDTO;
import com.sportverify.api.user.dto.ClubMemberDTO;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.Result;
import com.sportverify.common.result.ResultCode;
import com.sportverify.user.service.ClubService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 跑团域对外接口（网关 /user/api/clubs/** → StripPrefix → 本控制器，TASK-275）。
 *
 * <p>路由映射：{@code GET /api/clubs} 广场（keyword/page/size）、{@code POST /api/clubs}
 * 创建、{@code GET /api/clubs/my} 我的跑团、{@code GET /api/clubs/{clubId}} 详情、
 * {@code GET /api/clubs/{clubId}/members} 成员列表、{@code POST /api/clubs/{clubId}/join}
 * 加入、{@code POST /api/clubs/{clubId}/leave} 退出。身份遵循 {@code resolveUserId}
 * 统一鉴权范式（add-jwt-auth，见 ADR-0007）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/clubs")
@RequiredArgsConstructor
public class ClubController {

    private final ClubService clubService;

    /** 鉴权降级开关（true=以网关注入的 X-User-Id 为准；false=无可信身份来源，拒绝） */
    @Value("${app.auth.enabled:false}")
    private boolean authEnabled;

    /** 网关注入的 userId 请求头（唯一可信来源，外部伪造同名头被网关覆盖，见 ADR-0007） */
    private static final String HEADER_USER_ID = "X-User-Id";

    /** 跑团广场（GET /api/clubs）：keyword 名称模糊、分页钳制 1..100 由服务层收敛 */
    @GetMapping
    public Result<PageResult<ClubDTO>> list(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        return Result.success(clubService.listClubs(keyword, page, size));
    }

    /** 创建跑团（POST /api/clubs）：一人一团 + 名称唯一，身份自网关注入 X-User-Id */
    @PostMapping
    public Result<ClubDTO> create(@RequestBody ClubCreateDTO dto, HttpServletRequest request) {
        return Result.success(clubService.createClub(resolveUserId(request), dto));
    }

    /** 我的跑团（GET /api/clubs/my）：未入团 data 为 null */
    @GetMapping("/my")
    public Result<ClubDTO> my(HttpServletRequest request) {
        return Result.success(clubService.getMyClub(resolveUserId(request)));
    }

    /** 跑团详情（GET /api/clubs/{clubId}）：补齐团长昵称 */
    @GetMapping("/{clubId}")
    public Result<ClubDTO> detail(@PathVariable("clubId") Long clubId) {
        return Result.success(clubService.getClub(clubId));
    }

    /** 成员列表（GET /api/clubs/{clubId}/members）：按加入时间升序，批量补齐昵称 */
    @GetMapping("/{clubId}/members")
    public Result<List<ClubMemberDTO>> members(@PathVariable("clubId") Long clubId) {
        return Result.success(clubService.listMembers(clubId));
    }

    /** 加入跑团（POST /api/clubs/{clubId}/join）：一人一团 + 正常跑团，成员数原子累加 */
    @PostMapping("/{clubId}/join")
    public Result<ClubDTO> join(@PathVariable("clubId") Long clubId, HttpServletRequest request) {
        return Result.success(clubService.joinClub(resolveUserId(request), clubId));
    }

    /** 退出跑团（POST /api/clubs/{clubId}/leave）：团长有成员需先移交，仅剩团长一人即解散 */
    @PostMapping("/{clubId}/leave")
    public Result<Void> leave(@PathVariable("clubId") Long clubId, HttpServletRequest request) {
        clubService.leaveClub(resolveUserId(request), clubId);
        return Result.success(null);
    }

    /**
     * 自我服务身份认定（与 {@code UserController#resolveUserId} 同款）：
     * auth.enabled=true 时取网关注入的 X-User-Id，缺失/非法 → 401；false 时无可信身份来源，拒绝（401）。
     */
    private Long resolveUserId(HttpServletRequest request) {
        if (!authEnabled) {
            log.warn("跑团操作被拒：鉴权未开启，无可信用户身份");
            throw new BizException(ResultCode.UNAUTHORIZED, "鉴权未开启，无法识别用户身份");
        }
        String header = request.getHeader(HEADER_USER_ID);
        if (header == null || header.isBlank()) {
            throw new BizException(ResultCode.UNAUTHORIZED, "缺少网关注入的 X-User-Id");
        }
        try {
            return Long.parseLong(header);
        } catch (NumberFormatException e) {
            throw new BizException(ResultCode.UNAUTHORIZED, "X-User-Id 格式非法");
        }
    }
}

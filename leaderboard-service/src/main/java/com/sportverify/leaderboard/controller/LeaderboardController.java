package com.sportverify.leaderboard.controller;

import com.sportverify.api.record.dto.LeaderboardDTO;
import com.sportverify.api.mapmatch.dto.SegmentEffortDTO;
import com.sportverify.api.mapmatch.dto.SegmentLeaderboardDTO;
import com.sportverify.api.user.dto.ClubLeaderboardDTO;
import com.sportverify.api.user.dto.ClubMemberContributionDTO;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.Result;
import com.sportverify.common.result.ResultCode;
import com.sportverify.leaderboard.service.LeaderboardService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
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
     * 每日报表只读查询：{@code GET /api/leaderboard/daily?date=2026-09-20&size=20}。
     * 取某日累计快照前 N 名（默认 50、上限 500）；日期缺失或格式非法由 Spring 直接 400。
     */
    @GetMapping("/daily")
    public Result<List<LeaderboardDTO>> daily(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        return Result.success(leaderboardService.dailyReport(date, size));
    }

    /**
     * 日期范围榜单只读查询（TASK-209）：{@code GET /api/leaderboard/range?start=2026-09-01&end=2026-09-07&size=50}。
     * 公共聚合面（同 daily，不涉 identity/userId）：按 {@code settled_at} 半开窗口聚合
     * ACTIVE 贡献里程取前 N（默认 50、上限 500，service 内收敛）。start 必填、end 缺省今天；
     * start&gt;end 或跨度&gt;366 天 → 400（IllegalArgumentException → GlobalExceptionHandler 400 PARAM）。
     * 自然周期（周=ISO 周一、月=本月 1 号，end=今天）由调用方组装，服务只做原始范围聚合。
     */
    @GetMapping("/range")
    public Result<List<LeaderboardDTO>> range(
            @RequestParam("start") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(value = "end", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        if (end == null) {
            end = LocalDate.now();
        }
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("start 不能晚于 end：" + start + " > " + end);
        }
        if (start.plusDays(366).isBefore(end)) {
            throw new IllegalArgumentException("日期范围跨度不能超过 366 天");
        }
        return Result.success(leaderboardService.rangeReport(start, end, size));
    }

    /**
     * 赛段 KOM 榜（TASK-272）：{@code GET /api/leaderboard/segments/{segmentId}?size=50}。
     * 按 elapsed_seconds 升序前 N（耗时最短 = rank 1 = 赛段擂主 KOM 👑）；昵称/配速/回指记录
     * 由 service 组装。
     */
    @GetMapping("/segments/{segmentId}")
    public Result<List<SegmentLeaderboardDTO>> segmentLeaderboard(
            @PathVariable("segmentId") Long segmentId,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        return Result.success(leaderboardService.getSegmentLeaderboard(segmentId, size));
    }

    /**
     * 赛段成绩上报（TASK-272）：{@code POST /api/leaderboard/segments/{segmentId}/effort}。
     * body 复用 SegmentEffortDTO（取 recordId/userId/elapsedSeconds，其余字段忽略），
     * 响应回填 isPr/isKom 判定；auth 会话下 userId 以网关 X-User-Id 为准（body 自报值仅
     * auth.enabled=false 的降级口径使用，复用 {@link #resolveUserId} 同款越权拦截）。
     */
    @PostMapping("/segments/{segmentId}/effort")
    public Result<SegmentEffortDTO> submitSegmentEffort(
            @PathVariable("segmentId") Long segmentId,
            @RequestBody SegmentEffortDTO effort,
            HttpServletRequest request) {
        if (effort == null || effort.getRecordId() == null
                || effort.getElapsedSeconds() == null || effort.getElapsedSeconds() <= 0) {
            throw new IllegalArgumentException("赛段成绩上报必须携带 recordId 与正数 elapsedSeconds");
        }
        Long userId = resolveUserId(request, effort.getUserId());
        return Result.success(leaderboardService.recordSegmentEffort(
                segmentId, userId, effort.getRecordId(), effort.getElapsedSeconds()));
    }

    /**
     * 跑团团队里程榜（TASK-275）：{@code GET /api/leaderboard/clubs?period=overall|weekly&size=20}。
     * ZSet 降序前 N（默认 20、上限 100）；period=weekly 读当周周一键，clubName 经名称缓存
     * Hash 补齐，由 service 组装 {@link ClubLeaderboardDTO}。
     */
    @GetMapping("/clubs")
    public Result<List<ClubLeaderboardDTO>> clubLeaderboard(
            @RequestParam(value = "period", required = false) String period,
            @RequestParam(value = "size", required = false) Integer size) {
        return Result.success(leaderboardService.getClubLeaderboard(period, size));
    }

    /**
     * 跑团成员贡献明细（TASK-276 R3）：{@code GET /api/leaderboard/clubs/{clubId}/contributions?size=20}。
     * ZSet {@code leaderboard:club:member:{clubId}} 降序前 N（默认 20、上限 100，service 内收敛），
     * 返回 rank/userId/nickname/distanceMeters；昵称缺失回退「跑者{userId}」。
     */
    @GetMapping("/clubs/{clubId}/contributions")
    public Result<List<ClubMemberContributionDTO>> clubContributions(
            @PathVariable("clubId") Long clubId,
            @RequestParam(value = "size", required = false) Integer size) {
        return Result.success(leaderboardService.getClubContributions(clubId, size));
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
}

/**
 * 跑团里程上报内部接口（TASK-275）：{@code POST /internal/leaderboard/clubs/distance}。
 *
 * <p>与 {@link LeaderboardController} 同文件托管的包级私有控制器——/internal/** 路径无法
 * 挂在该控制器的类级 {@code /api/leaderboard} 前缀下，而独立新文件不在本任务白名单内；
 * Spring 组件扫描按包扫描，包级私有顶级类同样被装配。供跑团域（后续链路）服务间上报
 * 累计里程使用：ZINCRBY 累加总榜与周榜，并同步 clubName 进名称缓存 Hash。</p>
 */
@RestController
@RequestMapping("/internal/leaderboard/clubs")
@RequiredArgsConstructor
class ClubLeaderboardInternalController {

    private final LeaderboardService leaderboardService;

    /** 跑团里程上报（POST /internal/leaderboard/clubs/distance）：body 携带 clubId/clubName/distanceMeters/weekStart/userId */
    @PostMapping("/distance")
    public Result<Boolean> recordDistance(@RequestBody ClubDistancePayload payload) {
        if (payload == null || payload.getClubId() == null) {
            throw new IllegalArgumentException("跑团里程上报必须携带 clubId");
        }
        leaderboardService.recordClubDistance(payload.getClubId(), payload.getClubName(),
                payload.getDistanceMeters(), payload.getWeekStart(), payload.getUserId());
        return Result.success(Boolean.TRUE);
    }

    /** 上报请求体（weekStart 为周一 ISO 日期；缺省时服务端归一化到当周周一） */
    @Data
    static class ClubDistancePayload {
        /** 跑团ID */
        private Long clubId;
        /** 跑团名称（同步进名称缓存 Hash；可空则不更新名称） */
        private String clubName;
        /** 净额增量（米）：正数入账 / 负数回退，不得为 0（TASK-276 R2 带符号口径） */
        private Double distanceMeters;
        /** 周一锚点日期（ISO yyyy-MM-dd） */
        private LocalDate weekStart;
        /** 贡献成员用户ID（TASK-276 R3 成员贡献 ZSet member；可空则不计成员明细） */
        private Long userId;
    }
}

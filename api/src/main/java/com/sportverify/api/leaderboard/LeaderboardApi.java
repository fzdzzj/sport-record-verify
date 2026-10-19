package com.sportverify.api.leaderboard;

import com.sportverify.api.record.dto.LeaderboardDTO;
import com.sportverify.common.result.Result;
import lombok.Data;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

/**
 * leaderboard-service Feign 契约（leaderboard-api，接口与实现分离）。
 *
 * <p>契约随服务拆分自 RecordApi 迁出（服务数 4→5，榜单职责独立为
 * leaderboard-service，见 ADR-0005）。实现位于 leaderboard-service 的
 * InternalLeaderboardController（{@code /internal} 前缀），榜单查询
 * 供后续变更跨服务取数。</p>
 *
 * <p>降级决策（add-resilience-hardening）：契约层显式抛 4008（见 {@link LeaderboardApiFallback}），
 * 不假装返回榜单。消费方现状：record-service 删除记录后经 {@link #rollbackContribution(Long)}
 * 回撤榜单里程贡献（TASK-223 D1，best-effort 写路径）；榜单查询读路径暂无消费方。</p>
 */
@FeignClient(name = "leaderboard-service", path = "/internal", fallbackFactory = LeaderboardApiFallback.class)
public interface LeaderboardApi {

    /**
     * 榜单查询（审批版 §4.5）。
     *
     * @param type   overall 总榜 / friend 好友榜
     * @param userId 查询人（friend 榜必填：按其好友列表过滤 ZSet；overall 忽略）
     * @param size   取前 N 名（榜单读多写少：ZREVRANGE 秒级）
     */
    @GetMapping("/leaderboard")
    Result<List<LeaderboardDTO>> leaderboard(@RequestParam("type") String type,
                                             @RequestParam(value = "userId", required = false) Long userId,
                                             @RequestParam(value = "size", defaultValue = "50") int size);

    /**
     * 榜单里程贡献回撤（TASK-223 D1）：用户删除/撤回运动记录后，由 record-service 在删除事务
     * {@code afterCommit} 内 best-effort 调用，通知 leaderboard-service 把该记录的贡献锚点
     * {@code ACTIVE → ROLLED_BACK} 并扣回 ZSet 里程。
     *
     * <p>服务侧幂等（复用 {@code LeaderboardService.rollbackOnRejected}）：无贡献锚点（记录从未入榜）
     * 或已 ROLLED_BACK（重复调用）均跳过，不产生负里程；同一 {@code lock:rollback:{recordId}}
     * 与入榜互斥，锚点行乐观迁移保证只有一次调用真正扣分。</p>
     *
     * <p>语义为「尽力而为」：调用方吞并异常留证，跨服务最终一致（ADR-0009，无本地跨库事务）；
     * 降级时 fallback 显式抛 4008（不假装成功），使残留的 ACTIVE 锚点可被对账/人工发现。</p>
     *
     * @param recordId 运动记录 id（已删除记录的主键，作为贡献锚点主键）
     */
    @PostMapping("/leaderboard/records/{recordId}/rollback")
    Result<Void> rollbackContribution(@PathVariable("recordId") Long recordId);

    /**
     * 跑团里程入账（TASK-276 R2）：record-service 在运动记录终态迁移成功后按<b>净额增量</b>
     * 上报用户所在跑团里程——{@code distanceMeters > 0} 入账（迁移至 PASSED/RE_PASSED），
     * {@code distanceMeters < 0} 回退（迁移至 RE_CONFIRMED 且此前已入账）。
     *
     * <p>leaderboard-service 侧原子 {@code ZINCRBY} 总榜 {@code leaderboard:club:overall} 与
     * 周榜 {@code leaderboard:club:weekly:{weekStart}}（member=clubId），并在 {@code userId}
     * 非空时同步累加成员贡献 ZSet {@code leaderboard:club:member:{clubId}}（member=userId），
     * 供团队榜与成员贡献明细共用同一份里程来源。</p>
     *
     * <p>语义为「尽力而为」：调用方 record-service 的跑团里程入账是<b>旁路</b>，任何异常
     * （本契约降级抛 4008 / 锁竞争 / 网络故障）只 WARN 留证，不抛出、不阻断状态回调主流程
     * （跨服务最终一致，ADR-0009）；降级时 fallback 显式抛 4008（不假装成功），残留净额缺口
     * 可由后续对账课题收敛。</p>
     *
     * @param payload 入账请求体（clubId 必填、distanceMeters 非 0、userId 用于成员贡献明细）
     */
    @PostMapping("/leaderboard/clubs/distance")
    Result<Boolean> recordClubDistance(@RequestBody ClubDistancePayload payload);

    /**
     * 跑团里程入账请求体（TASK-276 R2）。
     *
     * <p>与 leaderboard-service 侧 internal 端点接收的 {@code ClubDistancePayload} <b>按字段名同构</b>
     * （JSON 契约而非类继承）：接口定义方在 api 模块、实现在 leaderboard-service，两侧各自持有
     * 自己的请求体类型是本仓「接口与实现分离」的既有形态。本类以接口内嵌类型承载，避免为一个
     * 请求体新增 api 模块文件（TASK-276 白名单恰 21 项）。</p>
     */
    @Data
    class ClubDistancePayload implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 跑团ID（必填） */
        private Long clubId;

        /** 跑团名称（同步进名称缓存 Hash；可空则不更新名称） */
        private String clubName;

        /** 净额增量（米）：正数入账 / 负数回退，禁止 0（0 视为无变化，服务端拒绝） */
        private Double distanceMeters;

        /** 周一锚点日期（ISO yyyy-MM-dd；缺省时服务端归一化到当周周一） */
        private LocalDate weekStart;

        /** 贡献成员用户ID（成员贡献 ZSet member；可空则该次上报不计成员明细） */
        private Long userId;
    }
}

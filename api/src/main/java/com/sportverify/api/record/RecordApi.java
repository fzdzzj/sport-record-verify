package com.sportverify.api.record;

import com.sportverify.api.record.dto.LikeDTO;
import com.sportverify.api.record.dto.LikeRequestDTO;
import com.sportverify.api.record.dto.SportRecordDTO;
import com.sportverify.api.record.dto.StatusCallbackDTO;
import com.sportverify.api.record.dto.TrackPointDTO;
import com.sportverify.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * record-service Feign 契约（record-api，接口与实现分离）。
 *
 * <p>消费方为 verify-service：判定前拉取记录与轨迹，判定/终判后回调状态迁移。
 * 实现位于 record-service 的 InternalRecordController（{@code /internal} 前缀）。
 * 榜单查询契约已迁出至 {@link com.sportverify.api.leaderboard.LeaderboardApi}
 * （榜单职责独立为 leaderboard-service，服务数 4→5，见 ADR-0005）。</p>
 */
@FeignClient(name = "record-service", path = "/internal")
public interface RecordApi {

    /**
     * 健康探活：verify-service 等消费方经本接口确认 record-service 存活。
     */
    @GetMapping("/health")
    Result<String> health();

    /**
     * 拉取记录详情（元数据 + 乐观锁版本）。
     * verify 判定/终判前先取记录：既能拿 user_id（轨迹分片路由键），
     * 又能拿到 version 作为回调乐观锁条件（规范「记录本身不分片」）。
     */
    @GetMapping("/records/{recordId}")
    Result<SportRecordDTO> getRecord(@PathVariable("recordId") Long recordId);

    /**
     * 拉取记录全部轨迹点（校验引擎输入）。
     * record-service 内部先经 sport_record 解析 user_id，再按 user_id 路由单分片查询。
     */
    @GetMapping("/records/{recordId}/points")
    Result<List<TrackPointDTO>> listPoints(@PathVariable("recordId") Long recordId);

    /**
     * 状态回调：verify 判定/终判后驱动 record 状态机迁移。
     * 乐观锁 {@code UPDATE ... WHERE status AND version}，冲突（影响 0 行）返回 3003。
     */
    @PostMapping("/records/{recordId}/status-callback")
    Result<Void> statusCallback(@PathVariable("recordId") Long recordId,
                                @RequestBody StatusCallbackDTO dto);

    /**
     * 点赞（审批版 §4.6）：仅 PASSED / RE_PASSED 记录可赞，否则 6001。
     * 幂等：Redis 成员集 SADD 返回 0（已赞）时计数不变、不重复落库。
     */
    @PostMapping("/records/{recordId}/like")
    Result<LikeDTO> likeRecord(@PathVariable("recordId") Long recordId,
                               @RequestBody LikeRequestDTO dto);

    /**
     * 取消点赞（审批版 §4.6）：SREM 返回 0（未赞）幂等跳过；计数 DECR 下限 0。
     */
    @DeleteMapping("/records/{recordId}/like")
    Result<LikeDTO> unlikeRecord(@PathVariable("recordId") Long recordId,
                                 @RequestParam("userId") Long userId);

    /**
     * 查询点赞状态（读热写冷）：计数优先读 Redis，缺失兜底 DB COUNT(*) 并回填；
     * 「是否已赞」走成员集 SISMEMBER。
     */
    @GetMapping("/records/{recordId}/like")
    Result<LikeDTO> getRecordLike(@PathVariable("recordId") Long recordId,
                                  @RequestParam("userId") Long userId);
}

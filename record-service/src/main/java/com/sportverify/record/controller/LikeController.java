package com.sportverify.record.controller;

import com.sportverify.api.record.dto.LikeDTO;
import com.sportverify.api.record.dto.LikeRequestDTO;
import com.sportverify.common.result.Result;
import com.sportverify.record.service.RecordLikeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * <p>骨架无认证鉴权，点赞人 userId 由调用方显式携带：POST 走请求体
 * {@link LikeRequestDTO}，DELETE/GET 走 query 参数（DELETE 带 body 各端支持不一，
 * 统一 query 更稳）。错误码：记录不存在 3001、未通过校验 6001。</p>
 */
@RestController
@RequestMapping("/api/records")
@RequiredArgsConstructor
public class LikeController {

    private final RecordLikeService recordLikeService;

    /**
     * 点赞（仅 PASSED / RE_PASSED 记录；重复点赞幂等，计数不变）。
     */
    @PostMapping("/{id}/like")
    public Result<LikeDTO> like(@PathVariable("id") Long id, @RequestBody LikeRequestDTO dto) {
        return Result.success(recordLikeService.like(id, dto.getUserId()));
    }

    /**
     * 取消点赞（重复取消幂等；计数 DECR 下限 0，异步删行）。
     */
    @DeleteMapping("/{id}/like")
    public Result<LikeDTO> unlike(@PathVariable("id") Long id, @RequestParam("userId") Long userId) {
        return Result.success(recordLikeService.unlike(id, userId));
    }

    /**
     * 查询点赞状态（计数读热写冷：Redis 优先、DB 兜底回填；liked 走成员集）。
     */
    @GetMapping("/{id}/like")
    public Result<LikeDTO> getLike(@PathVariable("id") Long id, @RequestParam("userId") Long userId) {
        return Result.success(recordLikeService.getLike(id, userId));
    }
}

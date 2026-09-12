package com.sportverify.api.record.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 点赞状态 DTO（record-api 点赞契约，审批版 §4.6）。
 *
 * <p>点赞/取消/查询三接口统一返回本结构：{@code recordId} 被赞记录、
 * {@code likeCount} 当前计数（读热写冷，Redis 为准、DB 兜底）、
 * {@code liked} 当前用户是否已赞（Redis 成员集 SISMEMBER）。</p>
 */
@Data
public class LikeDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 被赞记录ID */
    private Long recordId;

    /** 当前点赞数（Redis like:count:{recordId}，缺失时兜底 DB COUNT(*)） */
    private Long likeCount;

    /** 当前用户是否已赞 */
    private Boolean liked;

    public static LikeDTO of(Long recordId, Long likeCount, Boolean liked) {
        LikeDTO dto = new LikeDTO();
        dto.setRecordId(recordId);
        dto.setLikeCount(likeCount);
        dto.setLiked(liked);
        return dto;
    }
}

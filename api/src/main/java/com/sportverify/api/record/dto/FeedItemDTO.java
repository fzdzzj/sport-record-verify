package com.sportverify.api.record.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 好友动态 feed 条目 DTO（record-api 契约，GET /record/api/records/feed 响应元素，
 * TASK-222 add-friend-feed）。
 *
 * <p>在 {@link RecordSummaryDTO} 全字段（id/sportType/distance/status/visibility/createdAt）
 * 之上追加 {@code authorUserId} 与 {@code authorNickname}。归属隔离语义：feed 的过滤即身份——
 * 服务层把 caller 解析为「本人 ∪ 好友」的 {@code user_id IN (...)} 查询，返回的记录归属人
 * 均属于该集合；作者昵称由 user-service 批量概要接口按当页 distinct user_id 补齐。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class FeedItemDTO extends RecordSummaryDTO {

    private static final long serialVersionUID = 1L;

    /** 作者（记录归属人）用户ID */
    private Long authorUserId;

    /** 作者昵称（user-service 批量概要补齐；user-service 不可用时为空，web 回退 authorUserId 字符串渲染） */
    private String authorNickname;
}

package com.sportverify.api.record.dto;

import java.io.Serializable;
import lombok.Data;

/**
 * 连续打卡统计 DTO（record-api 契约，GET /record/api/records/my/streak 响应）。
 *
 * <p>统计口径（TASK-220）：仅计入通过终态（PASSED / RE_PASSED，见
 * {@link com.sportverify.api.record.RecordStatus}）的记录——与榜单/点赞的
 * 入榜口径一致，天然防注水；同一天多条记录只算一天（按自然日去重）。
 * current 为截止今日的当前连续天数：今日有通过记录则从今日往回数；
 * 今日无记录但昨日有则从昨日往回数（连击活到当日结束）；两者皆无则为 0。
 * longest 为历史最大连续天数（跨断档取最长连续段）。</p>
 */
@Data
public class MyStreakDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 当前连续打卡天数（今日无记录时从昨日续算，昨日也无记录为 0） */
    private Integer current;

    /** 历史最长连续打卡天数（跨断档的最大连续段） */
    private Integer longest;
}

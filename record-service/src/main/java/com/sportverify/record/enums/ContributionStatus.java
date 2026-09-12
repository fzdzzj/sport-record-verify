package com.sportverify.record.enums;

import lombok.Getter;

/**
 * 榜单贡献状态（record_db.leaderboard_contribution.status，审批版 §6.2）。
 *
 * <p>贡献行是榜单的<b>权威源与回滚锚点</b>：入榜 = ACTIVE（ZSet 加分已生效），
 * 改判回滚 = ROLLED_BACK（ZSet 已扣回）。状态机迁移均以
 * {@code UPDATE ... WHERE record_id=? AND status=?} 乐观语义流转，
 * 影响行数即「本次操作是否真正生效」，天然支撑回滚幂等。</p>
 */
@Getter
public enum ContributionStatus {

    /** 有效贡献：该记录里程已计入 ZSet */
    ACTIVE(0),
    /** 已回滚：该记录里程已从 ZSet 扣回（幂等锚点，重复回滚不再扣分） */
    ROLLED_BACK(1);

    private final int code;

    ContributionStatus(int code) {
        this.code = code;
    }
}

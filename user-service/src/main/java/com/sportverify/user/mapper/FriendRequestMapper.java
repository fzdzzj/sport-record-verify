package com.sportverify.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sportverify.user.entity.FriendRequest;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 好友申请 Mapper（user_db.friend_request）。
 */
public interface FriendRequestMapper extends BaseMapper<FriendRequest> {

    /**
     * 乐观状态流转（规范差异「申请状态机」）：
     * {@code UPDATE friend_request SET status=?, updated_at=? WHERE id=? AND status=?}
     *
     * <p>仅 PENDING 可流转；影响 0 行表示申请已不存在或已处理
     * （并发 accept/reject 只有一个成功），调用方报 5002。</p>
     *
     * @return 影响行数；0 = 申请不存在或状态已变更
     */
    @Update("UPDATE friend_request SET status = #{toStatus}, updated_at = #{updatedAt} " +
            "WHERE id = #{id} AND status = #{fromStatus}")
    int updateStatus(@Param("id") Long id,
                     @Param("fromStatus") Integer fromStatus,
                     @Param("toStatus") Integer toStatus,
                     @Param("updatedAt") LocalDateTime updatedAt);
}

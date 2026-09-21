package com.sportverify.verify.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sportverify.verify.entity.VerifyEventOutbox;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 校验事件 outbox Mapper（verify_db.verify_event_outbox，F03 本地消息表）。
 */
public interface VerifyEventOutboxMapper extends BaseMapper<VerifyEventOutbox> {

    /**
     * 取一批待投递事件（按自增 id 顺序，先到先得；批量上限由 relay 配置）。
     */
    @Select("SELECT * FROM verify_event_outbox WHERE status = 'PENDING' ORDER BY id LIMIT #{limit}")
    List<VerifyEventOutbox> selectPendingBatch(@Param("limit") int limit);

    /**
     * 投递成功标记（条件含 status='PENDING'：并发下只生效一次，重复标记幂等）。
     */
    @Update("UPDATE verify_event_outbox SET status = 'SENT', sent_at = NOW() " +
            "WHERE id = #{id} AND status = 'PENDING'")
    int markSent(@Param("id") Long id);

    /**
     * 投递失败计数 +1（行保留 PENDING，下轮 relay 再试）。
     */
    @Update("UPDATE verify_event_outbox SET retry_count = retry_count + 1 " +
            "WHERE id = #{id} AND status = 'PENDING'")
    int incrRetry(@Param("id") Long id);
}

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
     * 取一批待投递事件（按自增 id 顺序，先到先得；批量上限与重试上限由 relay 配置）。
     *
     * <p>资格条件含 {@code retry_count < maxRetry}：重试已耗尽的 PENDING 行不占发送批次，
     * 避免较小 ID 的耗尽行占满查询上限后永久遮挡后续可投递行；耗尽行本身保留原状态与原数据
     * 供人工处理（不删除、不重置计数、不改 eventId、不重投）。</p>
     */
    @Select("SELECT * FROM verify_event_outbox WHERE status = 'PENDING' AND retry_count < #{maxRetry} " +
            "ORDER BY id LIMIT #{limit}")
    List<VerifyEventOutbox> selectPendingBatch(@Param("limit") int limit, @Param("maxRetry") int maxRetry);

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

    /**
     * 分块批量投递成功标记（条件含 status='PENDING'：并发下只生效一次，重复标记幂等）.
     *
     * <p>仅当 {@code verify.outbox.relay-batch-mark-enabled=true} 时由
     * relay 局部累积后分块调用。
     * 调用方必须拦截空列表（禁止发出 IN ()）。</p>
     *
     * @param ids 待标记成功的事件主键列表
     * @return 实际受影响行数
     */
    @Update("<script>"
            + "UPDATE verify_event_outbox "
            + "SET status = 'SENT', sent_at = NOW() "
            + "WHERE status = 'PENDING' AND id IN "
            + "<foreach collection='ids' item='id' open='(' "
            + "separator=',' close=')'>"
            + "#{id}"
            + "</foreach>"
            + "</script>")
    int markSentBatch(@Param("ids") List<Long> ids);
}

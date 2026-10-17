package com.sportverify.verify.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sportverify.verify.entity.VerifyEventOutbox;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 校验事件 outbox Mapper（verify_db.verify_event_outbox，F03 本地消息表）。
 *
 * <p>行状态字面量（'PENDING'/'SENT'/'EXHAUSTED'）统一收拢为下列 SQL 形态常量拼接
 * （TASK-262 R4/G15：单引号形态入常量，注解 SQL 文本与收拢前逐字等价，
 * 由 VerifyEventOutboxMapperSqlContractTest 既有 SQL 契约测试钉守）。</p>
 */
public interface VerifyEventOutboxMapper extends BaseMapper<VerifyEventOutbox> {

    /** 待投递行状态（SQL 内联单引号形态，收拢前为各注解 SQL 内联字面量）. */
    String SQL_STATUS_PENDING = "'PENDING'";

    /** 已投递行状态（SQL 内联单引号形态）. */
    String SQL_STATUS_SENT = "'SENT'";

    /** 重试耗尽终态（SQL 内联单引号形态）. */
    String SQL_STATUS_EXHAUSTED = "'EXHAUSTED'";

    /**
     * 取一批待投递事件（按自增 id 顺序，先到先得；批量上限与重试上限由 relay 配置）。
     *
     * <p>资格条件含 {@code retry_count < maxRetry}：重试已耗尽的 PENDING 行不占发送批次，
     * 避免较小 ID 的耗尽行占满查询上限后永久遮挡后续可投递行；耗尽行本身保留原状态与原数据
     * 供人工处理（不删除、不重置计数、不改 eventId、不重投）。</p>
     */
    @Select("SELECT * FROM verify_event_outbox WHERE status = " + SQL_STATUS_PENDING
            + " AND retry_count < #{maxRetry} "
            + "ORDER BY id LIMIT #{limit}")
    List<VerifyEventOutbox> selectPendingBatch(@Param("limit") int limit, @Param("maxRetry") int maxRetry);

    /**
     * 投递成功标记（条件含 PENDING：并发下只生效一次，重复标记幂等）。
     */
    @Update("UPDATE verify_event_outbox SET status = " + SQL_STATUS_SENT + ", sent_at = NOW() "
            + "WHERE id = #{id} AND status = " + SQL_STATUS_PENDING)
    int markSent(@Param("id") Long id);

    /**
     * 投递失败计数 +1（行保留 PENDING，下轮 relay 再试）。
     */
    @Update("UPDATE verify_event_outbox SET retry_count = retry_count + 1 "
            + "WHERE id = #{id} AND status = " + SQL_STATUS_PENDING)
    int incrRetry(@Param("id") Long id);

    /**
     * 投递重试耗尽流转至 EXHAUSTED 终态（条件含 PENDING：并发下只生效一次，
     * 幂等；移出 PENDING 扫描集，消除毒丸死信的全表扫描 I/O 放大）。
     */
    @Update("UPDATE verify_event_outbox SET status = " + SQL_STATUS_EXHAUSTED + " "
            + "WHERE id = #{id} AND status = " + SQL_STATUS_PENDING)
    int markExhausted(@Param("id") Long id);

    /**
     * 分块批量投递成功标记（条件含 PENDING：并发下只生效一次，重复标记幂等）.
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
            + "SET status = " + SQL_STATUS_SENT + ", sent_at = NOW() "
            + "WHERE status = " + SQL_STATUS_PENDING + " AND id IN "
            + "<foreach collection='ids' item='id' open='(' "
            + "separator=',' close=')'>"
            + "#{id}"
            + "</foreach>"
            + "</script>")
    int markSentBatch(@Param("ids") List<Long> ids);

    /**
     * B20 毒丸死信列表（管理端查看）：按 id 倒序返回 EXHAUSTED 终态行，供人工重放处置。
     *
     * @param limit 拉取上限（调用方已在 controller cap，表中含量可能很大）
     */
    @Select("SELECT * FROM verify_event_outbox WHERE status = " + SQL_STATUS_EXHAUSTED + " "
            + "ORDER BY id DESC LIMIT #{limit}")
    List<VerifyEventOutbox> selectExhausted(@Param("limit") int limit);

    /**
     * B20 毒丸人工重放：EXHAUSTED 终态行重置为 PENDING 并清空重试计数，重新进入 relay 扫描集。
     * 条件含 EXHAUSTED：非死信行不做操作（幂等，仅毒丸可重放）。
     *
     * @param id 目标事件主键
     * @return 受影响行数（1 = 重放成功；0 = 不存在或非 EXHAUSTED）
     */
    @Update("UPDATE verify_event_outbox SET status = " + SQL_STATUS_PENDING + ", retry_count = 0 "
            + "WHERE id = #{id} AND status = " + SQL_STATUS_EXHAUSTED)
    int replayExhausted(@Param("id") Long id);

    /**
     * B20 历史已投递行物理分批清理：仅删除已投递（SENT）且投递时间早于阈值的行，
     * 逐批上限 {@code limit} 限流单次删除规模（PENDING/EXHAUSTED 行永不触碰）。
     *
     * @param before 清理截止时间（仅删除 sent_at &lt; before 的 SENT 行）
     * @param limit  单批删除行数上限
     * @return 实际删除行数
     */
    @Delete("DELETE FROM verify_event_outbox WHERE status = " + SQL_STATUS_SENT
            + " AND sent_at < #{before} LIMIT #{limit}")
    int cleanSentEvents(@Param("before") LocalDateTime before, @Param("limit") int limit);
}

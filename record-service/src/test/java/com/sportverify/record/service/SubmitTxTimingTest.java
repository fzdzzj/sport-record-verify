package com.sportverify.record.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportverify.record.config.TimingHikariDataSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提交分段计时插桩单元测试（TASK-140：有界保留 / 回滚不计成功样本 / connWait 桥接）。
 *
 * <p>纯单测：不依赖 MySQL / Spring 上下文。池包装类的获取路径用空配置 Hikari
 * （无 jdbcUrl，getConnection 必然快速失败）驱动——失败同样经过计时边界，
 * 样本只进入被武装请求的 Rec，聚合只统计显式 flush 的成功收口。</p>
 */
class SubmitTxTimingTest {

    private final SubmitTxTiming timing = new SubmitTxTiming();
    private final ObjectMapper json = new ObjectMapper();

    /** 关闭态：Rec 为共享 no-op，span/flush 零样本零分段 */
    @Test
    void disabledRec_isNoop_zeroSamples() throws Exception {
        SubmitTxTiming.Rec rec = timing.begin(false);
        rec.span("select", System.nanoTime() - 1_000);
        rec.flush();
        JsonNode node = json.readTree(timing.snapshot());
        assertEquals(0, node.get("samples").asLong());
        assertFalse(node.has("select"), "关闭态不得产生任何分段样本");
    }

    /** 回滚不计成功样本：只有显式 flush（afterCommit）才并入聚合 */
    @Test
    void flushOnlyOnAfterCommit_rollbackDrops() throws Exception {
        SubmitTxTiming.Rec rec = timing.begin(true);
        rec.span("select", System.nanoTime() - 1_000);
        JsonNode noFlush = json.readTree(timing.snapshot());
        assertEquals(0, noFlush.get("samples").asLong(), "未收口（回滚）不得计入样本");
        rec.flush();
        JsonNode flushed = json.readTree(timing.snapshot());
        assertEquals(1, flushed.get("samples").asLong());
        assertEquals(1, flushed.get("select").get("n").asInt());
    }

    /** 开启态内存有界：超过上限后各段只保留最近 cap 个样本（淘汰最旧），snapshot 声明 cap */
    @Test
    void retention_boundedAtDeclaredCap() throws Exception {
        for (int i = 0; i < SubmitTxTiming.SAMPLE_CAP + 500; i++) {
            SubmitTxTiming.Rec rec = timing.begin(true);
            rec.span("select", System.nanoTime() - 1_000);
            rec.flush();
        }
        JsonNode node = json.readTree(timing.snapshot());
        assertEquals(SubmitTxTiming.SAMPLE_CAP + 500, node.get("samples").asLong(), "收口总数不受限");
        assertEquals(SubmitTxTiming.SAMPLE_CAP, node.get("cap").asInt());
        assertEquals(SubmitTxTiming.SAMPLE_CAP, node.get("select").get("n").asInt(), "驻留样本必须封顶在 cap");
    }

    /** connWait 桥接：武装后经获取点回调记入该请求，解除后不再记录；其他段不受影响 */
    @Test
    void connWait_bridge_recordsOnlyWhileArmed() throws Exception {
        try {
            SubmitTxTiming.Rec rec = timing.begin(true);
            SubmitTxTiming.armConnWait(rec);
            assertTrue(SubmitTxTiming.connWaitArmed());
            SubmitTxTiming.recordConnWait(1_234_567);
            rec.span("select", System.nanoTime() - 2_000);
            SubmitTxTiming.disarmConnWait();
            assertFalse(SubmitTxTiming.connWaitArmed());
            SubmitTxTiming.recordConnWait(9_999_999);
            rec.flush();
            JsonNode node = json.readTree(timing.snapshot());
            assertEquals(1, node.get("samples").asLong());
            assertEquals(1, node.get("connWait").get("n").asInt());
            assertEquals(1234, node.get("connWait").get("p50us").asLong(), "1_234_567ns → 1234us");
            assertEquals(1, node.get("select").get("n").asInt());
        } finally {
            SubmitTxTiming.disarmConnWait();
        }
    }

    /**
     * 池包装类端到端：武装线程上 getConnection（空配置池必然快速失败）仍走过计时边界并
     * 记入该请求；未武装线程同样调用不产生任何样本（关闭态/其他端点零样本）。
     */
    @Test
    void poolWrapper_timesArmedThread_only() throws Exception {
        TimingHikariDataSource ds = new TimingHikariDataSource();
        try {
            SubmitTxTiming.Rec armed = timing.begin(true);
            SubmitTxTiming.armConnWait(armed);
            assertThrows(Exception.class, ds::getConnection, "空配置池必须快速失败");
            SubmitTxTiming.disarmConnWait();
            SubmitTxTiming.Rec unarmed = timing.begin(true);
            assertThrows(Exception.class, ds::getConnection);
            armed.flush();
            unarmed.flush();
            JsonNode node = json.readTree(timing.snapshot());
            assertEquals(1, node.get("samples").asLong(), "只有武装请求收口（未武装 Rec 无段样本，flush 短路）");
            assertEquals(1, node.get("connWait").get("n").asInt(), "只有武装请求记 connWait");
        } finally {
            SubmitTxTiming.disarmConnWait();
        }
    }
}

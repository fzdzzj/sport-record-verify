package com.sportverify.verify.mq;

import com.sportverify.verify.mq.RelayDiagnostics.BatchSummary;
import com.sportverify.verify.mq.RelayDiagnostics.IdleSummary;
import org.junit.jupiter.api.Test;

import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * relay 周期级诊断单元测试（TASK-145）：覆盖开关关闭、成功批、失败批、空轮与锁竞争的有界汇总，
 * 以及分段耗时非负与残差口径。纯逻辑判定，不依赖日志框架与真实时钟。
 */
class RelayDiagnosticsTest {

    /** 可手动推进的单调时钟（纳秒），用于验证窗口有界性而不真等。 */
    private static final class FakeClock implements LongSupplier {
        private long nanos;

        @Override
        public long getAsLong() {
            return nanos;
        }

        void advance(long deltaNanos) {
            nanos += deltaNanos;
        }
    }

    private static final long WINDOW_NANOS = 1_000_000_000L; // 1s

    @Test
    void disabledSwitch_isNoOp_andReportsNothing() {
        RelayDiagnostics d = new RelayDiagnostics(false, WINDOW_NANOS, new FakeClock());

        assertFalse(d.enabled());
        assertTrue(d.lockSkipped().isEmpty(), "关闭时锁竞争汇总必须为空");
        assertTrue(d.emptyRound().isEmpty(), "关闭时空轮汇总必须为空");
        assertTrue(d.batch(5, 5, 0, 0, 1, 2, 3, 4, 5, 20, 22, 1).isEmpty(),
                "关闭时批次汇总必须为空（不产生批次日志）");
    }

    @Test
    void batch_reportsCountsAndNonNegativeSegments() {
        FakeClock clock = new FakeClock();
        RelayDiagnostics d = new RelayDiagnostics(true, WINDOW_NANOS, clock);

        BatchSummary s = d.batch(3, 2, 1, 0, 1, 2, 30, 4, 5, 50, 60, 1).orElseThrow();

        assertEquals(3, s.rows());
        assertEquals(2, s.success());
        assertEquals(1, s.failed());
        assertEquals(0, s.exhausted());
        assertEquals(1, s.lockWaitMs());
        assertEquals(2, s.selectMs());
        assertEquals(30, s.sendMs());
        assertEquals(4, s.markMs());
        assertEquals(5, s.incrRetryMs());
        assertEquals(50, s.lockProcessingMs());
        assertEquals(60, s.lockHoldMs());
        // residual = 锁内处理段 - (取批 + 发送 + 标记 + 失败计数) = 50 - 41
        assertEquals(9, s.residualMs());
        assertEquals(0, s.emptyRounds());
        assertEquals(0, s.lockSkips());
    }

    @Test
    void batch_residualNeverNegative_whenSegmentsExceedHold() {
        RelayDiagnostics d = new RelayDiagnostics(true, WINDOW_NANOS, new FakeClock());

        // 人为给出超过锁内处理段总时长的分段合计：残差必须钳到 0，不能出现负墙钟
        BatchSummary s = d.batch(1, 1, 0, 0, 0, 100, 100,
                100, 0, 5, 6, 1).orElseThrow();

        assertEquals(0, s.residualMs());
    }

    @Test
    void batch_includesIdleCountersSinceLastSummary_andResetsThem() {
        FakeClock clock = new FakeClock();
        RelayDiagnostics d = new RelayDiagnostics(true, WINDOW_NANOS, clock);

        IdleSummary first = d.emptyRound().orElseThrow(); // 首次即输出，随后窗口内只累加
        assertEquals(1, first.emptyRounds());
        assertEquals(0, first.lockSkips());

        clock.advance(10);
        assertTrue(d.lockSkipped().isEmpty(), "窗口内锁竞争不逐轮输出");
        clock.advance(10);
        assertTrue(d.emptyRound().isEmpty(), "窗口内空轮不逐轮输出");

        clock.advance(10);
        BatchSummary s = d.batch(2, 2, 0, 0, 0, 1, 1, 1, 0, 5, 6, 1).orElseThrow();
        assertEquals(1, s.emptyRounds(), "批次汇总带上自上次汇总以来的空轮计数");
        assertEquals(1, s.lockSkips(), "批次汇总带上自上次汇总以来的锁竞争计数");

        clock.advance(10);
        assertTrue(d.emptyRound().isEmpty(), "批次汇总后重新开始窗口计时");
    }

    @Test
    void emptyRounds_areSummarizedAtBoundedFrequency() {
        FakeClock clock = new FakeClock();
        RelayDiagnostics d = new RelayDiagnostics(true, WINDOW_NANOS, clock);

        assertEquals(1, d.emptyRound().orElseThrow().emptyRounds(), "首轮即输出一条");

        clock.advance(100);
        assertTrue(d.emptyRound().isEmpty());
        clock.advance(100);
        assertTrue(d.emptyRound().isEmpty());

        clock.advance(WINDOW_NANOS); // 越过窗口
        IdleSummary s = d.emptyRound().orElseThrow();
        assertEquals(3, s.emptyRounds(), "窗口内的空轮只累加，到点才汇总");
        assertEquals(0, s.lockSkips());

        clock.advance(1);
        assertTrue(d.emptyRound().isEmpty(), "输出后重新开始窗口计时");
    }

    @Test
    void lockSkips_areSummarizedAtBoundedFrequency() {
        FakeClock clock = new FakeClock();
        RelayDiagnostics d = new RelayDiagnostics(true, WINDOW_NANOS, clock);

        IdleSummary s = d.lockSkipped().orElseThrow();
        assertEquals(1, s.lockSkips());

        clock.advance(10);
        assertTrue(d.lockSkipped().isEmpty());
        clock.advance(10);
        assertTrue(d.lockSkipped().isEmpty());

        clock.advance(WINDOW_NANOS);
        IdleSummary again = d.lockSkipped().orElseThrow();
        assertEquals(3, again.lockSkips());
        assertEquals(0, again.emptyRounds(), "空轮与锁竞争计数分账");
    }
}
package com.sportverify.verify.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则快照编解码验收用例（规范「规则版本化」：快照跨时间稳定可读）。
 *
 * <p>覆盖：阈值往返一致、空 JSON 回落默认值、未知字段向前兼容（旧代码可读新快照）、
 * 非法快照显式抛出（调用方降级基线）。</p>
 */
class RulesSnapshotCodecTest {

    /** 往返：序列化→反序列化后规则与阈值逐项一致 */
    @Test
    void roundTrip_preservesThresholds() {
        VerifyProperties props = new VerifyProperties();
        props.getRules().setVDrift(25.0);
        props.getRules().getR1().setSpeed(6.6);
        props.getRules().getR1().setWindowPoints(12);
        props.getRules().getR2().setAccel(4.0);
        props.getRules().getR3().setSegmentRatio(0.5);
        props.getRules().getR4().setMaxRatio(2.5);
        props.getPolicy().setSoftOnlyReject(false);

        VerifyProperties parsed = RulesSnapshotCodec.fromJson(RulesSnapshotCodec.toJson(props));

        assertEquals(25.0, parsed.getRules().getVDrift());
        assertEquals(6.6, parsed.getRules().getR1().getSpeed());
        assertEquals(12, parsed.getRules().getR1().getWindowPoints());
        assertEquals(4.0, parsed.getRules().getR2().getAccel());
        assertEquals(0.5, parsed.getRules().getR3().getSegmentRatio());
        assertEquals(2.5, parsed.getRules().getR4().getMaxRatio());
        assertFalse(parsed.getPolicy().isSoftOnlyReject());
    }

    /** 空 JSON：缺省字段回落本地默认值（与审批版 §5.2 一致） */
    @Test
    void emptyJson_fallsBackToDefaults() {
        VerifyProperties parsed = RulesSnapshotCodec.fromJson("{}");
        assertEquals(5.5, parsed.getRules().getR1().getSpeed());
        assertEquals(20.0, parsed.getRules().getVDrift());
        assertTrue(parsed.getPolicy().isSoftOnlyReject());
    }

    /** 向前兼容：新旧版本快照字段集不一致时忽略未知字段，不阻断解析 */
    @Test
    void unknownFields_ignored() {
        VerifyProperties parsed = RulesSnapshotCodec.fromJson(
                "{\"rules\":{\"r1\":{\"speed\":6.0,\"futureField\":1}},\"legacy\":true}");
        assertEquals(6.0, parsed.getRules().getR1().getSpeed());
    }

    /** 非法快照显式抛出：由路由服务记日志并降级 Nacos 实时配置 */
    @Test
    void brokenJson_throws() {
        assertThrows(IllegalArgumentException.class, () -> RulesSnapshotCodec.fromJson("{oops"));
        assertThrows(IllegalArgumentException.class, () -> RulesSnapshotCodec.fromJson(null));
        assertThrows(IllegalArgumentException.class, () -> RulesSnapshotCodec.fromJson("  "));
    }
}

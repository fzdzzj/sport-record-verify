package com.sportverify.gateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Sentinel 网关流控规则 converter 纯逻辑测试（TASK-254 E16 空集护栏，spec §3 R5 三用例）。
 *
 * <p>toGatewayFlowRules 调整为包私有可见性（C-02 登记实际形态），同包直调；Nacos 真实
 * 推送链路本机无 Nacos 不实测（spec §5 预注册 2），converter 为纯逻辑按三用例覆盖：合法空
 * 数组 → null（空规则集视为无效更新，保持上一版）、非 JSON 垃圾 → null（既有行为回归）、
 * 合法两条 → size==2。</p>
 */
class SentinelGatewayRuleConfigTest {

    private final SentinelGatewayRuleConfig config = new SentinelGatewayRuleConfig();

    /** R5 用例 1：合法空 JSON 数组 → null（不得静默清空兜底规则） */
    @Test
    void emptyJsonArray_returnsNull() {
        assertNull(config.toGatewayFlowRules("[]"));
    }

    /** R5 用例 2：非 JSON 垃圾 → null（既有解析失败行为回归，保持上一版不抖动） */
    @Test
    void garbageJson_returnsNull() {
        assertNull(config.toGatewayFlowRules("{oops"));
        assertNull(config.toGatewayFlowRules("not-json"));
    }

    /** R5 用例 3：合法两条规则 → Set size==2（普通更新正常生效） */
    @Test
    void twoValidRules_returnsSizeTwo() {
        String json = "[{\"resource\":\"route-user-service\",\"count\":100,\"intervalSec\":1,\"grade\":1},"
                + "{\"resource\":\"route-record-service\",\"count\":50,\"intervalSec\":1,\"grade\":1}]";
        Set<GatewayFlowRule> rules = config.toGatewayFlowRules(json);
        assertNotNull(rules);
        assertEquals(2, rules.size());
    }
}

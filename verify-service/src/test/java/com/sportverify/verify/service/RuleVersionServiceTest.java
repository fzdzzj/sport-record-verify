package com.sportverify.verify.service;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.sportverify.common.exception.BizException;
import com.sportverify.verify.config.RulesSnapshotCodec;
import com.sportverify.verify.config.VerifyProperties;
import com.sportverify.verify.dto.RuleVersionCreateRequest;
import com.sportverify.verify.entity.RuleVersion;
import com.sportverify.verify.entity.RuleVersionStatus;
import com.sportverify.verify.mapper.RuleVersionMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 规则灰度路由与版本生命周期验收用例（tasks.json T3-T5 / 规范「灰度采样路由」
 * 「规则快照隔离」「秒级回滚」「全量发布」）。
 *
 * <p>基线=Nacos 实时配置（R1 速度 5.5），灰度快照=R1 速度 6.6，二者可区分以验证分支来源。
 * Mapper 打桩模拟 DB 状态，Caffeine 用真实实例验证缓存失效时机。</p>
 */
class RuleVersionServiceTest {

    /** 基线阈值（Nacos 实时配置代身）：R1 速度默认 5.5 */
    private final VerifyProperties live = new VerifyProperties();
    private final RuleVersionMapper mapper = mock(RuleVersionMapper.class);
    private final RuleVersionService service = new RuleVersionService(mapper,
            Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(1)).build(), live);

    /** 构造 GRAY 版本：快照 R1 速度 6.6（与基线可区分） */
    private static RuleVersion grayVersion(int ratio) {
        VerifyProperties props = new VerifyProperties();
        props.getRules().getR1().setSpeed(6.6);
        RuleVersion v = new RuleVersion();
        v.setId(2L);
        v.setVersion("v-test");
        v.setStatus(RuleVersionStatus.GRAY.getCode());
        v.setGrayRatio(ratio);
        v.setRulesJson(RulesSnapshotCodec.toJson(props));
        return v;
    }

    private static RuleVersionCreateRequest request(String version, int ratio, VerifyProperties rules) {
        RuleVersionCreateRequest req = new RuleVersionCreateRequest();
        req.setVersion(version);
        req.setGrayRatio(ratio);
        req.setRules(rules);
        return req;
    }

    // ===== 灰度采样路由（规范「命中灰度」「未命中灰度」） =====

    /** 命中灰度：gray_ratio=10，userId%100=5（<10）→ 使用灰度快照（6.6）而非基线 */
    @Test
    void grayHit_belowRatio_usesSnapshot() {
        when(mapper.selectSamplingGray()).thenReturn(grayVersion(10));
        VerifyProperties props = service.getActiveRulesForUser(105L); // 105%100=5
        assertEquals(6.6, props.getRules().getR1().getSpeed());
    }

    /** 未命中灰度：userId%100=50（≥10）→ 使用基线（Nacos 实时配置本尊，非副本） */
    @Test
    void grayMiss_atOrAboveRatio_usesBaseline() {
        when(mapper.selectSamplingGray()).thenReturn(grayVersion(10));
        assertSame(live, service.getActiveRulesForUser(150L)); // 150%100=50
    }

    /** 采样边界：比例上界开区间——9 命中、10 不命中 */
    @Test
    void grayBoundary_exclusiveUpper() {
        when(mapper.selectSamplingGray()).thenReturn(grayVersion(10));
        assertEquals(6.6, service.getActiveRulesForUser(109L).getRules().getR1().getSpeed()); // 9 < 10
        assertEquals(5.5, service.getActiveRulesForUser(110L).getRules().getR1().getSpeed()); // 10 ≥ 10
    }

    /** gray_ratio=0 全走基线：即使防御性读到 0 比例行（正常由 SQL 过滤），比较逻辑也须挡住 */
    @Test
    void grayRatioZero_allBaseline() {
        when(mapper.selectSamplingGray()).thenReturn(grayVersion(0));
        assertEquals(5.5, service.getActiveRulesForUser(105L).getRules().getR1().getSpeed());
        assertEquals(5.5, service.getActiveRulesForUser(5L).getRules().getR1().getSpeed());
    }

    /** 无灰度版本（回滚后 DB 态）：全部基线 */
    @Test
    void noGrayVersion_allBaseline() {
        when(mapper.selectSamplingGray()).thenReturn(null);
        assertEquals(5.5, service.getActiveRulesForUser(105L).getRules().getR1().getSpeed());
    }

    /** 采样稳定性（规范「采样稳定性」）：同一用户重复触发，分支恒定不随请求时序抖动 */
    @Test
    void samplingStable_sameUserSameBranch() {
        when(mapper.selectSamplingGray()).thenReturn(grayVersion(10));
        for (int i = 0; i < 5; i++) {
            assertEquals(6.6, service.getActiveRulesForUser(105L).getRules().getR1().getSpeed(),
                    "命中用户须恒定走灰度");
            assertEquals(5.5, service.getActiveRulesForUser(150L).getRules().getR1().getSpeed(),
                    "未命中用户须恒定走基线");
        }
    }

    // ===== 秒级回滚与全量发布 =====

    /** 回滚生效（规范「回滚生效」）：命中灰度后置 gray_ratio=0，路由缓存失效，立即回归基线 */
    @Test
    void rollback_ratioZero_stopsSamplingImmediately() {
        RuleVersion gray = grayVersion(10);
        when(mapper.selectSamplingGray()).thenReturn(gray);
        assertEquals(6.6, service.getActiveRulesForUser(105L).getRules().getR1().getSpeed()); // 先命中灰度

        when(mapper.selectById(2L)).thenReturn(gray);
        when(mapper.updateGrayRatio(2L, 0)).thenReturn(1);
        when(mapper.selectSamplingGray()).thenReturn(null); // 回滚后 DB：ratio=0 不再满足采样查询
        service.updateGrayRatio(2L, 0);

        assertEquals(5.5, service.getActiveRulesForUser(105L).getRules().getR1().getSpeed(),
                "回滚后须立即回归基线（本实例缓存失效）");
    }

    /** 全量生效（规范「全量生效」「旧版本退役」）：activate 后全部用户（含原未命中者）走新基线 */
    @Test
    void activate_allUsersOnNewBaseline() {
        RuleVersion gray = grayVersion(10);
        when(mapper.selectSamplingGray()).thenReturn(gray);
        when(mapper.lockNonRetiredForUpdate()).thenReturn(List.of(2L));
        when(mapper.selectById(2L)).thenReturn(gray);
        when(mapper.retireOthers(2L)).thenReturn(1);
        when(mapper.promoteToActive(2L)).thenAnswer(inv -> {
            gray.setStatus(RuleVersionStatus.ACTIVE.getCode()); // 模拟 DB 更新对后续查询可见
            gray.setGrayRatio(100);
            return 1;
        });

        RuleVersion activated = service.activate(2L);
        assertEquals(RuleVersionStatus.ACTIVE.getCode(), activated.getStatus());
        assertEquals(100, activated.getGrayRatio());
        verify(mapper).retireOthers(2L); // 旧版本退役

        when(mapper.selectSamplingGray()).thenReturn(null); // 全量后无采样中版本
        when(mapper.selectActive()).thenReturn(gray);       // 基线 = ACTIVE 快照
        assertEquals(6.6, service.getActiveRulesForUser(150L).getRules().getR1().getSpeed(),
                "全量后原未命中用户也走新规则");
        assertEquals(6.6, service.getActiveRulesForUser(105L).getRules().getR1().getSpeed());
    }

    // ===== 创建与参数约束 =====

    /** 创建版本（规范「创建灰度版本」）：快照落库 + GRAY + 比例 10 */
    @Test
    void createVersion_snapshotsAndGrays() {
        when(mapper.countOtherSampling(null)).thenReturn(0L);
        RuleVersion created = service.createVersion(request("v20260912", 10, live));
        assertEquals("v20260912", created.getVersion());
        assertEquals(RuleVersionStatus.GRAY.getCode(), created.getStatus());
        assertEquals(10, created.getGrayRatio());
        assertTrue(created.getRulesJson().contains("\"r1\""), "rules_json 须含规则快照");
    }

    /** 至多一个采样中版本：已有灰度在采样时，携带正比例的创建被拒绝 */
    @Test
    void createVersion_withSamplingConflict_rejected() {
        when(mapper.countOtherSampling(null)).thenReturn(1L);
        assertThrows(BizException.class, () -> service.createVersion(request("v2", 10, live)));
    }

    /** 版本号唯一键冲突 → 显式业务异常（非裸 DuplicateKey） */
    @Test
    void createVersion_duplicateVersionNo_rejected() {
        when(mapper.countOtherSampling(null)).thenReturn(0L);
        doThrow(new DuplicateKeyException("dup")).when(mapper).insert(any(RuleVersion.class));
        BizException e = assertThrows(BizException.class,
                () -> service.createVersion(request("dup", 0, live)));
        assertTrue(e.getMessage().contains("dup"));
    }

    /** 灰度比例越界拒绝 */
    @Test
    void updateGrayRatio_outOfRange_rejected() {
        assertThrows(BizException.class, () -> service.updateGrayRatio(2L, 150));
    }

    /** 仅 GRAY 可调灰度：ACTIVE（已全量）/RETIRED 均拒绝 */
    @Test
    void updateGrayRatio_onActive_rejected() {
        RuleVersion active = grayVersion(100);
        active.setStatus(RuleVersionStatus.ACTIVE.getCode());
        when(mapper.selectById(2L)).thenReturn(active);
        assertThrows(BizException.class, () -> service.updateGrayRatio(2L, 0));
    }

    // ===== 端到端（服务级全流程） =====

    /**
     * 端到端：创建版本(gray=0) → 调灰度 10 → 命中/未命中分支 → 异常回滚 0 → 全量发布。
     */
    @Test
    void e2e_create_gray_hitMiss_rollback_activate() {
        // 1 创建：gray=0 不采样，全部基线
        VerifyProperties candidate = new VerifyProperties();
        candidate.getRules().getR1().setSpeed(6.6);
        when(mapper.countOtherSampling(null)).thenReturn(0L);
        doAnswer(inv -> {
            inv.<RuleVersion>getArgument(0).setId(2L);
            return 1;
        }).when(mapper).insert(any(RuleVersion.class));
        RuleVersion created = service.createVersion(request("v-e2e", 0, candidate));
        assertEquals(2L, created.getId().longValue());
        assertEquals(5.5, service.getActiveRulesForUser(105L).getRules().getR1().getSpeed());

        // 2 调灰度 10：命中走快照 / 未命中走基线
        when(mapper.selectById(2L)).thenReturn(created);
        when(mapper.updateGrayRatio(2L, 10)).thenReturn(1);
        created.setGrayRatio(10);
        when(mapper.selectSamplingGray()).thenReturn(created);
        service.updateGrayRatio(2L, 10);
        assertEquals(6.6, service.getActiveRulesForUser(105L).getRules().getR1().getSpeed());
        assertEquals(5.5, service.getActiveRulesForUser(150L).getRules().getR1().getSpeed());

        // 3 异常回滚 0：立即回归基线
        when(mapper.updateGrayRatio(2L, 0)).thenReturn(1);
        created.setGrayRatio(0);
        when(mapper.selectSamplingGray()).thenReturn(null);
        service.updateGrayRatio(2L, 0);
        assertEquals(5.5, service.getActiveRulesForUser(105L).getRules().getR1().getSpeed());

        // 4 全量发布：全部用户（含未命中者）走新规则，旧版本退役
        when(mapper.lockNonRetiredForUpdate()).thenReturn(List.of(2L));
        when(mapper.retireOthers(2L)).thenReturn(1);
        when(mapper.promoteToActive(2L)).thenAnswer(inv -> {
            created.setStatus(RuleVersionStatus.ACTIVE.getCode());
            created.setGrayRatio(100);
            return 1;
        });
        when(mapper.selectActive()).thenReturn(created);
        service.activate(2L);
        assertEquals(6.6, service.getActiveRulesForUser(150L).getRules().getR1().getSpeed());
        verify(mapper).retireOthers(2L);
    }
}

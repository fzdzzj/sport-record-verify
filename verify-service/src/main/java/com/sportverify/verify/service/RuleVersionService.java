package com.sportverify.verify.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.ResultCode;
import com.sportverify.verify.config.RulesSnapshotCodec;
import com.sportverify.verify.config.VerifyProperties;
import com.sportverify.verify.dto.RuleVersionCreateRequest;
import com.sportverify.verify.entity.RuleVersion;
import com.sportverify.verify.entity.RuleVersionStatus;
import com.sportverify.verify.mapper.RuleVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Supplier;

/**
 * 规则灰度路由服务（规范「灰度采样路由」「规则快照隔离」，审批版 §7.4）。
 *
 * <p>路由规则：{@code floorMod(userId,100) < gray_ratio} 走灰度版本库内快照，
 * 否则走基线（ACTIVE 版本快照，尚无版本时回退 Nacos 实时配置）。
 * 同一 userId 的采样键恒定，分支不随请求时序/实例抖动。</p>
 *
 * <p>读路径全部经 Caffeine（写入 1 分钟过期，即规范「≤60s」上限）：
 * 路由行两把 key（灰度/基线）+ 每版本快照 key（{@code verify:rules:v{version}}），
 * 命中即不查库。版本生命周期操作（调比例/全量）负责失效路由 key——本实例立即生效，
 * 其余实例靠 TTL 收敛，这是回滚延迟的上界。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleVersionService {

    /** 灰度路由缓存 key：缓存「当前采样中灰度版本」整行（含 gray_ratio），回滚/调比例后必须失效 */
    private static final String KEY_GRAY_ROUTE = "verify:rules:gray";

    /** 基线路由缓存 key：缓存「当前 ACTIVE 版本」整行，全量发布后必须失效 */
    private static final String KEY_ACTIVE_ROUTE = "verify:rules:active";

    /** 版本快照缓存 key 前缀：key 含版本号（rules:v{version}），版本间快照互不混淆 */
    private static final String KEY_SNAPSHOT_PREFIX = "verify:rules:v";

    /** 「无路由」哨兵：Cache 不缓存 null，空结果放哨兵，避免无版本阶段每次判定都查库 */
    private static final String NO_ROUTE = "-";

    private final RuleVersionMapper ruleVersionMapper;
    private final Cache<String, Object> caffeineCache;
    private final VerifyProperties verifyProperties;

    /**
     * 取 userId 当前应用的规则集（校验入口每次判定调用）：
     * <ol>
     *   <li>存在采样中的灰度版本且 floorMod(userId,100) &lt; gray_ratio → 库内灰度快照；</li>
     *   <li>否则基线：ACTIVE 版本快照；尚无版本 → Nacos 实时配置。</li>
     * </ol>
     *
     * <p>userId 为空（历史数据无归属）时防御性走基线，不让灰度路由阻断判定。</p>
     */
    public VerifyProperties getActiveRulesForUser(Long userId) {
        if (userId != null) {
            RuleVersion gray = cachedRoute(KEY_GRAY_ROUTE, ruleVersionMapper::selectSamplingGray);
            if (gray != null && gray.getGrayRatio() != null
                    && Math.floorMod(userId, 100) < gray.getGrayRatio()) {
                return snapshotRules(gray);
            }
        }
        return baselineRules();
    }

    // ===== 版本生命周期（管理端：创建 → 调灰度/回滚 → 全量发布） =====

    /**
     * 创建规则版本（规范「创建灰度版本」）：快照 rules_json 落库 + 状态 GRAY + 初始灰度比例。
     *
     * <p>快照来源优先级：请求携带 rules（管理员直接下发新阈值）&gt; 当前基线
     * （ACTIVE 快照 / Nacos 实时值）。携带正灰度比例时校验「至多一个采样中版本」，
     * 避免两个灰度版本争抢同一段 userId%100 采样桶；版本号唯一键兜底重复创建。</p>
     */
    public RuleVersion createVersion(RuleVersionCreateRequest request) {
        int grayRatio = request.getGrayRatio() == null ? 0 : request.getGrayRatio();
        if (grayRatio < 0 || grayRatio > 100) {
            throw new BizException(ResultCode.RULE_VERSION_CONFLICT, "灰度比例须在 0-100");
        }
        if (grayRatio > 0 && ruleVersionMapper.countOtherSampling(null) > 0) {
            throw new BizException(ResultCode.RULE_VERSION_CONFLICT,
                    "已有灰度版本在采样，请先回滚（gray_ratio=0）或全量发布");
        }
        VerifyProperties snapshot = request.getRules() != null ? request.getRules() : baselineRules();
        RuleVersion version = new RuleVersion();
        version.setVersion(resolveVersionNo(request.getVersion()));
        version.setRulesJson(RulesSnapshotCodec.toJson(snapshot));
        version.setGrayRatio(grayRatio);
        version.setStatus(RuleVersionStatus.GRAY.getCode());
        version.setCreatedAt(LocalDateTime.now());
        try {
            ruleVersionMapper.insert(version);
        } catch (DuplicateKeyException e) {
            throw new BizException(ResultCode.RULE_VERSION_CONFLICT, "版本号已存在：" + version.getVersion());
        }
        invalidateRouteCache();
        return version;
    }

    /**
     * 调整灰度比例（规范「秒级回滚」）：gray_ratio=0 即回滚——本实例立即失效路由缓存，
     * 其余实例 ≤60s TTL 收敛，新版本不再被采样，基线不受影响。
     *
     * <p>仅 GRAY 可调，且 UPDATE 带 status=0 乐观条件：与并发全量发布冲突影响 0 行即报错，
     * 避免「比例已改但版本已全量」的脏状态。</p>
     */
    public RuleVersion updateGrayRatio(Long id, Integer grayRatio) {
        if (grayRatio == null || grayRatio < 0 || grayRatio > 100) {
            throw new BizException(ResultCode.RULE_VERSION_CONFLICT, "灰度比例须在 0-100");
        }
        RuleVersion version = mustGet(id);
        if (version.getStatus() != RuleVersionStatus.GRAY.getCode()) {
            throw new BizException(ResultCode.RULE_VERSION_STATUS_INVALID, "仅 GRAY 版本可调灰度比例");
        }
        if (grayRatio > 0 && ruleVersionMapper.countOtherSampling(id) > 0) {
            throw new BizException(ResultCode.RULE_VERSION_CONFLICT,
                    "已有灰度版本在采样，请先回滚（gray_ratio=0）或全量发布");
        }
        if (ruleVersionMapper.updateGrayRatio(id, grayRatio) == 0) {
            throw new BizException(ResultCode.RULE_VERSION_STATUS_INVALID, "并发冲突：版本状态已变更");
        }
        invalidateRouteCache();
        return mustGet(id);
    }

    /**
     * 全量发布（规范「全量发布」「版本状态约束」）：gray_ratio=100 + 状态 ACTIVE，
     * 旧 ACTIVE 与遗留 GRAY 一并置 RETIRED——此后全部用户路由到本版本快照（新基线）。
     *
     * <p>并发保证「同一时刻至多一个 ACTIVE」：事务内先 FOR UPDATE 锁全部未退役行
     * 串行化（后到者能看到先到者晋升的新 ACTIVE 并将其退役），晋升再带 status=0
     * 乐观条件双保险。重复发布幂等返回。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public RuleVersion activate(Long id) {
        ruleVersionMapper.lockNonRetiredForUpdate(); // 串行化并发状态迁移（必须事务内）
        RuleVersion version = mustGet(id);
        if (version.getStatus() == RuleVersionStatus.ACTIVE.getCode()) {
            return version; // 幂等：已全量
        }
        if (version.getStatus() != RuleVersionStatus.GRAY.getCode()) {
            throw new BizException(ResultCode.RULE_VERSION_STATUS_INVALID, "仅 GRAY 版本可全量发布");
        }
        ruleVersionMapper.retireOthers(id);
        if (ruleVersionMapper.promoteToActive(id) == 0) {
            throw new BizException(ResultCode.RULE_VERSION_STATUS_INVALID, "并发冲突：版本状态已变更");
        }
        invalidateRouteCache();
        return mustGet(id);
    }

    // ===== 私有工具 =====

    /**
     * 基线规则集：全量 ACTIVE 版本快照（「新版本变为基线」，全量后仍执行已验证的规则，
     * 不受 Nacos 瞬时变更影响）；尚无版本时回退 Nacos 实时配置（阈值可配的原路径）。
     */
    private VerifyProperties baselineRules() {
        RuleVersion active = cachedRoute(KEY_ACTIVE_ROUTE, ruleVersionMapper::selectActive);
        return active == null ? verifyProperties : snapshotRules(active);
    }

    /**
     * 版本快照 → 规则执行对象。缓存 key 含 version：快照落库后不可变，
     * 命中即不查库、不重复反序列化。快照损坏不阻断校验主流程：
     * 记错误日志（可观测告警点）并降级 Nacos 实时配置。
     */
    private VerifyProperties snapshotRules(RuleVersion version) {
        String key = KEY_SNAPSHOT_PREFIX + version.getVersion();
        Object cached = caffeineCache.getIfPresent(key);
        if (cached instanceof VerifyProperties props) {
            return props;
        }
        try {
            VerifyProperties props = RulesSnapshotCodec.fromJson(version.getRulesJson());
            caffeineCache.put(key, props);
            return props;
        } catch (Exception e) {
            log.error("规则快照解析失败，降级 Nacos 实时配置：version={}", version.getVersion(), e);
            return verifyProperties;
        }
    }

    /**
     * 失效灰度/基线路由缓存：生命周期操作后本实例立即生效，
     * 其余实例靠 ≤60s TTL 收敛（回滚延迟上界）。快照 key 不失效——快照落库后不可变。
     */
    private void invalidateRouteCache() {
        caffeineCache.invalidate(KEY_GRAY_ROUTE);
        caffeineCache.invalidate(KEY_ACTIVE_ROUTE);
    }

    /** 按 id 取版本，不存在报 4002（管理端显式 404 语义） */
    private RuleVersion mustGet(Long id) {
        RuleVersion version = ruleVersionMapper.selectById(id);
        if (version == null) {
            throw new BizException(ResultCode.RULE_VERSION_NOT_FOUND);
        }
        return version;
    }

    /** 版本号缺省自动生成 vyyyyMMddHHmmss（同秒重复由 uk_version 兜底报冲突） */
    private String resolveVersionNo(String requested) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim();
        }
        return "v" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
    }

    /**
     * 路由行缓存读取（灰度/基线各一把 key）：miss 查库回填，空结果放 NO_ROUTE 哨兵。
     * 注意缓存的行含 gray_ratio 快照——生命周期操作改库后必须失效对应 key，
     * 否则本实例在 TTL 内仍按旧比例采样。
     */
    private RuleVersion cachedRoute(String key, Supplier<RuleVersion> loader) {
        Object cached = caffeineCache.getIfPresent(key);
        if (cached instanceof RuleVersion rv) {
            return rv;
        }
        if (cached != null) {
            return null; // NO_ROUTE 哨兵：确认无路由
        }
        RuleVersion loaded = loader.get();
        caffeineCache.put(key, loaded != null ? loaded : NO_ROUTE);
        return loaded;
    }
}

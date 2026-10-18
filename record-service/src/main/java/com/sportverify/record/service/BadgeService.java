package com.sportverify.record.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.sportverify.api.record.RecordStatus;
import com.sportverify.api.record.SportType;
import com.sportverify.api.record.dto.MyStreakDTO;
import com.sportverify.api.record.dto.MyStatsDTO;
import com.sportverify.record.dto.BadgeDTO;
import com.sportverify.record.entity.SportRecord;
import com.sportverify.record.entity.UserBadge;
import com.sportverify.record.enums.BadgeType;
import com.sportverify.record.mapper.SportRecordMapper;
import com.sportverify.record.mapper.UserBadgeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 运动徽章与成就服务（TASK-269，读时结算 + 增量幂等持久化）。
 *
 * <p>解锁模式为 pull-on-read auto-unlock：{@link #listMyBadges} 每次读取时对未解锁勋章
 * 基于已通过记录（PASSED / RE_PASSED，即「有效运动」口径）快速比对——单次维度取
 * {@link SportRecordMapper} 投影，连续打卡维度取 {@link SportRecordService#myStreak}
 * （历史最长连续天数，成就语义下追溯点亮），累计维度取 {@link SportRecordService#myStats}
 * （passCount / totalDistance 全量聚合）——满足即补插 {@code user_badge} 并捕获
 * {@link DuplicateKeyException} 幂等兜底。该模式对历史记录零丢失自动追溯，同时对
 * {@code submitRecord} 高并发写主干 0 侵入、0 性能损耗。</p>
 *
 * <p>达成判定统一口径：当前值 &ge; 目标值即解锁；夜间窗口按运动开始时间（null 回退
 * created_at，与 TASK-235 时间口径统一）的小时数 &ge;21 或 &lt;5 判定；运动类型维度仅
 * RUNNING / CYCLING 参与单次里程比较。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BadgeService {

    private final UserBadgeMapper userBadgeMapper;
    private final SportRecordMapper sportRecordMapper;
    private final SportRecordService sportRecordService;

    /** 通过终态码集（PASSED(2) / RE_PASSED(5)）：与 myStats 聚合入参、myStreak 入账口径同源 */
    private static final List<Integer> PASS_STATUS_CODES =
            List.of(RecordStatus.PASSED.getCode(), RecordStatus.RE_PASSED.getCode());

    /** 夜间窗口起始小时（含）：21:00 及以后 */
    private static final int NIGHT_START_HOUR = 21;

    /** 夜间窗口结束小时（不含）：05:00 之前 */
    private static final int NIGHT_END_HOUR = 5;

    /**
     * 我的勋章墙：全量 9 枚勋章与各自进度，读时结算自动解锁并追溯历史记录。
     *
     * <p>流程：查已持久化解锁映射 → 投影已通过记录算单次/夜间维度峰值 →
     * {@code myStreak}/{@code myStats} 算连续打卡与累计维度 → 未解锁且已达成者补插
     * （唯一键并发冲突捕获后重查，幂等）→ 组装全量 {@link BadgeDTO}（进度百分比
     * 0..100 整数截断封顶）。佩戴状态随持久化行返回，本方法不改变佩戴态。</p>
     *
     * @param userId 归属用户 ID（控制器按身份语义解析，非空）
     * @return 全量 9 枚勋章视图（枚举声明序）
     */
    public List<BadgeDTO> listMyBadges(Long userId) {
        Map<String, UserBadge> unlockedMap = userBadgeMapper.selectList(
                        new LambdaQueryWrapper<UserBadge>().eq(UserBadge::getUserId, userId)).stream()
                .collect(Collectors.toMap(UserBadge::getBadgeCode, Function.identity(), (first, second) -> first));

        BadgeProgress progress = measureProgress(userId);

        // 读时结算：未解锁且已达成 → 补插解锁行（unlocked_at 记当前时间，佩戴态默认不佩戴）
        LocalDateTime now = LocalDateTime.now();
        for (BadgeType type : BadgeType.values()) {
            if (unlockedMap.containsKey(type.name())) {
                continue;
            }
            if (currentValue(type, progress).compareTo(type.getTargetValue()) < 0) {
                continue;
            }
            UserBadge badge = new UserBadge();
            badge.setUserId(userId);
            badge.setBadgeCode(type.name());
            badge.setIsEquipped(0);
            badge.setUnlockedAt(now);
            try {
                userBadgeMapper.insert(badge);
                unlockedMap.put(type.name(), badge);
                log.info("勋章解锁：userId={}, badge={}, unlockedAt={}", userId, type.name(), now);
            } catch (DuplicateKeyException e) {
                // 并发读时结算命中唯一键 uk_user_badge：以库内已解锁行为准，幂等跳过
                UserBadge winner = userBadgeMapper.selectOne(new LambdaQueryWrapper<UserBadge>()
                        .eq(UserBadge::getUserId, userId)
                        .eq(UserBadge::getBadgeCode, type.name()));
                if (winner != null) {
                    unlockedMap.put(type.name(), winner);
                }
                log.info("并发解锁幂等跳过：userId={}, badge={}", userId, type.name());
            }
        }

        return Arrays.stream(BadgeType.values())
                .map(type -> toDTO(type, unlockedMap.get(type.name()), progress))
                .toList();
    }

    /**
     * 佩戴勋章（未解锁抛 400 业务校验异常）。
     *
     * <p>单事务两步 UPDATE：先将该用户全部勋章 {@code is_equipped=0}，再将目标勋章置 1，
     * 保证同一用户至多佩戴一枚（「先清后置」，事务内原子生效）。勋章编码不在域内或
     * 尚未解锁时抛 {@link IllegalArgumentException}，由控制器局部 handler 映射 400
     * （消息携带 BADGE_NOT_UNLOCKED 标识串区分未解锁场景）。</p>
     *
     * @param userId    归属用户 ID
     * @param badgeCode 勋章编码（{@code BadgeType} 枚举名）
     */
    @Transactional(rollbackFor = Exception.class)
    public void wearBadge(Long userId, String badgeCode) {
        BadgeType type = BadgeType.fromCode(badgeCode);
        if (type == null) {
            throw new IllegalArgumentException("勋章编码非法：" + badgeCode);
        }
        UserBadge unlocked = userBadgeMapper.selectOne(new LambdaQueryWrapper<UserBadge>()
                .eq(UserBadge::getUserId, userId)
                .eq(UserBadge::getBadgeCode, badgeCode));
        if (unlocked == null) {
            throw new IllegalArgumentException("BADGE_NOT_UNLOCKED：勋章尚未解锁，不可佩戴：" + type.getDisplayName());
        }
        userBadgeMapper.update(null, new LambdaUpdateWrapper<UserBadge>()
                .eq(UserBadge::getUserId, userId)
                .set(UserBadge::getIsEquipped, 0));
        userBadgeMapper.update(null, new LambdaUpdateWrapper<UserBadge>()
                .eq(UserBadge::getUserId, userId)
                .eq(UserBadge::getBadgeCode, badgeCode)
                .set(UserBadge::getIsEquipped, 1));
        log.info("佩戴勋章：userId={}, badge={}", userId, badgeCode);
    }

    /**
     * 卸下勋章（幂等）：将该用户全部勋章 {@code is_equipped=0}。
     *
     * @param userId 归属用户 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void unwearBadge(Long userId) {
        userBadgeMapper.update(null, new LambdaUpdateWrapper<UserBadge>()
                .eq(UserBadge::getUserId, userId)
                .set(UserBadge::getIsEquipped, 0));
        log.info("卸下勋章：userId={}", userId);
    }

    /**
     * 查询用户当前佩戴的勋章（社交外显读路径，未佩戴返回 null）。
     *
     * <p>纯只读：不触发读时结算与任何写操作（动态流逐作者调用，写放大不可接受）。
     * 进度字段按已解锁语义回填（currentValue=targetValue、progressPct=100），勋章墙
     * 的实时进度以 {@link #listMyBadges} 为准。</p>
     *
     * @param userId 归属用户 ID
     * @return 佩戴中的勋章视图；未佩戴或编码为脏数据时返回 null
     */
    public BadgeDTO getEquippedBadge(Long userId) {
        UserBadge equipped = userBadgeMapper.selectOne(new LambdaQueryWrapper<UserBadge>()
                .eq(UserBadge::getUserId, userId)
                .eq(UserBadge::getIsEquipped, 1));
        if (equipped == null) {
            return null;
        }
        BadgeType type = BadgeType.fromCode(equipped.getBadgeCode());
        if (type == null) {
            log.warn("佩戴行勋章编码不在域内，防御性返回空：userId={}, badgeCode={}", userId, equipped.getBadgeCode());
            return null;
        }
        return toDTO(type, equipped, null);
    }

    /**
     * 单次读取窗口内的进度度量（个人量级，与 myStreak 同款投影形态）。
     *
     * <p>单次维度（跑步/骑行/夜间峰值）由 {@code user_id=? AND status IN (2,5)} 投影
     * 内存聚合；连续打卡与累计维度按任务书指定数据源委托 {@code myStreak}/{@code myStats}，
     * 与个人仪表盘口径完全同源。</p>
     */
    private BadgeProgress measureProgress(Long userId) {
        List<SportRecord> passedRecords = sportRecordMapper.selectList(
                new LambdaQueryWrapper<SportRecord>()
                        .select(SportRecord::getSportType, SportRecord::getDistance,
                                SportRecord::getStartTime, SportRecord::getCreatedAt)
                        .eq(SportRecord::getUserId, userId)
                        .in(SportRecord::getStatus, PASS_STATUS_CODES));

        BadgeProgress progress = new BadgeProgress();
        for (SportRecord record : passedRecords) {
            BigDecimal distance = record.getDistance();
            if (distance == null) {
                continue;
            }
            Integer sportType = record.getSportType();
            if (sportType != null && SportType.RUNNING.getCode() == sportType) {
                progress.maxRunDistance = progress.maxRunDistance.max(distance);
            }
            if (sportType != null && SportType.CYCLING.getCode() == sportType) {
                progress.maxCyclingDistance = progress.maxCyclingDistance.max(distance);
            }
            LocalDateTime anchor = record.getStartTime() != null ? record.getStartTime() : record.getCreatedAt();
            if (anchor != null && isNightHour(anchor.getHour())) {
                progress.maxNightDistance = progress.maxNightDistance.max(distance);
            }
        }

        MyStreakDTO streak = sportRecordService.myStreak(userId);
        progress.longestStreakDays = streak == null || streak.getLongest() == null ? 0 : streak.getLongest();
        MyStatsDTO stats = sportRecordService.myStats(userId);
        progress.passCount = stats == null || stats.getPassCount() == null ? 0L : stats.getPassCount();
        progress.totalDistance = stats == null || stats.getTotalDistance() == null
                ? BigDecimal.ZERO : stats.getTotalDistance();
        return progress;
    }

    /** 夜间窗口判定：小时数 &ge;21（21:00-23:59）或 &lt;5（00:00-04:59） */
    private boolean isNightHour(int hour) {
        return hour >= NIGHT_START_HOUR || hour < NIGHT_END_HOUR;
    }

    /** 勋章当前进度值（公里数或天数，按勋章语义映射进度度量） */
    private BigDecimal currentValue(BadgeType type, BadgeProgress progress) {
        return switch (type) {
            case FIRST_STEP -> BigDecimal.valueOf(progress.passCount);
            case TEN_KM_RUN, HALF_MARATHON, FULL_MARATHON -> progress.maxRunDistance;
            case STREAK_7_DAYS, STREAK_30_DAYS -> BigDecimal.valueOf(progress.longestStreakDays);
            case HUNDRED_KM_CLUB -> progress.totalDistance;
            case CYCLING_MASTER -> progress.maxCyclingDistance;
            case NIGHT_RUNNER -> progress.maxNightDistance;
        };
    }

    /** 组装勋章视图：进度值来自实时度量；佩戴态与解锁时间来自持久化行（未解锁为 null） */
    private BadgeDTO toDTO(BadgeType type, UserBadge persisted, BadgeProgress progress) {
        BadgeDTO dto = new BadgeDTO();
        dto.setCode(type.name());
        dto.setName(type.getDisplayName());
        dto.setDescription(type.getDescription());
        dto.setCategory(type.getCategory());
        dto.setTier(type.getTier());
        dto.setIcon(type.getIcon());
        dto.setUnlocked(persisted != null);
        dto.setUnlockedAt(persisted == null ? null : persisted.getUnlockedAt());
        dto.setIsEquipped(persisted != null && Integer.valueOf(1).equals(persisted.getIsEquipped()));
        BigDecimal current;
        if (progress != null) {
            current = currentValue(type, progress);
        } else {
            // 外显读路径（getEquippedBadge）：已解锁语义下进度恒满格，不触发度量查询
            current = type.getTargetValue();
        }
        dto.setCurrentValue(current);
        dto.setTargetValue(type.getTargetValue());
        dto.setProgressPct(cappedPercent(current, type.getTargetValue()));
        return dto;
    }

    /** 进度百分比：0..100 整数截断封顶（GoalService 同款口径） */
    private int cappedPercent(BigDecimal current, BigDecimal target) {
        if (target == null || target.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        if (current.compareTo(target) >= 0) {
            return 100;
        }
        int pct = current.multiply(BigDecimal.valueOf(100))
                .divide(target, 0, RoundingMode.FLOOR)
                .intValue();
        return Math.max(0, Math.min(100, pct));
    }

    /** 进度度量载体（一次读取窗口内复用；字段包内可见由 BadgeService 独占维护） */
    private static final class BadgeProgress {

        /** 单次跑步有效里程峰值 */
        private BigDecimal maxRunDistance = BigDecimal.ZERO;

        /** 单次骑行有效里程峰值 */
        private BigDecimal maxCyclingDistance = BigDecimal.ZERO;

        /** 夜间窗口内单次有效里程峰值 */
        private BigDecimal maxNightDistance = BigDecimal.ZERO;

        /** 历史最长连续打卡天数（成就语义取 longest，支持历史追溯点亮） */
        private int longestStreakDays;

        /** 已通过记录条数（首次有效运动判定） */
        private long passCount;

        /** 历史累计运动总里程 */
        private BigDecimal totalDistance = BigDecimal.ZERO;
    }
}

package com.sportverify.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sportverify.user.dto.NotificationPreferenceView;
import com.sportverify.user.dto.UpdateNotificationPreferenceRequest;
import com.sportverify.user.entity.NotificationPreference;
import com.sportverify.user.enums.NotificationType;
import com.sportverify.user.mapper.NotificationPreferenceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 通知偏好服务（user_db.notification_preference，TASK-187 add-notification-preference）。
 *
 * <p>提供偏好读取（缺行补默认全 true，无预填充）、行级原子 upsert（ON DUPLICATE KEY UPDATE）
 * 以及创建通知落库前的单一权威偏好闸门判定。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationPreferenceService {

    private final NotificationPreferenceMapper preferenceMapper;

    /** 支持的通知类型全集（四类，TASK-188 扩点赞） */
    public static final List<String> SUPPORTED_TYPES = List.of(
            NotificationType.RECORD_VERIFIED,
            NotificationType.RECORD_REJECTED,
            NotificationType.FRIEND_ACCEPTED,
            NotificationType.RECORD_LIKED
    );

    /**
     * 获取用户通知偏好列表（四类全量视图，DB 缺行补 enabled=true）。
     *
     * @param userId 用户 ID
     * @return 四类通知偏好视图列表
     */
    public List<NotificationPreferenceView> getPreferences(Long userId) {
        if (userId == null) {
            return defaultPreferences();
        }

        List<NotificationPreference> dbList = preferenceMapper.selectList(
                new LambdaQueryWrapper<NotificationPreference>()
                        .eq(NotificationPreference::getUserId, userId));

        Map<String, NotificationPreference> map = dbList.stream()
                .collect(Collectors.toMap(NotificationPreference::getType, Function.identity(), (a, b) -> a));

        List<NotificationPreferenceView> result = new ArrayList<>(SUPPORTED_TYPES.size());
        for (String type : SUPPORTED_TYPES) {
            NotificationPreference pref = map.get(type);
            if (pref != null && pref.getEnabled() != null) {
                result.add(new NotificationPreferenceView(type, pref.getEnabled() != 0, pref.getUpdatedAt()));
            } else {
                // 缺行 = 默认开启（读取侧补默认，无预填充）
                result.add(new NotificationPreferenceView(type, true, null));
            }
        }
        return result;
    }

    /**
     * 行级原子 upsert 偏好：更新时间采用服务端当前时间。
     *
     * @param userId  用户 ID
     * @param type    通知类型
     * @param enabled 是否开启
     */
    public void upsert(Long userId, String type, boolean enabled) {
        if (userId == null || type == null || !SUPPORTED_TYPES.contains(type)) {
            return;
        }
        NotificationPreference pref = new NotificationPreference();
        pref.setUserId(userId);
        pref.setType(type);
        pref.setEnabled(enabled ? 1 : 0);
        pref.setUpdatedAt(LocalDateTime.now());
        preferenceMapper.upsert(pref);
    }

    /**
     * 批量更新偏好，并返回刷新后的全量偏好视图。
     *
     * @param userId  用户 ID
     * @param request 更新请求体
     * @return 更新后的全量偏好视图列表
     */
    public List<NotificationPreferenceView> updatePreferences(Long userId, UpdateNotificationPreferenceRequest request) {
        if (userId != null && request != null && request.preferences() != null) {
            for (UpdateNotificationPreferenceRequest.PreferenceItem item : request.preferences()) {
                if (item != null && item.type() != null && item.enabled() != null) {
                    upsert(userId, item.type(), item.enabled());
                }
            }
        }
        return getPreferences(userId);
    }

    /**
     * 偏好闸门判定：检查该用户是否开启了该类型通知。
     *
     * <p>偏好与通知同库同命运，缺行默认开启返回 true；关闭返回 false。</p>
     *
     * @param userId 用户 ID
     * @param type   通知类型
     * @return true = 接收（开启或缺省默认）；false = 明确关闭
     */
    public boolean isNotificationEnabled(Long userId, String type) {
        if (userId == null || type == null || !SUPPORTED_TYPES.contains(type)) {
            return true;
        }
        NotificationPreference pref = preferenceMapper.selectOne(
                new LambdaQueryWrapper<NotificationPreference>()
                        .eq(NotificationPreference::getUserId, userId)
                        .eq(NotificationPreference::getType, type));
        if (pref == null || pref.getEnabled() == null) {
            return true;
        }
        return pref.getEnabled() != 0;
    }

    private List<NotificationPreferenceView> defaultPreferences() {
        return SUPPORTED_TYPES.stream()
                .map(type -> new NotificationPreferenceView(type, true, null))
                .toList();
    }
}

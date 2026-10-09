package com.sportverify.user.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sportverify.user.dto.NotificationPreferenceView;
import com.sportverify.user.dto.UpdateNotificationPreferenceRequest;
import com.sportverify.user.entity.NotificationPreference;
import com.sportverify.user.enums.NotificationType;
import com.sportverify.user.mapper.NotificationPreferenceMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通知偏好服务单元测试（TASK-187 add-notification-preference）。
 *
 * <p>覆盖：缺行补默认全 true、已有行覆盖默认、upsert 插入新行、upsert 重复同值幂等、
 * 批量更新视图返回、偏好闸门判定三态。</p>
 */
class NotificationPreferenceServiceTest {

    private NotificationPreferenceMapper mapper;
    private NotificationPreferenceService service;

    @BeforeEach
    void setUp() {
        mapper = mock(NotificationPreferenceMapper.class);
        service = new NotificationPreferenceService(mapper);

        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), NotificationPreference.class);
    }

    /** 缺行补默认：DB 无记录时，返回全量三类全开（enabled=true，updatedAt=null） */
    @Test
    void getPreferences_missingRows_defaultsAllTrue() {
        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());

        List<NotificationPreferenceView> prefs = service.getPreferences(1001L);

        assertEquals(4, prefs.size(), "应包含既有四类通知偏好");
        for (NotificationPreferenceView v : prefs) {
            assertTrue(v.getEnabled(), "缺行应默认开启");
            assertNull(v.getUpdatedAt(), "默认开启无更新时间");
        }
        assertEquals(NotificationType.RECORD_VERIFIED, prefs.get(0).getType());
        assertEquals(NotificationType.RECORD_REJECTED, prefs.get(1).getType());
        assertEquals(NotificationType.FRIEND_ACCEPTED, prefs.get(2).getType());
        assertEquals(NotificationType.RECORD_LIKED, prefs.get(3).getType());
    }

    /** 已有行覆盖默认：DB 中某类关闭（enabled=0），视图体现关闭状态，其余缺省仍为开启 */
    @Test
    void getPreferences_existingRows_overrideDefaults() {
        NotificationPreference p1 = new NotificationPreference();
        p1.setUserId(1001L);
        p1.setType(NotificationType.RECORD_VERIFIED);
        p1.setEnabled(0);
        LocalDateTime now = LocalDateTime.now();
        p1.setUpdatedAt(now);

        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(p1));

        List<NotificationPreferenceView> prefs = service.getPreferences(1001L);

        assertEquals(4, prefs.size());
        assertEquals(NotificationType.RECORD_VERIFIED, prefs.get(0).getType());
        assertFalse(prefs.get(0).getEnabled(), "已有行 enabled=0 应覆盖默认开启");
        assertEquals(now, prefs.get(0).getUpdatedAt());

        assertEquals(NotificationType.RECORD_REJECTED, prefs.get(1).getType());
        assertTrue(prefs.get(1).getEnabled(), "缺行仍默认开启");

        assertEquals(NotificationType.FRIEND_ACCEPTED, prefs.get(2).getType());
        assertTrue(prefs.get(2).getEnabled(), "缺行仍默认开启");
        // 新增默认开启的 RECORD_LIKED
        assertEquals(NotificationType.RECORD_LIKED, prefs.get(3).getType());
        assertTrue(prefs.get(3).getEnabled(), "默认开启 RECORD_LIKED");
    }

    /** upsert 插入新行：校验调用 mapper.upsert 且更新时间与参数正确传递 */
    @Test
    void upsert_newRow_callsMapperUpsert() {
        when(mapper.upsert(any(NotificationPreference.class))).thenReturn(1);

        service.upsert(1001L, NotificationType.RECORD_VERIFIED, false);

        ArgumentCaptor<NotificationPreference> captor = ArgumentCaptor.forClass(NotificationPreference.class);
        verify(mapper).upsert(captor.capture());

        NotificationPreference captured = captor.getValue();
        assertEquals(1001L, captured.getUserId());
        assertEquals(NotificationType.RECORD_VERIFIED, captured.getType());
        assertEquals(0, captured.getEnabled());
        assertNotNull(captured.getUpdatedAt(), "应填充服务端当前时间");
    }

    /** upsert 重复同值幂等：第二次执行同值 upsert，底层 ON DUPLICATE KEY UPDATE 保持行级幂等 */
    @Test
    void upsert_duplicateValue_idempotent() {
        when(mapper.upsert(any(NotificationPreference.class))).thenReturn(1, 0);

        service.upsert(1001L, NotificationType.FRIEND_ACCEPTED, true);
        service.upsert(1001L, NotificationType.FRIEND_ACCEPTED, true);

        verify(mapper, times(2)).upsert(any(NotificationPreference.class));
    }

    /** 批量更新：遍历项逐一调用 upsert 并返回最新的偏好视图 */
    @Test
    void updatePreferences_batch_returnsUpdatedViews() {
        when(mapper.upsert(any(NotificationPreference.class))).thenReturn(1);
        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());

        UpdateNotificationPreferenceRequest req = new UpdateNotificationPreferenceRequest(List.of(
                new UpdateNotificationPreferenceRequest.PreferenceItem(NotificationType.RECORD_VERIFIED, false),
                new UpdateNotificationPreferenceRequest.PreferenceItem(NotificationType.RECORD_REJECTED, true)
        ));

        List<NotificationPreferenceView> views = service.updatePreferences(1001L, req);

        verify(mapper, times(2)).upsert(any(NotificationPreference.class));
        assertEquals(4, views.size());
    }

    /** 偏好闸门判定：缺行时默认返回 true */
    @Test
    void isNotificationEnabled_missingRow_returnsTrue() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        boolean enabled = service.isNotificationEnabled(1001L, NotificationType.RECORD_VERIFIED);

        assertTrue(enabled, "缺行应判定为开启");
    }

    /** 偏好闸门判定：DB 记录为 enabled=0 时返回 false */
    @Test
    void isNotificationEnabled_rowDisabled_returnsFalse() {
        NotificationPreference p = new NotificationPreference();
        p.setUserId(1001L);
        p.setType(NotificationType.RECORD_REJECTED);
        p.setEnabled(0);

        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(p);

        boolean enabled = service.isNotificationEnabled(1001L, NotificationType.RECORD_REJECTED);

        assertFalse(enabled, "记录为 0 应判定为关闭");
    }

    /** 偏好闸门判定：DB 记录为 enabled=1 时返回 true */
    @Test
    void isNotificationEnabled_rowEnabled_returnsTrue() {
        NotificationPreference p = new NotificationPreference();
        p.setUserId(1001L);
        p.setType(NotificationType.FRIEND_ACCEPTED);
        p.setEnabled(1);

        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(p);

        boolean enabled = service.isNotificationEnabled(1001L, NotificationType.FRIEND_ACCEPTED);

        assertTrue(enabled, "记录为 1 应判定为开启");
    }

    /** 偏好闸门判定：RECORD_LIKED 偏好关闭（enabled=0）时返回 false（闸门生效） */
    @Test
    void isNotificationEnabled_recordLikedDisabled_returnsFalse() {
        NotificationPreference p = new NotificationPreference();
        p.setUserId(1001L);
        p.setType(NotificationType.RECORD_LIKED);
        p.setEnabled(0);

        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(p);

        boolean enabled = service.isNotificationEnabled(1001L, NotificationType.RECORD_LIKED);

        assertFalse(enabled, "RECORD_LIKED 记录为 0 应判定为关闭（闸门拦截）");
    }
}

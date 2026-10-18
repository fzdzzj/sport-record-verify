package com.sportverify.record.controller;

import com.sportverify.common.result.Result;
import com.sportverify.record.dto.BadgeDTO;
import com.sportverify.record.enums.BadgeType;
import com.sportverify.record.service.BadgeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BadgeController 契约单测（TASK-269）：覆盖四端点委托、未解锁 400 映射与
 * 社交外显空值语义（未佩戴 data 为 null）。
 */
@ExtendWith(MockitoExtension.class)
class BadgeControllerTest {

    private static final Long USER_ID = 7L;

    @Mock
    private BadgeService badgeService;

    @InjectMocks
    private BadgeController controller;

    /** GET /api/records/my/badges：返回勋章墙列表（Result code=0，data 逐项透传） */
    @Test
    void listMyBadges_returnsWall() {
        List<BadgeDTO> wall = List.of(dto(BadgeType.FIRST_STEP));
        when(badgeService.listMyBadges(USER_ID)).thenReturn(wall);

        Result<List<BadgeDTO>> out = controller.listMyBadges(USER_ID);

        assertEquals(0, out.getCode());
        assertEquals(wall, out.getData());
        verify(badgeService).listMyBadges(USER_ID);
    }

    /** PUT /api/records/my/badges/{badgeCode}/wear：委托服务层并返回成功 */
    @Test
    void wearBadge_delegatesToService() {
        Result<Void> out = controller.wearBadge(USER_ID, "FIRST_STEP");

        assertEquals(0, out.getCode());
        verify(badgeService).wearBadge(USER_ID, "FIRST_STEP");
    }

    /** 未解锁佩戴：服务层抛业务校验异常向调用方传播，局部 handler 映射 400 + 原始标识串 */
    @Test
    void wearBadge_notUnlocked_propagatesAndHandlerMaps400() {
        doThrow(new IllegalArgumentException("BADGE_NOT_UNLOCKED：勋章尚未解锁，不可佩戴：马拉松大师"))
                .when(badgeService).wearBadge(USER_ID, "FULL_MARATHON");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> controller.wearBadge(USER_ID, "FULL_MARATHON"));
        assertTrue(e.getMessage().contains("BADGE_NOT_UNLOCKED"));

        Result<Void> mapped = controller.handleIllegalArgument(e);
        assertEquals(400, mapped.getCode());
        assertTrue(mapped.getMessage().contains("BADGE_NOT_UNLOCKED"));
    }

    /** PUT /api/records/my/badges/unwear：委托服务层并返回成功 */
    @Test
    void unwearBadge_delegatesToService() {
        Result<Void> out = controller.unwearBadge(USER_ID);

        assertEquals(0, out.getCode());
        verify(badgeService).unwearBadge(USER_ID);
    }

    /** GET /api/records/users/{userId}/equipped-badge：佩戴中返回完整视图 */
    @Test
    void getEquippedBadge_equipped_returnsDto() {
        BadgeDTO dto = dto(BadgeType.TEN_KM_RUN);
        when(badgeService.getEquippedBadge(USER_ID)).thenReturn(dto);

        Result<BadgeDTO> out = controller.getEquippedBadge(USER_ID);

        assertEquals(0, out.getCode());
        assertEquals("TEN_KM_RUN", out.getData().getCode());
    }

    /** 未佩戴：data 为 null 但响应仍为成功（社交外显空值语义） */
    @Test
    void getEquippedBadge_none_returnsNullData() {
        when(badgeService.getEquippedBadge(USER_ID)).thenReturn(null);

        Result<BadgeDTO> out = controller.getEquippedBadge(USER_ID);

        assertEquals(0, out.getCode());
        assertNull(out.getData());
    }

    /** 局部 handler：非法勋章编码等参数异常统一 400 + 透传消息 */
    @Test
    void handleIllegalArgument_returns400WithMessage() {
        Result<Void> out = controller.handleIllegalArgument(new IllegalArgumentException("勋章编码非法：X"));
        assertEquals(400, out.getCode());
        assertEquals("勋章编码非法：X", out.getMessage());
    }

    private BadgeDTO dto(BadgeType type) {
        BadgeDTO dto = new BadgeDTO();
        dto.setCode(type.name());
        dto.setName(type.getDisplayName());
        dto.setDescription(type.getDescription());
        dto.setCategory(type.getCategory());
        dto.setTier(type.getTier());
        dto.setIcon(type.getIcon());
        dto.setUnlocked(true);
        dto.setIsEquipped(true);
        dto.setCurrentValue(type.getTargetValue());
        dto.setTargetValue(type.getTargetValue());
        dto.setProgressPct(100);
        return dto;
    }
}

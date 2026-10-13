package com.sportverify.mapmatch.controller;

import com.sportverify.api.mapmatch.dto.MapMatchPointDTO;
import com.sportverify.api.mapmatch.dto.MapMatchRequestDTO;
import com.sportverify.api.mapmatch.dto.MapMatchResultDTO;
import com.sportverify.common.result.Result;
import com.sportverify.common.result.ResultCode;
import com.sportverify.mapmatch.service.MapMatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 匹配接口单测（TASK-214）：委托 + Result.success 包装 + 请求实例透传。
 *
 * <p>纯 Mockito 手工构造（@RequiredArgsConstructor 生成公有构造器），不启动 Spring 上下文，
 * 与仓库既有纯单测风格一致。注意手工构造下 @Valid 不触发——入参校验语义由
 * MapMatchRequestValidationTest（Validator 级）承载，本类只锁控制器职责：
 * 服务返回值原样包装为 code=SUCCESS、请求 DTO 同实例透传。</p>
 */
class MapMatchControllerTest {

    private MapMatchService mapMatchService;
    private MapMatchController controller;

    @BeforeEach
    void setUp() {
        mapMatchService = mock(MapMatchService.class);
        controller = new MapMatchController(mapMatchService);
    }

    private static MapMatchRequestDTO request() {
        MapMatchPointDTO p = new MapMatchPointDTO();
        p.setSeq(0);
        p.setLat(31.22);
        p.setLng(121.47);
        MapMatchRequestDTO req = new MapMatchRequestDTO();
        req.setPoints(List.of(p));
        return req;
    }

    /** R2 委托与包装：code=SUCCESS、data 与服务返回值同一实例 */
    @Test
    void match_wrapsServiceResultAsSuccess() {
        MapMatchRequestDTO req = request();
        MapMatchResultDTO result = new MapMatchResultDTO(1.0, 0.0, 12.5, 30.0, 1, 1);
        when(mapMatchService.match(req)).thenReturn(result);

        Result<MapMatchResultDTO> r = controller.match(req);

        assertEquals(ResultCode.SUCCESS.getCode(), r.getCode());
        assertSame(result, r.getData());
    }

    /** R2 请求实例透传：控制器把同一请求实例原样交给服务 */
    @Test
    void match_passesThroughSameRequestInstance() {
        MapMatchRequestDTO req = request();
        when(mapMatchService.match(any(MapMatchRequestDTO.class)))
                .thenReturn(new MapMatchResultDTO(1.0, 0.0, 0.0, 0.0, 1, 1));

        controller.match(req);

        ArgumentCaptor<MapMatchRequestDTO> captor = ArgumentCaptor.forClass(MapMatchRequestDTO.class);
        verify(mapMatchService).match(captor.capture());
        assertSame(req, captor.getValue());
    }
}

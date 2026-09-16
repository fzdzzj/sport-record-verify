package com.sportverify.api.record;

import com.sportverify.api.record.dto.LikeDTO;
import com.sportverify.api.record.dto.LikeRequestDTO;
import com.sportverify.api.record.dto.SportRecordDTO;
import com.sportverify.api.record.dto.StatusCallbackDTO;
import com.sportverify.api.record.dto.TrackPointDTO;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.Result;
import com.sportverify.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * record-service Feign「显式失败」工厂（add-resilience-hardening）。
 *
 * <p><b>为何不可软降级</b>：verify 判定强依赖记录元数据与轨迹；拿不到轨迹却假装成功
 * 会产生错误判定。本工厂<b>只</b>把连接拒绝/超时/熔断转为 {@code RECORD_SERVICE_UNAVAILABLE(4007)}，
 * 由 MQ 消费重试或进 DLQ；绝不返回空轨迹或伪造成功回调。</p>
 */
@Slf4j
@Component
public class RecordApiFallback implements FallbackFactory<RecordApi> {

    @Override
    public RecordApi create(Throwable cause) {
        return new RecordApi() {
            @Override
            public Result<String> health() {
                throw fail("health", cause);
            }

            @Override
            public Result<SportRecordDTO> getRecord(Long recordId) {
                throw fail("getRecord:" + recordId, cause);
            }

            @Override
            public Result<List<TrackPointDTO>> listPoints(Long recordId) {
                throw fail("listPoints:" + recordId, cause);
            }

            @Override
            public Result<Void> statusCallback(Long recordId, StatusCallbackDTO dto) {
                throw fail("statusCallback:" + recordId, cause);
            }

            @Override
            public Result<LikeDTO> likeRecord(Long recordId, LikeRequestDTO dto) {
                throw fail("likeRecord:" + recordId, cause);
            }

            @Override
            public Result<LikeDTO> unlikeRecord(Long recordId, Long userId) {
                throw fail("unlikeRecord:" + recordId, cause);
            }

            @Override
            public Result<LikeDTO> getRecordLike(Long recordId, Long userId) {
                throw fail("getRecordLike:" + recordId, cause);
            }
        };
    }

    private static BizException fail(String op, Throwable cause) {
        log.warn("record-service 不可用（不可软降级）：op={}, cause={}",
                op, cause == null ? "unknown" : cause.toString());
        return new BizException(ResultCode.RECORD_SERVICE_UNAVAILABLE, "记录服务不可用，待重试：" + op);
    }
}

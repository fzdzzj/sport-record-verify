package com.sportverify.verify.mq;

import com.sportverify.verify.entity.VerifyEventOutbox;
import com.sportverify.verify.mapper.VerifyEventOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * outbox 事件投递 relay（F03 本地消息表补偿）——判定事件的**唯一**发送出口。
 *
 * <p>定时扫 verify_event_outbox 的 PENDING 行 → 经 {@link VerifyEventProducer#syncSend} 投递
 * （行内 topic/tag/payload/eventId 原样使用，traceId 透传）→ 成功标 SENT，
 * 失败 retry_count+1 留下轮；超 {@code verify.outbox.max-retry}（默认 16）仅记 error
 * 告警并保留行供人工处理（不重投、不删除）。</p>
 *
 * <p>多实例防重：Redisson 锁 {@code verify:outbox:relay}，tryLock(0 等待)——
 * 拿不到锁说明另一实例正在跑，直接跳过本轮（周期短，无需等待）。</p>
 *
 * <p>判定链路不直发（见 VerifyOutboxService）：事件到达延迟由本 relay 周期（默认 5s）决定，
 * 榜单侧定时结算纠偏仍是最终一致的兜底。</p>
 *
 * <p>本类同时承担 {@link EnableScheduling}：verify-service 此前无定时任务，
 * 启动类不在本任务改动清单内，故由 relay 自带调度开关。</p>
 */
@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class VerifyOutboxRelay {

    /** relay 防重锁键 */
    private static final String LOCK_KEY = "verify:outbox:relay";

    private final VerifyEventOutboxMapper outboxMapper;
    private final VerifyEventProducer verifyEventProducer;
    private final RedissonClient redissonClient;

    /** 单轮批量上限 */
    @Value("${verify.outbox.batch-size:100}")
    private int batchSize;

    /** 最大重试次数：超过后仅告警保留行，供人工排查 */
    @Value("${verify.outbox.max-retry:16}")
    private int maxRetry;

    /** 定时投递一轮（默认 5s；多实例经 Redisson 锁互斥） */
    @Scheduled(fixedDelayString = "${verify.outbox.relay-interval-ms:5000}",
            initialDelayString = "${verify.outbox.relay-initial-delay-ms:10000}")
    public void relay() {
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean locked = false;
        try {
            locked = lock.tryLock(0, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("outbox relay 锁获取被中断，跳过本轮");
            return;
        }
        if (!locked) {
            return; // 另一实例正在跑，周期短直接跳过
        }
        try {
            List<VerifyEventOutbox> batch = outboxMapper.selectPendingBatch(batchSize);
            for (VerifyEventOutbox row : batch) {
                if (row.getRetryCount() != null && row.getRetryCount() >= maxRetry) {
                    log.error("outbox 事件超过最大重试次数，保留行供人工处理：id={}, eventId={}, topic={}, tag={}, retryCount={}",
                            row.getId(), row.getEventId(), row.getTopic(), row.getTag(), row.getRetryCount());
                    continue;
                }
                try {
                    // 唯一投递出口：行内 topic/tag/payload/eventId 原样交给生产者（重发不换 eventId）
                    verifyEventProducer.syncSend(row);
                    outboxMapper.markSent(row.getId());
                    log.info("outbox 事件投递成功：id={}, eventId={}, tag={}",
                            row.getId(), row.getEventId(), row.getTag());
                } catch (Exception e) {
                    outboxMapper.incrRetry(row.getId());
                    log.warn("outbox 事件投递失败，下轮重试：id={}, eventId={}, retryCount={}",
                            row.getId(), row.getEventId(),
                            row.getRetryCount() == null ? 1 : row.getRetryCount() + 1, e);
                }
            }
        } finally {
            try {
                lock.unlock();
            } catch (Exception e) {
                log.warn("outbox relay 锁释放异常（租期兜底释放）");
            }
        }
    }
}

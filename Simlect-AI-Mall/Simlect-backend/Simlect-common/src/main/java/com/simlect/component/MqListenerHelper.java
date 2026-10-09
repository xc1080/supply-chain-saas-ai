package com.simlect.component;

import com.simlect.constants.Constants;
import com.simlect.utils.StringTools;
import com.rabbitmq.client.Channel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class MqListenerHelper {

    public static final int DEFAULT_MAX_CONSUME_RETRIES = 3;

    public static final long CONSUME_IDEMPOTENCY_TTL_STANDARD_SECONDS = 24 * 3600L;

    public static final long CONSUME_IDEMPOTENCY_TTL_HIGH_SECONDS = 7 * 24 * 3600L;

    @Resource
    private MqConsumerIdempotencyHelper mqConsumerIdempotencyHelper;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private MqConsumeFailureRecorder mqConsumeFailureRecorder;

    public boolean tryBeginConsume(Message message, long ttlSeconds) {
        return mqConsumerIdempotencyHelper.tryBeginConsume(message, ttlSeconds);
    }

    public void releaseConsume(Message message) {
        mqConsumerIdempotencyHelper.releaseConsume(message);
    }

    public String resolveIdempotencyKey(Message message) {
        return mqConsumerIdempotencyHelper.resolveIdempotencyKey(message);
    }

    public void nackWithRetryOrDlq(Channel channel, long deliveryTag, Message message,
                                   String queueName, Object payload, Exception error) throws IOException {
        String idempotencyKey = resolveIdempotencyKey(message);
        if (StringTools.isEmpty(idempotencyKey)) {
            mqConsumeFailureRecorder.record(queueName, message, payload, error);
            channel.basicNack(deliveryTag, false, false);
            return;
        }
        String retryKey = Constants.REDIS_KEY_MQ_CONSUME_RETRY + queueName + ":" + idempotencyKey;
        Long attempt = stringRedisTemplate.opsForValue().increment(retryKey);
        if (attempt != null && attempt == 1L) {
            stringRedisTemplate.expire(retryKey, 1, TimeUnit.DAYS);
        }
        if (attempt != null && attempt <= DEFAULT_MAX_CONSUME_RETRIES) {
            log.warn("MQ 消费失败将重试 queue={}, key={}, attempt={}/{}",
                    queueName, idempotencyKey, attempt, DEFAULT_MAX_CONSUME_RETRIES);
            releaseConsume(message);
            channel.basicNack(deliveryTag, false, true);
            return;
        }
        mqConsumeFailureRecorder.record(queueName, message, payload, error);
        stringRedisTemplate.delete(retryKey);
        channel.basicNack(deliveryTag, false, false);
    }

    public void clearConsumeRetry(String queueName, Message message) {
        String idempotencyKey = resolveIdempotencyKey(message);
        if (StringTools.isEmpty(idempotencyKey)) {
            return;
        }
        stringRedisTemplate.delete(Constants.REDIS_KEY_MQ_CONSUME_RETRY + queueName + ":" + idempotencyKey);
    }
}

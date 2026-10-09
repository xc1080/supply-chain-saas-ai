package com.simlect.component;

import com.simlect.constants.Constants;
import com.simlect.utils.StringTools;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class MqConsumerIdempotencyHelper {

    public static final String HEADER_IDEMPOTENCY_KEY = "x-idempotency-key";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public boolean tryBeginConsume(Message message, long ttlSeconds) {
        String key = resolveIdempotencyKey(message);
        if (StringTools.isEmpty(key)) {
            return true;
        }
        String redisKey = Constants.REDIS_KEY_MQ_CONSUME_IDEMPOTENT + key;
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(redisKey, "1", ttlSeconds, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("MQ 消费幂等跳过, key={}", key);
            return false;
        }
        return true;
    }

    public void releaseConsume(Message message) {
        String key = resolveIdempotencyKey(message);
        if (StringTools.isEmpty(key)) {
            return;
        }
        stringRedisTemplate.delete(Constants.REDIS_KEY_MQ_CONSUME_IDEMPOTENT + key);
    }

    public String resolveIdempotencyKey(Message message) {
        if (message == null || message.getMessageProperties() == null) {
            return null;
        }
        Object header = message.getMessageProperties().getHeader(HEADER_IDEMPOTENCY_KEY);
        if (header != null && !StringTools.isEmpty(String.valueOf(header))) {
            return String.valueOf(header);
        }
        return message.getMessageProperties().getMessageId();
    }
}

package com.simlect.component;

import com.simlect.constants.Constants;
import com.simlect.utils.StringTools;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class MqIdempotencyGuard {

    private static final String STATUS_SENT = "SENT";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public boolean tryAcquireSend(String idempotencyKey, long ttlSeconds) {
        if (StringTools.isEmpty(idempotencyKey)) {
            throw new IllegalArgumentException("idempotencyKey 不能为空");
        }
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("idempotencyTtlSeconds 必须大于 0");
        }
        String redisKey = Constants.REDIS_KEY_MQ_SEND_IDEMPOTENT + idempotencyKey;
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(redisKey, STATUS_SENT, ttlSeconds, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("MQ 发送幂等跳过, key={}", idempotencyKey);
            return false;
        }
        return true;
    }

    public void releaseSend(String idempotencyKey) {
        if (StringTools.isEmpty(idempotencyKey)) {
            return;
        }
        stringRedisTemplate.delete(Constants.REDIS_KEY_MQ_SEND_IDEMPOTENT + idempotencyKey);
    }
}

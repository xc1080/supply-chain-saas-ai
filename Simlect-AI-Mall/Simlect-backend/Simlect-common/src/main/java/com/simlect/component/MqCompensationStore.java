package com.simlect.component;

import com.simlect.constants.Constants;
import com.simlect.entity.dto.MqCompensationRecord;
import com.simlect.utils.JsonUtils;
import com.simlect.utils.StringTools;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class MqCompensationStore {

    private static final long COMPENSATE_TTL_SECONDS = 7 * 24 * 3600L;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void saveToRedis(MqCompensationRecord record) {
        if (record == null || StringTools.isEmpty(record.getIdempotencyKey())) {
            return;
        }
        try {
            String json = JsonUtils.toJson(record);
            stringRedisTemplate.opsForValue().set(
                    Constants.REDIS_KEY_MQ_COMPENSATE + record.getIdempotencyKey(),
                    json,
                    COMPENSATE_TTL_SECONDS,
                    TimeUnit.SECONDS);
            stringRedisTemplate.opsForZSet().add(
                    Constants.REDIS_KEY_MQ_COMPENSATE_PENDING,
                    record.getIdempotencyKey(),
                    record.getFailedAt() > 0 ? record.getFailedAt() : System.currentTimeMillis());
            log.info("MQ 补偿 Redis 索引已写入, key={}", record.getIdempotencyKey());
        } catch (Exception e) {
            log.error("MQ 补偿 Redis 索引写入失败, key={}", record.getIdempotencyKey(), e);
        }
    }

    public void remove(String idempotencyKey) {
        if (StringTools.isEmpty(idempotencyKey)) {
            return;
        }
        stringRedisTemplate.delete(Constants.REDIS_KEY_MQ_COMPENSATE + idempotencyKey);
        stringRedisTemplate.opsForZSet().remove(Constants.REDIS_KEY_MQ_COMPENSATE_PENDING, idempotencyKey);
        log.info("MQ 补偿 Redis 索引已清理, key={}", idempotencyKey);
    }

    public Set<String> listPendingKeys(int limit) {
        if (limit <= 0) {
            limit = 20;
        }
        Set<String> keys = stringRedisTemplate.opsForZSet()
                .range(Constants.REDIS_KEY_MQ_COMPENSATE_PENDING, 0, limit - 1L);
        return keys == null ? Set.of() : keys;
    }
}

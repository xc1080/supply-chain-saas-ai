package com.simlect.redis;

import com.simlect.constants.Constants;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component("redisUtils")
@Slf4j
public class RedisUtils<V> {

    @Resource
    private RedisTemplate<String, V> redisTemplate;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void delete(String... key) {
        if (key != null && key.length > 0) {
            if (key.length == 1) {
                redisTemplate.delete(key[0]);
            } else {
                redisTemplate.delete((Collection<String>) CollectionUtils.arrayToList(key));
            }
        }
    }

    public V get(String key) {
        return key == null ? null : redisTemplate.opsForValue().get(key);
    }

    public Boolean set(String key, V value) {
        try {
            redisTemplate.opsForValue().set(key, value);
            return true;
        } catch (Exception e) {
            log.error("设置redisKey:{},value:{}失败", key, value);
            return false;
        }
    }

    public Boolean setex(String key, V value, long time) {
        try {
            if (time > 0) {
                redisTemplate.opsForValue().set(key, value, time, TimeUnit.SECONDS);
            } else {
                set(key, value);
            }
            return true;
        } catch (Exception e) {
            log.error("设置redisKey:{},value:{}失败", key, value);
            return false;
        }
    }

    public void zsetAdd(String key, V value, double score) {
        redisTemplate.opsForZSet().add(key, value, score);
    }

    public Set<V> zsetRangeByScore(String key, double min, double max) {
        return redisTemplate.opsForZSet().rangeByScore(key, min, max);
    }

    public Long zsetAddRemove(String key, V v) {
        return redisTemplate.opsForZSet().remove(key, v);
    }

    // bitMap相关操作
    public void bitMapSet(String key, long offset, Boolean value) {
        redisTemplate.opsForValue().setBit(key, offset, value);
    }

    public Boolean bitMapGet(String key, long offset) {
        return redisTemplate.opsForValue().getBit(key, offset);
    }

    public List<Long> bitMapField(String key, int count, String value) {
        // 返回的是一个十进制数字，由二进制转换得到
        return redisTemplate.opsForValue().bitField(key, BitFieldSubCommands.create()
                .get(BitFieldSubCommands.BitFieldType.unsigned(count))
                .valueAt(0));
    }

    // Hash相关操作
    public void hashPut(String key, String value1, Integer value2) {
        redisTemplate.opsForHash().put(key, value1, value2);
    }

    public Integer hashGet(String key, String value1) {
        return (Integer) redisTemplate.opsForHash().get(key, value1);
    }

}

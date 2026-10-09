package com.simlect.captcha;

import com.simlect.component.SpringContext;
import com.xingyuv.captcha.service.CaptchaCacheService;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

public class CaptchaCacheServiceRedisImpl implements CaptchaCacheService {

    private StringRedisTemplate redis() {
        return (StringRedisTemplate) SpringContext.getBean("stringRedisTemplate");
    }

    @Override
    public void set(String key, String value, long expiresInSeconds) {
        if (expiresInSeconds > 0) {
            redis().opsForValue().set(key, value, expiresInSeconds, TimeUnit.SECONDS);
        } else {
            redis().opsForValue().set(key, value);
        }
    }

    @Override
    public boolean exists(String key) {
        return Boolean.TRUE.equals(redis().hasKey(key));
    }

    @Override
    public void delete(String key) {
        redis().delete(key);
    }

    @Override
    public String get(String key) {
        return redis().opsForValue().get(key);
    }

    @Override
    public Long increment(String key, long val) {
        return redis().opsForValue().increment(key, val);
    }

    @Override
    public String type() {
        return "redis";
    }
}

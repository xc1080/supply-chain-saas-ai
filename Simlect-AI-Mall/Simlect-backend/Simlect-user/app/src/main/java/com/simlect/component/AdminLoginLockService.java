package com.simlect.component;

import com.simlect.constants.Constants;
import com.simlect.exception.BusinessException;
import com.simlect.utils.StringTools;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class AdminLoginLockService {

    private static final int MAX_FAIL_COUNT = 5;
    private static final long LOCK_SECONDS = 15 * 60L;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void ensureNotLocked(String ip) {
        if (StringTools.isEmpty(ip)) {
            return;
        }
        String lockKey = Constants.REDIS_KEY_ADMIN_LOGIN_LOCK + ip;
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(lockKey))) {
            throw new BusinessException("登录失败次数过多，请15分钟后再试");
        }
    }

    public void recordFailure(String ip) {
        if (StringTools.isEmpty(ip)) {
            return;
        }
        String failKey = Constants.REDIS_KEY_ADMIN_LOGIN_FAIL + ip;
        Long count = stringRedisTemplate.opsForValue().increment(failKey);
        if (count != null && count == 1L) {
            stringRedisTemplate.expire(failKey, LOCK_SECONDS, TimeUnit.SECONDS);
        }
        if (count != null && count >= MAX_FAIL_COUNT) {
            String lockKey = Constants.REDIS_KEY_ADMIN_LOGIN_LOCK + ip;
            stringRedisTemplate.opsForValue().set(lockKey, "1", LOCK_SECONDS, TimeUnit.SECONDS);
            log.warn("管理端登录 IP 已锁定 ip={}, failCount={}", ip, count);
        }
    }

    public void clearFailures(String ip) {
        if (StringTools.isEmpty(ip)) {
            return;
        }
        stringRedisTemplate.delete(Constants.REDIS_KEY_ADMIN_LOGIN_FAIL + ip);
        stringRedisTemplate.delete(Constants.REDIS_KEY_ADMIN_LOGIN_LOCK + ip);
    }
}

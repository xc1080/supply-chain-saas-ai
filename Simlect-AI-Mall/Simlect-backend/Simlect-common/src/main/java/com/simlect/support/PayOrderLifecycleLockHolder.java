package com.simlect.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PayOrderLifecycleLockHolder {

    private static final Logger log = LoggerFactory.getLogger(PayOrderLifecycleLockHolder.class);

    private static final ThreadLocal<String> LOCK_KEY = new ThreadLocal<>();
    private static final ThreadLocal<String> TOKEN = new ThreadLocal<>();

    private PayOrderLifecycleLockHolder() {
    }

    public static void bind(String lockKey, String token) {
        if (lockKey == null || token == null) {
            throw new IllegalArgumentException("lockKey and token must not be null");
        }
        String existingKey = LOCK_KEY.get();
        if (existingKey != null) {
            log.warn("PayOrderLifecycleLockHolder 重复绑定，旧 lockKey={} 将被覆盖，请确保外层已 release Redis 锁",
                    existingKey);
            clear();
        }
        LOCK_KEY.set(lockKey);
        TOKEN.set(token);
    }

    public static boolean isBound() {
        return LOCK_KEY.get() != null;
    }

    public static String getLockKey() {
        return LOCK_KEY.get();
    }

    public static String getToken() {
        return TOKEN.get();
    }

    public static void clear() {
        LOCK_KEY.remove();
        TOKEN.remove();
    }
}

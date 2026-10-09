package com.simlect.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class CouponCacheExecutorConfig {

    public static final String CACHE_REBUILD_EXECUTOR = "CACHE_REBUILD_EXECUTOR";

    @Bean(name = CACHE_REBUILD_EXECUTOR)
    public ExecutorService couponCacheRebuildExecutor() {
        AtomicInteger seq = new AtomicInteger(1);
        return new ThreadPoolExecutor(
                10,
                10,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(500),
                r -> {
                    Thread t = new Thread(r, "coupon-cache-rebuild-" + seq.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}

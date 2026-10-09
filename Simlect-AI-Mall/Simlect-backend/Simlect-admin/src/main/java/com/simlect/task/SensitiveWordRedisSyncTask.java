package com.simlect.task;

import com.simlect.component.RedisComponent;
import com.simlect.constants.Constants;
import com.simlect.biz.SensitiveWordService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@Slf4j
@ConditionalOnProperty(name = "app.common-scheduling.enabled", havingValue = "true")
public class SensitiveWordRedisSyncTask {

    @Resource
    private SensitiveWordService sensitiveWordService;
    @Resource
    private RedisComponent redisComponent;

    @Scheduled(fixedRate = 3600000)
    public void syncDbToRedisHourly() {
        if (!redisComponent.setIfAbsent(
                Constants.REDIS_KEY_SENSITIVE_WORD_DB_SYNC_LOCK,
                "1",
                55,
                TimeUnit.MINUTES)) {
            return;
        }
        try {
            int count = sensitiveWordService.syncFromDbToRedis();
            log.info("敏感词 DB→Redis 定时同步完成，启用词 {} 条", count);
        } catch (Exception e) {
            log.error("敏感词 DB→Redis 定时同步异常", e);
        }
    }
}

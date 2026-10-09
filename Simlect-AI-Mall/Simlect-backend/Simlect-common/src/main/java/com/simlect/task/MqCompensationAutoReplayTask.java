package com.simlect.task;

import com.simlect.component.RedisComponent;
import com.simlect.constants.Constants;
import com.simlect.service.MqCompensationLogService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@Slf4j
@ConditionalOnProperty(name = "mq.compensation.auto-replay-enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(name = "app.common-scheduling.enabled", havingValue = "true")
public class MqCompensationAutoReplayTask {

    @Resource
    private MqCompensationLogService mqCompensationLogService;
    @Resource
    private RedisComponent redisComponent;

    @Value("${mq.compensation.auto-replay-batch-size:10}")
    private int batchSize;

    @Value("${mq.compensation.auto-replay-max-retries:5}")
    private int maxRetries;

    @Scheduled(fixedDelayString = "${mq.compensation.auto-replay-interval-ms:60000}")
    public void autoReplay() {
        if (!redisComponent.setIfAbsent(
                Constants.REDIS_KEY_MQ_COMPENSATE_AUTO_REPLAY_LOCK,
                "1",
                55,
                TimeUnit.SECONDS)) {
            return;
        }
        try {
            int count = mqCompensationLogService.autoReplayPendingSendFailures(batchSize, maxRetries);
            if (count > 0) {
                log.info("MQ 发送补偿自动重放完成，成功 {} 条", count);
            }
        } catch (Exception e) {
            log.error("MQ 发送补偿自动重放任务异常", e);
        }
    }
}

package com.simlect.component;

import com.simlect.constants.Constants;
import com.simlect.entity.dto.MqCompensationRecord;
import com.simlect.entity.enums.MessageReliabilityLevelEnum;
import com.simlect.service.MqCompensationLogService;
import com.simlect.utils.StringTools;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MqConsumeFailureRecorder {

    @Resource
    private MqCompensationStore mqCompensationStore;
    @Resource
    private MqCompensationLogService mqCompensationLogService;
    @Resource
    private MqConsumerIdempotencyHelper mqConsumerIdempotencyHelper;

    public void record(String queueName, Message message, Object payload, Exception error) {
        if (StringTools.isEmpty(queueName)) {
            return;
        }
        String msgKey = mqConsumerIdempotencyHelper.resolveIdempotencyKey(message);
        if (StringTools.isEmpty(msgKey)) {
            msgKey = String.valueOf(message != null && message.getMessageProperties() != null
                    ? message.getMessageProperties().getDeliveryTag() : System.currentTimeMillis());
        }
        MqCompensationRecord record = new MqCompensationRecord();
        record.setExchange(Constants.MQ_CONSUME_FAILURE_EXCHANGE);
        record.setRoutingKey(queueName);
        record.setIdempotencyKey("consume:" + queueName + ":" + msgKey);
        record.setPayload(payload);
        record.setReliabilityLevel(MessageReliabilityLevelEnum.STANDARD);
        record.setFailedAt(System.currentTimeMillis());
        record.setErrorMessage(error == null ? "consume failed" : error.getMessage());
        try {
            mqCompensationLogService.saveFromFailure(record);
            mqCompensationStore.saveToRedis(record);
        } catch (Exception e) {
            log.error("MQ 消费失败审查表写入异常 queue={}, key={}", queueName, record.getIdempotencyKey(), e);
            return;
        }
        log.error("MQ 消费最终失败已写入审查表 queue={}, key={}", queueName, record.getIdempotencyKey());
    }
}

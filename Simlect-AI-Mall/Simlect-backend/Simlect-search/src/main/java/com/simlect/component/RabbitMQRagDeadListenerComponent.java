package com.simlect.component;

import com.simlect.constants.RabbitMQConfig;
import com.simlect.entity.dto.RagDataDTO;
import com.rabbitmq.client.Channel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Slf4j
public class RabbitMQRagDeadListenerComponent {

    @Resource
    private RedisComponent redisComponent;
    @Resource
    private MqConsumeFailureRecorder mqConsumeFailureRecorder;

    @RabbitListener(queues = RabbitMQConfig.RAG_DEAD_QUEUE, ackMode = "MANUAL")
    public void handleRagDLQ(RagDataDTO dto, Channel channel, Message message) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            log.error("RAG同步最终失败: type={}, dataId={}",
                    dto != null ? dto.getType() : "null",
                    dto != null ? dto.getDataId() : "null");
            redisComponent.addRagFailRecord(dto);
            mqConsumeFailureRecorder.record(
                    RabbitMQConfig.RAG_DEAD_QUEUE,
                    message,
                    dto,
                    new com.simlect.exception.BusinessException("RAG 同步重试耗尽，进入死信队列"));
            // 2. 确定性失败（dataId 为空/格式错误）→ 告警
            if (dto == null || dto.getDataId() == null || dto.getType() == null) {
                log.error("【需人工介入】RAG消息数据异常: {}", dto);
            }

            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("DLQ处理异常", e);
            channel.basicNack(deliveryTag, false, false);
        }
    }
}

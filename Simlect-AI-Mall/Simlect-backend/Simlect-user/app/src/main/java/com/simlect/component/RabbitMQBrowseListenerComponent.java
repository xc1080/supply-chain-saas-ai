package com.simlect.component;

import com.simlect.constants.RabbitMQConfig;
import com.simlect.api.dto.BrowseHistoryMessageDTO;
import com.simlect.biz.UserBrowseHistoryService;
import com.rabbitmq.client.Channel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Slf4j
public class RabbitMQBrowseListenerComponent {

    @Resource
    private UserBrowseHistoryService userBrowseHistoryService;
    @Resource
    private MqListenerHelper mqListenerHelper;

    @RabbitListener(queues = RabbitMQConfig.BROWSE_RECORD_QUEUE, ackMode = "MANUAL")
    public void handleBrowseRecord(BrowseHistoryMessageDTO message, Channel channel, Message mqMessage) throws IOException {
        Long deliveryTag = mqMessage.getMessageProperties().getDeliveryTag();
        if (!mqListenerHelper.tryBeginConsume(mqMessage, MqListenerHelper.CONSUME_IDEMPOTENCY_TTL_HIGH_SECONDS)) {
            channel.basicAck(deliveryTag, false);
            return;
        }
        try {
            if (message == null || message.getUserId() == null || message.getProductId() == null) {
                channel.basicAck(deliveryTag, false);
                return;
            }
            userBrowseHistoryService.recordBrowse(message.getUserId(), message.getProductId());
            mqListenerHelper.clearConsumeRetry(RabbitMQConfig.BROWSE_RECORD_QUEUE, mqMessage);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("足迹落库失败 userId={}, productId={}",
                    message != null ? message.getUserId() : null,
                    message != null ? message.getProductId() : null, e);
            mqListenerHelper.nackWithRetryOrDlq(channel, deliveryTag, mqMessage,
                    RabbitMQConfig.BROWSE_RECORD_QUEUE, message, e);
        }
    }
}

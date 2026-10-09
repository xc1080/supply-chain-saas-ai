package com.simlect.component;

import com.simlect.constants.RabbitMQConfig;
import com.simlect.api.dto.UserTempBanDTO;
import com.simlect.component.UserTempBanService;
import com.rabbitmq.client.Channel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
public class RabbitMQUserTempBanListenerComponent {

    @Resource
    private UserTempBanService userTempBanService;

    @RabbitListener(queues = RabbitMQConfig.USER_TEMP_BAN_DEAD_QUEUE)
    public void onTempBanExpire(UserTempBanDTO dto, Channel channel, Message message) throws IOException {
        try {
            if (dto != null && dto.getUserId() != null) {
                userTempBanService.tryAutoUnban(dto);
            }
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
        } catch (Exception e) {
            log.error("临时封禁解封失败 userId={}", dto == null ? null : dto.getUserId(), e);
            channel.basicNack(message.getMessageProperties().getDeliveryTag(), false, true);
        }
    }
}

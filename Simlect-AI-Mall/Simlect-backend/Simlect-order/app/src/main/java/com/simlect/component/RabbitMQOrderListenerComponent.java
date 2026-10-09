package com.simlect.component;

import com.simlect.api.support.CouponFeignSupport;
import com.simlect.constants.RabbitMQConfig;
import com.simlect.api.dto.RushingCouponMessageDTO;
import com.simlect.entity.po.OrderInfo;
import com.simlect.entity.query.OrderInfoQuery;
import com.simlect.mappers.OrderInfoMapper;
import com.rabbitmq.client.Channel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;

@Component
@Slf4j
public class RabbitMQOrderListenerComponent {

    @Resource
    private OrderInfoMapper<OrderInfo, OrderInfoQuery> orderInfoMapper;
    @Resource
    private CouponFeignSupport couponFeignSupport;

    @RabbitListener(queues = RabbitMQConfig.RUSHING_ORDER_QUEUE, ackMode = "MANUAL")
    public void handleOrder(RushingCouponMessageDTO message, Channel channel, Message mqMessage) {
        String userCouponId = message.getUserCouponId();
        Long deliveryTag = mqMessage.getMessageProperties().getDeliveryTag();
        try {
            if (orderInfoMapper.selectByOrderId(message.getOrderId()) != null
                    || couponFeignSupport.getUserCoupon(userCouponId) != null) {
                channel.basicAck(deliveryTag, false);
                return;
            }
            log.warn("遗留抢购 MQ 消息无对应订单，跳过建单 userCouponId={}", userCouponId);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("创建抢购订单失败: {}", userCouponId, e);
            try {
                if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                    channel.basicNack(deliveryTag, false, false);
                }
            } catch (IOException ex) {
                log.error("兜底NACK失败", ex);
            }
        }
    }
}

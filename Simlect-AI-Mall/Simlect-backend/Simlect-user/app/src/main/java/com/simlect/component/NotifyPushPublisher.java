package com.simlect.component;

import com.simlect.constants.Constants;
import com.simlect.entity.dto.MessageSendDTO;
import com.simlect.entity.enums.DateTimePatternEnum;
import com.simlect.entity.po.UserNotification;
import com.simlect.utils.DateUtil;
import com.simlect.utils.JsonUtils;
import com.simlect.utils.StringTools;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class NotifyPushPublisher {

    @Resource
    private RedissonClient redissonClient;

    public void push(UserNotification notification) {
        if (notification == null || StringTools.isEmpty(notification.getUserId())) {
            return;
        }
        try {
            MessageSendDTO dto = new MessageSendDTO();
            dto.setMessageType(Constants.WS_MESSAGE_TYPE_NOTIFY);
            dto.setUserId(notification.getUserId());
            dto.setNotificationId(notification.getNotificationId());
            dto.setTitle(notification.getTitle());
            dto.setContent(notification.getContent());
            dto.setBizType(notification.getBizType());
            dto.setBizId(notification.getBizId());
            if (notification.getCreateTime() != null) {
                dto.setCreateTime(DateUtil.format(notification.getCreateTime(),
                        DateTimePatternEnum.YYYY_MM_DD_HH_MM_SS.getPattern()));
            }
            RTopic topic = redissonClient.getTopic(Constants.WS_MESSAGE_TOPIC);
            topic.publish(dto);
            RTopic agentTopic = redissonClient.getTopic(
                    Constants.WS_MESSAGE_TOPIC_AGENT, StringCodec.INSTANCE);
            agentTopic.publish(JsonUtils.toJson(dto));
            log.debug("通知 WS 广播已发布 userId={}, notificationId={}",
                    notification.getUserId(), notification.getNotificationId());
        } catch (Exception e) {
            log.warn("通知 WS 广播失败 userId={}, notificationId={}",
                    notification.getUserId(), notification.getNotificationId(), e);
        }
    }
}

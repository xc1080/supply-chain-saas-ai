package com.simlect.component;

import com.simlect.constants.Constants;
import com.simlect.constants.RabbitMQConfig;
import com.simlect.constants.ReliableMessageSender;
import com.simlect.support.MqIdempotencyKeys;
import com.simlect.api.dto.UserTempBanDTO;
import com.simlect.entity.enums.MessageReliabilityLevelEnum;
import com.simlect.api.enums.UserStatusEnum;
import com.simlect.entity.po.UserInfo;
import com.simlect.biz.UserInfoService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class UserTempBanService {

    @Resource
    private UserInfoService userInfoService;
    @Resource
    private RedisComponent redisComponent;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private ReliableMessageSender reliableMessageSender;

    public static String formatUnbanTime(long unbanAtMs) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(unbanAtMs));
    }

    public String buildTempBanMessage(long unbanAtMs) {
        return "账号因违规被临时封禁，解封时间：" + formatUnbanTime(unbanAtMs);
    }

    public long banUserHours(String userId, int hours) {
        long durationMs = TimeUnit.HOURS.toMillis(hours);
        long unbanAtMs = System.currentTimeMillis() + durationMs;
        UserInfo patch = new UserInfo();
        patch.setStatus(UserStatusEnum.DISABLE.getStatus());
        userInfoService.updateUserInfoByUserId(patch, userId);
        redisComponent.cleanAllToken(userId);
        stringRedisTemplate.opsForValue().set(
                Constants.REDIS_USER_TEMP_BAN + userId,
                String.valueOf(unbanAtMs),
                durationMs,
                TimeUnit.MILLISECONDS
        );
        reliableMessageSender.sendMessage(
                RabbitMQConfig.USER_TEMP_BAN_EXCHANGE,
                RabbitMQConfig.USER_TEMP_BAN_DELAY_KEY,
                new UserTempBanDTO(userId, unbanAtMs),
                MqIdempotencyKeys.tempBanUnban(userId, unbanAtMs),
                MessageReliabilityLevelEnum.STANDARD
        );
        log.info("用户 {} 临时封禁 {} 小时，解封时间 {}", userId, hours, formatUnbanTime(unbanAtMs));
        return unbanAtMs;
    }

    public Long getUnbanAtMs(String userId) {
        String key = Constants.REDIS_USER_TEMP_BAN + userId;
        String value = stringRedisTemplate.opsForValue().get(key);
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            Long ttlMs = stringRedisTemplate.getExpire(key, TimeUnit.MILLISECONDS);
            if (ttlMs != null && ttlMs > 0) {
                return System.currentTimeMillis() + ttlMs;
            }
            return null;
        }
    }

    public boolean isTempBanned(String userId) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(Constants.REDIS_USER_TEMP_BAN + userId));
    }

    public boolean tryAutoUnban(UserTempBanDTO dto) {
        if (dto == null || dto.getUserId() == null) {
            return false;
        }
        return releaseTempBanIfPresent(dto.getUserId(), dto.getUnbanAtMs(), "MQ 自动解封");
    }

    public boolean manualUnban(String userId) {
        return releaseTempBanIfPresent(userId, null, "管理员手动解封");
    }

    private boolean releaseTempBanIfPresent(String userId, Long expectedUnbanAtMs, String scene) {
        String key = Constants.REDIS_USER_TEMP_BAN + userId;
        String value = stringRedisTemplate.opsForValue().get(key);
        if (value == null || value.isEmpty()) {
            log.info("用户 {} 无临时封禁标记，跳过{}", userId, scene);
            return false;
        }
        long currentUnbanAt;
        try {
            currentUnbanAt = Long.parseLong(value);
        } catch (NumberFormatException e) {
            stringRedisTemplate.delete(key);
            log.warn("用户 {} 临时封禁标记格式异常，已清除", userId);
            return false;
        }
        if (expectedUnbanAtMs != null && !expectedUnbanAtMs.equals(currentUnbanAt)) {
            log.info("用户 {} 忽略过期解封消息（期望 {}，当前 {}）", userId, expectedUnbanAtMs, currentUnbanAt);
            return false;
        }
        if (System.currentTimeMillis() < currentUnbanAt - 2000L) {
            log.info("用户 {} 解封时间未到，跳过{}", userId, scene);
            return false;
        }
        Boolean deleted = stringRedisTemplate.delete(key);
        if (!Boolean.TRUE.equals(deleted)) {
            return false;
        }
        UserInfo user = userInfoService.getUserInfoByUserId(userId);
        if (user != null && UserStatusEnum.DISABLE.getStatus().equals(user.getStatus())) {
            UserInfo patch = new UserInfo();
            patch.setStatus(UserStatusEnum.ENABLE.getStatus());
            userInfoService.updateUserInfoByUserId(patch, userId);
            log.info("用户 {} 已{}，解封时间 {}", userId, scene, formatUnbanTime(currentUnbanAt));
        } else {
            log.info("用户 {} 已完成{}（账号非禁用状态）", userId, scene);
        }
        return true;
    }

    public void clearTempBanMark(String userId) {
        stringRedisTemplate.delete(Constants.REDIS_USER_TEMP_BAN + userId);
    }

    public void banUserPermanent(String userId) {
        clearTempBanMark(userId);
        UserInfo patch = new UserInfo();
        patch.setStatus(UserStatusEnum.DISABLE.getStatus());
        userInfoService.updateUserInfoByUserId(patch, userId);
        redisComponent.cleanAllToken(userId);
    }
}

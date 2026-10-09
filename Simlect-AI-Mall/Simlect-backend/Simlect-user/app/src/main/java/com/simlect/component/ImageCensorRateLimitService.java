package com.simlect.component;

import com.simlect.constants.Constants;
import com.simlect.exception.BusinessException;
import com.simlect.utils.StringTools;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

@Component
public class ImageCensorRateLimitService {

    @Resource
    private CouponRushRateLimitService rateLimitService;

    public void checkUserAndIp(String userId, String ip) {
        if (StringTools.isEmpty(userId)) {
            throw new BusinessException("请先登录后再上传图片");
        }
        if (StringTools.isEmpty(ip)) {
            ip = "unknown";
        }
        if (!rateLimitService.tryAcquire(Constants.REDIS_RATE_LIMIT + "img:user:" + userId, 5, 1)) {
            throw new BusinessException("图片审核过于频繁，请5秒后再试");
        }
        if (!rateLimitService.tryAcquire(Constants.REDIS_RATE_LIMIT + "img:ip:" + ip, 5, 1)) {
            throw new BusinessException("图片审核过于频繁，请5秒后再试");
        }
    }
}

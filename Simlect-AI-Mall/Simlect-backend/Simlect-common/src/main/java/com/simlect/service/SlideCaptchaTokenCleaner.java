package com.simlect.service;

import com.simlect.utils.StringTools;
import com.xingyuv.captcha.service.CaptchaCacheService;
import com.xingyuv.captcha.service.impl.CaptchaServiceFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SlideCaptchaTokenCleaner {

    private static final String CAPTCHA_KEY = "RUNNING:CAPTCHA:%s";

    @Value("${aj.captcha.cache-type:redis}")
    private String cacheType;

    public void discardToken(String token) {
        if (StringTools.isEmpty(token)) {
            return;
        }
        try {
            CaptchaCacheService cache = CaptchaServiceFactory.getCache(cacheType);
            if (cache == null) {
                log.warn("滑动拼图缓存服务未就绪，无法清理 token");
                return;
            }
            String key = String.format(CAPTCHA_KEY, token);
            if (cache.exists(key)) {
                cache.delete(key);
                log.debug("已清理未使用的滑动拼图 token: {}", token);
            }
        } catch (Exception e) {
            log.warn("清理滑动拼图 token 失败: {}", e.getMessage());
        }
    }
}

package com.simlect.interceptor;

import com.simlect.component.RedisComponent;
import com.simlect.entity.enums.ResponseCodeEnum;
import com.simlect.exception.BusinessException;
import com.simlect.utils.AuthCookieHelper;
import com.simlect.utils.StringTools;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@ConditionalOnProperty(name = "simlect.security.admin-interceptor", havingValue = "true")
public class AppInterceptor implements HandlerInterceptor {

    @Resource
    private RedisComponent redisComponent;

    @Resource
    private AuthCookieHelper authCookieHelper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (null == handler) {
            return false;
        }
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        String token = authCookieHelper.resolveAdminToken(request);
        if (StringTools.isEmpty(token)) {
            throw new BusinessException(ResponseCodeEnum.CODE_901);
        }
        Object sessionObj = redisComponent.getLoginInfo4Admin(token);
        if (sessionObj == null) {
            throw new BusinessException(ResponseCodeEnum.CODE_901);
        }
        return true;
    }
}

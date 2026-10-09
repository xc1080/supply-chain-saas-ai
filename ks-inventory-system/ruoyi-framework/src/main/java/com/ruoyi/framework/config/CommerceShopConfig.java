package com.ruoyi.framework.config;

import com.ruoyi.common.core.tenant.CommerceShopContext;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.service.CommerceMerchantService;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Configuration
@Profile("local")
public class CommerceShopConfig implements WebMvcConfigurer {
    private final CommerceMerchantService merchants;
    public CommerceShopConfig(CommerceMerchantService merchants) { this.merchants=merchants; }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
                CommerceShopContext.clear();
                String path=request.getRequestURI();
                if(path.equals("/commerce/shops") || path.equals("/commerce/context"))return true;
                String shop=request.getHeader("X-Shop-ID"); if(shop==null || shop.isEmpty())shop="default";
                merchants.requireShop(shop,SecurityUtils.getUserId(),!"GET".equals(request.getMethod()));
                CommerceShopContext.set(shop); response.setHeader("X-Shop-ID",shop); return true;
            }
            @Override public void afterCompletion(HttpServletRequest request,HttpServletResponse response,Object handler,Exception error) { CommerceShopContext.clear(); }
        }).addPathPatterns("/commerce/**");
    }
}

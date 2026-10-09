package com.ruoyi.framework.config;

import com.ruoyi.common.core.tenant.CommerceShopContext;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.service.CommerceMerchantService;
import com.ruoyi.system.service.CommerceAccessPolicy;
import com.ruoyi.system.service.CommercePermission;
import com.ruoyi.common.exception.ServiceException;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.method.HandlerMethod;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Configuration
@Profile({"local","commerce"})
public class CommerceShopConfig implements WebMvcConfigurer {
    private final CommerceMerchantService merchants;
    public CommerceShopConfig(CommerceMerchantService merchants) { this.merchants=merchants; }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
                CommerceShopContext.clear();
                CommercePermission permission=handler instanceof HandlerMethod ? ((HandlerMethod)handler).getMethodAnnotation(CommercePermission.class) : null;
                if(permission==null)throw new ServiceException("商城入口尚未声明业务权限",403);
                boolean customer=CommerceAccessPolicy.customerService();
                if(customer && !permission.customer())throw new ServiceException("客户服务账号不能访问商家入口",403);
                if(customer && permission.ownerScoped() && "GET".equals(request.getMethod()))
                    CommerceAccessPolicy.requireCustomerOwner(request.getParameter("ownerId"));
                if(!permission.shopRequired())return true;
                String shop=request.getHeader("X-Shop-ID"); if(shop==null || shop.isEmpty())shop="default";
                if(customer)merchants.requireCustomerService(shop,SecurityUtils.getUserId());
                else merchants.requireCapability(shop,SecurityUtils.getUserId(),permission.value());
                CommerceShopContext.set(shop); response.setHeader("X-Shop-ID",shop); return true;
            }
            @Override public void afterCompletion(HttpServletRequest request,HttpServletResponse response,Object handler,Exception error) { CommerceShopContext.clear(); }
        }).addPathPatterns("/commerce/**");
    }
}

package com.ruoyi.framework.config;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.service.CommerceAccessPolicy;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Set;

/** Legacy ERP is shared tenant administration, never a shop employee's private data view. */
@Configuration
@Profile({"local","commerce"})
public class TenantErpAccessConfig implements WebMvcConfigurer {
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
                if(SecurityUtils.getAuthentication()==null)return true; // Spring Security owns unauthenticated rejection.
                Set<String> roles=CommerceAccessPolicy.roles(SecurityUtils.getLoginUser());
                if(!CommerceAccessPolicy.tenantAdministration(roles))
                    throw new ServiceException("此入口属于租户共享管理，店铺成员请使用商城工作台",403);
                return true;
            }
        }).addPathPatterns("/baseDate/**","/inventory/**","/purchase/**","/sales/**","/afterSales/**",
                "/system/user/**","/system/role/**","/system/dept/**","/system/menu/**","/system/config/**",
                "/system/post/**","/system/dict/**","/monitor/**","/tool/**","/jmreport/**","/druid/**","/common/**");
        // A trusted customer proxy can only use explicitly annotated commerce routes.
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
                if(SecurityUtils.getAuthentication()==null)return true;
                if(CommerceAccessPolicy.customerService())throw new ServiceException("客户服务账号仅可访问商城客户入口",403);
                return true;
            }
        }).addPathPatterns("/**").excludePathPatterns("/commerce/**","/login","/logout","/captchaImage","/error");
    }
}

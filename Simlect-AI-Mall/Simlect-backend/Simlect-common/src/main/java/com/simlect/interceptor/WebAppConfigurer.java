package com.simlect.interceptor;

import jakarta.annotation.Resource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@ConditionalOnBean(AppInterceptor.class)
public class WebAppConfigurer implements WebMvcConfigurer {

    @Resource
    private AppInterceptor appInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(appInterceptor)
                .addPathPatterns("/admin/**")
                .excludePathPatterns(
                        "/admin/account/**",
                        "/admin/file/getResource",
                        "/admin/file/getResource/**",
                        "/internal/**",
                        "/actuator/**",
                        "/error"
                );
    }
}

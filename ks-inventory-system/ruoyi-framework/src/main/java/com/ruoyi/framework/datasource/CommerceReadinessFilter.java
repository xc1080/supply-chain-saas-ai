package com.ruoyi.framework.datasource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.IOException;
import java.util.UUID;

/** Do not expose a partially migrated commerce schema. Request IDs contain no customer data. */
@Component
@Profile({"local","commerce"})
public class CommerceReadinessFilter extends OncePerRequestFilter {
    private final ObjectProvider<CommerceMaintenance> maintenance;
    public CommerceReadinessFilter(ObjectProvider<CommerceMaintenance> maintenance){this.maintenance=maintenance;}
    @Override protected boolean shouldNotFilter(HttpServletRequest request){return !request.getRequestURI().startsWith("/commerce/");}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
        String supplied=request.getHeader("X-Request-ID");
        response.setHeader("X-Request-ID",supplied!=null&&supplied.matches("[A-Za-z0-9_-]{8,64}")?supplied:UUID.randomUUID().toString().replace("-",""));
        CommerceMaintenance worker=maintenance.getIfAvailable();
        if(worker==null||!worker.isReady()){
            response.setStatus(503);response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":503,\"msg\":\"交易服务正在初始化，请稍后重试\"}");return;
        }
        chain.doFilter(request,response);
    }
}

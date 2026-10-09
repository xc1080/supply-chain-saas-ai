package com.ruoyi.framework.security.filter;

import java.io.IOException;
import com.ruoyi.common.core.tenant.TenantContext;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.framework.datasource.TenantRegistry;
import org.springframework.beans.factory.ObjectProvider;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.framework.web.service.TokenService;

/**
 * token过滤器 验证token有效性
 * 
 * @author KrityCat
 */
@Component
public class JwtAuthenticationTokenFilter extends OncePerRequestFilter {
    @Autowired
    private TokenService tokenService;

    @Autowired private ObjectProvider<TenantRegistry> tenantRegistry;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
        LoginUser loginUser = tokenService.getLoginUser(request);
        if (StringUtils.isNotNull(loginUser) && StringUtils.isNull(SecurityUtils.getAuthentication())) {
            tokenService.verifyToken(loginUser);
            UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities());
            authenticationToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authenticationToken);
        }
            TenantRegistry registry = tenantRegistry.getIfAvailable();
            if (registry != null && loginUser != null) {
                String tenant = registry.tenantFor(loginUser.getUserId());
                String supplied = request.getHeader("X-Tenant-ID");
                if (supplied != null && !tenant.equals(supplied)) throw new ServiceException("不能切换到其他租户", 403);
                String path = request.getRequestURI();
                if (!"demo".equals(tenant) && (path.startsWith("/monitor/") || path.startsWith("/tool/") || path.startsWith("/druid/") || path.startsWith("/jmreport/") || path.startsWith("/common/") || path.equals("/register")))
                    throw new ServiceException("该入口仅对平台演示管理员开放", 403);
                TenantContext.set(tenant);
                response.setHeader("X-Tenant-ID", tenant);
            }
            chain.doFilter(request, response);
        } catch (ServiceException ex) {
            SecurityContextHolder.clearContext();
            int code=ex.getCode()!=null&&ex.getCode()==503?503:403;
            response.setStatus(code); response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(code==503?"{\"code\":503,\"msg\":\"账号校验暂时不可用\"}":"{\"code\":403,\"msg\":\"租户访问被拒绝\"}");
        } finally { TenantContext.clear(); }
    }
}

package com.simlect.web;

import com.simlect.constants.InternalApiHeaders;
import com.simlect.utils.StringTools;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = request.getHeader(InternalApiHeaders.TRACE_ID);
        if (StringTools.isEmpty(traceId)) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        MDC.put(InternalApiHeaders.TRACE_ID_MDC, traceId);
        response.setHeader(InternalApiHeaders.TRACE_ID, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(InternalApiHeaders.TRACE_ID_MDC);
        }
    }
}

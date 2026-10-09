package com.simlect.api.feign;

import com.simlect.constants.InternalApiHeaders;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;

public class FeignTraceInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        String traceId = MDC.get(InternalApiHeaders.TRACE_ID_MDC);
        if (!StringUtils.hasText(traceId)) {
            traceId = java.util.UUID.randomUUID().toString().replace("-", "");
            MDC.put(InternalApiHeaders.TRACE_ID_MDC, traceId);
        }
        template.header(InternalApiHeaders.TRACE_ID, traceId);
    }
}

package com.ruoyi.framework.config;

import com.ruoyi.system.service.CommerceAccessPolicy;
import com.ruoyi.system.service.CommercePermission;
import org.springframework.context.annotation.Profile;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import java.lang.reflect.Type;
import java.util.Map;

/** Check the decoded body without consuming the servlet input stream in the interceptor. */
@ControllerAdvice
@Profile({"local","commerce"})
public class CommerceCustomerBodyAdvice extends RequestBodyAdviceAdapter {
    @Override public boolean supports(MethodParameter parameter,Type targetType,Class<? extends HttpMessageConverter<?>> converterType) {
        CommercePermission permission=parameter.getMethodAnnotation(CommercePermission.class);
        return permission!=null && permission.ownerScoped();
    }
    @Override public Object afterBodyRead(Object body,HttpInputMessage message,MethodParameter parameter,Type targetType,Class<? extends HttpMessageConverter<?>> converterType) {
        Object owner=body instanceof Map ? ((Map<?,?>)body).get("ownerId") : null;
        CommerceAccessPolicy.requireCustomerOwner(owner instanceof String ? (String)owner : null);
        return body;
    }
}

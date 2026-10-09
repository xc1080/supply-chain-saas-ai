package com.simlect.api.feign;

import feign.Request;
import feign.RequestInterceptor;
import feign.codec.ErrorDecoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.TimeUnit;

public class SimlectFeignConfiguration {

    @Bean
    public Request.Options feignRequestOptions(
            @Value("${simlect.feign.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${simlect.feign.read-timeout-ms:10000}") int readTimeoutMs) {
        return new Request.Options(
                connectTimeoutMs, TimeUnit.MILLISECONDS,
                readTimeoutMs, TimeUnit.MILLISECONDS,
                true);
    }

    @Bean
    public ErrorDecoder simlectFeignErrorDecoder() {
        return new SimlectFeignErrorDecoder();
    }

    @Bean
    public RequestInterceptor feignInternalAuthInterceptor(
            @Value("${simlect.internal.token:your-token}") String internalToken) {
        return new FeignInternalAuthInterceptor(internalToken);
    }

    @Bean
    public RequestInterceptor feignTraceInterceptor() {
        return new FeignTraceInterceptor();
    }
}

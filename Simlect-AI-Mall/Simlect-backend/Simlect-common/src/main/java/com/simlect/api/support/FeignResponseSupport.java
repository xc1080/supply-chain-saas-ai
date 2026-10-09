package com.simlect.api.support;

import com.simlect.entity.enums.ResponseCodeEnum;
import com.simlect.entity.vo.ResponseVO;
import com.simlect.exception.BusinessException;
import com.simlect.utils.StringTools;
import feign.FeignException;
import feign.RetryableException;
import org.springframework.stereotype.Component;

@Component
public class FeignResponseSupport {

    public <T> T unwrap(ResponseVO<T> resp, String fallbackMsg) {
        if (resp == null) {
            throw new BusinessException(fallbackMsg);
        }
        if (resp.getCode() != null && !ResponseCodeEnum.CODE_200.getCode().equals(resp.getCode())) {
            throw new BusinessException(StringTools.isEmpty(resp.getInfo()) ? fallbackMsg : resp.getInfo());
        }
        return resp.getData();
    }

    public BusinessException toBusiness(Throwable ex, String fallbackMsg) {
        if (ex instanceof BusinessException biz) {
            return biz;
        }
        if (ex instanceof RetryableException) {
            return new BusinessException(ResponseCodeEnum.CODE_500.getCode(), "下游服务超时或不可用，请稍后重试");
        }
        if (ex instanceof FeignException feignEx) {
            int status = feignEx.status();
            if (status == 503 || status == 502 || status == 504 || status == 408) {
                return new BusinessException(ResponseCodeEnum.CODE_500.getCode(), "下游服务暂不可用，请稍后重试");
            }
            return new BusinessException(ResponseCodeEnum.CODE_500.getCode(),
                    StringTools.isEmpty(fallbackMsg) ? "服务调用失败，请稍后重试" : fallbackMsg);
        }
        Throwable cause = ex.getCause();
        if (cause != null && cause != ex) {
            return toBusiness(cause, fallbackMsg);
        }
        return new BusinessException(StringTools.isEmpty(fallbackMsg) ? "服务调用失败" : fallbackMsg);
    }

    public <T> T call(FeignCallable<T> callable, String fallbackMsg) {
        try {
            return unwrap(callable.call(), fallbackMsg);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw toBusiness(e, fallbackMsg);
        }
    }

    public void run(FeignRunnable runnable, String fallbackMsg) {
        try {
            unwrap(runnable.run(), fallbackMsg);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw toBusiness(e, fallbackMsg);
        }
    }

    @FunctionalInterface
    public interface FeignCallable<T> {
        ResponseVO<T> call() throws Exception;
    }

    @FunctionalInterface
    public interface FeignRunnable {
        ResponseVO<?> run() throws Exception;
    }
}

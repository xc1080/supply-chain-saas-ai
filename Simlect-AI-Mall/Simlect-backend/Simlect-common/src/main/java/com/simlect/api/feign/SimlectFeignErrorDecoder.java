package com.simlect.api.feign;

import com.simlect.entity.enums.ResponseCodeEnum;
import com.simlect.entity.vo.ResponseVO;
import com.simlect.exception.BusinessException;
import com.simlect.utils.JsonUtils;
import com.simlect.utils.StringTools;
import feign.Response;
import feign.codec.ErrorDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class SimlectFeignErrorDecoder implements ErrorDecoder {

    private static final Logger log = LoggerFactory.getLogger(SimlectFeignErrorDecoder.class);
    private final ErrorDecoder defaultDecoder = new Default();

    @Override
    public Exception decode(String methodKey, Response response) {
        int status = response == null ? 0 : response.status();
        String body = readBody(response);
        if (!StringTools.isEmpty(body)) {
            try {
                ResponseVO<?> vo = JsonUtils.parseObject(body, ResponseVO.class);
                if (vo != null && !StringTools.isEmpty(vo.getInfo())) {
                    Integer code = vo.getCode() == null ? ResponseCodeEnum.CODE_500.getCode() : vo.getCode();
                    return new BusinessException(code, vo.getInfo());
                }
            } catch (Exception parseEx) {
                log.debug("Feign error body 非 ResponseVO, methodKey={}", methodKey);
            }
        }
        if (status == 408 || status == 504 || status == 503 || status == 502) {
            return new BusinessException(ResponseCodeEnum.CODE_500.getCode(), "下游服务暂不可用，请稍后重试");
        }
        if (status == 404) {
            return new BusinessException(ResponseCodeEnum.CODE_404.getCode(), "下游服务接口不存在");
        }
        Exception fallback = defaultDecoder.decode(methodKey, response);
        if (fallback instanceof BusinessException) {
            return fallback;
        }
        return new BusinessException(ResponseCodeEnum.CODE_500.getCode(),
                "服务调用失败(" + status + ")，请稍后重试");
    }

    private static String readBody(Response response) {
        if (response == null || response.body() == null) {
            return null;
        }
        try (InputStream in = response.body().asInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}

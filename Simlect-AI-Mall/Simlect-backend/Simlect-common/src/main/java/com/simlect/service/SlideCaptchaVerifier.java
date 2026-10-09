package com.simlect.service;

import com.simlect.exception.BusinessException;
import com.simlect.utils.StringTools;
import com.xingyuv.captcha.model.common.ResponseModel;
import com.xingyuv.captcha.model.vo.CaptchaVO;
import com.xingyuv.captcha.service.CaptchaService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

@Component
public class SlideCaptchaVerifier {

    private static final String SUCCESS_CODE = "0000";

    @Resource
    private CaptchaService captchaService;

    public void verify(String captchaVerification) {
        if (StringTools.isEmpty(captchaVerification)) {
            throw new BusinessException("请先完成滑动拼图验证");
        }
        CaptchaVO vo = new CaptchaVO();
        vo.setCaptchaVerification(captchaVerification);
        ResponseModel result = captchaService.verification(vo);
        if (result == null || !SUCCESS_CODE.equals(result.getRepCode())) {
            String msg = result != null && !StringTools.isEmpty(result.getRepMsg())
                    ? result.getRepMsg()
                    : "滑动验证已失效，请重新验证";
            throw new BusinessException(msg);
        }
    }
}

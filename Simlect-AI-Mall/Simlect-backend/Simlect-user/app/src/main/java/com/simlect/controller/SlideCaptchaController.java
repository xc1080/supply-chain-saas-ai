package com.simlect.controller;

import com.simlect.service.SlideCaptchaTokenCleaner;
import com.simlect.utils.StringTools;
import com.xingyuv.captcha.model.common.ResponseModel;
import com.xingyuv.captcha.model.vo.CaptchaVO;
import com.xingyuv.captcha.service.CaptchaService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/captcha")
public class SlideCaptchaController {

    @Resource
    private CaptchaService captchaService;

    @Resource
    private SlideCaptchaTokenCleaner slideCaptchaTokenCleaner;

    @PostMapping("/get")
    public ResponseModel get(@RequestBody CaptchaVO data, HttpServletRequest request) {
        data.setBrowserInfo(remoteId(request));
        return captchaService.get(data);
    }

    @PostMapping("/check")
    public ResponseModel check(@RequestBody CaptchaVO data, HttpServletRequest request) {
        data.setBrowserInfo(remoteId(request));
        return captchaService.check(data);
    }

    @PostMapping("/cancel")
    public ResponseModel cancel(@RequestBody CaptchaVO data) {
        slideCaptchaTokenCleaner.discardToken(data.getToken());
        return ResponseModel.success();
    }

    private static String remoteId(HttpServletRequest request) {
        String xfwd = request.getHeader("X-Forwarded-For");
        String ip = null;
        if (!StringTools.isEmpty(xfwd)) {
            ip = xfwd.split(",")[0].trim();
        }
        if (StringTools.isEmpty(ip)) {
            ip = request.getRemoteAddr();
        }
        String ua = request.getHeader("User-Agent");
        return ip + (ua != null ? ua : "");
    }
}

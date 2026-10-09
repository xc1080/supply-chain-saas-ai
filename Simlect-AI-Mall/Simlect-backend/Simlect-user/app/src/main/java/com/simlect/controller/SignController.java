package com.simlect.controller;

import com.simlect.annotation.GlobalInterceptor;
import com.simlect.entity.vo.ResponseVO;
import com.simlect.biz.SignService;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RequestMapping("/sign")
@RestController
public class SignController extends ABaseController {

    @Resource
    private SignService signService;

    // 获取签到信息
    @PostMapping("/getSignCalendar")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO getSignCalendar(@NotEmpty String yearMonth) {
        String userId = getTokenUserInfo().getUserId();
        return getSuccessResponseVO(signService.getSignCalendar(userId, yearMonth));
    }

    // 签到
    @PostMapping("/sign")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO sign() {
        String userId = getTokenUserInfo().getUserId();
        signService.sign(userId);
        return getSuccessResponseVO(null);
    }

    // 补签
    @PostMapping("/msign")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO msign(@NotEmpty String date) {
        String userId = getTokenUserInfo().getUserId();
        signService.msign(userId, date);
        return getSuccessResponseVO(null);
    }
}

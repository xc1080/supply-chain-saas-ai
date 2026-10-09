package com.simlect.controller;

import com.simlect.annotation.GlobalInterceptor;
import com.simlect.entity.vo.ResponseVO;
import com.simlect.biz.UserNotificationService;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RequestMapping("/userNotification")
@RestController
public class UserNotificationController extends ABaseController {

    @Resource
    private UserNotificationService userNotificationService;

    @PostMapping("/loadNotification")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO loadNotification(@NotNull Integer pageNo, Integer readStatus) {
        return getSuccessResponseVO(userNotificationService.loadPage(getTokenUserInfo().getUserId(), pageNo, readStatus));
    }

    @GetMapping("/countUnread")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO countUnread() {
        return getSuccessResponseVO(userNotificationService.countUnread(getTokenUserInfo().getUserId()));
    }

    @PostMapping("/markRead")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO markRead(@NotEmpty String notificationId) {
        userNotificationService.markRead(getTokenUserInfo().getUserId(), notificationId);
        return getSuccessResponseVO(null);
    }

    @PostMapping("/markAllRead")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO markAllRead() {
        userNotificationService.markAllRead(getTokenUserInfo().getUserId());
        return getSuccessResponseVO(null);
    }

    @PostMapping("/deleteNotification")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO deleteNotification(@NotEmpty String notificationId) {
        userNotificationService.delete(getTokenUserInfo().getUserId(), notificationId);
        return getSuccessResponseVO(null);
    }

    @PostMapping("/clearAll")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO clearAll() {
        userNotificationService.clearAll(getTokenUserInfo().getUserId());
        return getSuccessResponseVO(null);
    }

    @GetMapping("/getPopupNotification")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO getPopupNotification() {
        return getSuccessResponseVO(userNotificationService.getPopupNotification(getTokenUserInfo().getUserId()));
    }

    @PostMapping("/clearPopupNotification")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO clearPopupNotification(@NotEmpty String notificationId) {
        userNotificationService.clearPopupNotification(getTokenUserInfo().getUserId(), notificationId);
        return getSuccessResponseVO(null);
    }
}

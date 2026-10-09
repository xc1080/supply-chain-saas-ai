package com.simlect.controller;

import com.simlect.annotation.GlobalInterceptor;
import com.simlect.entity.vo.ResponseVO;
import com.simlect.biz.UserBrowseHistoryService;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RequestMapping("/browseHistory")
@RestController
public class UserBrowseHistoryController extends ABaseController {

    @Resource
    private UserBrowseHistoryService userBrowseHistoryService;

    @PostMapping("/loadBrowse")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO loadBrowse(@NotNull Integer pageNo) {
        return getSuccessResponseVO(userBrowseHistoryService.loadBrowsePage(getTokenUserInfo().getUserId(), pageNo));
    }

    @PostMapping("/clearBrowse")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO clearBrowse() {
        userBrowseHistoryService.clearBrowse(getTokenUserInfo().getUserId());
        return getSuccessResponseVO(null);
    }

    @PostMapping("/removeBrowse")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO removeBrowse(@NotNull Long historyId) {
        userBrowseHistoryService.removeBrowse(getTokenUserInfo().getUserId(), historyId);
        return getSuccessResponseVO(null);
    }
}


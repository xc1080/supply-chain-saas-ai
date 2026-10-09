package com.simlect.controller;

import com.simlect.annotation.GlobalInterceptor;
import com.simlect.entity.vo.ResponseVO;
import com.simlect.biz.UserProductFavoriteService;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RequestMapping("/userFavorite")
@RestController
public class UserFavoriteController extends ABaseController {

    @Resource
    private UserProductFavoriteService userProductFavoriteService;

    @PostMapping("/loadFavorite")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO loadFavorite(@NotNull Integer pageNo) {
        return getSuccessResponseVO(userProductFavoriteService.loadFavoritePage(getTokenUserInfo().getUserId(), pageNo));
    }

    @PostMapping("/toggleFavorite")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO toggleFavorite(@NotEmpty String productId) {
        boolean favorited = userProductFavoriteService.toggleFavorite(getTokenUserInfo().getUserId(), productId);
        return getSuccessResponseVO(favorited);
    }

    @PostMapping("/isFavorite")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO isFavorite(@NotEmpty String productId) {
        return getSuccessResponseVO(userProductFavoriteService.isFavorite(getTokenUserInfo().getUserId(), productId));
    }

    @PostMapping("/removeFavorite")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO removeFavorite(@NotEmpty String favoriteId) {
        userProductFavoriteService.removeFavorite(getTokenUserInfo().getUserId(), favoriteId);
        return getSuccessResponseVO(null);
    }
}

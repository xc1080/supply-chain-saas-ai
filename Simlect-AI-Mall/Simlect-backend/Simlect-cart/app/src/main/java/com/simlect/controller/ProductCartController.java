package com.simlect.controller;

import com.simlect.annotation.GlobalInterceptor;
import com.simlect.entity.dto.TokenUserInfoDTO;
import com.simlect.entity.enums.PageSize;
import com.simlect.entity.po.ProductCart;
import com.simlect.entity.query.ProductCartQuery;
import com.simlect.entity.query.SimplePage;
import com.simlect.entity.vo.PaginationResultVO;
import com.simlect.api.vo.ProductCartVO;
import com.simlect.entity.vo.ResponseVO;
import com.simlect.exception.BusinessException;
import com.simlect.biz.ProductCartService;
import com.simlect.utils.StringTools;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequestMapping("/productCart")
@RestController
public class ProductCartController extends ABaseController{

    @Resource
    private ProductCartService productCartService;
    // 加入购物车
    @PostMapping("/add2Cart")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO add2Cart(ProductCart productCart){
        TokenUserInfoDTO tokenUserInfo = getTokenUserInfo();
        if (tokenUserInfo == null || StringTools.isEmpty(tokenUserInfo.getUserId())) {
            throw new BusinessException("请先登录");
        }
        String userId = tokenUserInfo.getUserId();
        productCart.setUserId(userId);
        productCartService.add2Cart(productCart);
        return getSuccessResponseVO(null);
    }

    // 获取购物车列表
    @PostMapping("/loadProductCart")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO loadProductCart(@NotNull Integer pageNo){
        TokenUserInfoDTO tokenUserInfo = getTokenUserInfo();
        if (tokenUserInfo == null || StringTools.isEmpty(tokenUserInfo.getUserId())) {
            throw new BusinessException("请先登录");
        }
        String userId = tokenUserInfo.getUserId();
        SimplePage page = new SimplePage(pageNo, PageSize.SIZE15.getSize());
        ProductCartQuery param = new ProductCartQuery();
        param.setSimplePage(page);
        // 按最后操作时间降序排序
        param.setOrderBy("last_update_time desc");
        PaginationResultVO<ProductCartVO> result = productCartService.findListByPageAndUserId(param, userId);
        return getSuccessResponseVO(result);
    }

    // 移除购物车
    @PostMapping("/deleteCart")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO deleteCart(@NotEmpty String cartId){
        TokenUserInfoDTO tokenUserInfo = getTokenUserInfo();
        if (tokenUserInfo == null || StringTools.isEmpty(tokenUserInfo.getUserId())) {
            throw new BusinessException("请先登录");
        }
        String userId = tokenUserInfo.getUserId();
        ProductCartQuery param = new ProductCartQuery();
        param.setCartId(cartId);
        param.setUserId(userId);
        productCartService.deleteByParam(param);
        return getSuccessResponseVO(null);
    }
}

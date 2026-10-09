package com.simlect.controller;

import com.simlect.annotation.GlobalInterceptor;
import com.simlect.component.RedisComponent;
import com.simlect.entity.po.UserAddress;
import com.simlect.entity.query.UserAddressQuery;
import com.simlect.entity.vo.ResponseVO;
import com.simlect.biz.UserAddressService;
import com.simlect.valid.Create;
import com.simlect.valid.Update;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequestMapping("/userAddress")
@RestController
@Validated
public class UserAddressController extends ABaseController{

    @Resource
    private RedisComponent redisComponent;

    @Resource
    private UserAddressService userAddressService;

    // 获取用户地址
    @GetMapping("/loadDataList")
    // AOP实现登录校验
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO loadDataList(){
        String userId = getTokenUserInfo().getUserId();
        // 根据userId查询地址
        // 创建查询条件
        UserAddressQuery query = new UserAddressQuery();
        query.setUserId(userId);
        query.setOrderBy("default_type desc");
        List<UserAddress> list = userAddressService.findListByParam(query);
        // 返回地址
        return getSuccessResponseVO(list);
    }

    // 添加用户地址
    @PostMapping("/addAddress")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO addAddress(@Validated(Create.class) UserAddress userAddress){
        userAddress.setUserId(getTokenUserInfo().getUserId());
        userAddressService.saveAddress(userAddress);
        return getSuccessResponseVO(null);
    }

    // 修改用户地址
    @PostMapping("/updateAddress")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO updateAddress(@Validated(Update.class) UserAddress userAddress){
        userAddress.setUserId(getTokenUserInfo().getUserId());
        userAddressService.saveAddress(userAddress);
        return getSuccessResponseVO(null);
    }

    // 设置地址为默认
    @PostMapping("/updateDefault")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO updateDefault(@NotEmpty String addressId){
        String userId = getTokenUserInfo().getUserId();
        userAddressService.updateDefault(userId,addressId);
        return getSuccessResponseVO(null);
    }

    // 删除用户地址
    @PostMapping("/delAddress")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO delAddress(@NotEmpty String addressId){
        String userId = getTokenUserInfo().getUserId();
        userAddressService.deleteUserAddress(userId,addressId);
        return getSuccessResponseVO(null);
    }
}

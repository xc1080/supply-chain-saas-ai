package com.simlect.controller.admin;

import java.util.List;

import com.simlect.entity.query.UserAddressQuery;
import com.simlect.entity.po.UserAddress;
import com.simlect.entity.vo.ResponseVO;
import com.simlect.biz.UserAddressService;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;

@RestController("adminUserAddressController")
@RequestMapping("/admin/userAddress")
public class UserAddressController extends com.simlect.controller.admin.ABaseController{

	@Resource
	private UserAddressService userAddressService;

	@PostMapping("/loadDataList")
	public ResponseVO loadDataList(UserAddressQuery query){
		return getSuccessResponseVO(userAddressService.findListByPage(query));
	}

	@PostMapping("/add")
	public ResponseVO add(UserAddress bean) {
		userAddressService.add(bean);
		return getSuccessResponseVO(null);
	}

	@PostMapping("/addBatch")
	public ResponseVO addBatch(@RequestBody List<UserAddress> listBean) {
		userAddressService.addBatch(listBean);
		return getSuccessResponseVO(null);
	}

	@PostMapping("/addOrUpdateBatch")
	public ResponseVO addOrUpdateBatch(@RequestBody List<UserAddress> listBean) {
		userAddressService.addBatch(listBean);
		return getSuccessResponseVO(null);
	}

	@PostMapping("/getUserAddressByAddressId")
	public ResponseVO getUserAddressByAddressId(String addressId) {
		return getSuccessResponseVO(userAddressService.getUserAddressByAddressId(addressId));
	}

	@PostMapping("/updateUserAddressByAddressId")
	public ResponseVO updateUserAddressByAddressId(UserAddress bean,String addressId) {
		userAddressService.updateUserAddressByAddressId(bean,addressId);
		return getSuccessResponseVO(null);
	}

	@PostMapping("/deleteUserAddressByAddressId")
	public ResponseVO deleteUserAddressByAddressId(String addressId) {
		userAddressService.deleteUserAddressByAddressId(addressId);
		return getSuccessResponseVO(null);
	}
}

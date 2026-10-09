package com.simlect.biz;

import java.util.List;

import com.simlect.entity.query.UserAddressQuery;
import com.simlect.entity.po.UserAddress;
import com.simlect.entity.vo.PaginationResultVO;
import jakarta.validation.constraints.NotEmpty;

public interface UserAddressService {

	List<UserAddress> findListByParam(UserAddressQuery param);

	Integer findCountByParam(UserAddressQuery param);

	PaginationResultVO<UserAddress> findListByPage(UserAddressQuery param);

	Integer add(UserAddress bean);

	Integer addBatch(List<UserAddress> listBean);

	Integer addOrUpdateBatch(List<UserAddress> listBean);

	Integer updateByParam(UserAddress bean,UserAddressQuery param);

	Integer deleteByParam(UserAddressQuery param);

	UserAddress getUserAddressByAddressId(String addressId);

	Integer updateUserAddressByAddressId(UserAddress bean,String addressId);

	Integer deleteUserAddressByAddressId(String addressId);

	void saveAddress(UserAddress userAddress);

	void updateDefault(@NotEmpty String userId, @NotEmpty String addressId);

	void deleteUserAddress(@NotEmpty String userId, @NotEmpty String addressId);
}

package com.simlect.mappers;

import com.simlect.entity.po.UserAddress;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface UserAddressMapper<T,P> extends BaseMapper<T,P> {

	 Integer updateByAddressId(@Param("bean") T t,@Param("addressId") String addressId);

	 Integer deleteByAddressId(@Param("addressId") String addressId);

	 T selectByAddressId(@Param("addressId") String addressId);

    void cleanAllDefaultType(@Param("userId")String userId);

	void updateDefaultType(@Param("addressId")String addressId);
}

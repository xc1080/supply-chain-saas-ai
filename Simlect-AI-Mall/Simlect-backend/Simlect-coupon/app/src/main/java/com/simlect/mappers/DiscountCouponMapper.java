package com.simlect.mappers;

import org.apache.ibatis.annotations.Param;

public interface DiscountCouponMapper<T,P> extends BaseMapper<T,P> {

	 Integer updateByCouponId(@Param("bean") T t,@Param("couponId") String couponId);

	 Integer deleteByCouponId(@Param("couponId") String couponId);

	 T selectByCouponId(@Param("couponId") String couponId);

	T selectByCouponIdForUpdate(@Param("couponId") String couponId);

	 // 扣减库存
	Integer deductStock(@Param("couponId") String couponId);

	Integer addStock(@Param("couponId") String couponId);
}

package com.simlect.mappers;

import org.apache.ibatis.annotations.Param;

public interface OrderCouponRelMapper<T,P> extends BaseMapper<T,P> {

	Integer updateById(@Param("bean") T t, @Param("id") Long id);
	Integer deleteById(@Param("id") Long id);
	T selectById(@Param("id") Long id);

}

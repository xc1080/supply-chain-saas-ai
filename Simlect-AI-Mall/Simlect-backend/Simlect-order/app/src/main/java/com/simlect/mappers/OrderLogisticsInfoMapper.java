package com.simlect.mappers;

import org.apache.ibatis.annotations.Param;

public interface OrderLogisticsInfoMapper<T,P> extends BaseMapper<T,P> {

	 Integer updateByOrderId(@Param("bean") T t,@Param("orderId") String orderId);

	 Integer deleteByOrderId(@Param("orderId") String orderId);

	 T selectByOrderId(@Param("orderId") String orderId);

}

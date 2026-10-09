package com.simlect.mappers;

import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface OrderItemMapper<T,P> extends BaseMapper<T,P> {

	 Integer updateByOrderItemId(@Param("bean") T t,@Param("orderItemId") String orderItemId);

	 Integer deleteByOrderItemId(@Param("orderItemId") String orderItemId);

	 T selectByOrderItemId(@Param("orderItemId") String orderItemId);

	 List<T> selectByOrderIds(@Param("orderIds") List<String> orderIds);

}

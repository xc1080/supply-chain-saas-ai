package com.simlect.mappers;

import org.apache.ibatis.annotations.Param;

public interface OrderCommentMapper<T,P> extends BaseMapper<T,P> {

	 Integer updateByOrderId(@Param("bean") T t,@Param("orderId") String orderId);

	 Integer deleteByOrderId(@Param("orderId") String orderId);

	 T selectByOrderId(@Param("orderId") String orderId);

	Integer deleteByOrderIdIfStatus(@Param("orderId") String orderId, @Param("status") Integer status);

	Integer publishIfStatus(@Param("orderId") String orderId,
	                      @Param("commentImages") String commentImages,
	                      @Param("fromStatus") Integer fromStatus,
	                      @Param("toStatus") Integer toStatus);

	java.util.Map<String, Object> selectProductCommentStats(@Param("productId") String productId);

}

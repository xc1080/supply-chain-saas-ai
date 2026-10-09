package com.simlect.mappers;

import com.simlect.entity.po.ProductItem;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface OrderInfoMapper<T,P> extends BaseMapper<T,P> {

	 Integer updateByOrderId(@Param("bean") T t,@Param("orderId") String orderId);

	 Integer deleteByOrderId(@Param("orderId") String orderId);

	 T selectByOrderId(@Param("orderId") String orderId);

	Integer markCommentEvaluatedIfNotEvaluated(@Param("orderId") String orderId, @Param("userId") String userId);

	Integer revertCommentStatusIfEvaluated(@Param("orderId") String orderId);

}

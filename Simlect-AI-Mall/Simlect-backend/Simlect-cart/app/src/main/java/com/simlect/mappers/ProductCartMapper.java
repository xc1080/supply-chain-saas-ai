package com.simlect.mappers;

import com.simlect.entity.po.ProductCart;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ProductCartMapper<T,P> extends BaseMapper<T,P> {

	 Integer updateByCartId(@Param("bean") T t,@Param("cartId") String cartId);

	 Integer deleteByCartId(@Param("cartId") String cartId);

	 T selectByCartId(@Param("cartId") String cartId);

	 Integer updateByProductIdAndPropertyValueIdHashAndUserId(@Param("bean") T t,@Param("productId") String productId,@Param("propertyValueIdHash") String propertyValueIdHash,@Param("userId") String userId);

	 Integer deleteByProductIdAndPropertyValueIdHashAndUserId(@Param("productId") String productId,@Param("propertyValueIdHash") String propertyValueIdHash,@Param("userId") String userId);

	 T selectByProductIdAndPropertyValueIdHashAndUserId(@Param("productId") String productId,@Param("propertyValueIdHash") String propertyValueIdHash,@Param("userId") String userId);

    void setBuyCountByProductIdAndPropertyValueIdHashAndUserId(@Param("buyCount") Integer buyCount,@Param("productId") String productId,@Param("propertyValueIdHash") String propertyValueIdHash,@Param("userId") String userId);

	void deleteBatch(@Param("list")List<ProductCart> list);
}

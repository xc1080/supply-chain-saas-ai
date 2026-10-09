package com.simlect.mappers;

import com.simlect.entity.po.ProductPropertyValue;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ProductPropertyValueMapper<T,P> extends BaseMapper<T,P> {

	 Integer updateByProductIdAndPropertyValueId(@Param("bean") T t,@Param("productId") String productId,@Param("propertyValueId") String propertyValueId);

	 Integer deleteByProductIdAndPropertyValueId(@Param("productId") String productId,@Param("propertyValueId") String propertyValueId);

	 T selectByProductIdAndPropertyValueId(@Param("productId") String productId,@Param("propertyValueId") String propertyValueId);

    ProductPropertyValue selectListByProductId(String productId);

	ProductPropertyValue selectByProductId(String productId);

	void updateBatch(@Param("productId") String productId,@Param("updateList")  List<ProductPropertyValue> updateList);

	void deleteBatch(@Param("productId") String productId,@Param("deleteList")  List<ProductPropertyValue> deleteList);
}

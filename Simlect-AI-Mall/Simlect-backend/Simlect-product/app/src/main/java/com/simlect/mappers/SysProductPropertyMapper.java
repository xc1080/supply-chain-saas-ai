package com.simlect.mappers;

import com.simlect.entity.query.SysProductPropertyQuery;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface SysProductPropertyMapper<T,P> extends BaseMapper<T,P> {

	 Integer updateByPropertyId(@Param("bean") T t,@Param("propertyId") String propertyId);

	 Integer deleteByPropertyId(@Param("propertyId") String propertyId);

	 T selectByPropertyId(@Param("propertyId") String propertyId);

	Integer selectMaxPropertySort(String categoryId);

}

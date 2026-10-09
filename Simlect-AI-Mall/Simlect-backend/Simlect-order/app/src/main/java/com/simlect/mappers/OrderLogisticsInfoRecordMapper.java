package com.simlect.mappers;

import org.apache.ibatis.annotations.Param;

public interface OrderLogisticsInfoRecordMapper<T,P> extends BaseMapper<T,P> {

	 Integer updateByRecordId(@Param("bean") T t,@Param("recordId") Integer recordId);

	 Integer deleteByRecordId(@Param("recordId") Integer recordId);

	 T selectByRecordId(@Param("recordId") Integer recordId);

}

package com.simlect.mappers;

import org.apache.ibatis.annotations.Param;

public interface StatisticsInfoMapper<T,P> extends BaseMapper<T,P> {

	// 根据StatisticsDateAndDataType更新
	 Integer updateByStatisticsDateAndDataType(@Param("bean") T t,@Param("statisticsDate") String statisticsDate,@Param("dataType") Integer dataType);

	// 根据StatisticsDateAndDataType删除
	 Integer deleteByStatisticsDateAndDataType(@Param("statisticsDate") String statisticsDate,@Param("dataType") Integer dataType);

	// 根据StatisticsDateAndDataType获取对象
	 T selectByStatisticsDateAndDataType(@Param("statisticsDate") String statisticsDate,@Param("dataType") Integer dataType);

}

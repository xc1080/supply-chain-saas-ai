package com.simlect.mappers;

import org.apache.ibatis.annotations.Param;

public interface PayTradeRecordMapper<T,P> extends BaseMapper<T,P> {

	Integer updateByTradeId(@Param("bean") T t, @Param("tradeId") String tradeId);
	Integer deleteByTradeId(@Param("tradeId") String tradeId);
	T selectByTradeId(@Param("tradeId") String tradeId);

}

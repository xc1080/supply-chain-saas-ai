package com.simlect.mappers;

import org.apache.ibatis.annotations.Param;

public interface UserBrowseHistoryMapper<T,P> extends BaseMapper<T,P> {

	Integer updateByHistoryId(@Param("bean") T t, @Param("historyId") Long historyId);
	Integer deleteByHistoryId(@Param("historyId") Long historyId);
	T selectByHistoryId(@Param("historyId") Long historyId);

}

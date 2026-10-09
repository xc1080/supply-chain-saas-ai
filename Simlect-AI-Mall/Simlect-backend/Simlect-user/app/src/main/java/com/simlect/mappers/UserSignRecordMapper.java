package com.simlect.mappers;

import org.apache.ibatis.annotations.Param;

public interface UserSignRecordMapper<T,P> extends BaseMapper<T,P> {

	T selectByUserId(@Param("userId") String userId);
	Integer updateByUserId(@Param("continuousDays") Integer continuousDays,
						   @Param("totalSignDays") Integer totalSignDays,
						   @Param("usedCount") Integer usedCount,
						   @Param("userId") String userId);

}

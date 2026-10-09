package com.simlect.mappers;

import org.apache.ibatis.annotations.Param;

public interface UserProductFavoriteMapper<T,P> extends BaseMapper<T,P> {

	Integer updateByFavoriteId(@Param("bean") T t, @Param("favoriteId") String favoriteId);
	Integer deleteByFavoriteId(@Param("favoriteId") String favoriteId);
	T selectByFavoriteId(@Param("favoriteId") String favoriteId);

}

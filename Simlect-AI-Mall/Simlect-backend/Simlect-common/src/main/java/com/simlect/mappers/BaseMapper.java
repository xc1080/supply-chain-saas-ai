package com.simlect.mappers;

 import org.apache.ibatis.annotations.Param;

 import java.util.List;

 interface BaseMapper<T, P> {

 	 List<T> selectList(@Param("query") P p);

 	 Integer selectCount(@Param("query") P p);

	 Integer insert(@Param("bean") T t);

	 Integer insertOrUpdate(@Param("bean") T t);

	 Integer insertBatch(@Param("list") List<T> list);

	 Integer insertOrUpdateBatch(@Param("list") List<T> list);

     Integer updateByParam(@Param("bean") T t,@Param("query") P p);

     Integer deleteByParam(@Param("query") P p);
}

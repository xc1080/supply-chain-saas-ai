package com.simlect.mappers;

 import org.apache.ibatis.annotations.Param;

 import java.util.List;

 interface BaseMapperTableSplit<T, P> {

 	 List<T> selectList(@Param("tableName")String tableName,@Param("query") P p);

 	 Integer selectCount(@Param("tableName")String tableName,@Param("query") P p);

	 Integer insert(@Param("tableName")String tableName,@Param("bean") T t);

	 Integer insertOrUpdate(@Param("tableName")String tableName,@Param("bean") T t);

	 Integer insertBatch(@Param("tableName")String tableName,@Param("list") List<T> list);

	 Integer insertOrUpdateBatch(@Param("tableName")String tableName,@Param("list") List<T> list);

     Integer updateByParam(@Param("tableName")String tableName,@Param("bean") T t,@Param("query") P p);

     Integer deleteByParam(@Param("tableName")String tableName,@Param("query") P p);
}

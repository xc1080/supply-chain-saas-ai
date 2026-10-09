package com.simlect.mappers;

import org.apache.ibatis.annotations.Param;

public interface RagQuestionMapper<T,P> extends BaseMapper<T,P> {

	 Integer updateByQuestionId(@Param("bean") T t,@Param("questionId") Integer questionId);

	 Integer deleteByQuestionId(@Param("questionId") Integer questionId);

	 T selectByQuestionId(@Param("questionId") Integer questionId);

}

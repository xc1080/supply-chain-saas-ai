package com.simlect.mappers;

import org.apache.ibatis.annotations.Param;

public interface CommentReportMapper<T, P> extends BaseMapper<T, P> {

    T selectByReportId(@Param("reportId") Integer reportId);

    Integer updateByReportId(@Param("bean") T t, @Param("reportId") Integer reportId);

    Integer deleteByReportId(@Param("reportId") Integer reportId);
}

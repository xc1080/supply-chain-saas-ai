package com.simlect.mappers;

import org.apache.ibatis.annotations.Param;

public interface ImageModerationRecordMapper<T, P> extends BaseMapper<T, P> {

    T selectByRecordId(@Param("recordId") Integer recordId);

    Integer updateByRecordId(@Param("bean") T t, @Param("recordId") Integer recordId);

    Integer updateByRecordIdIfPending(@Param("bean") T t, @Param("recordId") Integer recordId);

    Integer deleteByRecordId(@Param("recordId") Integer recordId);
}

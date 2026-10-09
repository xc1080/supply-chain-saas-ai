package com.simlect.mappers;

import org.apache.ibatis.annotations.Param;

public interface MqCompensationLogMapper<T, P> extends BaseMapper<T, P> {

    T selectByLogId(@Param("logId") Integer logId);

    T selectByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    Integer updateByLogId(@Param("bean") T t, @Param("logId") Integer logId);
}

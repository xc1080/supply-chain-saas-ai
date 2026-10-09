package com.simlect.mappers;

import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface UserNotificationMapper<T, P> extends BaseMapper<T, P> {

    T selectByNotificationId(@Param("notificationId") String notificationId);

    Integer updateByNotificationId(@Param("bean") T bean, @Param("notificationId") String notificationId);

    Integer insertBatch(@Param("list") List<T> list);
}

package com.simlect.mappers;

import com.simlect.entity.po.LocalMessageOutbox;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

public interface LocalMessageOutboxMapper {

    Integer insert(LocalMessageOutbox row);

    LocalMessageOutbox selectById(@Param("id") Long id);

    LocalMessageOutbox selectByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    Integer updateStatus(@Param("id") Long id,
                         @Param("fromStatus") Integer fromStatus,
                         @Param("toStatus") Integer toStatus,
                         @Param("errorMessage") String errorMessage,
                         @Param("sentTime") Date sentTime,
                         @Param("incRetry") boolean incRetry);

    List<LocalMessageOutbox> selectDispatchBatch(@Param("statuses") List<Integer> statuses,
                                                 @Param("beforeTime") Date beforeTime,
                                                 @Param("limit") int limit);
}

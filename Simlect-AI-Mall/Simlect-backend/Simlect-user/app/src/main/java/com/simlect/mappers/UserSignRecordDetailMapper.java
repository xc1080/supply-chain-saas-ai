package com.simlect.mappers;

import com.simlect.entity.po.UserSignRecordDetail;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

public interface UserSignRecordDetailMapper<T, P> extends BaseMapper<T, P> {

    Integer insertIgnore(@Param("bean") UserSignRecordDetail bean);

    List<UserSignRecordDetail> selectByUserIdAndDateRange(@Param("userId") String userId,
                                                          @Param("signDateStart") String signDateStart,
                                                          @Param("signDateEnd") String signDateEnd);

    List<UserSignRecordDetail> selectCreatedAfter(@Param("createTimeStart") Date createTimeStart);

    List<String> selectDistinctUserIdsInDateRange(@Param("signDateStart") String signDateStart,
                                                  @Param("signDateEnd") String signDateEnd);
}

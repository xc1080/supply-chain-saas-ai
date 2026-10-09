package com.simlect.mappers;

import org.apache.ibatis.annotations.Param;

public interface UserSearchKeywordMapper<T, P> extends BaseMapper<T, P> {

    Integer deleteById(@Param("id") Long id);
}

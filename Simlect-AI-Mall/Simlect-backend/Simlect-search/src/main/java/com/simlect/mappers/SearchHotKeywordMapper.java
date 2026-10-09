package com.simlect.mappers;

import org.apache.ibatis.annotations.Param;

public interface SearchHotKeywordMapper<T, P> extends BaseMapper<T, P> {

    T selectByKeyword(@Param("keyword") String keyword);

    Integer deleteByKeyword(@Param("keyword") String keyword);
}

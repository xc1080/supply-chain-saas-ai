package com.simlect.biz;

import com.simlect.entity.po.SearchHotKeyword;

import java.util.List;

public interface SearchHotKeywordService {

    List<SearchHotKeyword> loadList();

    void save(String keyword, Integer sort, Integer status);

    void deleteByKeyword(String keyword);
}

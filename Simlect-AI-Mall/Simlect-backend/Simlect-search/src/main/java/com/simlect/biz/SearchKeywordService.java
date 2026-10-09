package com.simlect.biz;

import java.util.List;

public interface SearchKeywordService {

    List<String> loadHotKeywords();

    List<String> loadRecentKeywords(String userId);

    void saveUserKeyword(String userId, String keyword);

    void clearUserKeywords(String userId);

    void removeUserKeyword(String userId, String keyword);
}

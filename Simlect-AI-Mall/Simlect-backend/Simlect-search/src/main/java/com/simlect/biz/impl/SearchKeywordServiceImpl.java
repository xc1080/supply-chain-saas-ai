package com.simlect.biz.impl;

import com.simlect.entity.po.SearchHotKeyword;
import com.simlect.entity.po.UserSearchKeyword;
import com.simlect.entity.query.SearchHotKeywordQuery;
import com.simlect.entity.query.UserSearchKeywordQuery;
import com.simlect.mappers.SearchHotKeywordMapper;
import com.simlect.mappers.UserSearchKeywordMapper;
import com.simlect.biz.SearchKeywordService;
import com.simlect.utils.StringTools;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

@Service("searchKeywordService")
public class SearchKeywordServiceImpl implements SearchKeywordService {

    private static final int RECENT_LIMIT = 10;

    @Resource
    private SearchHotKeywordMapper<SearchHotKeyword, SearchHotKeywordQuery> searchHotKeywordMapper;
    @Resource
    private UserSearchKeywordMapper<UserSearchKeyword, UserSearchKeywordQuery> userSearchKeywordMapper;

    @Override
    public List<String> loadHotKeywords() {
        SearchHotKeywordQuery query = new SearchHotKeywordQuery();
        query.setStatus(1);
        query.setOrderBy("sort asc, update_time desc");
        List<SearchHotKeyword> list = searchHotKeywordMapper.selectList(query);
        if (list == null || list.isEmpty()) {
            return List.of("牛肉干", "零食", "女装", "手机", "运动鞋", "美妆", "家居", "数码");
        }
        return list.stream().map(SearchHotKeyword::getKeyword).collect(Collectors.toList());
    }

    @Override
    public List<String> loadRecentKeywords(String userId) {
        UserSearchKeywordQuery query = new UserSearchKeywordQuery();
        query.setUserId(userId);
        query.setOrderBy("search_time desc");
        List<UserSearchKeyword> list = userSearchKeywordMapper.selectList(query);
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (list != null) {
            for (UserSearchKeyword item : list) {
                if (!StringTools.isEmpty(item.getKeyword())) {
                    set.add(item.getKeyword().trim());
                }
                if (set.size() >= RECENT_LIMIT) {
                    break;
                }
            }
        }
        return new ArrayList<>(set);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveUserKeyword(String userId, String keyword) {
        if (StringTools.isEmpty(keyword)) {
            return;
        }
        String trimmed = keyword.trim();
        UserSearchKeywordQuery dupQuery = new UserSearchKeywordQuery();
        dupQuery.setUserId(userId);
        dupQuery.setKeyword(trimmed);
        userSearchKeywordMapper.deleteByParam(dupQuery);
        UserSearchKeyword record = new UserSearchKeyword();
        record.setUserId(userId);
        record.setKeyword(trimmed);
        record.setSearchTime(new Date());
        userSearchKeywordMapper.insert(record);
        trimRecent(userId);
    }

    @Override
    public void clearUserKeywords(String userId) {
        UserSearchKeywordQuery query = new UserSearchKeywordQuery();
        query.setUserId(userId);
        userSearchKeywordMapper.deleteByParam(query);
    }

    @Override
    public void removeUserKeyword(String userId, String keyword) {
        UserSearchKeywordQuery query = new UserSearchKeywordQuery();
        query.setUserId(userId);
        query.setKeyword(keyword);
        userSearchKeywordMapper.deleteByParam(query);
    }

    private void trimRecent(String userId) {
        UserSearchKeywordQuery query = new UserSearchKeywordQuery();
        query.setUserId(userId);
        query.setOrderBy("search_time desc");
        List<UserSearchKeyword> list = userSearchKeywordMapper.selectList(query);
        if (list == null || list.size() <= RECENT_LIMIT) {
            return;
        }
        for (int i = RECENT_LIMIT; i < list.size(); i++) {
            userSearchKeywordMapper.deleteById(list.get(i).getId());
        }
    }
}

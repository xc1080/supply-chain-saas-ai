package com.simlect.biz.impl;

import com.simlect.component.EsSearchComponent;
import com.simlect.api.dto.ProductInfoDTO;
import com.simlect.entity.vo.PaginationResultVO;
import com.simlect.biz.SearchKeywordService;
import com.simlect.biz.SearchRecommendService;
import com.simlect.utils.StringTools;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

@Service("searchRecommendService")
public class SearchRecommendServiceImpl implements SearchRecommendService {

    @Resource
    private SearchKeywordService searchKeywordService;
    @Resource
    private EsSearchComponent esSearchComponent;

    @Override
    public List<String> loadGuessKeywords(String userId) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (!StringTools.isEmpty(userId)) {
            List<String> recent = searchKeywordService.loadRecentKeywords(userId);
            if (recent != null) {
                set.addAll(recent);
            }
        }
        if (set.size() < 6) {
            for (String hot : searchKeywordService.loadHotKeywords()) {
                set.add(hot);
                if (set.size() >= 10) {
                    break;
                }
            }
        }
        return new ArrayList<>(set).stream().limit(10).collect(Collectors.toList());
    }

    @Override
    public List<ProductInfoDTO> loadRecommendProducts(String userId, int limit) {
        int size = limit <= 0 ? 8 : Math.min(limit, 20);
        String keyword = null;
        if (!StringTools.isEmpty(userId)) {
            List<String> recent = searchKeywordService.loadRecentKeywords(userId);
            if (recent != null && !recent.isEmpty()) {
                keyword = recent.get(0);
            }
        }
        if (StringTools.isEmpty(keyword)) {
            List<String> hot = searchKeywordService.loadHotKeywords();
            if (hot != null && !hot.isEmpty()) {
                keyword = hot.get(0);
            }
        }
        if (StringTools.isEmpty(keyword)) {
            keyword = " ";
        }
        PaginationResultVO<ProductInfoDTO> page = esSearchComponent.searchProducts(
                keyword, null, null, "desc", "totalSale", 1);
        if (page == null || page.getList() == null) {
            return List.of();
        }
        return page.getList().stream().limit(size).collect(Collectors.toList());
    }
}

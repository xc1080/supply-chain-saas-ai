package com.simlect.biz;

import com.simlect.entity.vo.PaginationResultVO;
import com.simlect.api.vo.UserBrowseProductVO;

public interface UserBrowseHistoryService {

    void recordBrowse(String userId, String productId);

    void enqueueRecordBrowse(String userId, String productId);

    PaginationResultVO<UserBrowseProductVO> loadBrowsePage(String userId, Integer pageNo);

    void clearBrowse(String userId);

    void removeBrowse(String userId, Long historyId);
}

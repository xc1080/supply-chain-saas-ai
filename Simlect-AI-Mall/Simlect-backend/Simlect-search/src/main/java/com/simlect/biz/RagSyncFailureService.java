package com.simlect.biz;

import com.simlect.entity.query.RagSyncFailureQuery;
import com.simlect.entity.vo.PaginationResultVO;
import com.simlect.entity.vo.RagSyncFailureVO;

public interface RagSyncFailureService {

    PaginationResultVO<RagSyncFailureVO> loadList(RagSyncFailureQuery query);

    void replay(Integer logId);

    void updateStatus(Integer logId, Integer status, String handleRemark);

    void dismissRedisSnapshot(String dataId, String dataType);
}

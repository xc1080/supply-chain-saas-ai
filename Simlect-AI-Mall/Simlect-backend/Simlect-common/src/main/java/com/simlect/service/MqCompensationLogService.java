package com.simlect.service;

import com.simlect.entity.dto.MqCompensationRecord;
import com.simlect.entity.po.MqCompensationLog;
import com.simlect.entity.query.MqCompensationLogQuery;
import com.simlect.entity.vo.PaginationResultVO;

public interface MqCompensationLogService {

    PaginationResultVO<MqCompensationLog> findListByPage(MqCompensationLogQuery query);

    MqCompensationLog getByLogId(Integer logId);

    void saveFromFailure(MqCompensationRecord record);

    void updateHandleStatus(Integer logId, Integer status, String handleRemark);

    void replay(Integer logId);

    int autoReplayPendingSendFailures(int batchSize, int maxRetryCount);
}

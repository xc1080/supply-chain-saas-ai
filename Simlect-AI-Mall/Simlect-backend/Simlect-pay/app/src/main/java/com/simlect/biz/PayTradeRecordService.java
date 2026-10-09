package com.simlect.biz;

import com.simlect.entity.po.PayTradeRecord;
import com.simlect.entity.vo.PaginationResultVO;

import java.math.BigDecimal;

public interface PayTradeRecordService {

    void createPending(String userId, String payOrderId, String orderId, BigDecimal payAmount, String payChannel);

    void markSuccess(String payOrderId, String channelOrderId);

    void markClosed(String payOrderId);

    void markRefunded(String payOrderId);

    PaginationResultVO<PayTradeRecord> loadUserTrades(String userId, Integer pageNo);
}

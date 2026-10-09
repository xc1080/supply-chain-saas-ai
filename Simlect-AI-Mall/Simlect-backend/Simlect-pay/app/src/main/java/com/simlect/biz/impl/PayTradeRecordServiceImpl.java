package com.simlect.biz.impl;

import com.simlect.entity.enums.PageSize;
import com.simlect.entity.po.PayTradeRecord;
import com.simlect.entity.query.PayTradeRecordQuery;
import com.simlect.entity.query.SimplePage;
import com.simlect.entity.vo.PaginationResultVO;
import com.simlect.mappers.PayTradeRecordMapper;
import com.simlect.biz.PayTradeRecordService;
import com.simlect.utils.StringTools;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

@Service("payTradeRecordService")
public class PayTradeRecordServiceImpl implements PayTradeRecordService {

    private static final int STATUS_PENDING = 0;
    private static final int STATUS_SUCCESS = 1;
    private static final int STATUS_CLOSED = 2;
    private static final int STATUS_REFUNDED = 3;

    @Resource
    private PayTradeRecordMapper<PayTradeRecord, PayTradeRecordQuery> payTradeRecordMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createPending(String userId, String payOrderId, String orderId, BigDecimal payAmount, String payChannel) {
        if (StringTools.isEmpty(payOrderId) || payAmount == null) {
            return;
        }
        PayTradeRecordQuery exist = new PayTradeRecordQuery();
        exist.setPayOrderId(payOrderId);
        exist.setTradeStatus(STATUS_PENDING);
        if (payTradeRecordMapper.selectCount(exist) > 0) {
            return;
        }
        PayTradeRecord record = new PayTradeRecord();
        record.setTradeId(StringTools.createTradeId());
        record.setOrderId(orderId);
        record.setUserId(userId);
        record.setPayOrderId(payOrderId);
        record.setPayChannel(payChannel);
        record.setPayAmount(payAmount);
        record.setTradeStatus(STATUS_PENDING);
        record.setCreateTime(new Date());
        payTradeRecordMapper.insert(record);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markSuccess(String payOrderId, String channelOrderId) {
        PayTradeRecordQuery query = new PayTradeRecordQuery();
        query.setPayOrderId(payOrderId);
        List<PayTradeRecord> list = payTradeRecordMapper.selectList(query);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (PayTradeRecord item : list) {
            if (item.getTradeStatus() != null && item.getTradeStatus() == STATUS_SUCCESS) {
                continue;
            }
            PayTradeRecord update = new PayTradeRecord();
            update.setTradeStatus(STATUS_SUCCESS);
            update.setChannelOrderId(channelOrderId);
            update.setPayTime(new Date());
            payTradeRecordMapper.updateByTradeId(update, item.getTradeId());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markClosed(String payOrderId) {
        PayTradeRecordQuery query = new PayTradeRecordQuery();
        query.setPayOrderId(payOrderId);
        query.setTradeStatus(STATUS_PENDING);
        PayTradeRecord update = new PayTradeRecord();
        update.setTradeStatus(STATUS_CLOSED);
        payTradeRecordMapper.updateByParam(update, query);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markRefunded(String payOrderId) {
        PayTradeRecordQuery query = new PayTradeRecordQuery();
        query.setPayOrderId(payOrderId);
        PayTradeRecord update = new PayTradeRecord();
        update.setTradeStatus(STATUS_REFUNDED);
        payTradeRecordMapper.updateByParam(update, query);
    }

    @Override
    public PaginationResultVO<PayTradeRecord> loadUserTrades(String userId, Integer pageNo) {
        PayTradeRecordQuery query = new PayTradeRecordQuery();
        query.setUserId(userId);
        query.setPageNo(pageNo);
        query.setOrderBy("create_time desc");
        int count = payTradeRecordMapper.selectCount(query);
        int pageSize = PageSize.SIZE15.getSize();
        SimplePage page = new SimplePage(pageNo, count, pageSize);
        query.setSimplePage(page);
        List<PayTradeRecord> list = payTradeRecordMapper.selectList(query);
        return new PaginationResultVO<>(count, page.getPageSize(), page.getPageNo(), page.getPageTotal(), list);
    }
}

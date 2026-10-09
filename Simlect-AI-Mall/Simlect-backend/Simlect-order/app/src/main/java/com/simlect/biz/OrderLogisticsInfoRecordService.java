package com.simlect.biz;

import java.util.List;

import com.simlect.entity.query.OrderLogisticsInfoRecordQuery;
import com.simlect.entity.po.OrderLogisticsInfoRecord;
import com.simlect.entity.vo.PaginationResultVO;

public interface OrderLogisticsInfoRecordService {

	List<OrderLogisticsInfoRecord> findListByParam(OrderLogisticsInfoRecordQuery param);

	Integer findCountByParam(OrderLogisticsInfoRecordQuery param);

	PaginationResultVO<OrderLogisticsInfoRecord> findListByPage(OrderLogisticsInfoRecordQuery param);

	Integer add(OrderLogisticsInfoRecord bean);

	Integer addBatch(List<OrderLogisticsInfoRecord> listBean);

	Integer addOrUpdateBatch(List<OrderLogisticsInfoRecord> listBean);

	Integer updateByParam(OrderLogisticsInfoRecord bean,OrderLogisticsInfoRecordQuery param);

	Integer deleteByParam(OrderLogisticsInfoRecordQuery param);

	OrderLogisticsInfoRecord getOrderLogisticsInfoRecordByRecordId(Integer recordId);

	Integer updateOrderLogisticsInfoRecordByRecordId(OrderLogisticsInfoRecord bean,Integer recordId);

	Integer deleteOrderLogisticsInfoRecordByRecordId(Integer recordId);

}

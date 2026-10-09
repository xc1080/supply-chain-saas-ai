package com.simlect.biz;

import java.util.List;

import com.simlect.entity.query.OrderLogisticsInfoQuery;
import com.simlect.entity.po.OrderLogisticsInfo;
import com.simlect.entity.vo.PaginationResultVO;
import jakarta.validation.constraints.NotEmpty;

public interface OrderLogisticsInfoService {

	List<OrderLogisticsInfo> findListByParam(OrderLogisticsInfoQuery param);

	Integer findCountByParam(OrderLogisticsInfoQuery param);

	PaginationResultVO<OrderLogisticsInfo> findListByPage(OrderLogisticsInfoQuery param);

	Integer add(OrderLogisticsInfo bean);

	Integer addBatch(List<OrderLogisticsInfo> listBean);

	Integer addOrUpdateBatch(List<OrderLogisticsInfo> listBean);

	Integer updateByParam(OrderLogisticsInfo bean,OrderLogisticsInfoQuery param);

	Integer deleteByParam(OrderLogisticsInfoQuery param);

	OrderLogisticsInfo getOrderLogisticsInfoByOrderId(String orderId);

	Integer updateOrderLogisticsInfoByOrderId(OrderLogisticsInfo bean,String orderId);

	Integer deleteOrderLogisticsInfoByOrderId(String orderId);

    OrderLogisticsInfo getOrderLogisticsRecords(@NotEmpty String userId, @NotEmpty String orderId);

	void delivery(OrderLogisticsInfo orderLogisticsInfo);
}

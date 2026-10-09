package com.simlect.biz;

import java.util.List;

import com.simlect.entity.query.OrderItemQuery;
import com.simlect.entity.po.OrderItem;
import com.simlect.entity.vo.PaginationResultVO;

public interface OrderItemService {

	List<OrderItem> findListByParam(OrderItemQuery param);

	Integer findCountByParam(OrderItemQuery param);

	PaginationResultVO<OrderItem> findListByPage(OrderItemQuery param);

	Integer add(OrderItem bean);

	Integer addBatch(List<OrderItem> listBean);

	Integer addOrUpdateBatch(List<OrderItem> listBean);

	Integer updateByParam(OrderItem bean,OrderItemQuery param);

	Integer deleteByParam(OrderItemQuery param);

	OrderItem getOrderItemByOrderItemId(String orderItemId);

	Integer updateOrderItemByOrderItemId(OrderItem bean,String orderItemId);

	Integer deleteOrderItemByOrderItemId(String orderItemId);

}

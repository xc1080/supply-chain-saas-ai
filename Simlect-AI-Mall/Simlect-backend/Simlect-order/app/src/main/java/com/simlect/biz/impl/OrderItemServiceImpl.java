package com.simlect.biz.impl;

import java.util.List;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import com.simlect.entity.enums.PageSize;
import com.simlect.entity.query.OrderItemQuery;
import com.simlect.entity.po.OrderItem;
import com.simlect.entity.vo.PaginationResultVO;
import com.simlect.entity.query.SimplePage;
import com.simlect.mappers.OrderItemMapper;
import com.simlect.biz.OrderItemService;
import com.simlect.utils.StringTools;

@Service("orderItemService")
public class OrderItemServiceImpl implements OrderItemService {

	@Resource
	private OrderItemMapper<OrderItem, OrderItemQuery> orderItemMapper;

	@Override
	public List<OrderItem> findListByParam(OrderItemQuery param) {
		return this.orderItemMapper.selectList(param);
	}

	@Override
	public Integer findCountByParam(OrderItemQuery param) {
		return this.orderItemMapper.selectCount(param);
	}

	@Override
	public PaginationResultVO<OrderItem> findListByPage(OrderItemQuery param) {
		int count = this.findCountByParam(param);
		int pageSize = param.getPageSize() == null ? PageSize.SIZE15.getSize() : param.getPageSize();

		SimplePage page = new SimplePage(param.getPageNo(), count, pageSize);
		param.setSimplePage(page);
		List<OrderItem> list = this.findListByParam(param);
		PaginationResultVO<OrderItem> result = new PaginationResultVO(count, page.getPageSize(), page.getPageNo(), page.getPageTotal(), list);
		return result;
	}

	@Override
	public Integer add(OrderItem bean) {
		return this.orderItemMapper.insert(bean);
	}

	@Override
	public Integer addBatch(List<OrderItem> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.orderItemMapper.insertBatch(listBean);
	}

	@Override
	public Integer addOrUpdateBatch(List<OrderItem> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.orderItemMapper.insertOrUpdateBatch(listBean);
	}

	@Override
	public Integer updateByParam(OrderItem bean, OrderItemQuery param) {
		StringTools.checkParam(param);
		return this.orderItemMapper.updateByParam(bean, param);
	}

	@Override
	public Integer deleteByParam(OrderItemQuery param) {
		StringTools.checkParam(param);
		return this.orderItemMapper.deleteByParam(param);
	}

	@Override
	public OrderItem getOrderItemByOrderItemId(String orderItemId) {
		return this.orderItemMapper.selectByOrderItemId(orderItemId);
	}

	@Override
	public Integer updateOrderItemByOrderItemId(OrderItem bean, String orderItemId) {
		return this.orderItemMapper.updateByOrderItemId(bean, orderItemId);
	}

	@Override
	public Integer deleteOrderItemByOrderItemId(String orderItemId) {
		return this.orderItemMapper.deleteByOrderItemId(orderItemId);
	}
}

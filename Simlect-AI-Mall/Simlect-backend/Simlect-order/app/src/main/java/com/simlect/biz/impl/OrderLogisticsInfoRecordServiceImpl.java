package com.simlect.biz.impl;

import java.util.List;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import com.simlect.entity.enums.PageSize;
import com.simlect.entity.query.OrderLogisticsInfoRecordQuery;
import com.simlect.entity.po.OrderLogisticsInfoRecord;
import com.simlect.entity.vo.PaginationResultVO;
import com.simlect.entity.query.SimplePage;
import com.simlect.mappers.OrderLogisticsInfoRecordMapper;
import com.simlect.biz.OrderLogisticsInfoRecordService;
import com.simlect.utils.StringTools;

@Service("orderLogisticsInfoRecordService")
public class OrderLogisticsInfoRecordServiceImpl implements OrderLogisticsInfoRecordService {

	@Resource
	private OrderLogisticsInfoRecordMapper<OrderLogisticsInfoRecord, OrderLogisticsInfoRecordQuery> orderLogisticsInfoRecordMapper;

	@Override
	public List<OrderLogisticsInfoRecord> findListByParam(OrderLogisticsInfoRecordQuery param) {
		return this.orderLogisticsInfoRecordMapper.selectList(param);
	}

	@Override
	public Integer findCountByParam(OrderLogisticsInfoRecordQuery param) {
		return this.orderLogisticsInfoRecordMapper.selectCount(param);
	}

	@Override
	public PaginationResultVO<OrderLogisticsInfoRecord> findListByPage(OrderLogisticsInfoRecordQuery param) {
		int count = this.findCountByParam(param);
		int pageSize = param.getPageSize() == null ? PageSize.SIZE15.getSize() : param.getPageSize();

		SimplePage page = new SimplePage(param.getPageNo(), count, pageSize);
		param.setSimplePage(page);
		List<OrderLogisticsInfoRecord> list = this.findListByParam(param);
		PaginationResultVO<OrderLogisticsInfoRecord> result = new PaginationResultVO(count, page.getPageSize(), page.getPageNo(), page.getPageTotal(), list);
		return result;
	}

	@Override
	public Integer add(OrderLogisticsInfoRecord bean) {
		return this.orderLogisticsInfoRecordMapper.insert(bean);
	}

	@Override
	public Integer addBatch(List<OrderLogisticsInfoRecord> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.orderLogisticsInfoRecordMapper.insertBatch(listBean);
	}

	@Override
	public Integer addOrUpdateBatch(List<OrderLogisticsInfoRecord> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.orderLogisticsInfoRecordMapper.insertOrUpdateBatch(listBean);
	}

	@Override
	public Integer updateByParam(OrderLogisticsInfoRecord bean, OrderLogisticsInfoRecordQuery param) {
		StringTools.checkParam(param);
		return this.orderLogisticsInfoRecordMapper.updateByParam(bean, param);
	}

	@Override
	public Integer deleteByParam(OrderLogisticsInfoRecordQuery param) {
		StringTools.checkParam(param);
		return this.orderLogisticsInfoRecordMapper.deleteByParam(param);
	}

	@Override
	public OrderLogisticsInfoRecord getOrderLogisticsInfoRecordByRecordId(Integer recordId) {
		return this.orderLogisticsInfoRecordMapper.selectByRecordId(recordId);
	}

	@Override
	public Integer updateOrderLogisticsInfoRecordByRecordId(OrderLogisticsInfoRecord bean, Integer recordId) {
		return this.orderLogisticsInfoRecordMapper.updateByRecordId(bean, recordId);
	}

	@Override
	public Integer deleteOrderLogisticsInfoRecordByRecordId(Integer recordId) {
		return this.orderLogisticsInfoRecordMapper.deleteByRecordId(recordId);
	}
}

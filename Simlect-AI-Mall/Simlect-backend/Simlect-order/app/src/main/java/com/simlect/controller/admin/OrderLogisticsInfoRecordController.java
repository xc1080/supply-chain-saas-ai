package com.simlect.controller.admin;

import java.util.List;

import com.simlect.entity.query.OrderLogisticsInfoRecordQuery;
import com.simlect.entity.po.OrderLogisticsInfoRecord;
import com.simlect.entity.vo.ResponseVO;
import com.simlect.biz.OrderLogisticsInfoRecordService;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;

@RestController("orderLogisticsInfoRecordController")
@RequestMapping("/admin/orderLogisticsInfoRecord")
public class OrderLogisticsInfoRecordController extends com.simlect.controller.admin.ABaseController{

	@Resource
	private OrderLogisticsInfoRecordService orderLogisticsInfoRecordService;

	@PostMapping("/loadDataList")
	public ResponseVO loadDataList(OrderLogisticsInfoRecordQuery query){
		return getSuccessResponseVO(orderLogisticsInfoRecordService.findListByPage(query));
	}

	@PostMapping("/add")
	public ResponseVO add(OrderLogisticsInfoRecord bean) {
		orderLogisticsInfoRecordService.add(bean);
		return getSuccessResponseVO(null);
	}

	@PostMapping("/addBatch")
	public ResponseVO addBatch(@RequestBody List<OrderLogisticsInfoRecord> listBean) {
		orderLogisticsInfoRecordService.addBatch(listBean);
		return getSuccessResponseVO(null);
	}

	@PostMapping("/addOrUpdateBatch")
	public ResponseVO addOrUpdateBatch(@RequestBody List<OrderLogisticsInfoRecord> listBean) {
		orderLogisticsInfoRecordService.addBatch(listBean);
		return getSuccessResponseVO(null);
	}

	@PostMapping("/getOrderLogisticsInfoRecordByRecordId")
	public ResponseVO getOrderLogisticsInfoRecordByRecordId(Integer recordId) {
		return getSuccessResponseVO(orderLogisticsInfoRecordService.getOrderLogisticsInfoRecordByRecordId(recordId));
	}

	@PostMapping("/updateOrderLogisticsInfoRecordByRecordId")
	public ResponseVO updateOrderLogisticsInfoRecordByRecordId(OrderLogisticsInfoRecord bean,Integer recordId) {
		orderLogisticsInfoRecordService.updateOrderLogisticsInfoRecordByRecordId(bean,recordId);
		return getSuccessResponseVO(null);
	}

	@PostMapping("/deleteOrderLogisticsInfoRecordByRecordId")
	public ResponseVO deleteOrderLogisticsInfoRecordByRecordId(Integer recordId) {
		orderLogisticsInfoRecordService.deleteOrderLogisticsInfoRecordByRecordId(recordId);
		return getSuccessResponseVO(null);
	}
}

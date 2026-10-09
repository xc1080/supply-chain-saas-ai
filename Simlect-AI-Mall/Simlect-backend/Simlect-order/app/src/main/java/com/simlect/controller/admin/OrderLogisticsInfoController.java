package com.simlect.controller.admin;

import java.util.List;

import com.simlect.entity.query.OrderLogisticsInfoQuery;
import com.simlect.entity.po.OrderLogisticsInfo;
import com.simlect.entity.vo.ResponseVO;
import com.simlect.biz.OrderLogisticsInfoService;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;

@RestController("orderLogisticsInfoController")
@RequestMapping("/admin/orderLogisticsInfo")
public class OrderLogisticsInfoController extends com.simlect.controller.admin.ABaseController{

	@Resource
	private OrderLogisticsInfoService orderLogisticsInfoService;

	@PostMapping("/loadDataList")
	public ResponseVO loadDataList(OrderLogisticsInfoQuery query){
		return getSuccessResponseVO(orderLogisticsInfoService.findListByPage(query));
	}

	@PostMapping("/add")
	public ResponseVO add(OrderLogisticsInfo bean) {
		orderLogisticsInfoService.add(bean);
		return getSuccessResponseVO(null);
	}

	@PostMapping("/addBatch")
	public ResponseVO addBatch(@RequestBody List<OrderLogisticsInfo> listBean) {
		orderLogisticsInfoService.addBatch(listBean);
		return getSuccessResponseVO(null);
	}

	@PostMapping("/addOrUpdateBatch")
	public ResponseVO addOrUpdateBatch(@RequestBody List<OrderLogisticsInfo> listBean) {
		orderLogisticsInfoService.addBatch(listBean);
		return getSuccessResponseVO(null);
	}

	@PostMapping("/getOrderLogisticsInfoByOrderId")
	public ResponseVO getOrderLogisticsInfoByOrderId(String orderId) {
		return getSuccessResponseVO(orderLogisticsInfoService.getOrderLogisticsInfoByOrderId(orderId));
	}

	@PostMapping("/updateOrderLogisticsInfoByOrderId")
	public ResponseVO updateOrderLogisticsInfoByOrderId(OrderLogisticsInfo bean,String orderId) {
		orderLogisticsInfoService.updateOrderLogisticsInfoByOrderId(bean,orderId);
		return getSuccessResponseVO(null);
	}

	@PostMapping("/deleteOrderLogisticsInfoByOrderId")
	public ResponseVO deleteOrderLogisticsInfoByOrderId(String orderId) {
		orderLogisticsInfoService.deleteOrderLogisticsInfoByOrderId(orderId);
		return getSuccessResponseVO(null);
	}
}

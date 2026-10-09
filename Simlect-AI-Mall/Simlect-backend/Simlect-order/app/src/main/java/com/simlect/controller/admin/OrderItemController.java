package com.simlect.controller.admin;

import java.util.List;

import com.simlect.entity.query.OrderItemQuery;
import com.simlect.entity.po.OrderItem;
import com.simlect.entity.vo.ResponseVO;
import com.simlect.biz.OrderItemService;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;

@RestController("orderItemController")
@RequestMapping("/admin/orderItem")
public class OrderItemController extends com.simlect.controller.admin.ABaseController{

	@Resource
	private OrderItemService orderItemService;

	@PostMapping("/loadDataList")
	public ResponseVO loadDataList(OrderItemQuery query){
		return getSuccessResponseVO(orderItemService.findListByPage(query));
	}

	@PostMapping("/add")
	public ResponseVO add(OrderItem bean) {
		orderItemService.add(bean);
		return getSuccessResponseVO(null);
	}

	@PostMapping("/addBatch")
	public ResponseVO addBatch(@RequestBody List<OrderItem> listBean) {
		orderItemService.addBatch(listBean);
		return getSuccessResponseVO(null);
	}

	@PostMapping("/addOrUpdateBatch")
	public ResponseVO addOrUpdateBatch(@RequestBody List<OrderItem> listBean) {
		orderItemService.addBatch(listBean);
		return getSuccessResponseVO(null);
	}

	@PostMapping("/getOrderItemByOrderItemId")
	public ResponseVO getOrderItemByOrderItemId(String orderItemId) {
		return getSuccessResponseVO(orderItemService.getOrderItemByOrderItemId(orderItemId));
	}

	@PostMapping("/updateOrderItemByOrderItemId")
	public ResponseVO updateOrderItemByOrderItemId(OrderItem bean,String orderItemId) {
		orderItemService.updateOrderItemByOrderItemId(bean,orderItemId);
		return getSuccessResponseVO(null);
	}

	@PostMapping("/deleteOrderItemByOrderItemId")
	public ResponseVO deleteOrderItemByOrderItemId(String orderItemId) {
		orderItemService.deleteOrderItemByOrderItemId(orderItemId);
		return getSuccessResponseVO(null);
	}
}

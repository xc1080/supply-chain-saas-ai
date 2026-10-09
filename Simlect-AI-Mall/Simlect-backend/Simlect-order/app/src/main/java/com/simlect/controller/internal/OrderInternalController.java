package com.simlect.controller.internal;

import com.simlect.api.dto.CouponRushPayRequestDTO;
import com.simlect.api.dto.CouponRushPrepareRequestDTO;
import com.simlect.api.dto.OrderIdDTO;
import com.simlect.api.dto.OrderStatsRangeDTO;
import com.simlect.api.dto.UserIdDTO;
import com.simlect.api.vo.OrderBriefVO;
import com.simlect.api.vo.OrderDailyStatsVO;
import com.simlect.api.vo.OrderRangeStatsVO;
import com.simlect.biz.OrderInfoService;
import com.simlect.biz.OrderInternalService;
import com.simlect.controller.ABaseController;
import com.simlect.api.dto.CouponRushPrepareDTO;
import com.simlect.api.dto.PayInfoDTO;
import com.simlect.api.dto.PayOrderNotifyDTO;
import com.simlect.api.enums.OrderStatusEnum;
import com.simlect.entity.po.OrderInfo;
import com.simlect.entity.query.OrderInfoQuery;
import com.simlect.entity.vo.ResponseVO;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/internal/order")
public class OrderInternalController extends ABaseController {

    @Resource
    private OrderInternalService orderInternalService;

    @Resource
    private OrderInfoService orderInfoService;

    @PostMapping("/get")
    public ResponseVO<OrderBriefVO> getOrder(@RequestBody OrderIdDTO dto) {
        return getSuccessResponseVO(orderInternalService.getOrder(dto));
    }

    @PostMapping("/cancelUnpaidForPayTimeout")
    public ResponseVO<Boolean> cancelUnpaidForPayTimeout(@RequestBody OrderIdDTO dto) {
        return getSuccessResponseVO(orderInternalService.cancelUnpaidForPayTimeout(dto));
    }

    @PostMapping("/confirmReceipt")
    public ResponseVO<Boolean> confirmReceipt(@RequestBody OrderIdDTO dto) {
        return getSuccessResponseVO(orderInternalService.confirmReceipt(dto));
    }

    @PostMapping("/onConfirmed")
    public ResponseVO<Void> onConfirmed(@RequestBody OrderIdDTO dto) {
        orderInternalService.onConfirmed(dto);
        return getSuccessResponseVO(null);
    }

    @PostMapping("/paySuccess")
    public ResponseVO<Void> paySuccess(@RequestBody PayOrderNotifyDTO dto) {
        orderInternalService.paySuccess(dto);
        return getSuccessResponseVO(null);
    }

    @PostMapping("/prepareCouponRush")
    public ResponseVO<CouponRushPrepareDTO> prepareCouponRush(@RequestBody CouponRushPrepareRequestDTO dto) {
        return getSuccessResponseVO(orderInternalService.prepareCouponRush(dto));
    }

    @PostMapping("/postCouponRushOrder")
    public ResponseVO<PayInfoDTO> postCouponRushOrder(@RequestBody CouponRushPayRequestDTO dto) {
        return getSuccessResponseVO(orderInternalService.postCouponRushOrder(dto));
    }

    @PostMapping("/syncPaidCouponRushUserCoupons")
    public ResponseVO<Void> syncPaidCouponRushUserCoupons(@RequestBody UserIdDTO dto) {
        orderInternalService.syncPaidCouponRushUserCoupons(dto);
        return getSuccessResponseVO(null);
    }

    @PostMapping("/cancelOrder")
    public ResponseVO<Void> cancelOrder(@RequestBody OrderIdDTO dto) {
        orderInternalService.cancelOrder(dto);
        return getSuccessResponseVO(null);
    }

    @PostMapping("/stats/range")
    public ResponseVO<OrderRangeStatsVO> aggregateRange(@RequestBody OrderStatsRangeDTO dto) {
        return getSuccessResponseVO(orderInternalService.aggregateRange(dto));
    }

    @PostMapping("/stats/daily")
    public ResponseVO<List<OrderDailyStatsVO>> aggregateDaily(@RequestBody OrderStatsRangeDTO dto) {
        return getSuccessResponseVO(orderInternalService.aggregateDaily(dto));
    }

    @PostMapping("/tool/addAllWaitPayToDelayQueue")
    public ResponseVO<Void> addAllWaitPayToDelayQueue() {
        OrderInfoQuery orderInfoQuery = new OrderInfoQuery();
        orderInfoQuery.setOrderStatus(OrderStatusEnum.WAIT_PAYMENT.getStatus());
        List<OrderInfo> orderInfoList = orderInfoService.findListByParam(orderInfoQuery);
        orderInfoService.addAllOrderToDelayQueue(orderInfoList);
        return getSuccessResponseVO(null);
    }
}

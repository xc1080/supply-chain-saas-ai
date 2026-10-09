package com.simlect.utils;

import com.simlect.entity.po.OrderInfo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public final class OrderListPayAmountHelper {

    private OrderListPayAmountHelper() {
    }

    public static void ensureOrderListMinTotalPay(List<OrderInfo> orderInfoList) {
        if (orderInfoList == null || orderInfoList.isEmpty()) {
            return;
        }
        BigDecimal minTotal = OrderPayAmountUtil.minOrderPayAmount();
        BigDecimal sum = orderInfoList.stream()
                .map(o -> o.getAmount() == null ? BigDecimal.ZERO : o.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(minTotal) >= 0) {
            return;
        }
        OrderInfo target = orderInfoList.get(orderInfoList.size() - 1);
        BigDecimal current = target.getAmount() == null ? BigDecimal.ZERO : target.getAmount();
        BigDecimal next = current.add(minTotal.subtract(sum)).setScale(2, RoundingMode.HALF_UP);
        target.setAmount(next);
    }
}

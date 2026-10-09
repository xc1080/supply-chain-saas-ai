package com.simlect.utils;

import com.simlect.constants.Constants;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class OrderPayAmountUtil {

    private OrderPayAmountUtil() {
    }

    public static BigDecimal minOrderPayAmount() {
        return new BigDecimal(Constants.MIN_ORDER_PAY_AMOUNT).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal capCouponDiscountForMinPay(BigDecimal orderTotal, BigDecimal discount) {
        if (orderTotal == null || discount == null || discount.compareTo(BigDecimal.ZERO) <= 0) {
            return discount == null ? BigDecimal.ZERO : discount;
        }
        BigDecimal minPay = minOrderPayAmount();
        if (orderTotal.compareTo(minPay) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal maxDiscount = orderTotal.subtract(minPay);
        if (discount.compareTo(maxDiscount) > 0) {
            return maxDiscount.setScale(2, RoundingMode.HALF_UP);
        }
        return discount.setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal normalizeChannelPayAmount(BigDecimal amount) {
        BigDecimal minPay = minOrderPayAmount();
        BigDecimal pay = amount == null ? BigDecimal.ZERO : amount;
        if (pay.compareTo(BigDecimal.ZERO) <= 0) {
            return minPay;
        }
        if (pay.compareTo(minPay) < 0) {
            return minPay;
        }
        return pay.setScale(2, RoundingMode.HALF_UP);
    }

    public static String formatChannelPayAmount(BigDecimal amount) {
        return normalizeChannelPayAmount(amount).toPlainString();
    }
}

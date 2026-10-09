package com.simlect.api.vo;

import java.io.Serializable;
import java.math.BigDecimal;

public class OrderRangeStatsVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private BigDecimal saleAmount = BigDecimal.ZERO;
    private BigDecimal saleOrderCount = BigDecimal.ZERO;
    private BigDecimal refundAmount = BigDecimal.ZERO;

    public BigDecimal getSaleAmount() {
        return saleAmount;
    }

    public void setSaleAmount(BigDecimal saleAmount) {
        this.saleAmount = saleAmount == null ? BigDecimal.ZERO : saleAmount;
    }

    public BigDecimal getSaleOrderCount() {
        return saleOrderCount;
    }

    public void setSaleOrderCount(BigDecimal saleOrderCount) {
        this.saleOrderCount = saleOrderCount == null ? BigDecimal.ZERO : saleOrderCount;
    }

    public BigDecimal getRefundAmount() {
        return refundAmount;
    }

    public void setRefundAmount(BigDecimal refundAmount) {
        this.refundAmount = refundAmount == null ? BigDecimal.ZERO : refundAmount;
    }
}

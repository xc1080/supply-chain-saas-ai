package com.simlect.api.vo;

import java.io.Serializable;
import java.math.BigDecimal;

public class OrderDailyStatsVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String statisticsDate;
    private BigDecimal saleAmount = BigDecimal.ZERO;
    private BigDecimal saleCount = BigDecimal.ZERO;
    private BigDecimal refundAmount = BigDecimal.ZERO;
    private BigDecimal refundCount = BigDecimal.ZERO;

    public String getStatisticsDate() {
        return statisticsDate;
    }

    public void setStatisticsDate(String statisticsDate) {
        this.statisticsDate = statisticsDate;
    }

    public BigDecimal getSaleAmount() {
        return saleAmount;
    }

    public void setSaleAmount(BigDecimal saleAmount) {
        this.saleAmount = saleAmount == null ? BigDecimal.ZERO : saleAmount;
    }

    public BigDecimal getSaleCount() {
        return saleCount;
    }

    public void setSaleCount(BigDecimal saleCount) {
        this.saleCount = saleCount == null ? BigDecimal.ZERO : saleCount;
    }

    public BigDecimal getRefundAmount() {
        return refundAmount;
    }

    public void setRefundAmount(BigDecimal refundAmount) {
        this.refundAmount = refundAmount == null ? BigDecimal.ZERO : refundAmount;
    }

    public BigDecimal getRefundCount() {
        return refundCount;
    }

    public void setRefundCount(BigDecimal refundCount) {
        this.refundCount = refundCount == null ? BigDecimal.ZERO : refundCount;
    }
}

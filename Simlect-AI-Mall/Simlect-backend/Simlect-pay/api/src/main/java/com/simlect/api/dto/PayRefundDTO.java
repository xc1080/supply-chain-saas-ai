package com.simlect.api.dto;

import java.io.Serializable;
import java.math.BigDecimal;

public class PayRefundDTO implements Serializable {
    private String sourcePayOrderId;
    private String refundOrderId;
    private BigDecimal refundAmount;
    private String payChannel;

    public PayRefundDTO() {
    }

    public PayRefundDTO(String sourcePayOrderId, String refundOrderId, BigDecimal refundAmount, String payChannel) {
        this.sourcePayOrderId = sourcePayOrderId;
        this.refundOrderId = refundOrderId;
        this.refundAmount = refundAmount;
        this.payChannel = payChannel;
    }

    public String getSourcePayOrderId() {
        return sourcePayOrderId;
    }

    public void setSourcePayOrderId(String sourcePayOrderId) {
        this.sourcePayOrderId = sourcePayOrderId;
    }

    public String getRefundOrderId() {
        return refundOrderId;
    }

    public void setRefundOrderId(String refundOrderId) {
        this.refundOrderId = refundOrderId;
    }

    public BigDecimal getRefundAmount() {
        return refundAmount;
    }

    public void setRefundAmount(BigDecimal refundAmount) {
        this.refundAmount = refundAmount;
    }

    public String getPayChannel() {
        return payChannel;
    }

    public void setPayChannel(String payChannel) {
        this.payChannel = payChannel;
    }
}

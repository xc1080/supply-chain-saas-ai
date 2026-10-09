package com.simlect.api.dto;

import java.io.Serializable;
import java.math.BigDecimal;

public class PayTradeCreateDTO implements Serializable {
    private String userId;
    private String payOrderId;
    private String orderId;
    private BigDecimal payAmount;
    private String payChannel;

    public PayTradeCreateDTO() {
    }

    public PayTradeCreateDTO(String userId, String payOrderId, String orderId, BigDecimal payAmount, String payChannel) {
        this.userId = userId;
        this.payOrderId = payOrderId;
        this.orderId = orderId;
        this.payAmount = payAmount;
        this.payChannel = payChannel;
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getPayOrderId() { return payOrderId; }
    public void setPayOrderId(String payOrderId) { this.payOrderId = payOrderId; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public BigDecimal getPayAmount() { return payAmount; }
    public void setPayAmount(BigDecimal payAmount) { this.payAmount = payAmount; }
    public String getPayChannel() { return payChannel; }
    public void setPayChannel(String payChannel) { this.payChannel = payChannel; }
}

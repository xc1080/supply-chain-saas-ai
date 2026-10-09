package com.simlect.api.dto;

import java.io.Serializable;
import java.math.BigDecimal;

public class PayTradeMarkDTO implements Serializable {
    private String userId;
    private String payOrderId;
    private String orderId;
    private BigDecimal payAmount;
    private String payChannel;
    private String channelOrderId;

    public PayTradeMarkDTO() {
    }

    public static PayTradeMarkDTO pending(String userId, String payOrderId, String orderId,
                                          BigDecimal payAmount, String payChannel) {
        PayTradeMarkDTO dto = new PayTradeMarkDTO();
        dto.userId = userId;
        dto.payOrderId = payOrderId;
        dto.orderId = orderId;
        dto.payAmount = payAmount;
        dto.payChannel = payChannel;
        return dto;
    }

    public static PayTradeMarkDTO ofPayOrder(String payOrderId) {
        PayTradeMarkDTO dto = new PayTradeMarkDTO();
        dto.payOrderId = payOrderId;
        return dto;
    }

    public static PayTradeMarkDTO success(String payOrderId, String channelOrderId) {
        PayTradeMarkDTO dto = ofPayOrder(payOrderId);
        dto.channelOrderId = channelOrderId;
        return dto;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getPayOrderId() {
        return payOrderId;
    }

    public void setPayOrderId(String payOrderId) {
        this.payOrderId = payOrderId;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public BigDecimal getPayAmount() {
        return payAmount;
    }

    public void setPayAmount(BigDecimal payAmount) {
        this.payAmount = payAmount;
    }

    public String getPayChannel() {
        return payChannel;
    }

    public void setPayChannel(String payChannel) {
        this.payChannel = payChannel;
    }

    public String getChannelOrderId() {
        return channelOrderId;
    }

    public void setChannelOrderId(String channelOrderId) {
        this.channelOrderId = channelOrderId;
    }
}

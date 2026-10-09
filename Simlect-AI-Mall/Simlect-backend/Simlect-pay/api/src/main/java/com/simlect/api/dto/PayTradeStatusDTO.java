package com.simlect.api.dto;

import java.io.Serializable;

public class PayTradeStatusDTO implements Serializable {
    private String payOrderId;
    private String channelOrderId;

    public PayTradeStatusDTO() {
    }

    public PayTradeStatusDTO(String payOrderId) {
        this.payOrderId = payOrderId;
    }

    public PayTradeStatusDTO(String payOrderId, String channelOrderId) {
        this.payOrderId = payOrderId;
        this.channelOrderId = channelOrderId;
    }

    public String getPayOrderId() { return payOrderId; }
    public void setPayOrderId(String payOrderId) { this.payOrderId = payOrderId; }
    public String getChannelOrderId() { return channelOrderId; }
    public void setChannelOrderId(String channelOrderId) { this.channelOrderId = channelOrderId; }
}

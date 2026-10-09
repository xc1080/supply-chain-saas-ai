package com.simlect.api.dto;

import java.io.Serializable;

public class PayQueryDTO implements Serializable {
    private String payOrderId;
    private String payChannel;

    public PayQueryDTO() {
    }

    public PayQueryDTO(String payOrderId, String payChannel) {
        this.payOrderId = payOrderId;
        this.payChannel = payChannel;
    }

    public String getPayOrderId() {
        return payOrderId;
    }

    public void setPayOrderId(String payOrderId) {
        this.payOrderId = payOrderId;
    }

    public String getPayChannel() {
        return payChannel;
    }

    public void setPayChannel(String payChannel) {
        this.payChannel = payChannel;
    }
}

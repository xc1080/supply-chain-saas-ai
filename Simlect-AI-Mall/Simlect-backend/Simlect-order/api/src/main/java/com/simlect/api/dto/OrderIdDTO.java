package com.simlect.api.dto;

import java.io.Serializable;

public class OrderIdDTO implements Serializable {
    private String orderId;
    private String userId;

    public OrderIdDTO() {
    }

    public OrderIdDTO(String orderId) {
        this.orderId = orderId;
    }

    public OrderIdDTO(String orderId, String userId) {
        this.orderId = orderId;
        this.userId = userId;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }
}

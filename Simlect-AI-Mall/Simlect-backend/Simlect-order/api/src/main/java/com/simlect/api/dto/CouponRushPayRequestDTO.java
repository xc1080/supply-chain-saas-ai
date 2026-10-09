package com.simlect.api.dto;

import java.io.Serializable;

public class CouponRushPayRequestDTO implements Serializable {
    private String userId;
    private String couponId;
    private String payMethod;

    public CouponRushPayRequestDTO() {
    }

    public CouponRushPayRequestDTO(String userId, String couponId, String payMethod) {
        this.userId = userId;
        this.couponId = couponId;
        this.payMethod = payMethod;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getCouponId() {
        return couponId;
    }

    public void setCouponId(String couponId) {
        this.couponId = couponId;
    }

    public String getPayMethod() {
        return payMethod;
    }

    public void setPayMethod(String payMethod) {
        this.payMethod = payMethod;
    }
}

package com.simlect.api.dto;

import java.io.Serializable;

public class CouponRushPrepareRequestDTO implements Serializable {
    private String userId;
    private String couponId;

    public CouponRushPrepareRequestDTO() {
    }

    public CouponRushPrepareRequestDTO(String userId, String couponId) {
        this.userId = userId;
        this.couponId = couponId;
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
}

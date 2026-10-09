package com.simlect.api.dto;

import java.io.Serializable;

public class CouponRushOpsDTO implements Serializable {
    private String couponId;
    private String userId;

    public CouponRushOpsDTO() {
    }

    public CouponRushOpsDTO(String couponId) {
        this.couponId = couponId;
    }

    public CouponRushOpsDTO(String couponId, String userId) {
        this.couponId = couponId;
        this.userId = userId;
    }

    public String getCouponId() {
        return couponId;
    }

    public void setCouponId(String couponId) {
        this.couponId = couponId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }
}

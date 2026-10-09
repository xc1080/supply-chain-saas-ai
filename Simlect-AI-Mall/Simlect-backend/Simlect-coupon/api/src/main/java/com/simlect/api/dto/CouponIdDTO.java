package com.simlect.api.dto;

import jakarta.validation.constraints.NotEmpty;

import java.io.Serializable;

public class CouponIdDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotEmpty
    private String couponId;

    public CouponIdDTO() {
    }

    public CouponIdDTO(String couponId) {
        this.couponId = couponId;
    }

    public String getCouponId() {
        return couponId;
    }

    public void setCouponId(String couponId) {
        this.couponId = couponId;
    }
}

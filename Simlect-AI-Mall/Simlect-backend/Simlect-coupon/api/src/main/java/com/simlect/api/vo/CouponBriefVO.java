package com.simlect.api.vo;

import java.io.Serializable;

public class CouponBriefVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String couponId;
    private String couponName;
    private Integer couponType;

    public String getCouponId() {
        return couponId;
    }

    public void setCouponId(String couponId) {
        this.couponId = couponId;
    }

    public String getCouponName() {
        return couponName;
    }

    public void setCouponName(String couponName) {
        this.couponName = couponName;
    }

    public Integer getCouponType() {
        return couponType;
    }

    public void setCouponType(Integer couponType) {
        this.couponType = couponType;
    }
}

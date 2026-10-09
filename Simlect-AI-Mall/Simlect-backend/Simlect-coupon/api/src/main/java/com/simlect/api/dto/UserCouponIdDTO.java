package com.simlect.api.dto;

import jakarta.validation.constraints.NotEmpty;

import java.io.Serializable;

public class UserCouponIdDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotEmpty
    private String userCouponId;

    public UserCouponIdDTO() {
    }

    public UserCouponIdDTO(String userCouponId) {
        this.userCouponId = userCouponId;
    }

    public String getUserCouponId() {
        return userCouponId;
    }

    public void setUserCouponId(String userCouponId) {
        this.userCouponId = userCouponId;
    }
}

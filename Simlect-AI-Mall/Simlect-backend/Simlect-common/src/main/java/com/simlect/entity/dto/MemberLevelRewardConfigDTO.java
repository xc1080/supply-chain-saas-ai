package com.simlect.entity.dto;

import java.io.Serializable;

public class MemberLevelRewardConfigDTO implements Serializable {

    private String level2CouponId;

    private String level3CouponId;

    private String level2CouponName;

    private String level3CouponName;

    public String getLevel2CouponId() {
        return level2CouponId;
    }

    public void setLevel2CouponId(String level2CouponId) {
        this.level2CouponId = level2CouponId;
    }

    public String getLevel3CouponId() {
        return level3CouponId;
    }

    public void setLevel3CouponId(String level3CouponId) {
        this.level3CouponId = level3CouponId;
    }

    public String getLevel2CouponName() {
        return level2CouponName;
    }

    public void setLevel2CouponName(String level2CouponName) {
        this.level2CouponName = level2CouponName;
    }

    public String getLevel3CouponName() {
        return level3CouponName;
    }

    public void setLevel3CouponName(String level3CouponName) {
        this.level3CouponName = level3CouponName;
    }
}

package com.simlect.api.dto;

import java.io.Serializable;

public class CouponRushStockReconcileDTO implements Serializable {

    private String couponId;
    private Integer dbRemainCount;
    private Integer redisStockBefore;
    private Integer redisStockAfter;
    private boolean adjusted;

    public String getCouponId() {
        return couponId;
    }

    public void setCouponId(String couponId) {
        this.couponId = couponId;
    }

    public Integer getDbRemainCount() {
        return dbRemainCount;
    }

    public void setDbRemainCount(Integer dbRemainCount) {
        this.dbRemainCount = dbRemainCount;
    }

    public Integer getRedisStockBefore() {
        return redisStockBefore;
    }

    public void setRedisStockBefore(Integer redisStockBefore) {
        this.redisStockBefore = redisStockBefore;
    }

    public Integer getRedisStockAfter() {
        return redisStockAfter;
    }

    public void setRedisStockAfter(Integer redisStockAfter) {
        this.redisStockAfter = redisStockAfter;
    }

    public boolean isAdjusted() {
        return adjusted;
    }

    public void setAdjusted(boolean adjusted) {
        this.adjusted = adjusted;
    }
}

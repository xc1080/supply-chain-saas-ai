package com.simlect.api.dto;

import jakarta.validation.constraints.NotEmpty;

import java.math.BigDecimal;

public class DiscountCouponDTO {
    private String couponId;
    @NotEmpty
    private String couponName;
    @NotEmpty
    private Integer couponType;
    private BigDecimal thresholdAmount;
    private BigDecimal discountAmount;

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

    public BigDecimal getThresholdAmount() {
        return thresholdAmount;
    }

    public void setThresholdAmount(BigDecimal thresholdAmount) {
        this.thresholdAmount = thresholdAmount;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public void setDiscountAmount(BigDecimal discountAmount) {
        this.discountAmount = discountAmount;
    }

    public BigDecimal getDiscountRate() {
        return discountRate;
    }

    public void setDiscountRate(BigDecimal discountRate) {
        this.discountRate = discountRate;
    }

    public Integer getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(Integer totalCount) {
        this.totalCount = totalCount;
    }

    public String getValidStartTime() {
        return validStartTime;
    }

    public void setValidStartTime(String validStartTime) {
        this.validStartTime = validStartTime;
    }

    public String getValidEndTime() {
        return validEndTime;
    }

    public void setValidEndTime(String validEndTime) {
        this.validEndTime = validEndTime;
    }

    public Integer getRushingstatus() {
        return rushingstatus;
    }

    public void setRushingstatus(Integer rushingstatus) {
        this.rushingstatus = rushingstatus;
    }

    public String getRushingStartTime() {
        return rushingStartTime;
    }

    public void setRushingStartTime(String rushingStartTime) {
        this.rushingStartTime = rushingStartTime;
    }

    public String getRushingEndTime() {
        return rushingEndTime;
    }

    public void setRushingEndTime(String rushingEndTime) {
        this.rushingEndTime = rushingEndTime;
    }

    private BigDecimal discountRate;
    private Integer totalCount;
    private String validStartTime;
    private String validEndTime;
    private Integer rushingstatus;
    private String rushingStartTime;
    private String rushingEndTime;
}

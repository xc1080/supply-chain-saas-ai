package com.simlect.api.vo;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

public class DiscountCouponVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String couponId;
    private String couponName;
    private Integer couponType;
    private BigDecimal thresholdAmount;
    private BigDecimal discountAmount;
    private BigDecimal discountRate;
    private Date validStartTime;
    private Date validEndTime;
    private Integer remainCount;
    private Integer rushingstatus;
    private Date rushingStartTime;
    private Date rushingEndTime;
    private Integer totalCount;

    public boolean isUnlimitedStock() {
        return totalCount != null && totalCount == 0;
    }

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

    public Date getValidStartTime() {
        return validStartTime;
    }

    public void setValidStartTime(Date validStartTime) {
        this.validStartTime = validStartTime;
    }

    public Date getValidEndTime() {
        return validEndTime;
    }

    public void setValidEndTime(Date validEndTime) {
        this.validEndTime = validEndTime;
    }

    public Integer getRemainCount() {
        return remainCount;
    }

    public void setRemainCount(Integer remainCount) {
        this.remainCount = remainCount;
    }

    public Integer getRushingstatus() {
        return rushingstatus;
    }

    public void setRushingstatus(Integer rushingstatus) {
        this.rushingstatus = rushingstatus;
    }

    public Date getRushingStartTime() {
        return rushingStartTime;
    }

    public void setRushingStartTime(Date rushingStartTime) {
        this.rushingStartTime = rushingStartTime;
    }

    public Date getRushingEndTime() {
        return rushingEndTime;
    }

    public void setRushingEndTime(Date rushingEndTime) {
        this.rushingEndTime = rushingEndTime;
    }

    public Integer getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(Integer totalCount) {
        this.totalCount = totalCount;
    }
}

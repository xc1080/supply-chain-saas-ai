package com.simlect.api.dto;

import java.io.Serializable;

public class CouponLogicalCacheEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    private String payload;

    private Long logicalExpireAt;

    public CouponLogicalCacheEntry() {
    }

    public CouponLogicalCacheEntry(String payload, Long logicalExpireAt) {
        this.payload = payload;
        this.logicalExpireAt = logicalExpireAt;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public Long getLogicalExpireAt() {
        return logicalExpireAt;
    }

    public void setLogicalExpireAt(Long logicalExpireAt) {
        this.logicalExpireAt = logicalExpireAt;
    }
}

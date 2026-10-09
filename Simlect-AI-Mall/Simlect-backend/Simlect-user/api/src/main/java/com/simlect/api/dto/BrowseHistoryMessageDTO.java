package com.simlect.api.dto;

import java.io.Serializable;

public class BrowseHistoryMessageDTO implements Serializable {

    private String userId;
    private String productId;
    private Long browseTime;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public Long getBrowseTime() {
        return browseTime;
    }

    public void setBrowseTime(Long browseTime) {
        this.browseTime = browseTime;
    }
}

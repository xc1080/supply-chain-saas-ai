package com.simlect.api.dto;

import java.io.Serializable;

public class CartDeleteItemDTO implements Serializable {
    private String userId;
    private String productId;
    private String propertyValueIdHash;
    private String propertyValueIds;

    public CartDeleteItemDTO() {}

    public CartDeleteItemDTO(String userId, String productId, String propertyValueIdHash, String propertyValueIds) {
        this.userId = userId;
        this.productId = productId;
        this.propertyValueIdHash = propertyValueIdHash;
        this.propertyValueIds = propertyValueIds;
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }
    public String getPropertyValueIdHash() { return propertyValueIdHash; }
    public void setPropertyValueIdHash(String propertyValueIdHash) { this.propertyValueIdHash = propertyValueIdHash; }
    public String getPropertyValueIds() { return propertyValueIds; }
    public void setPropertyValueIds(String propertyValueIds) { this.propertyValueIds = propertyValueIds; }
}

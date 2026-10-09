package com.simlect.api.dto;

import jakarta.validation.constraints.NotEmpty;

import java.io.Serializable;

public class SkuStockQueryDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotEmpty
    private String productId;
    @NotEmpty
    private String propertyValueIdHash;

    public SkuStockQueryDTO() {
    }

    public SkuStockQueryDTO(String productId, String propertyValueIdHash) {
        this.productId = productId;
        this.propertyValueIdHash = propertyValueIdHash;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public String getPropertyValueIdHash() {
        return propertyValueIdHash;
    }

    public void setPropertyValueIdHash(String propertyValueIdHash) {
        this.propertyValueIdHash = propertyValueIdHash;
    }
}

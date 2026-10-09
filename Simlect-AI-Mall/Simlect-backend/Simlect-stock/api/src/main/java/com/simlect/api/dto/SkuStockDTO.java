package com.simlect.api.dto;

import java.io.Serializable;

public class SkuStockDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String productId;
    private String propertyValueIdHash;
    private Integer stock;

    public SkuStockDTO() {
    }

    public SkuStockDTO(String productId, String propertyValueIdHash, Integer stock) {
        this.productId = productId;
        this.propertyValueIdHash = propertyValueIdHash;
        this.stock = stock;
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

    public Integer getStock() {
        return stock;
    }

    public void setStock(Integer stock) {
        this.stock = stock;
    }
}

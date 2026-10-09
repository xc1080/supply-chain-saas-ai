package com.simlect.domain;

public class SkuStock {

    private String productId;
    private String propertyValueIdHash;
    private Integer stock;

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

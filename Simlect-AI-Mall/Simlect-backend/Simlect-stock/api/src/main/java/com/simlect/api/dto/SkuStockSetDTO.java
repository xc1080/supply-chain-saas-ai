package com.simlect.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.io.Serializable;

public class SkuStockSetDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotEmpty
    private String productId;
    @NotEmpty
    private String propertyValueIdHash;
    @NotNull
    private Integer stock;

    public SkuStockSetDTO() {
    }

    public SkuStockSetDTO(String productId, String propertyValueIdHash, Integer stock) {
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

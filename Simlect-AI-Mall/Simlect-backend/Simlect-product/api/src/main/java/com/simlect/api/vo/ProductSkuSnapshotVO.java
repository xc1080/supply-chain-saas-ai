package com.simlect.api.vo;

import java.io.Serializable;
import java.math.BigDecimal;

public class ProductSkuSnapshotVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String productId;
    private String propertyValueIdHash;
    private String propertyValueIds;
    private BigDecimal price;
    private Integer sort;

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

    public String getPropertyValueIds() {
        return propertyValueIds;
    }

    public void setPropertyValueIds(String propertyValueIds) {
        this.propertyValueIds = propertyValueIds;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }
}

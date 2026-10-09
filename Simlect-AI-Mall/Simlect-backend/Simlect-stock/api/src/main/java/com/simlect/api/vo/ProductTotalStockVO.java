package com.simlect.api.vo;

import java.io.Serializable;

public class ProductTotalStockVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String productId;
    private Integer totalStock;

    public ProductTotalStockVO() {
    }

    public ProductTotalStockVO(String productId, Integer totalStock) {
        this.productId = productId;
        this.totalStock = totalStock;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public Integer getTotalStock() {
        return totalStock;
    }

    public void setTotalStock(Integer totalStock) {
        this.totalStock = totalStock;
    }
}

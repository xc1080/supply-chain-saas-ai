package com.simlect.api.dto;

import java.io.Serializable;

public class ProductSalesIncreaseDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String productId;
    private Integer qty;

    public ProductSalesIncreaseDTO() {
    }

    public ProductSalesIncreaseDTO(String productId, Integer qty) {
        this.productId = productId;
        this.qty = qty;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public Integer getQty() {
        return qty;
    }

    public void setQty(Integer qty) {
        this.qty = qty;
    }
}

package com.simlect.api.dto;

import jakarta.validation.constraints.NotEmpty;

import java.io.Serializable;

public class ProductIdDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotEmpty
    private String productId;

    public ProductIdDTO() {
    }

    public ProductIdDTO(String productId) {
        this.productId = productId;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }
}

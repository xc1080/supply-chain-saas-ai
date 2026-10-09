package com.simlect.api.dto;

import java.io.Serializable;
import java.util.List;

public class ProductIdListDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<String> productIds;

    public ProductIdListDTO() {
    }

    public ProductIdListDTO(List<String> productIds) {
        this.productIds = productIds;
    }

    public List<String> getProductIds() {
        return productIds;
    }

    public void setProductIds(List<String> productIds) {
        this.productIds = productIds;
    }
}

package com.simlect.api.vo;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class ProductRagIndexVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String productId;
    private String productName;
    private Integer status;
    private List<ProductRagPropertyVO> propertyValues = new ArrayList<>();
    private List<ProductRagSkuVO> skus = new ArrayList<>();

    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public List<ProductRagPropertyVO> getPropertyValues() { return propertyValues; }
    public void setPropertyValues(List<ProductRagPropertyVO> propertyValues) { this.propertyValues = propertyValues; }
    public List<ProductRagSkuVO> getSkus() { return skus; }
    public void setSkus(List<ProductRagSkuVO> skus) { this.skus = skus; }
}

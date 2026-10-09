package com.simlect.api.vo;

import java.math.BigDecimal;
import java.util.List;

public class ProductSkuListVO {

    private String productId;
    private String productName;
    private String propertyValueIds;
    private BigDecimal price;
    private Integer stock;
    private List<ProductSkuProperDataVO> propertyData;
    private String productCover;
    private Boolean productOnsale;

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public String getProductCover() {
        return productCover;
    }

    public void setProductCover(String productCover) {
        this.productCover = productCover;
    }

    public Boolean getProductOnsale() {
        return productOnsale;
    }

    public void setProductOnsale(Boolean productOnsale) {
        this.productOnsale = productOnsale;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
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

    public Integer getStock() {
        return stock;
    }

    public void setStock(Integer stock) {
        this.stock = stock;
    }

    public List<ProductSkuProperDataVO> getPropertyData() {
        return propertyData;
    }

    public void setPropertyData(List<ProductSkuProperDataVO> propertyData) {
        this.propertyData = propertyData;
    }
}

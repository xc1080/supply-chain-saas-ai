package com.simlect.api.vo;

import jakarta.validation.constraints.NotEmpty;

import java.math.BigDecimal;
import java.util.List;

public class ProductCartVO {
    @NotEmpty
    private String cartId;
    @NotEmpty
    private String productId;
    @NotEmpty
    private String productName;
    private String productCover;
    @NotEmpty
    private String propertyValueIds;
    private String propertyValueIdHash;
    @NotEmpty
    List<PropertyData> propertyData;
    @NotEmpty
    private BigDecimal price;

    private BigDecimal addPrice;

    public Integer getBuyCount() {
        return buyCount;
    }

    public void setBuyCount(Integer buyCount) {
        this.buyCount = buyCount;
    }

    public String getCartId() {
        return cartId;
    }

    public void setCartId(String cartId) {
        this.cartId = cartId;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public String getProductCover() {
        return productCover;
    }

    public void setProductCover(String productCover) {
        this.productCover = productCover;
    }

    public String getPropertyValueIds() {
        return propertyValueIds;
    }

    public void setPropertyValueIds(String propertyValueIds) {
        this.propertyValueIds = propertyValueIds;
    }

    public String getPropertyValueIdHash() {
        return propertyValueIdHash;
    }

    public void setPropertyValueIdHash(String propertyValueIdHash) {
        this.propertyValueIdHash = propertyValueIdHash;
    }

    public List<PropertyData> getPropertyData() {
        return propertyData;
    }

    public void setPropertyData(List<PropertyData> propertyData) {
        this.propertyData = propertyData;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public BigDecimal getAddPrice() {
        return addPrice;
    }

    public void setAddPrice(BigDecimal addPrice) {
        this.addPrice = addPrice;
    }

    public Boolean getProductOnSale() {
        return productOnSale;
    }

    public void setProductOnSale(Boolean productOnSale) {
        this.productOnSale = productOnSale;
    }

    @NotEmpty
    private Integer buyCount;
    @NotEmpty
    private Boolean productOnSale;

    @NotEmpty
    private Integer stock;

    public Integer getStock() {
        return stock;
    }

    public void setStock(Integer stock) {
        this.stock = stock;
    }
}

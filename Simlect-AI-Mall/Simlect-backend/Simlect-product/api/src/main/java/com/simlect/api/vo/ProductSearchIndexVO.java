package com.simlect.api.vo;

import java.io.Serializable;
import java.math.BigDecimal;

public class ProductSearchIndexVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String productId;
    private String productName;
    private String productDesc;
    private String cover;
    private String categoryId;
    private BigDecimal minPrice;
    private BigDecimal maxPrice;
    private Integer totalSale;
    private Integer status;

    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public String getProductDesc() { return productDesc; }
    public void setProductDesc(String productDesc) { this.productDesc = productDesc; }
    public String getCover() { return cover; }
    public void setCover(String cover) { this.cover = cover; }
    public String getCategoryId() { return categoryId; }
    public void setCategoryId(String categoryId) { this.categoryId = categoryId; }
    public BigDecimal getMinPrice() { return minPrice; }
    public void setMinPrice(BigDecimal minPrice) { this.minPrice = minPrice; }
    public BigDecimal getMaxPrice() { return maxPrice; }
    public void setMaxPrice(BigDecimal maxPrice) { this.maxPrice = maxPrice; }
    public Integer getTotalSale() { return totalSale; }
    public void setTotalSale(Integer totalSale) { this.totalSale = totalSale; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
}

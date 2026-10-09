package com.simlect.entity.dto;

import com.simlect.entity.po.ProductInfo;
import com.simlect.entity.po.ProductPropertyValue;
import com.simlect.entity.po.ProductSku;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.List;

public class ProductSaveDTO {
//    {
//        "productInfo": {...},           // 商品基本信息
//        "productPropertyList": [...],   // 商品属性值列表（至少1个）
//        "skuList": [...]                // SKU列表（至少1个）
//    }
    @Valid
    private ProductInfo productInfo;

    @Valid
    @Size(min = 1)
    private List<ProductPropertyValue> productPropertyList;

    public List<ProductSku> getSkuList() {
        return skuList;
    }

    public void setSkuList(List<ProductSku> skuList) {
        this.skuList = skuList;
    }

    public List<ProductPropertyValue> getProductPropertyList() {
        return productPropertyList;
    }

    public void setProductPropertyList(List<ProductPropertyValue> productPropertyList) {
        this.productPropertyList = productPropertyList;
    }

    public ProductInfo getProductInfo() {
        return productInfo;
    }

    public void setProductInfo(ProductInfo productInfo) {
        this.productInfo = productInfo;
    }

    @Valid
    @Size(min = 1)
    private List<ProductSku> skuList;
}

package com.simlect.entity.vo;

import com.simlect.api.vo.ProductPropertyVO;
import com.simlect.entity.po.ProductInfo;
import com.simlect.entity.po.ProductPropertyValue;
import com.simlect.entity.po.ProductSku;
import jakarta.validation.constraints.NotEmpty;

import java.io.Serializable;
import java.util.List;

public class Product4VO implements Serializable {

    private ProductInfo productInfo;

    private List<ProductPropertyVO> productPropertyList;

    private List<ProductSku> skuList;

    public ProductInfo getProductInfo() {
        return productInfo;
    }

    public void setProductInfo(ProductInfo productInfo) {
        this.productInfo = productInfo;
    }

    public List<ProductPropertyVO> getProductPropertyList() {
        return productPropertyList;
    }

    public void setProductPropertyList(List<ProductPropertyVO> productPropertyList) {
        this.productPropertyList = productPropertyList;
    }

    public List<ProductSku> getSkuList() {
        return skuList;
    }

    public void setSkuList(List<ProductSku> skuList) {
        this.skuList = skuList;
    }
}

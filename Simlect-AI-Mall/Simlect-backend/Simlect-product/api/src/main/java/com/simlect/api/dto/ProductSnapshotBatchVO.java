package com.simlect.api.dto;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import com.simlect.api.vo.ProductInfoSnapshotVO;
import com.simlect.api.vo.ProductPropertyValueSnapshotVO;
import com.simlect.api.vo.ProductSkuSnapshotVO;

public class ProductSnapshotBatchVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<ProductInfoSnapshotVO> products = new ArrayList<>();
    private List<ProductSkuSnapshotVO> skus = new ArrayList<>();
    private List<ProductPropertyValueSnapshotVO> propertyValues = new ArrayList<>();

    public List<ProductInfoSnapshotVO> getProducts() {
        return products;
    }

    public void setProducts(List<ProductInfoSnapshotVO> products) {
        this.products = products;
    }

    public List<ProductSkuSnapshotVO> getSkus() {
        return skus;
    }

    public void setSkus(List<ProductSkuSnapshotVO> skus) {
        this.skus = skus;
    }

    public List<ProductPropertyValueSnapshotVO> getPropertyValues() {
        return propertyValues;
    }

    public void setPropertyValues(List<ProductPropertyValueSnapshotVO> propertyValues) {
        this.propertyValues = propertyValues;
    }
}

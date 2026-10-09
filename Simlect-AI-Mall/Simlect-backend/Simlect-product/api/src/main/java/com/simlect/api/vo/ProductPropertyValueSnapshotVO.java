package com.simlect.api.vo;

import java.io.Serializable;

public class ProductPropertyValueSnapshotVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String productId;
    private String propertyValueId;
    private String propertyName;
    private String propertyValue;
    private String propertyCover;

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public String getPropertyValueId() {
        return propertyValueId;
    }

    public void setPropertyValueId(String propertyValueId) {
        this.propertyValueId = propertyValueId;
    }

    public String getPropertyName() {
        return propertyName;
    }

    public void setPropertyName(String propertyName) {
        this.propertyName = propertyName;
    }

    public String getPropertyValue() {
        return propertyValue;
    }

    public void setPropertyValue(String propertyValue) {
        this.propertyValue = propertyValue;
    }

    public String getPropertyCover() {
        return propertyCover;
    }

    public void setPropertyCover(String propertyCover) {
        this.propertyCover = propertyCover;
    }
}

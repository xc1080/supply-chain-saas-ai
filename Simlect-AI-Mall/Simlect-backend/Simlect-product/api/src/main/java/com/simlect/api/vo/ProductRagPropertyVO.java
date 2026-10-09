package com.simlect.api.vo;

import java.io.Serializable;

public class ProductRagPropertyVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String propertyValueId;
    private String propertyName;
    private String propertyValue;

    public String getPropertyValueId() { return propertyValueId; }
    public void setPropertyValueId(String propertyValueId) { this.propertyValueId = propertyValueId; }
    public String getPropertyName() { return propertyName; }
    public void setPropertyName(String propertyName) { this.propertyName = propertyName; }
    public String getPropertyValue() { return propertyValue; }
    public void setPropertyValue(String propertyValue) { this.propertyValue = propertyValue; }
}

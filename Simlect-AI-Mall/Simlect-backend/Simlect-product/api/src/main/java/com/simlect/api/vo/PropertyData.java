package com.simlect.api.vo;

import jakarta.validation.constraints.NotEmpty;

public class PropertyData {
    @NotEmpty
    private String propertyName;

    public String getPropertyValue() {
        return propertyValue;
    }

    public void setPropertyValue(String propertyValue) {
        this.propertyValue = propertyValue;
    }

    public String getPropertyName() {
        return propertyName;
    }

    public void setPropertyName(String propertyName) {
        this.propertyName = propertyName;
    }

    @NotEmpty
    private String propertyValue;
}

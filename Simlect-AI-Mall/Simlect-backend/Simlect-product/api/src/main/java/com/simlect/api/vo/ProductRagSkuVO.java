package com.simlect.api.vo;

import java.io.Serializable;

public class ProductRagSkuVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String propertyValueIds;
    private String propertyValueIdHash;

    public String getPropertyValueIds() { return propertyValueIds; }
    public void setPropertyValueIds(String propertyValueIds) { this.propertyValueIds = propertyValueIds; }
    public String getPropertyValueIdHash() { return propertyValueIdHash; }
    public void setPropertyValueIdHash(String propertyValueIdHash) { this.propertyValueIdHash = propertyValueIdHash; }
}

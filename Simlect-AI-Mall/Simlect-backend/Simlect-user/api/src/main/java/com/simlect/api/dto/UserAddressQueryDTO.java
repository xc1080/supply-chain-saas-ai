package com.simlect.api.dto;

import jakarta.validation.constraints.NotEmpty;

import java.io.Serializable;

public class UserAddressQueryDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotEmpty
    private String addressId;
    @NotEmpty
    private String userId;

    public UserAddressQueryDTO() {
    }

    public UserAddressQueryDTO(String addressId, String userId) {
        this.addressId = addressId;
        this.userId = userId;
    }

    public String getAddressId() {
        return addressId;
    }

    public void setAddressId(String addressId) {
        this.addressId = addressId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }
}

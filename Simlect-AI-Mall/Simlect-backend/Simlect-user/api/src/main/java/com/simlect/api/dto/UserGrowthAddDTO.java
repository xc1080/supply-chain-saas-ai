package com.simlect.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.io.Serializable;
import java.math.BigDecimal;

public class UserGrowthAddDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotEmpty
    private String userId;
    @NotNull
    private BigDecimal payAmount;

    public UserGrowthAddDTO() {
    }

    public UserGrowthAddDTO(String userId, BigDecimal payAmount) {
        this.userId = userId;
        this.payAmount = payAmount;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public BigDecimal getPayAmount() {
        return payAmount;
    }

    public void setPayAmount(BigDecimal payAmount) {
        this.payAmount = payAmount;
    }
}

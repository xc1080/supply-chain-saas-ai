package com.simlect.api.dto;

import com.simlect.entity.po.ProductItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Validated
public class PostOrderDTO {
    @NotEmpty
    private String payMethod;
    @NotEmpty
    private String addressId;
    @NotNull
    private Integer orderFrom;

    public List<ProductItem> getOrderList() {
        return orderList;
    }

    public void setOrderList(List<ProductItem> orderList) {
        this.orderList = orderList;
    }

    public Integer getOrderFrom() {
        return orderFrom;
    }

    public void setOrderFrom(Integer orderFrom) {
        this.orderFrom = orderFrom;
    }

    public String getAddressId() {
        return addressId;
    }

    public void setAddressId(String addressId) {
        this.addressId = addressId;
    }

    public String getPayMethod() {
        return payMethod;
    }

    public void setPayMethod(String payMethod) {
        this.payMethod = payMethod;
    }

    @NotEmpty
    @Valid
    private List<ProductItem> orderList;

    private String userCouponId;

    public String getUserCouponId() {
        return userCouponId;
    }

    public void setUserCouponId(String userCouponId) {
        this.userCouponId = userCouponId;
    }
}

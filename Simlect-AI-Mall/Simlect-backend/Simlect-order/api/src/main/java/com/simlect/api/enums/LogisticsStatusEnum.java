package com.simlect.api.enums;

import java.util.Arrays;
import java.util.Optional;

public enum LogisticsStatusEnum {
    PENDING_SHIPMENT(0, "待发货"),
    IN_TRANSIT(1, "运输中"),
    DELIVERED(2, "已送达"),
    CANCELLED(3, "订单取消");

    private Integer status;
    private String desc;

    LogisticsStatusEnum(Integer status, String desc) {
        this.status = status;
        this.desc = desc;
    }

    public Integer getStatus() {
        return status;
    }

    public String getDesc() {
        return desc;
    }

    public static LogisticsStatusEnum getByStatus(Integer status) {
        Optional<LogisticsStatusEnum> resultEnum = Arrays.stream(LogisticsStatusEnum.values()).filter(value -> value.getStatus().equals(status)).findFirst();
        return resultEnum == null || resultEnum.isEmpty() ? null : resultEnum.get();
    }
}
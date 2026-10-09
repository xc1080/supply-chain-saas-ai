package com.simlect.entity.enums;

public enum OutboxMessageStatusEnum {
    PENDING(0, "待发送"),
    SENDING(1, "发送中"),
    SENT(2, "已发送"),
    FAILED(3, "失败");

    private final Integer status;
    private final String desc;

    OutboxMessageStatusEnum(Integer status, String desc) {
        this.status = status;
        this.desc = desc;
    }

    public Integer getStatus() {
        return status;
    }

    public String getDesc() {
        return desc;
    }
}

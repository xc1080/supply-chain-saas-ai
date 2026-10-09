package com.simlect.entity.enums;

import java.util.Arrays;

public enum MqCompensationLogStatusEnum {

    PENDING(0, "待处理"),
    PROCESSING(1, "处理中"),
    REPLAYED(2, "已重放成功"),
    REPLAY_FAILED(3, "重放失败"),
    IGNORED(4, "已忽略");

    private final Integer status;
    private final String desc;

    MqCompensationLogStatusEnum(Integer status, String desc) {
        this.status = status;
        this.desc = desc;
    }

    public Integer getStatus() {
        return status;
    }

    public String getDesc() {
        return desc;
    }

    public static MqCompensationLogStatusEnum getByStatus(Integer status) {
        if (status == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(item -> item.status.equals(status))
                .findFirst()
                .orElse(null);
    }
}

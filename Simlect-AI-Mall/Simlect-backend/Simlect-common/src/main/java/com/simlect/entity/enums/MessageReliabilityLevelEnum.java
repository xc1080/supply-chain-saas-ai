package com.simlect.entity.enums;

public enum MessageReliabilityLevelEnum {

    HIGH("high", "高并发 - 异步刷盘+补偿"),
    STANDARD("standard", "同步刷盘+重试");

    private final String code;
    private final String description;

    MessageReliabilityLevelEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }
}

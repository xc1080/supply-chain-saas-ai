package com.simlect.api.enums;

import java.util.Arrays;
import java.util.Optional;

public enum ImageModerationStatusEnum {
    PENDING(0, "待人工复核"),
    APPROVED(1, "已通过"),
    VIOLATION(2, "确认违规"),
    DISMISSED(3, "误报驳回");

    private final Integer status;
    private final String desc;

    ImageModerationStatusEnum(Integer status, String desc) {
        this.status = status;
        this.desc = desc;
    }

    public Integer getStatus() {
        return status;
    }

    public String getDesc() {
        return desc;
    }

    public static ImageModerationStatusEnum getByStatus(Integer status) {
        Optional<ImageModerationStatusEnum> found = Arrays.stream(values())
                .filter(v -> v.getStatus().equals(status))
                .findFirst();
        return found.orElse(null);
    }
}

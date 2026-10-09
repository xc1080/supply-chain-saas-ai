package com.simlect.api.dto;

import java.io.Serializable;

public class UserTempBanDTO implements Serializable {
    private String userId;

    private Long unbanAtMs;

    public UserTempBanDTO() {
    }

    public UserTempBanDTO(String userId, Long unbanAtMs) {
        this.userId = userId;
        this.unbanAtMs = unbanAtMs;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Long getUnbanAtMs() {
        return unbanAtMs;
    }

    public void setUnbanAtMs(Long unbanAtMs) {
        this.unbanAtMs = unbanAtMs;
    }
}

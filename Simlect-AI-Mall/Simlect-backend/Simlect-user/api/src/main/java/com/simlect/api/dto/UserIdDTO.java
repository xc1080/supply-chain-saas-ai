package com.simlect.api.dto;

import java.io.Serializable;

public class UserIdDTO implements Serializable {
    private String userId;

    public UserIdDTO() {
    }

    public UserIdDTO(String userId) {
        this.userId = userId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }
}

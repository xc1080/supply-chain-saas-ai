package com.simlect.api.dto;

import java.io.Serializable;
import java.util.List;

public class UserIdsDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private List<String> userIds;

    public UserIdsDTO() {
    }

    public UserIdsDTO(List<String> userIds) {
        this.userIds = userIds;
    }

    public List<String> getUserIds() {
        return userIds;
    }

    public void setUserIds(List<String> userIds) {
        this.userIds = userIds;
    }
}

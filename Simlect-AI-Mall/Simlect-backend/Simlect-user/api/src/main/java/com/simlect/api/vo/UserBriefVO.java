package com.simlect.api.vo;

import java.io.Serializable;

public class UserBriefVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String userId;
    private String nickName;
    private String avatar;

    public UserBriefVO() {
    }

    public UserBriefVO(String userId, String nickName, String avatar) {
        this.userId = userId;
        this.nickName = nickName;
        this.avatar = avatar;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getNickName() {
        return nickName;
    }

    public void setNickName(String nickName) {
        this.nickName = nickName;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }
}

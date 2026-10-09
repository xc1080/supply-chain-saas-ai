package com.simlect.api.dto;

import java.io.Serializable;

public class UserNotifyDTO implements Serializable {
    private String userId;
    private String title;
    private String content;
    private String bizType;
    private String bizId;

    public UserNotifyDTO() {
    }

    public UserNotifyDTO(String userId, String title, String content, String bizType, String bizId) {
        this.userId = userId;
        this.title = title;
        this.content = content;
        this.bizType = bizType;
        this.bizId = bizId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getBizType() {
        return bizType;
    }

    public void setBizType(String bizType) {
        this.bizType = bizType;
    }

    public String getBizId() {
        return bizId;
    }

    public void setBizId(String bizId) {
        this.bizId = bizId;
    }
}

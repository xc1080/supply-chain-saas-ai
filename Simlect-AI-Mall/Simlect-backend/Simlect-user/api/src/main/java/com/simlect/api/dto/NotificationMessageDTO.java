package com.simlect.api.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class NotificationMessageDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String userId;
    
    private String title;
    
    private String content;
    
    private String bizType;
    
    private String bizId;

    public NotificationMessageDTO() {
    }

    public NotificationMessageDTO(String userId, String title, String content, String bizType, String bizId) {
        this.userId = userId;
        this.title = title;
        this.content = content;
        this.bizType = bizType;
        this.bizId = bizId;
    }
}
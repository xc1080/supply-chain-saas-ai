package com.simlect.api.dto;

import java.io.Serializable;

public class AdminAuditLogDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String operator;
    private String action;
    private String targetUserId;
    private String detail;

    public AdminAuditLogDTO() {
    }

    public AdminAuditLogDTO(String operator, String action, String targetUserId, String detail) {
        this.operator = operator;
        this.action = action;
        this.targetUserId = targetUserId;
        this.detail = detail;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getTargetUserId() {
        return targetUserId;
    }

    public void setTargetUserId(String targetUserId) {
        this.targetUserId = targetUserId;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }
}

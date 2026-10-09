package com.simlect.entity.dto;

import java.io.Serializable;

public class AdminAuditLogDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String operator;
    private String action;
    private String targetUserId;
    private String detail;

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

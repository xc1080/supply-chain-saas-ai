package com.simlect.api.dto;

import java.io.Serializable;

public class UserJoinCountDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String joinDateStart;
    private String joinDateEnd;

    public UserJoinCountDTO() {
    }

    public UserJoinCountDTO(String joinDateStart, String joinDateEnd) {
        this.joinDateStart = joinDateStart;
        this.joinDateEnd = joinDateEnd;
    }

    public String getJoinDateStart() {
        return joinDateStart;
    }

    public void setJoinDateStart(String joinDateStart) {
        this.joinDateStart = joinDateStart;
    }

    public String getJoinDateEnd() {
        return joinDateEnd;
    }

    public void setJoinDateEnd(String joinDateEnd) {
        this.joinDateEnd = joinDateEnd;
    }
}

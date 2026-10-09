package com.simlect.entity.dto;

import java.io.Serializable;

public class BaiduImageCensorResultDTO implements Serializable {

    private Integer conclusionType;
    private String conclusion;
    private String rawResponse;

    public Integer getConclusionType() {
        return conclusionType;
    }

    public void setConclusionType(Integer conclusionType) {
        this.conclusionType = conclusionType;
    }

    public String getConclusion() {
        return conclusion;
    }

    public void setConclusion(String conclusion) {
        this.conclusion = conclusion;
    }

    public String getRawResponse() {
        return rawResponse;
    }

    public void setRawResponse(String rawResponse) {
        this.rawResponse = rawResponse;
    }

    public boolean isPass() {
        return conclusionType != null && conclusionType == 1;
    }

    public boolean isSuspect() {
        return conclusionType != null && conclusionType == 3;
    }

    public boolean isReject() {
        return conclusionType != null && conclusionType == 2;
    }
}

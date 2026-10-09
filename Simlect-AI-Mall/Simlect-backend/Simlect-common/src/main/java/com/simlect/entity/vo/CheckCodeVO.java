package com.simlect.entity.vo;

public class CheckCodeVO {
    private String checkCode;
    private String checkCodeKey;

    public CheckCodeVO(String checkCode,String checkCodeKey) {
        this.checkCode = checkCode;
        this.checkCodeKey = checkCodeKey;
    }
    public String getCheckCode() {
        return checkCode;
    }

    public void setCheckCode(String checkCode) {
        this.checkCode = checkCode;
    }

    public String getCheckCodeKey() {
        return checkCodeKey;
    }

    public void setCheckCodeKey(String checkCodeKey) {
        this.checkCodeKey = checkCodeKey;
    }
}

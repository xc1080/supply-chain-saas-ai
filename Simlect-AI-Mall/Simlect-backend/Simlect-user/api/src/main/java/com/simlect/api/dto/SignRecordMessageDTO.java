package com.simlect.api.dto;

import java.io.Serializable;

public class SignRecordMessageDTO implements Serializable {

    private String userId;

    private Integer continuousDays;

    private Integer totalSignDays;

    private Integer usedCount;

    private String signDate;

    private Integer signType;

    public SignRecordMessageDTO() {
    }

    public SignRecordMessageDTO(String userId, Integer continuousDays, Integer totalSignDays, Integer usedCount) {
        this.userId = userId;
        this.continuousDays = continuousDays;
        this.totalSignDays = totalSignDays;
        this.usedCount = usedCount;
    }

    public SignRecordMessageDTO(String userId, Integer continuousDays, Integer totalSignDays, Integer usedCount,
                                String signDate, Integer signType) {
        this.userId = userId;
        this.continuousDays = continuousDays;
        this.totalSignDays = totalSignDays;
        this.usedCount = usedCount;
        this.signDate = signDate;
        this.signType = signType;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Integer getContinuousDays() {
        return continuousDays;
    }

    public void setContinuousDays(Integer continuousDays) {
        this.continuousDays = continuousDays;
    }

    public Integer getTotalSignDays() {
        return totalSignDays;
    }

    public void setTotalSignDays(Integer totalSignDays) {
        this.totalSignDays = totalSignDays;
    }

    public Integer getUsedCount() {
        return usedCount;
    }

    public void setUsedCount(Integer usedCount) {
        this.usedCount = usedCount;
    }

    public String getSignDate() {
        return signDate;
    }

    public void setSignDate(String signDate) {
        this.signDate = signDate;
    }

    public Integer getSignType() {
        return signType;
    }

    public void setSignType(Integer signType) {
        this.signType = signType;
    }
}

package com.simlect.api.vo;

import java.util.List;

public class SignDataVO {
    Integer continuousDays;
    Integer supplementCount;
    Integer totalSignDays;
    List<String> signDays;

    public Integer getContinuousDays() {
        return continuousDays;
    }

    public void setContinuousDays(Integer continuousDays) {
        this.continuousDays = continuousDays;
    }

    public Integer getSupplementCount() {
        return supplementCount;
    }

    public void setSupplementCount(Integer supplementCount) {
        this.supplementCount = supplementCount;
    }

    public List<String> getSignDays() {
        return signDays;
    }

    public void setSignDays(List<String> signDays) {
        this.signDays = signDays;
    }

    public Integer getTotalSignDays() {
        return totalSignDays;
    }

    public void setTotalSignDays(Integer totalSignDays) {
        this.totalSignDays = totalSignDays;
    }
}

package com.simlect.api.vo;

import java.io.Serializable;

public class MemberLevelRewardVO implements Serializable {

    private Integer levelCode;
    private String levelName;
    private Integer growthThreshold;
    private String rewardTitle;
    private String rewardDesc;

    private Boolean unlocked;

    private Boolean claimed;

    private Boolean claimable;

    public Integer getLevelCode() {
        return levelCode;
    }

    public void setLevelCode(Integer levelCode) {
        this.levelCode = levelCode;
    }

    public String getLevelName() {
        return levelName;
    }

    public void setLevelName(String levelName) {
        this.levelName = levelName;
    }

    public Integer getGrowthThreshold() {
        return growthThreshold;
    }

    public void setGrowthThreshold(Integer growthThreshold) {
        this.growthThreshold = growthThreshold;
    }

    public String getRewardTitle() {
        return rewardTitle;
    }

    public void setRewardTitle(String rewardTitle) {
        this.rewardTitle = rewardTitle;
    }

    public String getRewardDesc() {
        return rewardDesc;
    }

    public void setRewardDesc(String rewardDesc) {
        this.rewardDesc = rewardDesc;
    }

    public Boolean getUnlocked() {
        return unlocked;
    }

    public void setUnlocked(Boolean unlocked) {
        this.unlocked = unlocked;
    }

    public Boolean getClaimed() {
        return claimed;
    }

    public void setClaimed(Boolean claimed) {
        this.claimed = claimed;
    }

    public Boolean getClaimable() {
        return claimable;
    }

    public void setClaimable(Boolean claimable) {
        this.claimable = claimable;
    }
}

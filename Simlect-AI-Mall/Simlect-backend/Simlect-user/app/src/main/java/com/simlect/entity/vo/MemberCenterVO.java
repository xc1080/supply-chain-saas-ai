package com.simlect.entity.vo;

import com.simlect.api.vo.MemberLevelRewardVO;
import com.simlect.entity.po.UserMemberProfile;

import java.io.Serializable;
import java.util.List;

public class MemberCenterVO implements Serializable {

    private UserMemberProfile profile;
    private List<MemberLevelRewardVO> rewards;
    private Integer nextLevelCode;
    private Integer nextLevelGrowth;
    private Integer growthToNext;

    public UserMemberProfile getProfile() {
        return profile;
    }

    public void setProfile(UserMemberProfile profile) {
        this.profile = profile;
    }

    public List<MemberLevelRewardVO> getRewards() {
        return rewards;
    }

    public void setRewards(List<MemberLevelRewardVO> rewards) {
        this.rewards = rewards;
    }

    public Integer getNextLevelCode() {
        return nextLevelCode;
    }

    public void setNextLevelCode(Integer nextLevelCode) {
        this.nextLevelCode = nextLevelCode;
    }

    public Integer getNextLevelGrowth() {
        return nextLevelGrowth;
    }

    public void setNextLevelGrowth(Integer nextLevelGrowth) {
        this.nextLevelGrowth = nextLevelGrowth;
    }

    public Integer getGrowthToNext() {
        return growthToNext;
    }

    public void setGrowthToNext(Integer growthToNext) {
        this.growthToNext = growthToNext;
    }
}

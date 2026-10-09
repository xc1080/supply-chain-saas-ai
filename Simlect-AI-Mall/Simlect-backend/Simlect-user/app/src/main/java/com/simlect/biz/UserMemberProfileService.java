package com.simlect.biz;

import com.simlect.entity.po.UserMemberProfile;
import com.simlect.entity.vo.MemberCenterVO;

public interface UserMemberProfileService {

    UserMemberProfile getOrInitProfile(String userId);

    MemberCenterVO getMemberCenter(String userId);

    void claimLevelReward(String userId, Integer levelCode);

    void addGrowthOnPay(String userId, java.math.BigDecimal payAmount);

    void addGrowth(String userId, int points);
}

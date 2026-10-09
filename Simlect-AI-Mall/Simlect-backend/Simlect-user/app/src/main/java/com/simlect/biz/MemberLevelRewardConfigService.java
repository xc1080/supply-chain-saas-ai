package com.simlect.biz;

import com.simlect.entity.dto.MemberLevelRewardConfigDTO;

public interface MemberLevelRewardConfigService {

    MemberLevelRewardConfigDTO getConfig();

    void saveConfig(MemberLevelRewardConfigDTO config);

    String resolveLevelCouponId(int levelCode);
}

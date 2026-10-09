package com.simlect.biz;

import com.simlect.entity.dto.SignRewardConfigDTO;

public interface SignRewardConfigService {

    SignRewardConfigDTO getConfig();

    void saveConfig(SignRewardConfigDTO config);

    SignRewardConfigDTO resolveActiveConfig();
}

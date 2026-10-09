package com.simlect.biz;

import com.simlect.api.vo.SignDataVO;
import jakarta.validation.constraints.NotEmpty;

public interface SignService {
    SignDataVO getSignCalendar(@NotEmpty String userId, @NotEmpty String yyyyMM);

    void sign(String userId);

    void msign(String userId, String yyyyMMdd);
}

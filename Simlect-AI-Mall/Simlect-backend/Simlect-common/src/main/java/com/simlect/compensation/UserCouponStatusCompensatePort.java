package com.simlect.compensation;

import java.util.Date;

public interface UserCouponStatusCompensatePort {

    void changeUserCouponStatus(String userCouponId, String userId, Integer fromStatus, Integer toStatus, Date useTime);
}

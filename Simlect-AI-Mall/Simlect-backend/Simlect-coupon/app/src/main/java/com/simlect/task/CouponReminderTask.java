package com.simlect.task;

import com.simlect.biz.CouponReminderService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class CouponReminderTask {

    @Resource
    private CouponReminderService couponReminderService;

    @Scheduled(cron = "0 0 10 * * ?")
    public void remindExpiringCoupons() {
        try {
            couponReminderService.remindExpiringCoupons();
        } catch (Exception e) {
            log.error("优惠券过期提醒任务失败", e);
        }
    }
}

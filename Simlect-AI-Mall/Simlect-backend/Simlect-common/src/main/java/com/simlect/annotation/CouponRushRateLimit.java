package com.simlect.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CouponRushRateLimit {

    int userMaxPerMinute() default 30;

    int couponMaxPerSecond() default 200;
}

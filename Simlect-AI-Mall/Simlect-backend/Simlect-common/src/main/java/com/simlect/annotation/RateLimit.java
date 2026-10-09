package com.simlect.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    LimitType limitType() default LimitType.IP;

    long windowSeconds() default 60;

    int maxCount() default 10;

    String message() default "操作过于频繁，请稍后再试";

    enum LimitType {
        IP,
        USER
    }
}

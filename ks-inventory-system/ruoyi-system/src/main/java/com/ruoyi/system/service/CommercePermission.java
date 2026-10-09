package com.ruoyi.system.service;

import java.lang.annotation.*;

/** Explicit allowlist: an unannotated commerce route is denied, including future routes. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CommercePermission {
    CommerceCapability value() default CommerceCapability.READ;
    boolean customer() default false;
    boolean ownerScoped() default false;
    boolean shopRequired() default true;
}

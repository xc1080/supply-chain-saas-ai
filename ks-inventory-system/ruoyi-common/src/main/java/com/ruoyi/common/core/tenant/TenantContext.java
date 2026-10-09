package com.ruoyi.common.core.tenant;

/** Bound only by authenticated server membership; never by a caller-supplied tenant. */
public final class TenantContext {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    private TenantContext() {}
    public static String get() { return CURRENT.get(); }
    public static String id() { return get() == null ? "demo" : get(); }
    public static void set(String tenant) { if (tenant == null) clear(); else CURRENT.set(tenant); }
    public static void clear() { CURRENT.remove(); }
    public static String cacheKey(String key) {
        // Authentication remains in the control plane, independent of business databases.
        if (key.startsWith("login_tokens:") || key.startsWith("captcha_codes:") || key.startsWith("pwd_err_cnt:")) return key;
        if (key.startsWith("tenant:") || "demo".equals(id())) return key;
        return "tenant:" + id() + ":" + key;
    }
}

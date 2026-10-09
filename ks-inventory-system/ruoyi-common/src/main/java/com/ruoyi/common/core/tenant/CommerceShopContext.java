package com.ruoyi.common.core.tenant;

/** Shop is authorized inside the authenticated tenant before binding this context. */
public final class CommerceShopContext {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    private CommerceShopContext() {}
    public static String id() { return CURRENT.get() == null ? "default" : CURRENT.get(); }
    public static void set(String id) { if (id == null) clear(); else CURRENT.set(id); }
    public static void clear() { CURRENT.remove(); }
}

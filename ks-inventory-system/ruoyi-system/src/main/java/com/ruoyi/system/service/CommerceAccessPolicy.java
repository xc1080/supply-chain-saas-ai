package com.ruoyi.system.service;

import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import java.util.*;

/** Exact role checks deliberately avoid RuoYi's admin wildcard role matcher. */
public final class CommerceAccessPolicy {
    public static final String CUSTOMER_ROLE = "commerce_customer";
    private CommerceAccessPolicy() {}

    public static Set<String> roles(LoginUser login) {
        Set<String> result = new HashSet<>();
        if(login != null && login.getUser()!=null && login.getUser().getRoles()!=null)
            for(SysRole role:login.getUser().getRoles())
                if("0".equals(role.getStatus()) && role.getRoleKey()!=null) result.add(role.getRoleKey());
        return result;
    }
    public static boolean customerService(Set<String> roles) { return roles.contains(CUSTOMER_ROLE); }
    public static boolean customerService() { return customerService(roles(SecurityUtils.getLoginUser())); }
    public static boolean tenantAdministration(Set<String> roles) {
        // A service identity stays restricted even if accidentally assigned a second privileged role.
        return !customerService(roles) && (roles.contains("admin") || roles.contains("tenant_admin") || roles.contains("erp_manager"));
    }
    public static boolean allows(String role, CommerceCapability capability) {
        if("OWNER".equals(role)) return true;
        if("CUSTOMER_SERVICE".equals(role)) return false;
        if(capability == CommerceCapability.READ)
            return Arrays.asList("OPERATOR","VIEWER","CATALOG","FULFILMENT","FINANCE_REVIEW","FINANCE_EXECUTE","SUPPLY_PLANNER","SUPPLY_REVIEWER","WAREHOUSE").contains(role);
        if("OPERATOR".equals(role)) return capability==CommerceCapability.CATALOG || capability==CommerceCapability.FULFILMENT || capability==CommerceCapability.SUPPLY_DRAFT;
        if("CATALOG".equals(role)) return capability==CommerceCapability.CATALOG;
        if("FULFILMENT".equals(role)) return capability==CommerceCapability.FULFILMENT;
        if("FINANCE_REVIEW".equals(role)) return capability==CommerceCapability.REFUND_REVIEW;
        if("FINANCE_EXECUTE".equals(role)) return capability==CommerceCapability.REFUND_EXECUTE;
        if("SUPPLY_PLANNER".equals(role)) return capability==CommerceCapability.SUPPLY_DRAFT;
        if("SUPPLY_REVIEWER".equals(role)) return capability==CommerceCapability.SUPPLY_REVIEW;
        if("WAREHOUSE".equals(role)) return capability==CommerceCapability.FULFILMENT || capability==CommerceCapability.STOCK_ADJUST;
        return false;
    }
    public static void requireCustomerOwner(String owner) {
        if(customerService() && (owner==null || !owner.matches("[a-f0-9]{64}")))
            throw new ServiceException("客户请求必须提供有效的会话所有者",403);
    }
}

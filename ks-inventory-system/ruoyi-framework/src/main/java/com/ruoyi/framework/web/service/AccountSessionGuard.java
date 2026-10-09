package com.ruoyi.framework.web.service;

import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.tenant.TenantContext;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.framework.datasource.TenantRegistry;
import com.ruoyi.system.service.ISysUserService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import java.util.*;

/** Validate cached sessions against the current tenant account before renewal or use. */
@Component
public class AccountSessionGuard {
    private final ISysUserService users;
    private final SysPermissionService permissions;
    private final ObjectProvider<TenantRegistry> tenants;
    public AccountSessionGuard(ISysUserService users,SysPermissionService permissions,ObjectProvider<TenantRegistry> tenants) {
        this.users=users;this.permissions=permissions;this.tenants=tenants;
    }
    public boolean current(LoginUser session) {
        if(session==null||session.getUser()==null||session.getUserId()==null)return false;
        String previous=TenantContext.get();
        try {
            TenantRegistry registry=tenants.getIfAvailable();
            if(registry!=null) {
                String tenant;
                try{tenant=registry.tenantFor(session.getUserId());}catch(ServiceException denied){return false;}
                // A cached identity cannot follow a later reassignment into another tenant.
                if(!tenant.equals(session.getTenantId()))return false;
                TenantContext.set(tenant);
            }
            SysUser latest=users.selectUserById(session.getUserId()),cached=session.getUser();
            if(latest==null||!"0".equals(latest.getStatus())||!"0".equals(latest.getDelFlag()))return false;
            if(!Objects.equals(latest.getUserName(),cached.getUserName())
                ||latest.getPassword()==null||!latest.getPassword().equals(cached.getPassword())
                ||!Objects.equals(latest.getDeptId(),cached.getDeptId())
                ||!roleState(latest).equals(roleState(cached)))return false;
            return Objects.equals(permissions.getMenuPermission(latest),session.getPermissions());
        } catch(DataAccessException unavailable) {
            throw new ServiceException("账号校验暂时不可用，请稍后重试",503);
        } finally{TenantContext.set(previous);}
    }
    private static Set<String> roleState(SysUser user) {
        Set<String> result=new TreeSet<>();
        if(user.getRoles()!=null)for(SysRole role:user.getRoles())
            if("0".equals(role.getStatus()))result.add(role.getRoleId()+":"+role.getRoleKey()+":"+role.getDataScope());
        return result;
    }
}

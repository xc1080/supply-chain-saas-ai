package com.ruoyi.framework.web.service;

import com.alibaba.druid.pool.DruidDataSource;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.tenant.TenantContext;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.framework.datasource.TenantRegistry;
import com.ruoyi.system.service.ISysUserService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.env.MockEnvironment;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.Assert.*;

public class AccountSessionGuardTest {
    private SysUser latest;
    private LoginUser session;
    private AccountSessionGuard guard;
    private String binding="demo";
    private Set<String> menu;
    private boolean unavailable;
    private TenantRegistry registry;
    @Before public void setup() {
        latest=user();session=new LoginUser(42L,7L,user(),Collections.singleton("commerce:read"));
        session.setTenantId("demo");menu=Collections.singleton("commerce:read");
        ISysUserService users=(ISysUserService)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ISysUserService.class},(proxy,method,args)->{
            if(!"selectUserById".equals(method.getName()))throw new UnsupportedOperationException();
            assertEquals("demo",TenantContext.get());
            if(unavailable)throw new DataAccessResourceFailureException("fixture database unavailable");
            return latest;
        });
        SysPermissionService permissions=new SysPermissionService(){@Override public Set<String> getMenuPermission(SysUser user){return menu;}};
        DruidDataSource source=new DruidDataSource();source.setUrl("jdbc:mysql://127.0.0.1/test");
        MockEnvironment env=new MockEnvironment().withProperty("commerce.tenants.ids","demo");env.setActiveProfiles("local");
        registry=new TenantRegistry(source,env){@Override public String tenantFor(long id){if(binding==null)throw new ServiceException("fixture revoked",403);return binding;}};
        DefaultListableBeanFactory factory=new DefaultListableBeanFactory();factory.registerSingleton("tenants",registry);
        guard=new AccountSessionGuard(users,permissions,factory.getBeanProvider(TenantRegistry.class));
        TenantContext.set("previous");
    }
    @After public void cleanup(){TenantContext.clear();registry.close();}
    @Test public void unchangedSessionUsesItsOwnTenantAndRestoresCallerContext(){assertTrue(guard.current(session));assertEquals("previous",TenantContext.get());}
    @Test public void disabledAccountRejectsCachedSession(){latest.setStatus("1");assertFalse(guard.current(session));}
    @Test public void deletedAccountRejectsCachedSession(){latest.setDelFlag("2");assertFalse(guard.current(session));}
    @Test public void missingAccountRejectsCachedSession(){latest=null;assertFalse(guard.current(session));}
    @Test public void passwordRotationRejectsAllOldSnapshots(){latest.setPassword("new-password-hash");assertFalse(guard.current(session));}
    @Test public void roleRevocationRejectsCachedPrivileges(){latest.setRoles(new ArrayList<>());assertFalse(guard.current(session));}
    @Test public void roleDisabledRejectsCachedPrivileges(){latest.getRoles().get(0).setStatus("1");assertFalse(guard.current(session));}
    @Test public void menuRevocationRejectsCachedPermissions(){menu=Collections.emptySet();assertFalse(guard.current(session));}
    @Test public void departmentScopeChangeRejectsCachedPrivileges(){latest.setDeptId(8L);assertFalse(guard.current(session));}
    @Test public void tenantReassignmentDoesNotMoveExistingToken(){binding="studio";assertFalse(guard.current(session));assertEquals("previous",TenantContext.get());}
    @Test public void revokedTenantBindingRejectsCachedSession(){binding=null;assertFalse(guard.current(session));}
    @Test public void preBindingTokensMustAuthenticateAgain(){session.setTenantId(null);assertFalse(guard.current(session));}
    @Test public void databaseFailureDoesNotAuthenticateFromCache(){unavailable=true;try{guard.current(session);fail("Expected fail closed");}catch(ServiceException error){assertEquals(Integer.valueOf(503),error.getCode());}assertEquals("previous",TenantContext.get());}
    private static SysUser user(){
        SysUser user=new SysUser(42L);user.setUserName("fixture_staff");user.setDeptId(7L);user.setStatus("0");user.setDelFlag("0");user.setPassword("same-password-hash");
        SysRole role=new SysRole();role.setRoleId(9L);role.setRoleKey("shop_staff");role.setDataScope("1");role.setStatus("0");user.setRoles(new ArrayList<>(Collections.singletonList(role)));return user;
    }
}

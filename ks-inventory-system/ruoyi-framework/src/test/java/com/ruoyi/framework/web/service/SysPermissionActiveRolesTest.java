package com.ruoyi.framework.web.service;

import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.system.service.ISysMenuService;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.Assert.*;

public class SysPermissionActiveRolesTest {
    @Test public void disabledRolesCannotSupplyMenuPermissions(){
        SysUser user=new SysUser(42L);user.setRoles(Arrays.asList(role(9L,"0"),role(10L,"1")));
        List<Long> queried=new ArrayList<>();
        SysPermissionService service=service(queried);
        assertEquals(Collections.singleton("role:9"),service.getMenuPermission(user));
        assertEquals(Collections.singletonList(9L),queried);
    }
    @Test public void knownEmptyActiveRoleListDoesNotFallBackToDisabledAssignments(){
        SysUser user=new SysUser(42L);user.setRoles(Collections.emptyList());
        List<Long> queried=new ArrayList<>();
        assertTrue(service(queried).getMenuPermission(user).isEmpty());assertTrue(queried.isEmpty());
    }
    @Test public void freshSessionAndLiveCheckAgreeWhenDisabledRolesRemainInDatabase(){
        SysPermissionService service=service(new ArrayList<>());
        SysUser fresh=new SysUser(42L);fresh.setRoles(Collections.singletonList(role(9L,"0")));
        SysUser database=new SysUser(42L);database.setRoles(Arrays.asList(role(9L,"0"),role(10L,"1")));
        assertEquals(service.getMenuPermission(fresh),service.getMenuPermission(database));
    }
    private SysPermissionService service(List<Long> queried){
        ISysMenuService menus=(ISysMenuService)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ISysMenuService.class},(proxy,method,args)->{
            if(method.getDeclaringClass()==Object.class){
                if("toString".equals(method.getName()))return "fixture menu service";
                if("hashCode".equals(method.getName()))return System.identityHashCode(proxy);
                return proxy==args[0];
            }
            if(!"selectMenuPermsByRoleId".equals(method.getName()))throw new AssertionError("Unexpected permission fallback");
            Long role=(Long)args[0];queried.add(role);return Collections.singleton("role:"+role);
        });
        SysPermissionService service=new SysPermissionService();ReflectionTestUtils.setField(service,"menuService",menus);return service;
    }
    private static SysRole role(long id,String status){SysRole role=new SysRole();role.setRoleId(id);role.setStatus(status);return role;}
}

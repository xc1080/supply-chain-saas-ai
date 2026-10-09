package com.ruoyi.framework.config;

import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.tenant.CommerceShopContext;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.service.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.core.MethodParameter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import java.util.*;
import static org.junit.Assert.*;

public class CommerceAccessInterceptorTest {
    private HandlerInterceptor commerce,erp,serviceOnly;
    private final MockHttpServletResponse response=new MockHttpServletResponse();
    static class Registry extends InterceptorRegistry { List<Object> registered(){return getInterceptors();} }
    public static class Routes {
        @CommercePermission(customer=true) public void catalog() {}
        @CommercePermission(customer=true,ownerScoped=true) public void customerOrder() {}
        @CommercePermission(customer=true,ownerScoped=true) public void customerMutation(Map<String,Object> body) {}
        @CommercePermission(CommerceCapability.FULFILMENT) public void ship() {}
        @CommercePermission(CommerceCapability.REFUND_REVIEW) public void review() {}
        public void futureUndeclaredRoute() {}
    }
    @Before public void setup(){
        JdbcDataSource source=new JdbcDataSource();source.setURL("jdbc:h2:mem:permissions"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE commerce_shop(shop_id VARCHAR(32) PRIMARY KEY,shop_name VARCHAR(80),status VARCHAR(16))");
        jdbc.execute("CREATE TABLE commerce_shop_member(shop_id VARCHAR(32),user_id BIGINT,member_role VARCHAR(16),PRIMARY KEY(shop_id,user_id))");
        jdbc.update("INSERT INTO commerce_shop VALUES ('default','Shop','ENABLED')");
        jdbc.update("INSERT INTO commerce_shop_member VALUES ('default',1,'OWNER'),('default',3,'OPERATOR'),('default',6,'CUSTOMER_SERVICE')");
        Registry registry=new Registry();new CommerceShopConfig(new CommerceMerchantService(source)).addInterceptors(registry);
        commerce=((MappedInterceptor)registry.registered().get(0)).getInterceptor();
        Registry old=new Registry();new TenantErpAccessConfig().addInterceptors(old);
        erp=((MappedInterceptor)old.registered().get(0)).getInterceptor();serviceOnly=((MappedInterceptor)old.registered().get(1)).getInterceptor();
    }
    @After public void clear(){SecurityContextHolder.clearContext();CommerceShopContext.clear();}
    private void login(long id,String... roleKeys){
        List<SysRole> roles=new ArrayList<>();for(String key:roleKeys){SysRole role=new SysRole();role.setRoleKey(key);role.setStatus("0");roles.add(role);}
        SysUser user=new SysUser();user.setUserId(id);user.setRoles(roles);
        LoginUser login=new LoginUser(id,1L,user,Collections.singleton("*:*:*"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login,null,Collections.emptyList()));
    }
    private MockHttpServletRequest request(String method){return new MockHttpServletRequest(method,"/commerce/test");}
    private HandlerMethod route(String name) throws Exception{return new HandlerMethod(new Routes(),Routes.class.getMethod(name));}
    private void denied(HandlerInterceptor interceptor,MockHttpServletRequest request,Object handler) throws Exception{
        try{interceptor.preHandle(request,response,handler);fail("Expected denied");}catch(ServiceException e){assertEquals(Integer.valueOf(403),e.getCode());}
    }
    @Test public void customerOnlyUsesDeclaredPublicRoutesAndOwnsShop() throws Exception{
        login(6,"commerce_customer");assertTrue(commerce.preHandle(request("GET"),response,route("catalog")));
        assertEquals("default",CommerceShopContext.id());
        denied(commerce,request("POST"),route("ship"));denied(commerce,request("GET"),route("futureUndeclaredRoute"));
        MockHttpServletRequest other=request("GET");other.addHeader("X-Shop-ID","other");denied(commerce,other,route("catalog"));
    }
    @Test public void customerOwnerFilterIsMandatory() throws Exception{
        login(6,"commerce_customer");denied(commerce,request("GET"),route("customerOrder"));
        MockHttpServletRequest valid=request("GET");valid.setParameter("ownerId",String.join("",Collections.nCopies(64,"a")));
        assertTrue(commerce.preHandle(valid,response,route("customerOrder")));
        valid.setParameter("ownerId","");denied(commerce,valid,route("customerOrder"));
    }
    @Test public void operatorVerbDoesNotGrantRefundPower() throws Exception{
        login(3,"merchant");assertTrue(commerce.preHandle(request("POST"),response,route("ship")));
        denied(commerce,request("POST"),route("review"));denied(commerce,request("GET"),route("futureUndeclaredRoute"));
    }
    @Test public void legacyErpAndSystemRolesRequireTenantAdministrator() throws Exception{
        login(3,"merchant");denied(erp,new MockHttpServletRequest("GET","/baseDate/product/list"),null);
        login(1,"admin");assertTrue(erp.preHandle(new MockHttpServletRequest("GET","/baseDate/product/list"),response,null));
        login(6,"commerce_customer","admin");denied(erp,new MockHttpServletRequest("GET","/baseDate/product/list"),null);
        denied(serviceOnly,new MockHttpServletRequest("GET","/getInfo"),null);
    }
    @Test public void completionAlwaysClearsShop() throws Exception{
        login(6,"commerce_customer");MockHttpServletRequest request=request("GET");commerce.preHandle(request,response,route("catalog"));
        commerce.afterCompletion(request,response,route("catalog"),null);assertEquals("default",CommerceShopContext.id());
        // The default is the safe context fallback; a previous explicit shop must never survive.
        CommerceShopContext.set("other");commerce.afterCompletion(request,response,route("catalog"),null);assertEquals("default",CommerceShopContext.id());
    }
    @Test public void customerPostBodyAlwaysNeedsOwnerIncludingFutureDeclaredRoutes() throws Exception{
        login(6,"commerce_customer");CommerceCustomerBodyAdvice advice=new CommerceCustomerBodyAdvice();
        MethodParameter parameter=new MethodParameter(Routes.class.getMethod("customerMutation",Map.class),0);
        assertTrue(advice.supports(parameter,Map.class,MappingJackson2HttpMessageConverter.class));
        try{advice.afterBodyRead(Collections.emptyMap(),null,parameter,Map.class,MappingJackson2HttpMessageConverter.class);fail("Expected missing owner rejection");}
        catch(ServiceException e){assertEquals(Integer.valueOf(403),e.getCode());}
        Map<String,Object> valid=Collections.singletonMap("ownerId",String.join("",Collections.nCopies(64,"a")));
        assertSame(valid,advice.afterBodyRead(valid,null,parameter,Map.class,MappingJackson2HttpMessageConverter.class));
        login(1,"admin");Map<String,Object> merchant=Collections.emptyMap();
        assertSame(merchant,advice.afterBodyRead(merchant,null,parameter,Map.class,MappingJackson2HttpMessageConverter.class));
    }
}

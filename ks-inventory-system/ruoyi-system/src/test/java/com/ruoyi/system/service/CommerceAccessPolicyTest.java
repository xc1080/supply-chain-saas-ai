package com.ruoyi.system.service;

import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.core.tenant.CommerceShopContext;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import static org.junit.Assert.*;

public class CommerceAccessPolicyTest {
    private CommerceServiceTest fixture;
    @Before public void setup() throws Exception {
        fixture=new CommerceServiceTest();fixture.setup();
        fixture.jdbc.update("INSERT INTO commerce_shop_member VALUES ('default',3,'OPERATOR'),('default',4,'FINANCE_REVIEW'),('default',5,'FINANCE_EXECUTE'),('default',6,'CUSTOMER_SERVICE'),('default',7,'WAREHOUSE')");
    }
    @After public void clear(){SecurityContextHolder.clearContext();CommerceShopContext.clear();}
    private void denied(Runnable run){try{run.run();fail("Expected access denial");}catch(ServiceException e){assertEquals(Integer.valueOf(403),e.getCode());}}
    @Test public void operatorCannotReviewRefundMoveStockOrChangeSupplyPolicy(){
        for(CommerceCapability capability:Arrays.asList(CommerceCapability.CATALOG,CommerceCapability.FULFILMENT,CommerceCapability.SUPPLY_DRAFT))
            fixture.merchants.requireCapability("default",3,capability);
        for(CommerceCapability capability:Arrays.asList(CommerceCapability.REFUND_REVIEW,CommerceCapability.REFUND_EXECUTE,CommerceCapability.STOCK_ADJUST,CommerceCapability.SUPPLY_POLICY,CommerceCapability.SUPPLY_REVIEW,CommerceCapability.SHOP_MEMBERS))
            denied(()->fixture.merchants.requireCapability("default",3,capability));
    }
    @Test public void refundReviewAndExecutionAreSeparateRoles(){
        fixture.merchants.requireCapability("default",4,CommerceCapability.REFUND_REVIEW);
        denied(()->fixture.merchants.requireCapability("default",4,CommerceCapability.REFUND_EXECUTE));
        fixture.merchants.requireCapability("default",5,CommerceCapability.REFUND_EXECUTE);
        denied(()->fixture.merchants.requireCapability("default",5,CommerceCapability.REFUND_REVIEW));
    }
    @Test public void serviceRoleHasNoMerchantCapabilityEvenRead(){
        fixture.merchants.requireCustomerService("default",6);
        for(CommerceCapability capability:CommerceCapability.values())denied(()->fixture.merchants.requireCapability("default",6,capability));
        denied(()->fixture.merchants.requireCustomerService("default",1));
    }
    @Test public void revokedOrDisabledMembershipDeniesImmediately(){
        fixture.merchants.requireCapability("default",3,CommerceCapability.READ);
        fixture.jdbc.update("DELETE FROM commerce_shop_member WHERE user_id=3");
        denied(()->fixture.merchants.requireCapability("default",3,CommerceCapability.READ));
        fixture.jdbc.update("UPDATE commerce_shop SET status='DISABLED' WHERE shop_id='default'");
        denied(()->fixture.merchants.requireCapability("default",1,CommerceCapability.CATALOG));
    }
    @Test public void ownersCannotAccessOtherShopsWithoutMembership(){
        fixture.jdbc.update("INSERT INTO commerce_shop(shop_id,shop_name) VALUES ('other','Other shop')");
        denied(()->fixture.merchants.requireCapability("other",1,CommerceCapability.READ));
        for(CommerceCapability capability:CommerceCapability.values())fixture.merchants.requireCapability("default",1,capability);
    }
    @Test public void warehouseCannotApproveRefunds(){
        fixture.merchants.requireCapability("default",7,CommerceCapability.STOCK_ADJUST);
        denied(()->fixture.merchants.requireCapability("default",7,CommerceCapability.REFUND_REVIEW));
    }
    @Test public void adminWildcardAndMixedServiceRoleDoNotBypassTenantAdministration(){
        assertTrue(CommerceAccessPolicy.tenantAdministration(Collections.singleton("admin")));
        assertTrue(CommerceAccessPolicy.tenantAdministration(Collections.singleton("tenant_admin")));
        assertFalse(CommerceAccessPolicy.tenantAdministration(Collections.singleton("merchant")));
        assertFalse(CommerceAccessPolicy.tenantAdministration(new HashSet<>(Arrays.asList("commerce_customer","admin"))));
    }
    @Test public void disabledRolesAreIgnoredAndCustomerReadsNeedOwner(){
        SysRole service=new SysRole();service.setRoleKey("commerce_customer");service.setStatus("0");
        SysRole admin=new SysRole();admin.setRoleKey("admin");admin.setStatus("1");
        SysUser user=new SysUser();user.setUserId(6L);user.setRoles(Arrays.asList(service,admin));
        LoginUser login=new LoginUser(6L,1L,user,Collections.emptySet());
        assertEquals(Collections.singleton("commerce_customer"),CommerceAccessPolicy.roles(login));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login,null,Collections.emptyList()));
        denied(()->CommerceAccessPolicy.requireCustomerOwner(null));
        denied(()->CommerceAccessPolicy.requireCustomerOwner("all"));
        CommerceAccessPolicy.requireCustomerOwner(String.join("",Collections.nCopies(64,"a")));
    }
}

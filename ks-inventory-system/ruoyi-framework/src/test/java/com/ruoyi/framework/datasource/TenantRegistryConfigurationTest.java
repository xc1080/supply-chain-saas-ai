package com.ruoyi.framework.datasource;

import com.alibaba.druid.pool.DruidDataSource;
import org.junit.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.Assert.*;

public class TenantRegistryConfigurationTest {
    private DruidDataSource source(){DruidDataSource source=new DruidDataSource();source.setUrl("jdbc:mysql://127.0.0.1:3306/ksdatabase?useSSL=false");source.setUsername("fixture");return source;}
    @Test public void productionRequiresExplicitTenantsAndDatabaseRoutes(){
        MockEnvironment environment=new MockEnvironment();environment.setActiveProfiles("commerce");
        try{new TenantRegistry(source(),environment);fail("Must not bootstrap demonstration tenants");}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("tenants.ids"));}
        environment.withProperty("commerce.tenants.ids","merchant_a");
        try{new TenantRegistry(source(),environment);fail("Must not silently route to master");}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("database URL"));}
    }
    @Test public void serverConfiguredTenantRoutesCanBeDisabled(){
        MockEnvironment environment=new MockEnvironment();environment.setActiveProfiles("commerce");
        environment.withProperty("commerce.tenants.ids","merchant_a,merchant_b")
            .withProperty("commerce.tenants.merchant_a.database","merchant_a")
            .withProperty("commerce.tenants.merchant_b.enabled","false");
        TenantRegistry registry=new TenantRegistry(source(),environment);
        try{assertEquals(1,registry.tenants().size());assertTrue(registry.routingSources().containsKey("TENANT_merchant_a"));assertFalse(registry.routingSources().containsKey("TENANT_merchant_b"));}
        finally{registry.close();}
    }
    @Test public void localDemonstrationStillHasTwoExplicitRoutes(){
        MockEnvironment environment=new MockEnvironment();environment.setActiveProfiles("local");
        TenantRegistry registry=new TenantRegistry(source(),environment);
        try{assertEquals(2,registry.tenants().size());assertTrue(registry.tenants().contains("demo"));assertTrue(registry.tenants().contains("studio"));}
        finally{registry.close();}
    }
}

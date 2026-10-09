package com.ruoyi.framework.datasource;

import com.alibaba.druid.pool.DruidDataSource;
import com.ruoyi.common.core.tenant.TenantContext;
import com.ruoyi.common.exception.ServiceException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.env.Environment;
import javax.sql.DataSource;
import javax.annotation.PreDestroy;
import java.util.*;

/** Tenant routes are provisioned by server configuration, never by request headers. */
@Component
@Profile({"local","commerce"})
public class TenantRegistry {
    private final JdbcTemplate control;
    private final Map<String, DataSource> sources = new LinkedHashMap<>();
    private final List<DruidDataSource> owned = new ArrayList<>();
    public TenantRegistry(@Qualifier("masterDataSource") DataSource master,Environment environment) {
        control = new JdbcTemplate(master);
        DruidDataSource base = (DruidDataSource) master;
        boolean local=Arrays.asList(environment.getActiveProfiles()).contains("local");
        String ids=environment.getProperty("commerce.tenants.ids",local?"demo,studio":"");
        if(ids.trim().isEmpty())throw new IllegalStateException("Configure commerce.tenants.ids before enabling commerce");
        for(String value:ids.split(",")) {
            String tenant=value.trim();
            if(!tenant.matches("[A-Za-z0-9_-]{1,32}")||sources.containsKey(tenant))throw new IllegalStateException("Invalid or duplicate tenant configuration");
            String prefix="commerce.tenants."+tenant+".";
            if(!environment.getProperty(prefix+"enabled",Boolean.class,true))continue;
            String database=environment.getProperty(prefix+"database",local&&"studio".equals(tenant)?"ksdatabase_studio":null);
            String url=environment.getProperty(prefix+"url");
            if(url==null&&database!=null) {
                if(!database.matches("[A-Za-z0-9_]{1,64}"))throw new IllegalStateException("Invalid tenant database configuration");
                url=base.getUrl().replaceFirst("/[^/?]+(\\?|$)","/"+database+"$1");
            }
            if(url==null) {
                if(local&&"demo".equals(tenant))sources.put(tenant,master);
                else throw new IllegalStateException("Configure tenant database URL: "+tenant);
                continue;
            }
            DruidDataSource source=new DruidDataSource();
            source.setUrl(url);source.setUsername(environment.getProperty(prefix+"username",base.getUsername()));
            source.setPassword(environment.getProperty(prefix+"password",base.getPassword()));
            source.setDriverClassName(base.getDriverClassName());source.setInitialSize(0);
            source.setMaxActive(environment.getProperty(prefix+"max-active",Integer.class,12));source.setMaxWait(10000);
            owned.add(source);sources.put(tenant,source);
        }
        if(sources.isEmpty())throw new IllegalStateException("No enabled commerce tenants configured");
    }
    public Map<Object, Object> routingSources() {
        Map<Object, Object> result = new LinkedHashMap<>();
        sources.forEach((tenant, source) -> result.put("TENANT_" + tenant, source)); return result;
    }
    public Set<String> tenants() { return Collections.unmodifiableSet(sources.keySet()); }
    public String tenantFor(long userId) {
        List<String> result = control.queryForList("SELECT b.tenant_id FROM commerce_tenant_binding b JOIN commerce_tenant_registry t ON t.tenant_id=b.tenant_id WHERE b.user_id=? AND b.enabled=1 AND t.status='ENABLED'", String.class, userId);
        if (result.size() != 1 || !sources.containsKey(result.get(0))) throw new ServiceException("账号未绑定有效租户", 403);
        return result.get(0);
    }
    @PreDestroy public void close() { for(DruidDataSource source:owned)source.close(); }
}

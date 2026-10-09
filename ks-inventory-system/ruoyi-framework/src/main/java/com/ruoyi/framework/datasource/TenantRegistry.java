package com.ruoyi.framework.datasource;

import com.alibaba.druid.pool.DruidDataSource;
import com.ruoyi.common.core.tenant.TenantContext;
import com.ruoyi.common.exception.ServiceException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import javax.annotation.PreDestroy;
import java.util.*;

/** Local two-tenant registry. Credentials/database names are exclusively server configuration. */
@Component
@Profile("local")
public class TenantRegistry {
    private final JdbcTemplate control;
    private final Map<String, DataSource> sources = new LinkedHashMap<>();
    public TenantRegistry(@Qualifier("masterDataSource") DataSource master) {
        control = new JdbcTemplate(master);
        sources.put("demo", master);
        DruidDataSource base = (DruidDataSource) master;
        DruidDataSource studio = new DruidDataSource();
        String url = base.getUrl().replaceFirst("/[^/?]+(\\?|$)", "/ksdatabase_studio$1");
        if (url.equals(base.getUrl())) throw new IllegalStateException("Unable to resolve studio database URL");
        studio.setUrl(url); studio.setUsername(base.getUsername()); studio.setPassword(base.getPassword());
        studio.setDriverClassName(base.getDriverClassName()); studio.setInitialSize(0); studio.setMaxActive(12);
        studio.setMaxWait(10000); sources.put("studio", studio);
    }
    public Map<Object, Object> routingSources() {
        Map<Object, Object> result = new LinkedHashMap<>();
        sources.forEach((tenant, source) -> result.put("TENANT_" + tenant, source)); return result;
    }
    public Set<String> tenants() { return Collections.unmodifiableSet(sources.keySet()); }
    public String tenantFor(long userId) {
        List<String> result = control.queryForList("SELECT tenant_id FROM commerce_tenant_binding WHERE user_id=? AND enabled=1", String.class, userId);
        if (result.size() != 1 || !sources.containsKey(result.get(0))) throw new ServiceException("账号未绑定有效租户", 403);
        return result.get(0);
    }
    @PreDestroy public void close() { ((DruidDataSource) sources.get("studio")).close(); }
}

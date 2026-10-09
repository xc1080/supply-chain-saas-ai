package com.ruoyi.framework.datasource;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;
import com.ruoyi.common.core.tenant.TenantContext;
import java.util.Map;

/**
 * 动态数据源
 * 
 * @author KrityCat
 */
public class DynamicDataSource extends AbstractRoutingDataSource {
    public DynamicDataSource(DataSource defaultTargetDataSource, Map<Object, Object> targetDataSources) {
        super.setDefaultTargetDataSource(defaultTargetDataSource);
        super.setTargetDataSources(targetDataSources);
        super.setLenientFallback(false);
        super.afterPropertiesSet();
    }

    @Override
    protected Object determineCurrentLookupKey() {
        String tenant = TenantContext.get();
        return tenant == null ? DynamicDataSourceContextHolder.getDataSourceType() : "TENANT_" + tenant;
    }
}
package com.ruoyi.framework.datasource;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.tenant.CommerceShopContext;
import com.ruoyi.common.core.tenant.TenantContext;
import com.ruoyi.system.service.*;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import javax.sql.DataSource;
import java.util.*;

/** Shop-scoped operational health. Customer service identities cannot access this route. */
@RestController
@Profile({"local","commerce"})
@RequestMapping("/commerce/operations")
@PreAuthorize("isAuthenticated()")
public class CommerceOperationsController {
    private final JdbcTemplate jdbc;private final CommerceMaintenance workers;private final CommerceQueueService queue;
    private final RedisConnectionFactory redis;
    public CommerceOperationsController(DataSource source,CommerceMaintenance workers,CommerceQueueService queue,RedisConnectionFactory redis){
        this.jdbc=new JdbcTemplate(source);this.workers=workers;this.queue=queue;this.redis=redis;
    }
    @GetMapping("/health") @CommercePermission(CommerceCapability.READ)
    public AjaxResult health(){
        Map<String,Object> result=new LinkedHashMap<>();List<String> alerts=new ArrayList<>();
        boolean database=false,cache=false;
        try{database=jdbc.queryForObject("SELECT 1",Integer.class)==1;}catch(RuntimeException error){alerts.add("DATABASE_UNAVAILABLE");}
        try(RedisConnection connection=redis.getConnection()){cache="PONG".equalsIgnoreCase(connection.ping());}
        catch(RuntimeException error){alerts.add("REDIS_UNAVAILABLE");}
        result.put("tenantId",TenantContext.id());result.put("shopId",CommerceShopContext.id());
        result.put("ready",workers.isReady());result.put("database",database);result.put("redis",cache);
        if(database){
            Map<String,Object> counters=queue.metrics();result.put("checkout",counters);
            if(((Number)counters.get("oldestWaitingSeconds")).longValue()>30)alerts.add("CHECKOUT_BACKLOG_OVER_30S");
            long overdue=jdbc.queryForObject("SELECT COUNT(*) FROM commerce_order WHERE shop_id=? AND status=0 AND expires_at<CURRENT_TIMESTAMP",Long.class,CommerceShopContext.id());
            result.put("overdueUnpaidOrders",overdue);if(overdue>0)alerts.add("ORDER_EXPIRY_BACKLOG");
            long failedInbox=jdbc.queryForObject("SELECT COUNT(*) FROM commerce_provider_event WHERE shop_id=? AND processing_status='RECEIVED'",Long.class,CommerceShopContext.id());
            result.put("unappliedPaymentEvents",failedInbox);if(failedInbox>0)alerts.add("PAYMENT_EVENTS_UNAPPLIED");
            result.put("schema",jdbc.queryForList("SELECT version,name,state,finished_at FROM commerce_schema_history ORDER BY version"));
        }
        Map<String,Object> maintenance=workers.status(TenantContext.id());result.put("maintenance",maintenance);
        for(String kind:Arrays.asList("expiry","checkout","payments")){
            Object last=maintenance.get(kind+"LastSuccessAt");
            if(last==null||System.currentTimeMillis()-((Number)last).longValue()>30000)alerts.add(kind.toUpperCase(Locale.ROOT)+"_WORKER_STALE");
        }
        result.put("alerts",alerts);result.put("healthy",workers.isReady()&&database&&cache&&alerts.isEmpty());
        return AjaxResult.success(result);
    }
}

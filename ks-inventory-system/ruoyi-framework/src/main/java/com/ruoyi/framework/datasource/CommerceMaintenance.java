package com.ruoyi.framework.datasource;

import com.ruoyi.common.core.tenant.TenantContext;
import com.ruoyi.common.core.tenant.CommerceShopContext;
import com.ruoyi.system.service.CommerceService;
import com.ruoyi.system.service.CommerceQueueService;
import com.ruoyi.system.service.CommercePlanningService;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.PreDestroy;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@Profile("local")
@EnableScheduling
public class CommerceMaintenance {
    private final TenantRegistry tenants;
    private final CommerceService service;
    private final CommerceQueueService queue;
    private final CommercePlanningService planning;
    private final ConcurrentMap<String,AtomicBoolean> busy=new ConcurrentHashMap<>();
    private final ExecutorService workers=Executors.newFixedThreadPool(2,r -> { Thread t=new Thread(r,"commerce-checkout"); t.setDaemon(true); return t; });
    private volatile boolean ready;
    private static final Logger log = LoggerFactory.getLogger(CommerceMaintenance.class);
    public CommerceMaintenance(TenantRegistry tenants, CommerceService service,CommerceQueueService queue,CommercePlanningService planning) { this.tenants = tenants; this.service = service; this.queue=queue; this.planning=planning; }
    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        try { for (String tenant : tenants.tenants()) { TenantContext.set(tenant); service.initializeSchema(); queue.initializeSchema(); planning.initializeSchema(); } ready = true; }
        finally { TenantContext.clear(); }
    }
    @Scheduled(fixedDelayString="${commerce.expiry-scan-ms:2000}")
    public void expire() {
        if (!ready) return;
        for (String tenant : tenants.tenants()) {
            try {
                TenantContext.set(tenant);
                for(String shop:service.shopIds()) {
                    CommerceShopContext.set(shop);
                    for (String id : service.expiredOrderIds()) service.expireOrder(id);
                    service.expireActivities();
                }
            } catch (Exception ex) { log.error("Order expiry failed for tenant {}: {}", tenant, ex.getClass().getSimpleName()); }
            finally { TenantContext.clear(); CommerceShopContext.clear(); }
        }
    }
    @Scheduled(fixedDelayString="${commerce.checkout-poll-ms:150}")
    public void checkout() {
        if(!ready)return;
        for(String tenant:tenants.tenants()) {
            AtomicBoolean active=busy.computeIfAbsent(tenant,key->new AtomicBoolean());
            if(!active.compareAndSet(false,true))continue;
            workers.submit(()-> {
                try { TenantContext.set(tenant); queue.processBatch(20); }
                catch(Exception ex) { log.error("Checkout worker failed for tenant {}: {}",tenant,ex.getClass().getSimpleName()); }
                finally { CommerceShopContext.clear(); TenantContext.clear(); active.set(false); }
            });
        }
    }
    @PreDestroy public void stop() { workers.shutdown(); }
}

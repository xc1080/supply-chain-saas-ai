package com.ruoyi.framework.datasource;

import com.ruoyi.common.core.tenant.TenantContext;
import com.ruoyi.common.core.tenant.CommerceShopContext;
import com.ruoyi.system.service.CommerceService;
import com.ruoyi.system.service.CommerceQueueService;
import com.ruoyi.system.service.CommercePlanningService;
import com.ruoyi.system.service.CommerceSchemaMigrator;
import com.ruoyi.system.service.CommercePaymentService;
import com.ruoyi.system.service.CommerceAfterSalesService;
import com.ruoyi.system.service.CommerceDeliveryService;
import com.ruoyi.system.service.CommerceWarehouseAllocationService;
import com.ruoyi.system.service.CommerceCostService;
import com.ruoyi.system.service.CommerceAgentTaskService;
import org.springframework.core.env.Environment;
import javax.sql.DataSource;
import java.util.*;
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
@Profile({"local","commerce"})
@EnableScheduling
public class CommerceMaintenance {
    private final TenantRegistry tenants;
    private final CommerceService service;
    private final CommerceQueueService queue;
    private final CommercePlanningService planning;
    private final CommercePaymentService payments;
    private final CommerceAfterSalesService afterSales;
    private final CommerceDeliveryService delivery;
    private final CommerceWarehouseAllocationService warehouses;
    private final CommerceCostService costs;
    private final CommerceAgentTaskService agentTasks;
    private final DataSource source;
    private final boolean migrationsEnabled,retryFailed;
    private final ConcurrentMap<String,Map<String,Object>> status=new ConcurrentHashMap<>();
    private final ConcurrentMap<String,AtomicBoolean> busy=new ConcurrentHashMap<>();
    private final ExecutorService workers=Executors.newFixedThreadPool(2,r -> { Thread t=new Thread(r,"commerce-checkout"); t.setDaemon(true); return t; });
    private volatile boolean ready;
    private static final Logger log = LoggerFactory.getLogger(CommerceMaintenance.class);
    public CommerceMaintenance(TenantRegistry tenants, CommerceService service,CommerceQueueService queue,CommercePlanningService planning,CommercePaymentService payments,CommerceAfterSalesService afterSales,CommerceDeliveryService delivery,CommerceWarehouseAllocationService warehouses,CommerceCostService costs,CommerceAgentTaskService agentTasks,DataSource source,Environment environment) {
        this.tenants=tenants;this.service=service;this.queue=queue;this.planning=planning;this.payments=payments;this.afterSales=afterSales;this.delivery=delivery;this.source=source;
        this.warehouses=warehouses;this.costs=costs;this.agentTasks=agentTasks;
        this.migrationsEnabled=environment.getProperty("commerce.migrations.enabled",Boolean.class,true);
        this.retryFailed=environment.getProperty("commerce.migrations.retry-failed",Boolean.class,false);
    }
    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        try { for (String tenant : tenants.tenants()) {
            TenantContext.set(tenant);
            new CommerceSchemaMigrator(source).migrate(Arrays.asList(
                CommerceSchemaMigrator.Migration.resources(1,"legacy-commerce-baseline",service::initializeSchema,"db/commerce-demo.sql","db/commerce-merchant.sql","db/commerce-inventory.sql","db/commerce-after-sales.sql"),
                CommerceSchemaMigrator.Migration.resources(2,"durable-checkout",queue::initializeSchema,"db/commerce-queue.sql"),
                CommerceSchemaMigrator.Migration.resources(3,"inventory-planning",planning::initializeSchema,"db/commerce-planning.sql"),
                CommerceSchemaMigrator.Migration.resources(4,"payment-channel-state",payments::initializeSchema,"db/commerce-payment.sql"),
                CommerceSchemaMigrator.Migration.resources(5,"dated-delivery-capacity",()->{delivery.initializeSchema();delivery.migrateLegacy();},"db/commerce-delivery.sql"),
                CommerceSchemaMigrator.Migration.resources(6,"supply-commitments",planning::initializeSupplySchema,"db/commerce-supply-flow.sql"),
                CommerceSchemaMigrator.Migration.resources(7,"shop-collation-compatibility",()->applySql("db/commerce-collation.sql"),"db/commerce-collation.sql"),
                CommerceSchemaMigrator.Migration.resources(8,"supply-exceptions",planning::initializeSupplyExceptionsSchema,"db/commerce-supply-exceptions.sql"),
                CommerceSchemaMigrator.Migration.resources(9,"warehouse-order-allocations",()->{warehouses.initializeSchema();warehouses.allocateExisting();},"db/commerce-warehouse-allocation.sql"),
                CommerceSchemaMigrator.Migration.resources(10,"document-cost-reconciliation",costs::initializeSchema,"db/commerce-cost-reconciliation.sql"),
                CommerceSchemaMigrator.Migration.resources(11,"durable-procurement-agent-tasks",agentTasks::initializeSchema,"db/commerce-agent-tasks.sql")
            ),migrationsEnabled,retryFailed);
        } ready = true; }
        finally { TenantContext.clear(); }
    }
    private void applySql(String path){
        org.springframework.jdbc.datasource.init.ResourceDatabasePopulator script=new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource(path));
        script.setSqlScriptEncoding("UTF-8");script.execute(source);
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
                mark(tenant,"expiry",true);
            } catch (Exception ex) { mark(tenant,"expiry",false); log.error("Order expiry failed for tenant {}: {}", tenant, ex.getClass().getSimpleName()); }
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
                try { TenantContext.set(tenant); queue.processBatch(20); mark(tenant,"checkout",true); }
                catch(Exception ex) { mark(tenant,"checkout",false); log.error("Checkout worker failed for tenant {}: {}",tenant,ex.getClass().getSimpleName()); }
                finally { CommerceShopContext.clear(); TenantContext.clear(); active.set(false); }
            });
        }
    }
    @PreDestroy public void stop() { workers.shutdown(); }
    @Scheduled(fixedDelay=5000)
    public void recoverPayments() {
        if(!ready)return;
        for(String tenant:tenants.tenants())try {
            TenantContext.set(tenant);
            for(String shop:service.shopIds()) {
                CommerceShopContext.set(shop);
                Map<String,Object> report=payments.recoverPending(20,service::expireOrder,afterSales::providerRefundSucceeded);
                mark(tenant,"payments",((List<?>)report.get("failures")).isEmpty());
            }
        }catch(Exception error){mark(tenant,"payments",false);log.error("Payment recovery failed for tenant {}: {}",tenant,error.getClass().getSimpleName());}
        finally{CommerceShopContext.clear();TenantContext.clear();}
    }
    public boolean isReady(){return ready;}
    public Map<String,Object> status(String tenant){return new LinkedHashMap<>(status.getOrDefault(tenant,Collections.emptyMap()));}
    private void mark(String tenant,String kind,boolean ok) {
        status.compute(tenant,(id,prior)->{
            Map<String,Object> next=prior==null?new LinkedHashMap<>():new LinkedHashMap<>(prior);
            next.put(kind+"LastAttemptAt",System.currentTimeMillis());
            if(ok)next.put(kind+"LastSuccessAt",System.currentTimeMillis());
            else next.put(kind+"Failures",((Number)next.getOrDefault(kind+"Failures",0L)).longValue()+1);
            return next;
        });
    }
}

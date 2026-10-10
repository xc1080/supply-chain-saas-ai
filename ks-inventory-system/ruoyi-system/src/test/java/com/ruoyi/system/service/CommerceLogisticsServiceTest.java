package com.ruoyi.system.service;

import com.ruoyi.common.core.tenant.CommerceShopContext;
import com.ruoyi.common.core.tenant.TenantContext;
import com.ruoyi.common.exception.ServiceException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static com.ruoyi.system.service.CommerceServiceTest.*;
import static org.junit.Assert.*;

/** Real SQL transactions distinguish carrier observations, customer proof and physical acceptance. */
public class CommerceLogisticsServiceTest {
    private CommerceServiceTest fixture;
    private CommerceService commerce;
    private CommerceAfterSalesService after;
    private CommerceLogisticsService logistics;
    private JdbcTemplate jdbc;
    @Before public void setup()throws Exception {
        CommerceShopContext.clear();TenantContext.clear();fixture=new CommerceServiceTest();fixture.setup();
        jdbc=fixture.jdbc;commerce=fixture.service;after=fixture.afterSales;
        logistics=new CommerceLogisticsService(jdbc.getDataSource());logistics.initializeSchema();after.configureLogistics(logistics);
    }
    @After public void clear(){CommerceShopContext.clear();TenantContext.clear();}
    private <T>T tx(Supplier<T> work){return fixture.transaction(work);}
    private long n(String query,Object...args){return jdbc.queryForObject(query,Long.class,args);}
    private String paid(String key,int quantity){String id=fixture.id(fixture.create(key,quantity));tx(()->commerce.pay(id,map("ownerId",OWNER,"paymentRequestId",key+"-pay","scenario","success")));return id;}
    private String ship(String order,String key,int quantity){
        tx(()->commerce.ship(order,map("requestKey",key,"carrier","TEST","trackingNo","SAME-TRACKING","items",Collections.singletonList(map("productId",1,"quantity",quantity))),1));
        return jdbc.queryForObject("SELECT shipment_id FROM commerce_shipment WHERE order_id=? AND request_key=?",String.class,order,key);
    }
    private String apply(String order,String key,String kind,int quantity){
        String id=String.valueOf(tx(()->after.apply(order,map("ownerId",OWNER,"requestKey",key,"reason","学习退货流程","kind",kind,"items",Collections.singletonList(map("productId",1,"quantity",quantity))))).get("afterSalesId"));
        assertEquals(1L,n("SELECT return_evidence_version FROM commerce_after_sales_case WHERE after_sales_id=?",id));return id;
    }
    private void review(String id){tx(()->after.review(id,map("requestKey","approve","decision","APPROVE"),1));}
    private Map<String,Object> parcel(String key){return map("ownerId",OWNER,"carrierCode","TEST","trackingNo","RETURN-001","requestKey",key);}
    private Map<String,Object> node(String key,String status){return map("requestKey",key,"status",status,"occurredAt","2026-10-10T12:00:00","location","测试分拨中心","description","人工登记的本地模拟承运节点");}
    private Map<String,Object> accept(String id,String condition){return tx(()->after.acceptReturn(id,map("requestKey","accept","condition",condition),1));}
    private Map<String,Object> inventory(){return commerce.inventory().get(0);}
    private void rejected(int code,Runnable work){fixture.rejected(code,work);}
    private List<?> events(Map<String,Object> observation){return (List<?>)observation.get("events");}
    private void evidenceVersion(String id,int version){jdbc.update("UPDATE commerce_after_sales_case SET return_evidence_version=? WHERE after_sales_id=?",version,id);}
    private List<Map<String,Object>> businessSnapshot(){
        List<Map<String,Object>> rows=new ArrayList<>();
        for(String table:Arrays.asList("commerce_order","commerce_stock","commerce_stock_hold","commerce_stock_ledger","commerce_warehouse_ledger","commerce_cost_receipt","commerce_cost_entry","head_receipt","inventory_product"))rows.addAll(jdbc.queryForList("SELECT * FROM "+table));
        return rows;
    }

    @Test public void everyShipmentHasIndependentExplicitProviderFactsAndDeliveredDoesNotReceiveOrder() {
        String order=paid("two-parcels",2),one=ship(order,"first",1),two=ship(order,"second",1);
        Map<String,Object> empty=logistics.shipmentTracking(one,OWNER);assertEquals("SIMULATED",empty.get("source"));assertEquals("LOCAL_SIMULATED",empty.get("provider"));
        assertEquals("NO_OBSERVATION",empty.get("status"));assertNotNull(empty.get("observedAt"));assertTrue(events(empty).isEmpty());assertEquals(0L,fixture.count("commerce_logistics_event"));
        List<Map<String,Object>> before=businessSnapshot();Map<String,Object> observed=tx(()->logistics.recordShipmentEvent(one,node("delivered","DELIVERED"),1));
        assertEquals("DELIVERED",observed.get("status"));assertEquals(1,events(observed).size());assertEquals(before,businessSnapshot());assertEquals(2,commerce.detail(order,OWNER).get("orderStatus"));
        assertTrue(events(logistics.shipmentTracking(two,OWNER)).isEmpty());
        tx(()->logistics.recordShipmentEvent(one,node("delivered","DELIVERED"),1));assertEquals(1L,fixture.count("commerce_logistics_event"));
        for(String field:Arrays.asList("status","occurredAt","location","description")) {
            Map<String,Object> changed=node("delivered","DELIVERED");changed.put(field,"status".equals(field)?"EXCEPTION":"occurredAt".equals(field)?"2026-10-10T12:01:00":"changed");
            rejected(409,()->tx(()->logistics.recordShipmentEvent(one,changed,1)));
        }
        assertEquals(before,businessSnapshot());
    }

    @Test public void unauthorizedOwnerShopAndMemberAreRejectedBeforeCallingProvider() {
        String order=paid("access",1),shipment=ship(order,"first",1);AtomicInteger calls=new AtomicInteger();
        CommerceLogisticsProvider adapter=new CommerceLogisticsProvider(){public String name(){return "TEST";}public String source(){return "SIMULATED";}public Observation query(Subject subject){calls.incrementAndGet();return new Observation("2026-10-10T12:00:00",Collections.emptyList());}};
        CommerceLogisticsService guarded=new CommerceLogisticsService(jdbc.getDataSource(),fixture.merchants,adapter);
        rejected(404,()->guarded.shipmentTracking(shipment,FOREIGN_OWNER));assertEquals(0,calls.get());
        rejected(403,()->tx(()->logistics.recordShipmentEvent(shipment,node("viewer","ACCEPTED"),2)));assertEquals(0L,fixture.count("commerce_logistics_event"));
        jdbc.update("INSERT INTO commerce_shop(shop_id,shop_name) VALUES ('other','Other')");jdbc.update("INSERT INTO commerce_shop_member VALUES ('other',1,'OWNER')");
        CommerceShopContext.set("other");rejected(404,()->guarded.shipmentTracking(shipment,OWNER));rejected(404,()->tx(()->logistics.recordShipmentEvent(shipment,node("wrong-shop","ACCEPTED"),1)));assertEquals(0,calls.get());
        CommerceShopContext.clear();guarded.shipmentTracking(shipment,OWNER);assertEquals(1,calls.get());
    }

    @Test public void tenantRoutingAndProviderSubjectScopeNeverShareObservations()throws Exception {
        String order=paid("tenant",1),shipment=ship(order,"first",1);tx(()->logistics.recordShipmentEvent(shipment,node("node","ACCEPTED"),1));
        CommerceServiceTest other=new CommerceServiceTest();other.setup();
        AbstractRoutingDataSource routing=new AbstractRoutingDataSource(){@Override protected Object determineCurrentLookupKey(){return TenantContext.id();}};
        Map<Object,Object> sources=new HashMap<>();sources.put("demo",jdbc.getDataSource());sources.put("studio",other.jdbc.getDataSource());routing.setTargetDataSources(sources);routing.afterPropertiesSet();
        CommerceLogisticsService routed=new CommerceLogisticsService(routing);
        assertEquals(1,events(routed.shipmentTracking(shipment,OWNER)).size());
        TenantContext.set("studio");routed.initializeSchema();rejected(404,()->routed.shipmentTracking(shipment,OWNER));
        // A shared test-provider store still keys facts by the explicit tenant, without a cache.
        TenantContext.clear();CommerceLogisticsProvider.Subject demo=new CommerceLogisticsProvider.Subject("demo","default","OUTBOUND",shipment,"TEST","SAME-TRACKING");
        CommerceLogisticsProvider.Subject studio=new CommerceLogisticsProvider.Subject("studio","default","OUTBOUND",shipment,"TEST","SAME-TRACKING");
        CommerceLocalLogisticsProvider shared=new CommerceLocalLogisticsProvider(jdbc.getDataSource());assertEquals(1,shared.query(demo).events.size());assertTrue(shared.query(studio).events.isEmpty());
        assertEquals(1,events(routed.shipmentTracking(shipment,OWNER)).size());
    }

    @Test public void timeoutAndAdapterErrorsReturnUnavailableWithoutChangingBusinessFacts() {
        String order=paid("timeout",1),shipment=ship(order,"first",1);List<Map<String,Object>> before=businessSnapshot();
        for(boolean timeout:Arrays.asList(true,false)) {
            CommerceLogisticsProvider adapter=new CommerceLogisticsProvider(){public String name(){return "UNAVAILABLE_TEST";}public String source(){return "SIMULATED";}public Observation query(Subject subject)throws TimeoutException{if(timeout)throw new TimeoutException("secret response");throw new IllegalStateException("secret credential");}};
            CommerceLogisticsService guarded=new CommerceLogisticsService(jdbc.getDataSource(),fixture.merchants,adapter);
            Map<String,Object> result=guarded.shipmentTracking(shipment,OWNER);assertEquals("UNAVAILABLE",result.get("queryStatus"));assertEquals(timeout?"PROVIDER_TIMEOUT":"PROVIDER_ERROR",result.get("status"));
            assertFalse(result.toString().contains("secret"));assertNotNull(result.get("observedAt"));assertTrue(events(result).isEmpty());assertEquals(before,businessSnapshot());
        }
        assertEquals(0L,fixture.count("commerce_logistics_event"));
    }

    @Test public void returnRegistrationRequiresApprovedOwnerReturnAndReplaysOnlySameParameters() {
        String order=paid("register",2);ship(order,"first",2);String id=apply(order,"return","RETURN_REFUND",1);evidenceVersion(id,1);
        rejected(409,()->tx(()->logistics.registerReturnParcel(id,parcel("parcel"))));rejected(404,()->{Map<String,Object> foreign=parcel("parcel");foreign.put("ownerId",FOREIGN_OWNER);tx(()->logistics.registerReturnParcel(id,foreign));});
        review(id);List<Map<String,Object>> before=businessSnapshot();Map<String,Object> registered=tx(()->after.registerReturnParcel(id,parcel("parcel")));
        assertEquals(true,registered.get("registered"));assertEquals("REGISTERED",registered.get("status"));assertEquals("RETURN-001",registered.get("trackingNo"));assertEquals(before,businessSnapshot());
        assertEquals("AWAITING_RETURN",after.detail(id,OWNER).get("status"));assertEquals(0L,fixture.count("commerce_return_allocation"));assertEquals(8L,inventory().get("bookStock"));
        Map<String,Object> replay=tx(()->logistics.registerReturnParcel(id,parcel("parcel")));assertEquals(registered.get("registeredAt"),replay.get("registeredAt"));assertEquals(registered.get("trackingNo"),replay.get("trackingNo"));assertEquals(1L,fixture.count("commerce_return_parcel"));
        for(String field:Arrays.asList("carrierCode","trackingNo","requestKey")) {Map<String,Object> changed=parcel("parcel");changed.put(field,"changed");rejected(409,()->tx(()->logistics.registerReturnParcel(id,changed)));}
        rejected(404,()->logistics.returnParcel(id,FOREIGN_OWNER));CommerceShopContext.set("other");rejected(404,()->logistics.returnParcel(id,OWNER));CommerceShopContext.clear();
        assertEquals(1L,n("SELECT COUNT(*) FROM commerce_after_sales_event WHERE event_type='RETURN_REGISTERED'"));assertEquals(before,businessSnapshot());
    }

    @Test public void refundOnlyAndRejectedCasesCannotRegisterReturn() {
        String unshipped=paid("refund-only",1),refund=apply(unshipped,"refund","UNSHIPPED_REFUND",1);review(refund);
        rejected(409,()->tx(()->logistics.registerReturnParcel(refund,parcel("parcel"))));
        String shipped=paid("rejected",1);ship(shipped,"first",1);String id=apply(shipped,"return","RETURN_REFUND",1);
        tx(()->after.review(id,map("requestKey","reject","decision","REJECT"),1));rejected(409,()->tx(()->logistics.registerReturnParcel(id,parcel("parcel"))));assertEquals(0L,fixture.count("commerce_return_parcel"));
    }

    @Test public void newCaseMustHaveParcelOrExplicitManualReceiptEvidenceAndAcceptanceReplaysOnce() {
        String order=paid("evidence",1);ship(order,"first",1);String id=apply(order,"return","RETURN_REFUND",1);evidenceVersion(id,1);review(id);
        assertEquals(true,after.detail(id,OWNER).get("returnEvidenceRequired"));List<Map<String,Object>> before=businessSnapshot();
        rejected(409,()->accept(id,"DAMAGED"));assertEquals(before,businessSnapshot());assertEquals(0L,fixture.count("commerce_return_receipt_evidence"));
        Map<String,Object> proof=map("requestKey","accept","condition","DAMAGED","receiptEvidence","收货台人工核验，原订单货物一件，验收编号 RECEIPT-001");
        Map<String,Object> accepted=tx(()->after.acceptReturn(id,proof,1));assertEquals("RETURN_RECEIVED",accepted.get("status"));assertEquals("RECEIVED",((Map<?,?>)accepted.get("returnParcel")).get("status"));
        assertEquals(proof.get("receiptEvidence"),accepted.get("receiptEvidence"));
        assertEquals(10L,inventory().get("bookStock"));assertEquals(1L,inventory().get("unavailableStock"));assertEquals(9L,inventory().get("availableStock"));
        assertEquals(1L,fixture.count("commerce_return_receipt_evidence"));long events=fixture.count("commerce_stock_ledger");assertEquals(accepted,tx(()->after.acceptReturn(id,proof,1)));assertEquals(events,fixture.count("commerce_stock_ledger"));
        Map<String,Object> changed=new LinkedHashMap<>(proof);changed.put("receiptEvidence","更换证据");rejected(409,()->tx(()->after.acceptReturn(id,changed,1)));
        assertEquals(1L,n("SELECT COUNT(*) FROM commerce_warehouse_ledger WHERE operation='RETURN_ACCEPT'"));
    }

    @Test public void registeredReturnAndCarrierDeliveredDoNotRestockUntilQualityAcceptanceInOriginalWarehouses() {
        jdbc.update("UPDATE inventory_product SET plan_quantity=1 WHERE inventory_id=1");jdbc.update("INSERT INTO inventory_product(inventory_id,product_id,warehouse_id,plan_quantity) VALUES (2,1,2,9)");
        String order=paid("quality",2);ship(order,"first",2);String id=apply(order,"return","RETURN_REFUND",2);evidenceVersion(id,1);review(id);
        Map<String,Object> money=jdbc.queryForMap("SELECT refund_amount,refunded_amount FROM commerce_after_sales_case WHERE after_sales_id=?",id);
        List<Map<String,Object>> before=businessSnapshot();tx(()->logistics.registerReturnParcel(id,parcel("parcel")));tx(()->logistics.recordReturnEvent(id,node("delivered","DELIVERED"),1));
        assertEquals(before,businessSnapshot());assertEquals("AWAITING_RETURN",after.detail(id,OWNER).get("status"));
        assertEquals("DELIVERED",((Map<?,?>)logistics.returnParcel(id,OWNER).get("tracking")).get("status"));assertEquals(8L,inventory().get("bookStock"));
        accept(id,"QUALITY_HOLD");assertEquals(10L,inventory().get("bookStock"));assertEquals(2L,inventory().get("unavailableStock"));assertEquals(8L,inventory().get("availableStock"));
        assertEquals(1L,n("SELECT plan_quantity FROM inventory_product WHERE warehouse_id=1"));assertEquals(9L,n("SELECT plan_quantity FROM inventory_product WHERE warehouse_id=2"));
        assertEquals(1L,n("SELECT quality_hold FROM commerce_warehouse_condition WHERE warehouse_id=1"));assertEquals(1L,n("SELECT quality_hold FROM commerce_warehouse_condition WHERE warehouse_id=2"));
        assertEquals(2L,fixture.count("commerce_return_allocation"));assertEquals(money,jdbc.queryForMap("SELECT refund_amount,refunded_amount FROM commerce_after_sales_case WHERE after_sales_id=?",id));
        assertEquals(true,commerce.reconcile().get("healthy"));
    }

    @Test public void migrationKeepsOldCasesCompatibleWithoutManufacturingEvidence() {
        String order=paid("legacy",1);ship(order,"first",1);String id=apply(order,"return","RETURN_REFUND",1);evidenceVersion(id,0);review(id);
        logistics.initializeSchema();logistics.initializeSchema();assertEquals(0L,n("SELECT return_evidence_version FROM commerce_after_sales_case WHERE after_sales_id=?",id));
        assertEquals(false,after.detail(id,OWNER).get("returnEvidenceRequired"));assertEquals("RETURN_RECEIVED",accept(id,"SELLABLE").get("status"));
        assertEquals(10L,inventory().get("availableStock"));assertEquals(0L,fixture.count("commerce_return_parcel"));assertEquals(0L,fixture.count("commerce_return_receipt_evidence"));
    }

    @Test public void allocatedRefundCentsAndSellableReturnsKeepOriginalDispatchCostsAcrossCurrentPriceChanges() {
        CommerceOrderAmountService amounts=new CommerceOrderAmountService(jdbc.getDataSource());amounts.initializeSchema();
        tx(()->amounts.savePolicy(map("shippingFee","0.03"),1));
        String promotion=String.valueOf(tx(()->amounts.savePromotion(null,map("title","学习优惠","discountAmount","0.04","productIds",Collections.emptyList(),"startsAt",LocalDateTime.now().minusMinutes(1).toString(),"endsAt",LocalDateTime.now().plusHours(1).toString(),"enabled",true),1)).get("promotionId"));
        jdbc.update("UPDATE product SET univalence=0.05 WHERE product_id=1");Map<String,Object> request=fixture.request("money-evidence",3);request.put("promotionId",promotion);
        String order=fixture.id(tx(()->commerce.create(request)));tx(()->commerce.pay(order,map("ownerId",OWNER,"paymentRequestId","pay","scenario","success")));String shipment=ship(order,"first",3);
        assertEquals(new BigDecimal("0.14"),commerce.detail(order,OWNER).get("totalAmount"));
        jdbc.update("UPDATE product SET univalence=999,cost_price=555 WHERE product_id=1");
        String one=apply(order,"one","RETURN_REFUND",1);Map<String,Object> first=after.detail(one,OWNER);assertEquals(new BigDecimal("0.05"),first.get("refundAmount"));
        assertEquals(new BigDecimal("0.05"),((Map<?,?>)((List<?>)first.get("items")).get(0)).get("amount"));
        assertEquals(one,apply(order,"one","RETURN_REFUND",1));assertEquals(1L,fixture.count("commerce_refund_unit_allocation"));review(one);
        rejected(409,()->accept(one,"SELLABLE"));tx(()->logistics.registerReturnParcel(one,parcel("one-parcel")));accept(one,"SELLABLE");
        tx(()->after.refund(one,map("requestKey","refund-one","scenario","success"),1));
        String rest=apply(order,"rest","RETURN_REFUND",2);assertEquals(new BigDecimal("0.09"),after.detail(rest,OWNER).get("refundAmount"));review(rest);
        tx(()->after.acceptReturn(rest,map("requestKey","accept","condition","SELLABLE","receiptEvidence","商家收货台原订单两件实收核验"),1));tx(()->after.refund(rest,map("requestKey","refund-rest","scenario","success"),1));
        assertEquals(new BigDecimal("0.14"),commerce.detail(order,OWNER).get("refundedAmount"));assertEquals(3L,fixture.count("commerce_refund_unit_allocation"));assertEquals(10L,inventory().get("availableStock"));
        assertEquals(0L,n("SELECT COUNT(*) FROM commerce_cost_entry WHERE event_type='RETURN' AND (unit_cost<>70 OR cost_basis<>'ORIGINAL_DISPATCH' OR origin_entry_id IS NULL)"));
        assertEquals(new BigDecimal("210.0000"),jdbc.queryForObject("SELECT SUM(amount) FROM commerce_cost_entry WHERE event_type='RETURN'",BigDecimal.class));
        assertEquals(1L,n("SELECT COUNT(DISTINCT e.origin_entry_id) FROM commerce_cost_entry e JOIN commerce_cost_entry original ON original.entry_id=e.origin_entry_id WHERE e.event_type='RETURN' AND original.source_id=?",shipment));
        assertEquals(true,commerce.reconcile().get("healthy"));
    }

    @Test public void concurrentParcelReplaysSerializeOnOrderAndCaseAndRecordOneProof()throws Exception {
        String order=paid("parcel-race",1);ship(order,"first",1);String id=apply(order,"return","RETURN_REFUND",1);evidenceVersion(id,1);review(id);
        ExecutorService workers=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<Map<String,Object>>> results=new ArrayList<>();for(int i=0;i<2;i++)results.add(workers.submit(()->{start.await();return tx(()->logistics.registerReturnParcel(id,parcel("parcel")));}));
            start.countDown();Map<String,Object> one=results.get(0).get(15,TimeUnit.SECONDS),two=results.get(1).get(15,TimeUnit.SECONDS);assertEquals(one.get("registeredAt"),two.get("registeredAt"));assertEquals(one.get("trackingNo"),two.get("trackingNo"));assertEquals(true,one.get("registered"));assertEquals(true,two.get("registered"));
            assertEquals(1L,fixture.count("commerce_return_parcel"));assertEquals(1L,n("SELECT COUNT(*) FROM commerce_after_sales_event WHERE event_type='RETURN_REGISTERED'"));assertEquals(9L,inventory().get("bookStock"));
        }finally{workers.shutdownNow();}
    }
}

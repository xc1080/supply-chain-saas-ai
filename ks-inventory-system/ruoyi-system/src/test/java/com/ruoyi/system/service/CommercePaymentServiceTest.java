package com.ruoyi.system.service;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.core.tenant.CommerceShopContext;
import org.junit.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static com.ruoyi.system.service.CommerceServiceTest.*;

/** Exercise durable channel/business divergence using real SQL transactions, not mocked repositories. */
public class CommercePaymentServiceTest {
    private CommerceServiceTest fixture;
    private CommercePaymentService payments;
    private CommerceService commerce;
    private CommerceAfterSalesService after;
    private JdbcTemplate jdbc;
    @Before public void setup() throws Exception {
        fixture=new CommerceServiceTest();fixture.setup();jdbc=fixture.jdbc;commerce=fixture.service;after=fixture.afterSales;
        payments=new CommercePaymentService(jdbc.getDataSource());payments.configureWebhookSecret("test-only-stable-webhook-secret-123456789");
    }
    @After public void cleanup(){CommerceShopContext.clear();}
    private String order(String key){return fixture.id(fixture.create(key,1));}
    private Map<String,Object> pay(String order,String key,String scenario){return commerce.pay(order,map("ownerId",OWNER,"paymentRequestId",key,"scenario",scenario));}
    private String operation(Map<String,Object> result,String field){return String.valueOf(((Map<?,?>)result.get(field)).get("operationId"));}
    private long n(String sql,Object...args){return jdbc.queryForObject(sql,Long.class,args);}
    private Map<String,Object> event(String id,String eventId){Map<String,Object> row=jdbc.queryForMap("SELECT * FROM commerce_payment_operation WHERE operation_id=?",id);return map("eventId",eventId,"operationId",id,"status",row.get("provider_status"),"revision",row.get("provider_revision"),"amount",row.get("amount"),"currency","CNY","providerReference",row.get("provider_reference"));}
    private Map<String,Object> receive(Map<String,Object> event){String raw=JSON.toJSONString(event),timestamp=String.valueOf(System.currentTimeMillis()/1000);return payments.receive(raw,timestamp,payments.sign(timestamp,raw),commerce::expireOrder,after::providerRefundSucceeded);}
    private String approvedCase(String order){String id=String.valueOf(fixture.transaction(()->after.apply(order,map("ownerId",OWNER,"requestKey","case","reason","不要了"))).get("afterSalesId"));fixture.transaction(()->after.review(id,map("requestKey","approve","decision","APPROVE"),1));return id;}

    @Test public void timeoutAfterChannelChargeIsRecoveredWithoutSecondCharge() {
        String order=order("unknown");Map<String,Object> unknown=pay(order,"pay","timeout_after_success"),retry=pay(order,"pay","timeout_after_success");
        String operation=operation(unknown,"paymentOperation");assertEquals(operation,operation(retry,"paymentOperation"));assertEquals("UNKNOWN",unknown.get("paymentOutcome"));assertEquals(0,commerce.detail(order,OWNER).get("orderStatus"));
        assertEquals(1L,n("SELECT COUNT(*) FROM commerce_channel_entry WHERE movement='CHARGE'"));assertEquals(false,payments.reconciliation().get("healthy"));
        fixture.rejected(409,()->pay(order,"another","success"));
        assertEquals("SUCCEEDED",payments.query(operation,commerce::expireOrder,after::providerRefundSucceeded).get("outcome"));assertEquals(1,commerce.detail(order,OWNER).get("orderStatus"));
        payments.query(operation,commerce::expireOrder,after::providerRefundSucceeded);assertEquals(1L,n("SELECT COUNT(*) FROM commerce_channel_entry WHERE movement='CHARGE'"));assertEquals(true,payments.reconciliation().get("healthy"));
    }
    @Test public void duplicateAndOutOfOrderNotificationsNeverRevertSuccess() {
        String order=order("events"),operation=operation(pay(order,"pay","delayed_success"),"paymentOperation");Map<String,Object> stale=event(operation,"stale_pending");
        payments.advance(operation,"SUCCEEDED",commerce::expireOrder,after::providerRefundSucceeded);
        Map<String,Object> success=event(operation,"external_success");receive(success);receive(success);receive(stale);
        assertEquals(1,commerce.detail(order,OWNER).get("orderStatus"));assertEquals("SUCCEEDED",payments.detail(operation).get("outcome"));assertEquals(1L,n("SELECT COUNT(*) FROM commerce_channel_entry"));
        Map<String,Object> changed=new LinkedHashMap<>(success);changed.put("amount",new BigDecimal("130.00"));fixture.rejected(409,()->receive(changed));
        fixture.rejected(409,()->payments.advance(operation,"FAILED",commerce::expireOrder,after::providerRefundSucceeded));
    }
    @Test public void signatureTamperingAndExpiredTimestampAreRejectedBeforeInbox() {
        String id=operation(pay(order("signature"),"pay","delayed_success"),"paymentOperation"),raw=JSON.toJSONString(event(id,"bad_signature")),timestamp=String.valueOf(System.currentTimeMillis()/1000);
        long before=n("SELECT COUNT(*) FROM commerce_provider_event");fixture.rejected(401,()->payments.receive(raw,timestamp,"invalid",commerce::expireOrder,after::providerRefundSucceeded));
        String expired=String.valueOf(System.currentTimeMillis()/1000-600);fixture.rejected(401,()->payments.receive(raw,expired,payments.sign(expired,raw),commerce::expireOrder,after::providerRefundSucceeded));assertEquals(before,n("SELECT COUNT(*) FROM commerce_provider_event"));
    }
    @Test public void latePaymentAfterExpiryCompensatesWithoutReopeningOrReserving() {
        String order=order("expired"),id=operation(pay(order,"pay","delayed_success"),"paymentOperation");
        jdbc.update("UPDATE commerce_order SET expires_at=DATEADD('SECOND',-10,CURRENT_TIMESTAMP) WHERE order_id=?",order);fixture.transaction(()->commerce.expireOrder(order));assertEquals(10L,commerce.inventory().get(0).get("availableStock"));
        assertEquals("COMPENSATED",payments.advance(id,"SUCCEEDED",commerce::expireOrder,after::providerRefundSucceeded).get("outcome"));
        payments.query(id,commerce::expireOrder,after::providerRefundSucceeded);assertEquals(4,commerce.detail(order,OWNER).get("orderStatus"));assertEquals(10L,commerce.inventory().get(0).get("availableStock"));
        assertEquals(1L,n("SELECT COUNT(*) FROM commerce_payment_operation WHERE kind='COMPENSATION'"));assertEquals(2L,n("SELECT COUNT(*) FROM commerce_channel_entry"));assertEquals(true,payments.reconciliation().get("healthy"));assertEquals(true,commerce.reconcile().get("healthy"));
    }
    @Test public void latePaymentAfterCustomerCancellationAlsoCompensates() {
        String order=order("cancelled"),id=operation(pay(order,"pay","delayed_success"),"paymentOperation");fixture.transaction(()->commerce.cancel(order,OWNER));
        assertEquals("COMPENSATED",payments.advance(id,"SUCCEEDED",commerce::expireOrder,after::providerRefundSucceeded).get("outcome"));assertEquals(4,commerce.detail(order,OWNER).get("orderStatus"));assertEquals(true,payments.reconciliation().get("healthy"));
    }
    @Test public void pendingRefundFailureAndNewAttemptRecoverWithoutPrematureStockRelease() {
        String order=order("refund");pay(order,"pay","success");String id=approvedCase(order);
        Map<String,Object> pending=after.refund(id,map("requestKey","refund","scenario","refund_pending"),1);String operation=operation(pending,"refundOperation");
        assertEquals("PENDING",pending.get("refundOutcome"));assertEquals("APPROVED",pending.get("status"));assertEquals(1L,commerce.inventory().get(0).get("reservedStock"));
        fixture.rejected(409,()->after.refund(id,map("requestKey","another","scenario","success"),1));
        payments.advance(operation,"FAILED",commerce::expireOrder,after::providerRefundSucceeded);assertEquals(1L,commerce.inventory().get(0).get("reservedStock"));
        Map<String,Object> success=after.refund(id,map("requestKey","retry","scenario","timeout_after_success"),1);String retry=operation(success,"refundOperation");assertEquals("UNKNOWN",success.get("refundOutcome"));assertEquals(false,payments.reconciliation().get("healthy"));
        payments.query(retry,commerce::expireOrder,after::providerRefundSucceeded);payments.query(retry,commerce::expireOrder,after::providerRefundSucceeded);
        assertEquals("REFUNDED",after.detail(id,OWNER).get("status"));assertEquals(new BigDecimal("129.00"),commerce.detail(order,OWNER).get("refundedAmount"));assertEquals(0L,commerce.inventory().get(0).get("reservedStock"));assertEquals(1L,n("SELECT COUNT(*) FROM commerce_channel_entry WHERE movement='REFUND'"));assertEquals(true,payments.reconciliation().get("healthy"));
    }
    @Test public void inboxSurvivesBusinessFailureAndReplaysExactlyOnce() {
        String order=order("replay");pay(order,"pay","success");String id=approvedCase(order),operation=operation(after.refund(id,map("requestKey","refund","scenario","timeout_after_success"),1),"refundOperation");Map<String,Object> event=event(operation,"refund_replay");
        fixture.rejected(503,()->payments.applyEvent(event,commerce::expireOrder,null));assertEquals("RECEIVED",jdbc.queryForObject("SELECT processing_status FROM commerce_provider_event WHERE event_id='refund_replay'",String.class));
        assertEquals("SUCCEEDED",payments.replay("refund_replay",commerce::expireOrder,after::providerRefundSucceeded).get("outcome"));payments.replay("refund_replay",commerce::expireOrder,after::providerRefundSucceeded);
        assertEquals(1L,n("SELECT COUNT(*) FROM commerce_stock_ledger WHERE event_type='RELEASE'"));assertEquals(true,payments.reconciliation().get("healthy"));
    }
    @Test public void shopBoundaryAndLegacyCoverageAreExplicit() {
        String order=order("boundary"),id=operation(pay(order,"pay","success"),"paymentOperation");CommerceShopContext.set("other");fixture.rejected(404,()->payments.detail(id));CommerceShopContext.clear();
        jdbc.update("DELETE FROM commerce_channel_entry");jdbc.update("DELETE FROM commerce_payment_operation");assertEquals(1L,payments.reconciliation().get("untrackedLegacyOrders"));assertEquals("ALL_TRACKED_ORDERS",payments.reconciliation().get("coverage"));
    }
    @Test public void concurrentQueriesApplyOneChargeAndOnePaymentTransition() throws Exception {
        String order=order("concurrent"),id=operation(pay(order,"pay","timeout_after_success"),"paymentOperation");ExecutorService pool=Executors.newFixedThreadPool(4);
        try{List<Future<Map<String,Object>>> results=new ArrayList<>();for(int i=0;i<4;i++)results.add(pool.submit(()->payments.query(id,commerce::expireOrder,after::providerRefundSucceeded)));for(Future<Map<String,Object>> result:results)assertEquals("SUCCEEDED",result.get(10,TimeUnit.SECONDS).get("outcome"));}
        finally{pool.shutdownNow();}assertEquals(1L,n("SELECT COUNT(*) FROM commerce_channel_entry"));assertEquals(true,payments.reconciliation().get("healthy"));
    }
    @Test public void restartedWorkerRecoversUnknownUsingDurableBackoff() {
        String order=order("restart"),id=operation(pay(order,"pay","timeout_after_success"),"paymentOperation");
        jdbc.update("UPDATE commerce_payment_operation SET next_query_at=CURRENT_TIMESTAMP WHERE operation_id=?",id);
        CommercePaymentService restarted=new CommercePaymentService(jdbc.getDataSource());
        assertEquals(1,restarted.recoverPending(20,commerce::expireOrder,after::providerRefundSucceeded).get("processed"));
        assertEquals(1,commerce.detail(order,OWNER).get("orderStatus"));assertEquals(1L,n("SELECT query_count FROM commerce_payment_operation WHERE operation_id=?",id));
        assertEquals(0,restarted.recoverPending(20,commerce::expireOrder,after::providerRefundSucceeded).get("processed"));assertEquals(true,restarted.reconciliation().get("healthy"));
    }
}

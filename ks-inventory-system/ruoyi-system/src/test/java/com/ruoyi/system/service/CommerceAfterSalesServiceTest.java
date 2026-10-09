package com.ruoyi.system.service;

import com.ruoyi.common.core.domain.entity.DetailReceipt;
import com.ruoyi.common.core.tenant.CommerceShopContext;
import com.ruoyi.common.exception.ServiceException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static com.ruoyi.system.service.CommerceServiceTest.*;
import static org.junit.Assert.*;

/** Real SQL transactions: money and physical movement are independently audited, never reset. */
public class CommerceAfterSalesServiceTest {
    private CommerceServiceTest fixture;
    private CommerceService service;
    private CommerceAfterSalesService after;
    private JdbcTemplate jdbc;
    @Before public void setup() throws Exception {
        CommerceShopContext.clear();fixture=new CommerceServiceTest();fixture.setup();
        service=fixture.service;after=fixture.afterSales;jdbc=fixture.jdbc;
        // Match the legacy MySQL product actor column; a wider H2 fixture hid truncation.
        jdbc.execute("ALTER TABLE product MODIFY COLUMN update_by VARCHAR(16)");
    }
    @After public void cleanup() {CommerceShopContext.clear();}
    private <T>T tx(Supplier<T> task) {return fixture.transaction(task);}
    private String paid(String key,int quantity) {
        String id=fixture.id(fixture.create(key,quantity));
        tx(()->service.pay(id,map("ownerId",OWNER,"paymentRequestId",key+"-pay","scenario","success")));return id;
    }
    private String apply(String order,String key) {return String.valueOf(tx(()->after.apply(order,map("ownerId",OWNER,"requestKey",key,"reason","不再需要"))).get("afterSalesId"));}
    private Map<String,Object> review(String id) {return tx(()->after.review(id,map("requestKey","approve","decision","APPROVE"),1));}
    private Map<String,Object> accept(String id) {return tx(()->after.acceptReturn(id,map("requestKey","return","condition","SELLABLE"),1));}
    private Map<String,Object> refund(String id,String key,String scenario) {return tx(()->after.refund(id,map("requestKey",key,"scenario",scenario),1));}
    private long n(String sql,Object...args) {return jdbc.queryForObject(sql,Long.class,args);}
    private Map<String,Object> inventory() {return service.inventory().get(0);}
    private void rejected(int code,Runnable task) {fixture.rejected(code,task);}
    private void healthy() {assertEquals(service.reconcile().toString(),true,service.reconcile().get("healthy"));}

    @Test public void newOrdersRequireValidatedImmutableAddressAndLegacyReadsAllowNull() {
        Map<String,Object> request=fixture.request("address",1);
        request.remove("shippingAddress");rejected(400,()->tx(()->service.create(request)));
        for(Object bad:Arrays.asList("string",map("addressee","","phone","13800138000","address","完整地址测试"),
                map("addressee","张三","phone","not-a-phone","address","完整地址测试"),
                map("addressee","张三","phone","13800138000","address","短地址"),
                map("addressee","张\n三","phone","13800138000","address","完整地址测试"))) {
            request.put("shippingAddress",bad);rejected(400,()->tx(()->service.create(request)));
        }
        request.put("shippingAddress",address());String id=fixture.id(tx(()->service.create(request)));
        assertEquals(address(),service.detail(id,OWNER).get("shippingAddress"));
        Map<String,Object> changed=address();changed.put("address","广东省深圳市更换路 20 号");request.put("shippingAddress",changed);
        rejected(409,()->tx(()->service.create(request)));assertEquals(address(),service.detail(id,OWNER).get("shippingAddress"));
        jdbc.update("UPDATE commerce_order SET shipping_address=NULL WHERE order_id=?",id);
        assertNull(service.detail(id,OWNER).get("shippingAddress"));
        assertEquals(1L,fixture.count("commerce_order"));
    }

    @Test public void unshippedRefundReleasesHoldWithoutPhysicalMovementAndBlocksFulfillment() {
        String order=paid("unshipped",3),id=apply(order,"request");
        assertEquals(3L,inventory().get("reservedStock"));assertEquals(10L,inventory().get("bookStock"));
        assertEquals("REQUESTED",service.detail(order,OWNER).get("afterSalesStatus"));
        rejected(409,()->tx(()->service.ship(order,map(),1)));
        rejected(409,()->refund(id,"early","success"));
        assertEquals("APPROVED",review(id).get("status"));
        rejected(409,()->accept(id));
        Map<String,Object> refunded=refund(id,"refund","success");
        assertEquals("REFUNDED",refunded.get("status"));assertEquals(new BigDecimal("387.00"),refunded.get("refundedAmount"));
        assertEquals(1,service.detail(order,OWNER).get("orderStatus"));assertEquals(false,service.detail(order,OWNER).get("reservationActive"));
        assertEquals(10L,inventory().get("bookStock"));assertEquals(0L,inventory().get("reservedStock"));assertEquals(10L,inventory().get("availableStock"));
        assertEquals("RELEASED",jdbc.queryForObject("SELECT status FROM commerce_stock_hold WHERE order_id=?",String.class,order));
        assertEquals(0L,fixture.count("head_receipt"));assertEquals(0L,fixture.count("commerce_warehouse_ledger"));
        assertEquals(refunded.get("refundId"),refund(id,"refund","success").get("refundId"));
        assertEquals(refunded.get("refundId"),refund(id,"another-refund","success").get("refundId"));
        assertEquals(1L,fixture.count("commerce_refund_attempt"));assertEquals(1L,n("SELECT COUNT(*) FROM commerce_stock_ledger WHERE event_type='RELEASE'"));
        rejected(409,()->tx(()->service.ship(order,map(),1)));
        rejected(409,()->tx(()->service.pay(order,map("ownerId",OWNER,"paymentRequestId","new-pay","scenario","success"))));healthy();
    }

    @Test public void shippedFullReturnRestoresExactOriginalWarehousesOnlyAtAcceptanceThenRefundDoesNotRestock() {
        jdbc.update("UPDATE inventory_product SET plan_quantity=1 WHERE inventory_id=1");
        jdbc.update("INSERT INTO inventory_product(inventory_id,product_id,warehouse_id,plan_quantity) VALUES (2,1,2,9)");
        String order=paid("two-warehouse",3);
        String original=String.valueOf(tx(()->service.ship(order,map(),1)).get("receiptId"));
        tx(()->service.receive(order,OWNER));
        List<Map<String,Object>> originalJournal=jdbc.queryForList("SELECT * FROM commerce_warehouse_ledger WHERE receipt_id=? ORDER BY ledger_id",original);
        assertEquals(0L,n("SELECT plan_quantity FROM inventory_product WHERE inventory_id=1"));assertEquals(7L,n("SELECT plan_quantity FROM inventory_product WHERE inventory_id=2"));
        String id=apply(order,"request");assertEquals(7L,inventory().get("bookStock"));
        assertEquals("AWAITING_RETURN",review(id).get("status"));rejected(409,()->refund(id,"early","success"));
        rejected(409,()->tx(()->after.acceptReturn(id,map("requestKey","unknown","condition","UNKNOWN"),1)));
        assertEquals(7L,inventory().get("bookStock"));
        Map<String,Object> returned=accept(id);String receipt=String.valueOf(returned.get("returnReceiptId"));
        assertEquals("RETURN_RECEIVED",returned.get("status"));assertEquals(BigDecimal.ZERO.setScale(2),returned.get("refundedAmount"));
        assertEquals(1L,n("SELECT plan_quantity FROM inventory_product WHERE inventory_id=1"));assertEquals(9L,n("SELECT plan_quantity FROM inventory_product WHERE inventory_id=2"));
        assertEquals(10L,n("SELECT inventory_qty FROM product WHERE product_id=1"));assertEquals(10L,inventory().get("availableStock"));
        assertEquals(2L,n("SELECT COUNT(*) FROM commerce_warehouse_ledger WHERE operation='RETURN_ACCEPT'"));
        assertEquals(3L,n("SELECT SUM(delta_quantity) FROM commerce_warehouse_ledger WHERE receipt_id=?",receipt));
        assertEquals(originalJournal,jdbc.queryForList("SELECT * FROM commerce_warehouse_ledger WHERE receipt_id=? ORDER BY ledger_id",original));
        assertEquals("4",jdbc.queryForObject("SELECT receipt_type FROM head_receipt WHERE systematic_receipt=?",String.class,receipt));
        assertEquals(original,jdbc.queryForObject("SELECT original_receipt FROM head_receipt WHERE systematic_receipt=?",String.class,receipt));
        assertEquals(receipt,accept(id).get("returnReceiptId"));assertEquals(2L,fixture.count("head_receipt"));
        assertEquals("REFUNDED",refund(id,"refund","success").get("status"));assertEquals(3,service.detail(order,OWNER).get("orderStatus"));
        assertEquals(10L,inventory().get("bookStock"));assertEquals("RETURNED",jdbc.queryForObject("SELECT status FROM commerce_stock_hold",String.class));
        assertEquals(1L,n("SELECT COUNT(*) FROM commerce_stock_ledger WHERE event_type='RETURN_ACCEPT'"));
        assertEquals(4,((List<?>)after.detail(id,OWNER).get("events")).size());
        DetailReceipt identifier=new DetailReceipt();identifier.setSystematicReceipt(receipt);
        rejected(409,()->tx(()->fixture.warehouse.delete(Collections.singletonList(identifier))));
        identifier.setSystematicReceipt(original);rejected(409,()->tx(()->fixture.warehouse.delete(Collections.singletonList(identifier))));healthy();
    }

    @Test public void failedRefundIsAuditedWithoutMutationAndCannotChangeScenarioOnSameKey() {
        String order=paid("failure",2),id=apply(order,"request");review(id);
        assertEquals("FAILED",refund(id,"attempt1","failure").get("refundOutcome"));
        assertEquals("APPROVED",after.detail(id,OWNER).get("status"));assertEquals(2L,inventory().get("reservedStock"));
        refund(id,"attempt1","failure");rejected(409,()->refund(id,"attempt1","success"));assertEquals(1L,fixture.count("commerce_refund_attempt"));
        refund(id,"attempt2","success");assertEquals(2L,fixture.count("commerce_refund_attempt"));assertEquals(0L,inventory().get("reservedStock"));healthy();
    }

    @Test public void rejectedRequestReleasesItsClaimAndPermitsAnotherApplication() {
        String order=paid("reject",2),id=apply(order,"request");
        Map<String,Object> reject=map("requestKey","reject","decision","REJECT","note","不符合退货条件");
        assertEquals("REJECTED",tx(()->after.review(id,reject,1)).get("status"));
        tx(()->after.review(id,reject,1));assertEquals(2L,fixture.count("commerce_after_sales_event"));
        rejected(409,()->review(id));String retry=apply(order,"new-request");assertNotEquals(id,retry);
        tx(()->after.review(retry,map("requestKey","reject-again","decision","REJECT"),1));
        assertEquals(2,tx(()->service.ship(order,map(),1)).get("orderStatus"));
        assertEquals(3,tx(()->service.receive(order,OWNER)).get("orderStatus"));assertEquals(8L,inventory().get("bookStock"));healthy();
    }

    @Test public void wholeOrderOwnerShopAndMerchantRoleBoundariesAreEnforced() {
        String unpaid=fixture.id(fixture.create("unpaid",1));rejected(409,()->apply(unpaid,"unpaid-request"));
        String order=paid("boundary",2);
        rejected(404,()->tx(()->after.apply(order,map("ownerId",FOREIGN_OWNER,"requestKey","foreign","reason","退货"))));
        rejected(409,()->tx(()->after.apply(order,map("ownerId",OWNER,"requestKey","over-quantity","reason","退货","items",Collections.singletonList(map("productId",1,"quantity",3))))));
        assertEquals(0L,fixture.count("commerce_after_sales"));
        String id=apply(order,"request");assertEquals(id,apply(order,"request"));assertEquals(1L,fixture.count("commerce_after_sales_event"));
        rejected(404,()->after.detail(id,FOREIGN_OWNER));assertEquals(0L,after.list(FOREIGN_OWNER,null,1,20).get("total"));
        assertEquals(1L,after.list(OWNER,"REQUESTED",1,20).get("total"));rejected(400,()->after.list(OWNER,"invalid",1,20));
        rejected(403,()->tx(()->after.review(id,map("requestKey","viewer","decision","APPROVE"),2)));
        rejected(403,()->tx(()->after.review(id,map("requestKey","outsider","decision","APPROVE"),3)));
        CommerceShopContext.set("another-shop");try {rejected(404,()->after.detail(id,OWNER));assertEquals(0L,after.list(OWNER,null,1,20).get("total"));}
        finally {CommerceShopContext.clear();}assertEquals(3L,inventory().get("reservedStock"));healthy();
    }

    @Test public void unshippedActivityRefundRestoresQuotaExactlyOnceAndAllowsRepurchase() {
        tx(()->service.createActivity(map("activityId","sale","productId",1,"capacity",3,"perOwnerLimit",1,"price",99,
                "startsAt",LocalDateTime.now().minusMinutes(1).toString(),"endsAt",LocalDateTime.now().plusMinutes(10).toString())));
        String order=fixture.id(tx(()->service.seckill("sale",map("ownerId",OWNER,"requestKey","sale-order","shippingAddress",address()))));
        tx(()->service.pay(order,map("ownerId",OWNER,"paymentRequestId","sale-pay","scenario","success")));
        String id=apply(order,"request");review(id);refund(id,"refund","success");refund(id,"refund","success");
        assertEquals(3L,service.activities().get(0).get("remaining"));assertEquals(3L,inventory().get("activityStock"));assertEquals(7L,inventory().get("availableStock"));
        assertEquals(0L,service.activityParticipation(OWNER).get(0).get("participationCount"));assertNull(service.activityParticipation(OWNER).get(0).get("myOrderId"));
        tx(()->service.seckill("sale",map("ownerId",OWNER,"requestKey","repurchase","shippingAddress",address())));healthy();
    }

    @Test public void activityEndedBeforeRefundRestoresOrdinaryAvailabilityWithoutDoubleExpiryRelease() {
        tx(()->service.createActivity(map("activityId","ending","productId",1,"capacity",3,"perOwnerLimit",1,"price",99,
                "startsAt",LocalDateTime.now().minusMinutes(1).toString(),"endsAt",LocalDateTime.now().plusMinutes(10).toString())));
        String order=fixture.id(tx(()->service.seckill("ending",map("ownerId",OWNER,"requestKey","sale-order","shippingAddress",address()))));
        tx(()->service.pay(order,map("ownerId",OWNER,"paymentRequestId","sale-pay","scenario","success")));
        String id=apply(order,"request");review(id);jdbc.update("UPDATE commerce_activity SET ends_at=TIMESTAMPADD(SECOND,-2,CURRENT_TIMESTAMP)");
        tx(()->{service.expireActivities();return null;});refund(id,"refund","success");
        tx(()->{service.expireActivities();return null;});
        assertEquals(0L,inventory().get("activityStock"));assertEquals(10L,inventory().get("availableStock"));healthy();
    }

    @Test public void missingOriginalWarehouseTraceRejectsReturnWithoutInventingStock() {
        String order=paid("missing-trace",2);tx(()->service.ship(order,map(),1));String id=apply(order,"request");review(id);
        jdbc.update("DELETE FROM commerce_warehouse_ledger WHERE operation='DISPATCH'");
        rejected(409,()->accept(id));assertEquals(8L,inventory().get("bookStock"));assertEquals("AWAITING_RETURN",after.detail(id,OWNER).get("status"));
        assertEquals(0L,n("SELECT COUNT(*) FROM commerce_stock_ledger WHERE event_type='RETURN_ACCEPT'"));assertEquals(1L,fixture.count("head_receipt"));
    }

    @Test public void lateReceiptFailureRollsBackStockHoldPhysicalRowsAndBothJournals() {
        String order=paid("rollback-return",2);tx(()->service.ship(order,map(),1));String id=apply(order,"request");review(id);
        jdbc.execute("ALTER TABLE head_receipt ADD CONSTRAINT reject_sales_return CHECK (receipt_type<>'4')");
        try {accept(id);fail("Expected receipt insertion failure");}catch(DataIntegrityViolationException expected) { }
        assertEquals("AWAITING_RETURN",after.detail(id,OWNER).get("status"));assertEquals(8L,inventory().get("bookStock"));assertEquals(8L,n("SELECT inventory_qty FROM product WHERE product_id=1"));
        assertEquals(8L,n("SELECT plan_quantity FROM inventory_product WHERE inventory_id=1"));assertEquals("DISPATCHED",jdbc.queryForObject("SELECT status FROM commerce_stock_hold",String.class));
        assertEquals(0L,n("SELECT COUNT(*) FROM commerce_stock_ledger WHERE event_type='RETURN_ACCEPT'"));assertEquals(0L,n("SELECT COUNT(*) FROM commerce_warehouse_ledger WHERE operation='RETURN_ACCEPT'"));
        assertEquals(1L,fixture.count("head_receipt"));assertEquals(1L,fixture.count("detail_receipt"));healthy();
        jdbc.execute("ALTER TABLE head_receipt DROP CONSTRAINT reject_sales_return");assertEquals("RETURN_RECEIVED",accept(id).get("status"));healthy();
    }

    @Test public void nullableOriginalWarehouseBalanceCannotSilentlyLoseAcceptedReturn() {
        jdbc.update("UPDATE inventory_product SET plan_quantity=NULL WHERE inventory_id=1");
        jdbc.update("INSERT INTO inventory_product(inventory_id,product_id,warehouse_id,plan_quantity) VALUES (2,1,1,10)");
        String order=paid("null-balance",2);tx(()->service.ship(order,map(),1));String id=apply(order,"request");review(id);
        rejected(409,()->accept(id));assertEquals(8L,inventory().get("bookStock"));assertEquals(8L,n("SELECT plan_quantity FROM inventory_product WHERE inventory_id=2"));
        assertEquals("AWAITING_RETURN",after.detail(id,OWNER).get("status"));assertEquals(0L,n("SELECT COUNT(*) FROM commerce_stock_ledger WHERE event_type='RETURN_ACCEPT'"));healthy();
    }

    @Test public void existingErpReceiptNumberCannotBeOverwrittenByDispatch() {
        String order=paid("receipt-conflict",2),receipt="CX"+order.substring(2);
        jdbc.update("INSERT INTO head_receipt(systematic_receipt,receipt_type,receipt_status,receipt_notes) VALUES (?,'1','1','Existing ERP document')",receipt);
        rejected(409,()->tx(()->service.ship(order,map(),1)));
        assertEquals("Existing ERP document",jdbc.queryForObject("SELECT receipt_notes FROM head_receipt WHERE systematic_receipt=?",String.class,receipt));
        assertEquals(1L,fixture.count("head_receipt"));assertEquals(10L,inventory().get("bookStock"));assertEquals(2L,inventory().get("reservedStock"));healthy();
    }

    @Test public void concurrentAcceptanceAndRefundHaveOnePhysicalReturnAndOneSuccessfulRefund() throws Exception {
        String order=paid("concurrent-return",2);tx(()->service.ship(order,map(),1));String id=apply(order,"request");review(id);
        ExecutorService pool=Executors.newFixedThreadPool(6);
        try {
            List<Future<Map<String,Object>>> tasks=new ArrayList<>();
            for(int i=0;i<6;i++)tasks.add(pool.submit(()->accept(id)));
            for(Future<Map<String,Object>> task:tasks)assertEquals("RETURN_RECEIVED",task.get(15,TimeUnit.SECONDS).get("status"));
            tasks.clear();for(int i=0;i<6;i++)tasks.add(pool.submit(()->refund(id,"refund","success")));
            for(Future<Map<String,Object>> task:tasks)assertEquals("REFUNDED",task.get(15,TimeUnit.SECONDS).get("status"));
        } finally {pool.shutdownNow();}
        assertEquals(10L,inventory().get("bookStock"));assertEquals(1L,n("SELECT COUNT(*) FROM commerce_stock_ledger WHERE event_type='RETURN_ACCEPT'"));
        assertEquals(1L,n("SELECT COUNT(*) FROM commerce_warehouse_ledger WHERE operation='RETURN_ACCEPT'"));assertEquals(1L,fixture.count("commerce_refund_attempt"));healthy();
    }

    @Test public void applicationRacingShipmentChoosesConsistentReturnPath() throws Exception {
        String order=paid("ship-race",2);ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            Future<String> request=pool.submit(()->{start.await();return apply(order,"request");});
            Future<Boolean> shipment=pool.submit(()->{start.await();try {tx(()->service.ship(order,map(),1));return true;}catch(ServiceException ex){assertEquals(Integer.valueOf(409),ex.getCode());return false;}});
            start.countDown();String id=request.get(15,TimeUnit.SECONDS);boolean shipped=shipment.get(15,TimeUnit.SECONDS);
            assertEquals(shipped,after.detail(id,OWNER).get("returnRequired"));assertEquals(shipped?8L:10L,inventory().get("bookStock"));
            review(id);if(shipped)accept(id);refund(id,"refund","success");assertEquals(10L,inventory().get("bookStock"));healthy();
        }finally{pool.shutdownNow();}
    }

    @Test public void damagedReturnRestoresPhysicalStockWithoutRestoringAvailabilityAndRefundsOnce() {
        String order=paid("damaged-return",2);tx(()->service.ship(order,map(),1));String id=apply(order,"request");review(id);
        assertNull(after.detail(id,OWNER).get("returnCondition"));
        Map<String,Object> accepted=tx(()->after.acceptReturn(id,map("requestKey","damaged-accept","condition","DAMAGED"),1));
        assertEquals("RETURN_RECEIVED",accepted.get("status"));assertEquals("DAMAGED",accepted.get("returnCondition"));
        assertEquals(10L,inventory().get("bookStock"));assertEquals(2L,inventory().get("unavailableStock"));assertEquals(8L,inventory().get("availableStock"));
        assertEquals(2L,n("SELECT damaged FROM commerce_warehouse_condition WHERE product_id=1 AND warehouse_id=1"));
        assertEquals(0L,n("SELECT quality_hold FROM commerce_warehouse_condition WHERE product_id=1 AND warehouse_id=1"));
        assertEquals(2L,n("SELECT SUM(quantity) FROM commerce_condition_event WHERE from_state='RETURNED' AND to_state='DAMAGED'"));
        assertEquals(2L,n("SELECT SUM(delta_unavailable) FROM commerce_stock_ledger WHERE event_type='CONDITION_CHANGE'"));
        assertEquals(accepted.get("returnReceiptId"),tx(()->after.acceptReturn(id,map("requestKey","damaged-accept","condition","DAMAGED"),1)).get("returnReceiptId"));
        rejected(409,()->tx(()->after.acceptReturn(id,map("requestKey","damaged-accept","condition","SELLABLE"),1)));
        rejected(409,()->tx(()->after.acceptReturn(id,map("requestKey","changed-key","condition","DAMAGED"),1)));
        Map<String,Object> refunded=refund(id,"refund","success");assertEquals("REFUNDED",refunded.get("status"));assertEquals("DAMAGED",refunded.get("returnCondition"));
        assertEquals(new BigDecimal("258.00"),refunded.get("refundedAmount"));refund(id,"refund","success");
        assertEquals(2L,n("SELECT returned FROM commerce_fulfillment_line WHERE order_id=?",order));
        assertEquals(2L,n("SELECT refunded FROM commerce_fulfillment_line WHERE order_id=?",order));
        assertEquals(1L,fixture.count("commerce_condition_event"));assertEquals(1L,fixture.count("commerce_refund_attempt"));
        assertEquals("DAMAGED",((Map<?,?>)((List<?>)service.detail(order,OWNER).get("afterSalesCases")).get(0)).get("returnCondition"));
        assertEquals(8L,inventory().get("availableStock"));healthy();
    }

    @Test public void qualityReturnBlocksEachExactOriginalWarehouseAndRetainsBeforeAfterEvidence() {
        jdbc.update("UPDATE inventory_product SET plan_quantity=1 WHERE inventory_id=1");
        jdbc.update("INSERT INTO inventory_product(inventory_id,product_id,warehouse_id,plan_quantity) VALUES (2,1,2,9)");
        String order=paid("quality-return",3);tx(()->service.ship(order,map(),1));String id=apply(order,"request");review(id);
        tx(()->after.acceptReturn(id,map("requestKey","quality-accept","condition","QUALITY_HOLD"),1));
        assertEquals(1L,n("SELECT quality_hold FROM commerce_warehouse_condition WHERE product_id=1 AND warehouse_id=1"));
        assertEquals(2L,n("SELECT quality_hold FROM commerce_warehouse_condition WHERE product_id=1 AND warehouse_id=2"));
        assertEquals(1L,n("SELECT plan_quantity FROM inventory_product WHERE inventory_id=1"));assertEquals(9L,n("SELECT plan_quantity FROM inventory_product WHERE inventory_id=2"));
        assertEquals(3L,inventory().get("unavailableStock"));assertEquals(7L,inventory().get("availableStock"));
        assertEquals(2L,n("SELECT COUNT(*) FROM commerce_condition_event WHERE from_state='RETURNED' AND to_state='QUALITY_HOLD' AND before_quality=0 AND after_quality=quantity AND before_damaged=after_damaged"));
        refund(id,"refund","success");assertEquals(new BigDecimal("387.00"),service.detail(order,OWNER).get("refundedAmount"));healthy();
    }

    @Test public void lateConditionEvidenceFailureRollsBackPhysicalReturnUnavailableAndCaseTogether() {
        String order=paid("condition-rollback",2);tx(()->service.ship(order,map(),1));String id=apply(order,"request");review(id);
        jdbc.execute("ALTER TABLE commerce_condition_event ADD CONSTRAINT reject_damaged_condition CHECK(to_state<>'DAMAGED')");
        try {tx(()->after.acceptReturn(id,map("requestKey","damaged-accept","condition","DAMAGED"),1));fail("Expected condition evidence insertion failure");}
        catch(DataIntegrityViolationException expected) { }
        assertEquals("AWAITING_RETURN",after.detail(id,OWNER).get("status"));assertNull(after.detail(id,OWNER).get("returnCondition"));
        assertEquals(8L,inventory().get("bookStock"));assertEquals(0L,inventory().get("unavailableStock"));assertEquals(8L,inventory().get("availableStock"));
        assertEquals(8L,n("SELECT inventory_qty FROM product WHERE product_id=1"));assertEquals(8L,n("SELECT plan_quantity FROM inventory_product WHERE inventory_id=1"));
        assertEquals(0L,fixture.count("commerce_condition_event"));assertEquals(0L,fixture.count("commerce_warehouse_condition"));assertEquals(0L,fixture.count("commerce_return_allocation"));
        assertEquals(0L,n("SELECT COUNT(*) FROM commerce_stock_ledger WHERE event_type IN('RETURN_ACCEPT','CONDITION_CHANGE')"));healthy();
        jdbc.execute("ALTER TABLE commerce_condition_event DROP CONSTRAINT reject_damaged_condition");
        tx(()->after.acceptReturn(id,map("requestKey","damaged-accept","condition","DAMAGED"),1));healthy();
    }

    @Test public void historicalAcceptedReturnsMigrateToSellableIdempotentlyWithoutBalanceChanges() {
        String order=paid("return-condition-migration",2);tx(()->service.ship(order,map(),1));String id=apply(order,"request");review(id);accept(id);refund(id,"refund","success");
        jdbc.update("UPDATE commerce_after_sales_case SET return_condition=NULL WHERE after_sales_id=?",id);
        List<Map<String,Object>> before=service.inventory(),ledger=jdbc.queryForList("SELECT * FROM commerce_stock_ledger ORDER BY ledger_id");
        BigDecimal amount=(BigDecimal)after.detail(id,OWNER).get("refundedAmount");
        tx(()->{CommercePartialSupport.migrate(jdbc);return null;});tx(()->{CommercePartialSupport.migrate(jdbc);return null;});
        assertEquals("SELLABLE",after.detail(id,OWNER).get("returnCondition"));assertEquals(amount,after.detail(id,OWNER).get("refundedAmount"));
        assertEquals(before,service.inventory());assertEquals(ledger,jdbc.queryForList("SELECT * FROM commerce_stock_ledger ORDER BY ledger_id"));
        assertEquals("SELLABLE",accept(id).get("returnCondition"));healthy();
    }

    @Test public void concurrentDamagedAndQualityCasesCannotExposeReturnedGoodsToNewOrders() throws Exception {
        String order=paid("mixed-return-race",4);tx(()->service.ship(order,map(),1));
        String damaged=String.valueOf(tx(()->after.apply(order,map("ownerId",OWNER,"requestKey","damaged-case","reason","损坏样例","kind","RETURN_REFUND","items",Collections.singletonList(map("productId",1,"quantity",2))))).get("afterSalesId"));
        String quality=String.valueOf(tx(()->after.apply(order,map("ownerId",OWNER,"requestKey","quality-case","reason","质检样例","kind","RETURN_REFUND","items",Collections.singletonList(map("productId",1,"quantity",2))))).get("afterSalesId"));
        review(damaged);review(quality);ExecutorService pool=Executors.newFixedThreadPool(6);CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<?>> tasks=new ArrayList<>();
            for(int i=0;i<2;i++){
                tasks.add(pool.submit(()->{start.await();return tx(()->after.acceptReturn(damaged,map("requestKey","accept-damaged","condition","DAMAGED"),1));}));
                tasks.add(pool.submit(()->{start.await();return tx(()->after.acceptReturn(quality,map("requestKey","accept-quality","condition","QUALITY_HOLD"),1));}));
                tasks.add(pool.submit(()->{start.await();rejected(409,()->fixture.create("oversell-return",7));return null;}));
            }
            start.countDown();for(Future<?> task:tasks)task.get(15,TimeUnit.SECONDS);
            tasks.clear();for(int i=0;i<2;i++)for(String id:Arrays.asList(damaged,quality))tasks.add(pool.submit(()->refund(id,"refund","success")));
            for(Future<?> task:tasks)task.get(15,TimeUnit.SECONDS);
        } finally {pool.shutdownNow();}
        assertEquals(10L,inventory().get("bookStock"));assertEquals(4L,inventory().get("unavailableStock"));assertEquals(6L,inventory().get("availableStock"));
        assertEquals(2L,n("SELECT damaged FROM commerce_warehouse_condition"));assertEquals(2L,n("SELECT quality_hold FROM commerce_warehouse_condition"));
        assertEquals(1L,fixture.count("commerce_order"));assertEquals(2L,fixture.count("commerce_condition_event"));assertEquals(2L,fixture.count("commerce_refund_attempt"));
        assertEquals(new BigDecimal("516.00"),service.detail(order,OWNER).get("refundedAmount"));assertEquals(4L,n("SELECT returned FROM commerce_fulfillment_line WHERE order_id=?",order));healthy();
    }
}

package com.ruoyi.system.service;

import com.ruoyi.common.core.domain.entity.DetailReceipt;
import com.ruoyi.common.core.domain.entity.ReceiptFrom;
import com.ruoyi.common.core.tenant.CommerceShopContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.junit.Assert.*;

/** Isolated SQL examples: allocation, partial dispatch, release, legacy upgrade and contention. */
public class CommerceWarehouseAllocationTest {
    private CommerceServiceTest fixture;
    private CommerceWarehouseAllocationService warehouses;
    private CommerceInventoryService stock;
    private JdbcTemplate jdbc;
    @Before public void setup()throws Exception {
        fixture=new CommerceServiceTest();fixture.setup();jdbc=fixture.jdbc;
        warehouses=new CommerceWarehouseAllocationService(jdbc.getDataSource());stock=new CommerceInventoryService(jdbc.getDataSource());
        jdbc.update("UPDATE inventory_product SET plan_quantity=3 WHERE inventory_id=1");
        jdbc.update("INSERT INTO inventory_product(inventory_id,product_id,warehouse_id,plan_quantity) VALUES (2,1,2,7)");
    }
    @After public void clear(){CommerceShopContext.clear();}
    private <T>T tx(Supplier<T> work){return fixture.transaction(work);}
    private Map<String,Object> map(Object...pairs){return CommerceServiceTest.map(pairs);}
    private String create(String key,int quantity){return fixture.id(fixture.create(key,quantity));}
    private void pay(String order){tx(()->fixture.service.pay(order,map("ownerId",CommerceServiceTest.OWNER,"paymentRequestId","pay-"+order,"scenario","success")));}
    private Map<String,Object> ship(String order,String key,long warehouse,int quantity){return tx(()->fixture.service.ship(order,map("requestKey",key,"warehouseId",warehouse,"items",Collections.singletonList(map("productId",1,"quantity",quantity))),1));}
    private long held(String order,long warehouse){return jdbc.queryForObject("SELECT COALESCE(SUM(quantity-shipped-released),0) FROM commerce_warehouse_allocation WHERE order_id=? AND warehouse_id=?",Long.class,order,warehouse);}
    private long physical(long warehouse){return jdbc.queryForObject("SELECT SUM(plan_quantity) FROM inventory_product WHERE warehouse_id=?",Long.class,warehouse);}

    /** Tests only: the ERP guard supplies these locked, post-move capacities in production. */
    private void move(Map<Long,Long> deltas,String reference) {
        tx(()->{
            jdbc.queryForList("SELECT product_id FROM product WHERE product_id=1 FOR UPDATE");
            jdbc.queryForList("SELECT inventory_id FROM inventory_product WHERE product_id=1 ORDER BY warehouse_id,inventory_id FOR UPDATE");
            Map<Long,Long> after=new TreeMap<>();SortedSet<Long> incoming=new TreeSet<>();
            for(Map.Entry<Long,Long> delta:deltas.entrySet()) {
                long blocked=jdbc.queryForObject("SELECT COALESCE(SUM(quality_hold+damaged),0) FROM commerce_warehouse_condition WHERE product_id=1 AND warehouse_id=?",Long.class,delta.getKey());
                after.put(delta.getKey(),physical(delta.getKey())+delta.getValue()-blocked);
                if(delta.getValue()>0)incoming.add(delta.getKey());
            }
            warehouses.followMove(1,after,incoming,reference);
            for(Map.Entry<Long,Long> delta:deltas.entrySet())jdbc.update("UPDATE inventory_product SET plan_quantity=plan_quantity+? WHERE product_id=1 AND warehouse_id=?",delta.getValue(),delta.getKey());
            return null;
        });
    }
    private Map<Long,Long> deltas(long...pairs){Map<Long,Long> result=new TreeMap<>();for(int i=0;i<pairs.length;i+=2)result.put(pairs[i],pairs[i+1]);return result;}
    private long eventCount(String type){return jdbc.queryForObject("SELECT COUNT(*) FROM commerce_warehouse_allocation_event WHERE event_type=?",Long.class,type);}

    @Test public void transferKeepsOtherOrdersAndRecordsBalancedSourceTargetEventsWithReceiptReference() {
        String first=create("move-first",5),second=create("move-second",3);long globalEvents=fixture.count("commerce_stock_ledger");
        move(deltas(1,-2,2,2),"SAVE:ERP_MOVE");
        assertEquals(1,held(first,1));assertEquals(4,held(first,2));assertEquals(3,held(second,2));
        assertEquals(1,physical(1));assertEquals(9,physical(2));assertEquals(globalEvents,fixture.count("commerce_stock_ledger"));
        assertEquals(1,eventCount("MOVE_OUT"));assertEquals(1,eventCount("MOVE_IN"));
        List<Map<String,Object>> audit=warehouses.allocationEvents(first);Map<String,Object> out=null,in=null;
        for(Map<String,Object> row:audit){if("MOVE_OUT".equals(row.get("eventType")))out=row;if("MOVE_IN".equals(row.get("eventType")))in=row;}
        assertNotNull(out);assertNotNull(in);assertEquals(out.get("eventKey"),in.get("eventKey"));assertTrue(String.valueOf(out.get("eventKey")).startsWith("MOVE:SAVE:ERP_MOVE:"));
        assertEquals(1L,out.get("warehouseId"));assertEquals(2L,in.get("warehouseId"));assertEquals(out.get("quantity"),in.get("quantity"));
        assertEquals(true,fixture.service.reconcile().get("healthy"));
        // ERP replay has no net warehouse changes, so the existing post-move capacities create no second event.
        tx(()->{jdbc.queryForList("SELECT product_id FROM product WHERE product_id=1 FOR UPDATE");warehouses.followMove(1,deltas(1,1,2,9),new TreeSet<>(Arrays.asList(2L)),"SAVE:ERP_MOVE");return null;});
        assertEquals(1,eventCount("MOVE_OUT"));assertEquals(1,eventCount("MOVE_IN"));
    }

    @Test public void transferMovesOnlyUnshippedCommitmentsAndPreservesOriginalDispatchWarehouse() {
        String order=create("move-after-ship",5);pay(order);ship(order,"before-move",1,2);
        move(deltas(1,-1,2,1),"SAVE:PARTIAL_MOVE");
        assertEquals(0,held(order,1));assertEquals(3,held(order,2));
        assertEquals(2,jdbc.queryForObject("SELECT shipped FROM commerce_warehouse_allocation WHERE order_id=? AND warehouse_id=1",Long.class,order).longValue());
        assertEquals(2,jdbc.queryForObject("SELECT quantity FROM commerce_warehouse_allocation WHERE order_id=? AND warehouse_id=1",Long.class,order).longValue());
        fixture.rejected(409,()->ship(order,"wrong-old-warehouse",1,1));ship(order,"after-move",2,3);
        assertEquals(2,jdbc.queryForObject("SELECT SUM(plan_quantity) FROM detail_receipt WHERE retrieval_id=1",Long.class).longValue());
        assertEquals(3,jdbc.queryForObject("SELECT SUM(plan_quantity) FROM detail_receipt WHERE retrieval_id=2",Long.class).longValue());
        assertEquals(true,fixture.service.reconcile().get("healthy"));
    }

    @Test public void multipleSourcesAndTargetsPreserveEveryOrderQuantityAndPreferredCapacity() {
        // Isolated fixture: four warehouses with three sellable units each.
        jdbc.update("UPDATE inventory_product SET plan_quantity=3 WHERE inventory_id=2");
        jdbc.update("INSERT INTO inventory_product(inventory_id,product_id,warehouse_id,plan_quantity) VALUES(3,1,3,3),(4,1,4,3)");jdbc.update("UPDATE product SET inventory_qty=12 WHERE product_id=1");
        String first=create("many-first",7),second=create("many-second",3);
        move(deltas(1,-2,2,-2,3,2,4,2),"SAVE:MULTI_MOVE");
        assertEquals(1,warehouses.pending(1,1));assertEquals(1,warehouses.pending(1,2));assertTrue(warehouses.pending(1,3)<=5);assertTrue(warehouses.pending(1,4)<=5);
        assertEquals(7,jdbc.queryForObject("SELECT SUM(quantity-shipped-released) FROM commerce_warehouse_allocation WHERE order_id=?",Long.class,first).longValue());
        assertEquals(3,jdbc.queryForObject("SELECT SUM(quantity-shipped-released) FROM commerce_warehouse_allocation WHERE order_id=?",Long.class,second).longValue());
        assertEquals(4,jdbc.queryForObject("SELECT SUM(quantity) FROM commerce_warehouse_allocation_event WHERE event_type='MOVE_OUT'",Long.class).longValue());
        assertEquals(4,jdbc.queryForObject("SELECT SUM(quantity) FROM commerce_warehouse_allocation_event WHERE event_type='MOVE_IN'",Long.class).longValue());
        assertEquals(true,fixture.service.reconcile().get("healthy"));
    }

    @Test public void inverseTransferRebalancesOnlyWhatNoLongerFitsAndPreservesCommitments() {
        String first=create("reverse-first",5),second=create("reverse-second",3);
        move(deltas(1,-3,2,3),"SAVE:REVERSIBLE");assertEquals(0,warehouses.pending(1,1));assertEquals(8,warehouses.pending(1,2));
        move(deltas(1,3,2,-3),"DELETE:REVERSIBLE");assertEquals(1,warehouses.pending(1,1));assertEquals(7,warehouses.pending(1,2));
        assertEquals(3,physical(1));assertEquals(7,physical(2));
        assertEquals(5,jdbc.queryForObject("SELECT SUM(quantity-shipped-released) FROM commerce_warehouse_allocation WHERE order_id=?",Long.class,first).longValue());
        assertEquals(3,jdbc.queryForObject("SELECT SUM(quantity-shipped-released) FROM commerce_warehouse_allocation WHERE order_id=?",Long.class,second).longValue());
        assertEquals(true,fixture.service.reconcile().get("healthy"));
    }

    @Test public void transferDoesNotMoveQualityOrDamagedStockAndRefusesInvalidPostMoveCapacity() {
        tx(()->{stock.ensureStock(1);stock.adjustUnavailable(1,"move-quality",2,"质检与损坏隔离");return null;});
        jdbc.update("INSERT INTO commerce_warehouse_condition VALUES(1,1,1,1)");String order=create("safe-move",5);
        assertEquals(1,held(order,1));assertEquals(4,held(order,2));
        fixture.rejected(409,()->move(deltas(1,-2,2,2),"SAVE:INVALID_QUALITY_MOVE"));
        assertEquals(3,physical(1));assertEquals(7,physical(2));assertEquals(0,eventCount("MOVE_IN"));
        move(deltas(1,-1,2,1),"SAVE:SELLABLE_MOVE");assertEquals(2,physical(1));assertEquals(8,physical(2));assertEquals(0,held(order,1));assertEquals(5,held(order,2));
        assertEquals(1,jdbc.queryForObject("SELECT quality_hold FROM commerce_warehouse_condition WHERE warehouse_id=1",Long.class).longValue());
        assertEquals(1,jdbc.queryForObject("SELECT damaged FROM commerce_warehouse_condition WHERE warehouse_id=1",Long.class).longValue());
        assertEquals(true,fixture.service.reconcile().get("healthy"));
    }

    @Test public void longBatchReceiptReferenceFitsEventKeyAndRetainsCorrelationHash() {
        String order=create("batch-ref",5);String reference="DELETE:"+String.join(",",Collections.nCopies(100,"RECEIPT_123456789012345678901234"));
        move(deltas(1,-2,2,2),reference);
        for(Map<String,Object> row:warehouses.allocationEvents(order))if(String.valueOf(row.get("eventType")).startsWith("MOVE_")) {
            String event=String.valueOf(row.get("eventKey"));assertTrue(event.length()<=160);assertTrue(event.startsWith("MOVE:DELETE:RECEIPT_"));
        }
    }

    @Test public void transferAndCheckoutSerializeOnProductWithoutOverdrawingAnyWarehouse()throws Exception {
        String original=create("move-race-original",5);ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            Future<?> transfer=pool.submit(()->{start.await();move(deltas(1,-2,2,2),"SAVE:CONCURRENT_MOVE");return null;});
            Future<String> checkout=pool.submit(()->{start.await();return create("move-race-checkout",5);});start.countDown();transfer.get(15,TimeUnit.SECONDS);String placed=checkout.get(15,TimeUnit.SECONDS);
            assertEquals(1,warehouses.pending(1,1));assertEquals(9,warehouses.pending(1,2));
            assertEquals(5,jdbc.queryForObject("SELECT SUM(quantity-shipped-released) FROM commerce_warehouse_allocation WHERE order_id=?",Long.class,original).longValue());
            assertEquals(5,jdbc.queryForObject("SELECT SUM(quantity-shipped-released) FROM commerce_warehouse_allocation WHERE order_id=?",Long.class,placed).longValue());
            assertEquals(true,fixture.service.reconcile().get("healthy"));
        }finally{pool.shutdownNow();}
    }

    @Test public void twoOrdersHaveDistinctCommitmentsAndSpecificWarehouseCannotStealTheOtherOrder() {
        String first=create("first",5),second=create("second",3);pay(first);pay(second);
        assertEquals(3,held(first,1));assertEquals(2,held(first,2));assertEquals(3,held(second,2));
        fixture.rejected(409,()->ship(first,"over-own",2,3));assertEquals(7,physical(2));
        ship(first,"second-warehouse",2,2);assertEquals(5,physical(2));assertEquals(3,held(second,2));
        ship(first,"first-warehouse",1,3);assertEquals(0,physical(1));assertEquals(2,fixture.service.detail(first,null).get("status"));
        assertEquals(true,fixture.service.reconcile().get("healthy"));
    }

    @Test public void shipmentIdempotenceIncludesWarehouseAndPhysicalJournalPostsOnce() {
        String order=create("replay",5);pay(order);ship(order,"batch",2,2);ship(order,"batch",2,2);
        fixture.rejected(409,()->ship(order,"batch",1,2));fixture.rejected(409,()->ship(order,"batch",2,1));
        assertEquals(5,physical(2));assertEquals(1,fixture.count("commerce_shipment"));assertEquals(1,fixture.count("commerce_warehouse_ledger"));
    }

    @Test public void migratedLegacyShipmentHashAllowsExactRetryAndRejectsNewWarehouseOrQuantity() {
        String order=create("legacy-shipment",2);pay(order);tx(()->fixture.service.ship(order,map(),1));
        jdbc.update("UPDATE commerce_shipment SET request_hash=? WHERE order_id=?",String.join("",Collections.nCopies(64,"0")),order);
        tx(()->fixture.service.ship(order,map(),1));
        fixture.rejected(409,()->tx(()->fixture.service.ship(order,map("warehouseId",1),1)));
        fixture.rejected(409,()->tx(()->fixture.service.ship(order,map("items",Collections.singletonList(map("productId",1,"quantity",1))),1)));
        assertEquals(1,physical(1));assertEquals(1,fixture.count("commerce_shipment"));
    }

    @Test public void cancelAndTimeoutReleaseEveryWarehouseExactlyOnce() {
        String canceled=create("cancel",5);tx(()->fixture.service.cancel(canceled,CommerceServiceTest.OWNER));tx(()->fixture.service.cancel(canceled,CommerceServiceTest.OWNER));
        assertEquals(0,held(canceled,1));assertEquals(0,held(canceled,2));
        String expired=create("timeout",5);jdbc.update("UPDATE commerce_order SET expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE order_id=?",expired);
        assertTrue(tx(()->fixture.service.expireOrder(expired)));assertFalse(tx(()->fixture.service.expireOrder(expired)));
        assertEquals(0,held(expired,1));assertEquals(0,held(expired,2));assertEquals(3,physical(1));assertEquals(7,physical(2));
        assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM commerce_warehouse_allocation_event WHERE event_type='RELEASE'",Long.class).longValue());
        assertEquals(true,fixture.service.reconcile().get("healthy"));
    }

    @Test public void unshippedRefundReleasesOnlyRemainingCommitmentAndKeepsShipment() {
        String order=create("refund",5);pay(order);ship(order,"one-batch",1,2);
        String id=String.valueOf(tx(()->fixture.afterSales.apply(order,map("ownerId",CommerceServiceTest.OWNER,"requestKey","unshipped","kind","UNSHIPPED_REFUND","reason","仓占用释放验收","items",Collections.singletonList(map("productId",1,"quantity",3))))).get("afterSalesId"));
        tx(()->fixture.afterSales.review(id,map("requestKey","review","decision","APPROVE"),1));
        tx(()->fixture.afterSales.refund(id,map("requestKey","refund","scenario","success"),1));tx(()->fixture.afterSales.refund(id,map("requestKey","refund","scenario","success"),1));
        assertEquals(0,held(order,1));assertEquals(0,held(order,2));assertEquals(1,physical(1));assertEquals(7,physical(2));
        assertEquals(2,jdbc.queryForObject("SELECT SUM(shipped) FROM commerce_warehouse_allocation WHERE order_id=?",Long.class,order).longValue());
        assertEquals(true,fixture.service.reconcile().get("healthy"));
    }

    @Test public void erpOutboundAndConditionChangeCannotConsumeAllocatedWarehouseDespiteGlobalFreeStock() {
        create("protected",3);assertEquals(7L,fixture.service.inventory().get(0).get("availableStock"));
        ReceiptFrom receipt=new ReceiptFrom();receipt.setSystematicReceipt("ERP_BLOCKED");receipt.setReceiptStatus(2L);receipt.setReceiptType(3L);receipt.setRetrievalIds("1");
        DetailReceipt detail=new DetailReceipt();detail.setProductId("1");detail.setPlanQuantity("1");detail.setRetrievalId("1");receipt.setDetails(Collections.singletonList(detail));
        // Add warehouse masters required by the actual ERP guard; rejection occurs before mapper writes.
        jdbc.execute("CREATE TABLE warehouse(warehouse_id BIGINT PRIMARY KEY)");jdbc.update("INSERT INTO warehouse VALUES (1),(2)");
        fixture.rejected(409,()->tx(()->fixture.warehouse.save(receipt)));
        fixture.rejected(409,()->tx(()->{stock.validateWarehouseUnavailable(1,1,1);return null;}));
        assertEquals(3,physical(1));assertEquals(0,fixture.count("head_receipt"));
    }

    @Test public void unavailableStockIsExcludedAndWrongShopCannotReadCommitments() {
        tx(()->{stock.ensureStock(1);stock.adjustUnavailable(1,"quality",3,"待检隔离");return null;});
        jdbc.update("INSERT INTO commerce_warehouse_condition VALUES (1,1,2,1)");String order=create("quality",4);
        assertEquals(0,held(order,1));assertEquals(4,held(order,2));
        CommerceShopContext.set("foreign");fixture.rejected(404,()->warehouses.allocations(order));fixture.rejected(404,()->warehouses.warehouses(1));
    }

    @Test public void existingReservedOrdersUpgradeWithoutPhysicalOrGlobalLedgerChanges() {
        String order=create("legacy",5);jdbc.update("DELETE FROM commerce_warehouse_allocation_event");jdbc.update("DELETE FROM commerce_warehouse_allocation");long events=fixture.count("commerce_stock_ledger");
        tx(()->{warehouses.allocateExisting();return null;});tx(()->{warehouses.allocateExisting();return null;});
        assertEquals(3,held(order,1));assertEquals(2,held(order,2));assertEquals(events,fixture.count("commerce_stock_ledger"));assertEquals(3,physical(1));assertEquals(7,physical(2));
    }

    @Test public void concurrentOrdersNeverOverAllocateEitherWarehouse()throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            Future<String> left=pool.submit(()->{start.await();return create("left",5);});Future<String> right=pool.submit(()->{start.await();return create("right",5);});start.countDown();left.get(15,TimeUnit.SECONDS);right.get(15,TimeUnit.SECONDS);
            assertEquals(3,warehouses.pending(1,1));assertEquals(7,warehouses.pending(1,2));assertEquals(true,fixture.service.reconcile().get("healthy"));
        }finally{pool.shutdownNow();}
    }
}

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

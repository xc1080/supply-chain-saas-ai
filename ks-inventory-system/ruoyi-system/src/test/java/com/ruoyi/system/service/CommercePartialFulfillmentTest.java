package com.ruoyi.system.service;

import com.ruoyi.common.core.domain.entity.DetailReceipt;
import com.ruoyi.common.exception.ServiceException;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.junit.Assert.*;

public class CommercePartialFulfillmentTest {
    private CommerceServiceTest fixture;
    private CommerceService service;
    private CommerceAfterSalesService after;
    private CommerceInventoryService stock;
    private JdbcTemplate jdbc;
    private static final String OWNER=CommerceServiceTest.OWNER;
    @Before public void setup()throws Exception{fixture=new CommerceServiceTest();fixture.setup();service=fixture.service;after=fixture.afterSales;jdbc=fixture.jdbc;stock=new CommerceInventoryService(jdbc.getDataSource());}
    private <T>T tx(Supplier<T> task){return fixture.transaction(task);}
    private Map<String,Object> map(Object...pairs){return CommercePartialSupport.map(pairs);}
    private String paid(String key,int quantity){String order=fixture.id(fixture.create(key,quantity));tx(()->service.pay(order,map("ownerId",OWNER,"paymentRequestId",key+"-pay","scenario","success")));return order;}
    private Map<String,Object> ship(String order,String key,int quantity){return tx(()->service.ship(order,map("requestKey",key,"items",Collections.singletonList(map("productId",1,"quantity",quantity)),"carrier","演示物流"),1));}
    private String apply(String order,String key,String kind,int quantity){return String.valueOf(tx(()->after.apply(order,map("ownerId",OWNER,"requestKey",key,"kind",kind,"reason","部分履约学习","items",Collections.singletonList(map("productId",1,"quantity",quantity))))).get("afterSalesId"));}
    private void review(String id){tx(()->after.review(id,map("requestKey","review","decision","APPROVE"),1));}
    private void accept(String id){tx(()->after.acceptReturn(id,map("requestKey","accept","condition","SELLABLE"),1));}
    private void refund(String id){tx(()->after.refund(id,map("requestKey","refund","scenario","success"),1));}
    private Map<String,Object> item(String order){return (Map<String,Object>)((List<?>)service.detail(order,OWNER).get("items")).get(0);}
    private long n(String sql,Object...args){return jdbc.queryForObject(sql,Long.class,args);}
    private void healthy(){assertEquals(service.reconcile().toString(),true,service.reconcile().get("healthy"));}

    @Test public void partialDispatchRemainingRefundAndTwoIndependentReturnsKeepSeparateCumulativeAmounts(){
        String order=paid("partial",5);
        Map<String,Object> first=ship(order,"batch1",2);assertEquals(1,first.get("orderStatus"));assertEquals("PARTIALLY_SHIPPED",first.get("fulfillmentStatus"));
        assertEquals(2L,item(order).get("shippedQuantity"));assertEquals(3L,item(order).get("unshippedQuantity"));assertEquals(8L,service.inventory().get(0).get("bookStock"));
        ship(order,"batch2",1);ship(order,"batch2",1);fixture.rejected(409,()->ship(order,"batch2",2));assertEquals(2L,fixture.count("commerce_shipment"));
        String unsent=apply(order,"unshipped","UNSHIPPED_REFUND",1);assertEquals(1L,item(order).get("shippableQuantity"));fixture.rejected(409,()->ship(order,"too-many",2));ship(order,"batch3",1);
        review(unsent);refund(unsent);refund(unsent);assertEquals(2,service.detail(order,OWNER).get("orderStatus"));
        assertEquals(1L,item(order).get("cancelledQuantity"));assertEquals(4L,item(order).get("shippedQuantity"));assertEquals(0L,item(order).get("unshippedQuantity"));
        assertEquals(new BigDecimal("516.00"),service.detail(order,OWNER).get("shippedAmount"));assertEquals(new BigDecimal("129.00"),service.detail(order,OWNER).get("refundedAmount"));
        String returned1=apply(order,"return1","RETURN_REFUND",2),returned2=apply(order,"return2","RETURN_REFUND",2);
        fixture.rejected(409,()->apply(order,"over-return","RETURN_REFUND",1));review(returned1);review(returned2);
        accept(returned1);refund(returned1);accept(returned2);refund(returned2);accept(returned2);refund(returned2);
        Map<String,Object> finalOrder=service.detail(order,OWNER);assertEquals(new BigDecimal("645.00"),finalOrder.get("totalAmount"));assertEquals(new BigDecimal("516.00"),finalOrder.get("returnedAmount"));assertEquals(new BigDecimal("645.00"),finalOrder.get("refundedAmount"));assertEquals("RETURNED",finalOrder.get("fulfillmentStatus"));
        assertEquals(10L,service.inventory().get(0).get("bookStock"));assertEquals(0L,service.inventory().get(0).get("reservedStock"));assertEquals(3,((List<?>)finalOrder.get("afterSalesCases")).size());
        assertEquals(3L,fixture.count("commerce_refund_attempt"));assertEquals(4L,n("SELECT SUM(quantity) FROM commerce_return_allocation"));assertEquals(5L,item(order).get("refundedQuantity"));healthy();
    }

    @Test public void returnsTraceAcrossOriginalBatchesAndWarehousesAndNeverOverfillOneSource(){
        jdbc.update("UPDATE inventory_product SET plan_quantity=1 WHERE inventory_id=1");jdbc.update("INSERT INTO inventory_product(inventory_id,product_id,warehouse_id,plan_quantity) VALUES (2,1,2,9)");
        String order=paid("trace",4);ship(order,"batch1",2);ship(order,"batch2",2);
        String one=apply(order,"one","RETURN_REFUND",1);review(one);accept(one);refund(one);
        assertEquals(1L,n("SELECT plan_quantity FROM inventory_product WHERE inventory_id=1"));
        String three=apply(order,"three","RETURN_REFUND",3);review(three);accept(three);refund(three);
        assertEquals(9L,n("SELECT plan_quantity FROM inventory_product WHERE inventory_id=2"));
        assertEquals(2L,n("SELECT COUNT(DISTINCT source_receipt_id) FROM commerce_return_allocation"));assertEquals(4L,n("SELECT SUM(quantity) FROM commerce_return_allocation"));
        assertEquals(3L,n("SELECT SUM(quantity) FROM commerce_return_allocation WHERE warehouse_id=2"));fixture.rejected(409,()->apply(order,"extra","RETURN_REFUND",1));
        for(Map<String,Object> shipment:jdbc.queryForList("SELECT receipt_id FROM commerce_shipment")){
            DetailReceipt detail=new DetailReceipt();detail.setSystematicReceipt(String.valueOf(shipment.get("receipt_id")));fixture.rejected(409,()->tx(()->fixture.warehouse.delete(Collections.singletonList(detail))));
        }
        healthy();
    }

    @Test public void concurrentCasesReserveRemainingQuantityAndRefundMoneyExactlyOnce()throws Exception{
        String order=paid("race",5);ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try{
            List<Future<String>> results=new ArrayList<>();for(String key:Arrays.asList("one","two"))results.add(pool.submit(()->{start.await();try{return apply(order,key,"UNSHIPPED_REFUND",3);}catch(ServiceException exception){assertEquals(Integer.valueOf(409),exception.getCode());return null;}}));
            start.countDown();String left=results.get(0).get(15,TimeUnit.SECONDS),right=results.get(1).get(15,TimeUnit.SECONDS);assertTrue((left==null)^(right==null));String id=left==null?right:left;
            assertEquals(2L,item(order).get("unshippedRefundAvailableQuantity"));review(id);refund(id);assertEquals(new BigDecimal("387.00"),service.detail(order,OWNER).get("refundedAmount"));assertEquals(2L,service.inventory().get(0).get("reservedStock"));
            String remainder=apply(order,"remainder","UNSHIPPED_REFUND",2);review(remainder);refund(remainder);assertEquals(new BigDecimal("645.00"),service.detail(order,OWNER).get("refundedAmount"));healthy();
        }finally{pool.shutdownNow();}
    }

    @Test public void blockedGoodsStayInTheirWarehouseAndUnavailableChangesCannotConsumeReservations(){
        jdbc.update("UPDATE inventory_product SET plan_quantity=3 WHERE inventory_id=1");jdbc.update("INSERT INTO inventory_product(inventory_id,product_id,warehouse_id,plan_quantity) VALUES (2,1,2,7)");
        tx(()->{stock.ensureStock(1);stock.adjustUnavailable(1,"condition",3,"质检隔离");return null;});jdbc.update("INSERT INTO commerce_warehouse_condition(product_id,warehouse_id,quality_hold,damaged) VALUES (1,1,2,1)");
        String order=paid("blocked",5);fixture.rejected(409,()->tx(()->{stock.adjustUnavailable(1,"conflict",3,"不能侵占预留");return null;}));
        ship(order,"batch",5);assertEquals(3L,n("SELECT plan_quantity FROM inventory_product WHERE inventory_id=1"));assertEquals(2L,n("SELECT plan_quantity FROM inventory_product WHERE inventory_id=2"));assertEquals(3L,service.inventory().get(0).get("unavailableStock"));assertEquals(2L,service.inventory().get(0).get("availableStock"));
        tx(()->{stock.adjustUnavailable(1,"condition",3,"质检隔离");return null;});fixture.rejected(409,()->tx(()->{stock.adjustUnavailable(1,"condition",2,"重放改数量");return null;}));healthy();
    }

    @Test public void legacyFullOrderCasesMigrateOnceWithoutDroppingUniqueKeyOrChangingHistoricalBalances(){
        String order=paid("legacy",2);tx(()->service.ship(order,map(),1));String id=apply(order,"legacy-return","RETURN_REFUND",2);review(id);accept(id);refund(id);
        String columns="after_sales_id,order_id,shop_id,owner_id,request_key,reason,status,original_order_status,return_required,refund_amount,refunded_amount,review_key,review_note,return_key,return_receipt_id,refund_id,created_at,reviewed_at,returned_at,refunded_at";
        jdbc.update("INSERT INTO commerce_after_sales("+columns+") SELECT "+columns+" FROM commerce_after_sales_case WHERE after_sales_id=?",id);
        jdbc.update("DELETE FROM commerce_after_sales_item");jdbc.update("DELETE FROM commerce_after_sales_case");jdbc.update("DELETE FROM commerce_fulfillment_line");jdbc.update("DELETE FROM commerce_return_allocation");jdbc.update("DELETE FROM commerce_shipment_item");jdbc.update("DELETE FROM commerce_shipment");
        long ledger=fixture.count("commerce_stock_ledger");BigDecimal money=jdbc.queryForObject("SELECT refunded_amount FROM commerce_order WHERE order_id=?",BigDecimal.class,order);
        tx(()->{CommercePartialSupport.migrate(jdbc);return null;});tx(()->{CommercePartialSupport.migrate(jdbc);return null;});
        assertEquals(1L,fixture.count("commerce_after_sales"));assertEquals(1L,fixture.count("commerce_after_sales_case"));assertEquals(1L,fixture.count("commerce_partial_migration"));assertEquals(ledger,fixture.count("commerce_stock_ledger"));assertEquals(money,service.detail(order,OWNER).get("refundedAmount"));
        assertEquals(2L,item(order).get("shippedQuantity"));assertEquals(2L,item(order).get("returnedQuantity"));assertEquals(2L,item(order).get("refundedQuantity"));assertEquals(1,((List<?>)service.detail(order,OWNER).get("shipments")).size());
        assertEquals("REFUNDED",after.detail(id,OWNER).get("status"));assertEquals(2L,n("SELECT SUM(quantity) FROM commerce_return_allocation"));healthy();
    }

    @Test public void reconciliationDetectsWarehouseConditionDriftEvenWhenSnapshotAndJournalAgree(){
        tx(()->{stock.ensureStock(1);stock.adjustUnavailable(1,"condition",2,"质检隔离");return null;});
        jdbc.update("INSERT INTO commerce_warehouse_condition(product_id,warehouse_id,quality_hold,damaged) VALUES (1,1,2,0)");healthy();
        jdbc.update("UPDATE commerce_warehouse_condition SET quality_hold=3 WHERE product_id=1");
        assertEquals(false,service.reconcile().get("healthy"));assertTrue(service.reconcile().toString().contains("WAREHOUSE_UNAVAILABLE_MISMATCH"));
        jdbc.update("UPDATE commerce_warehouse_condition SET quality_hold=11 WHERE product_id=1");assertTrue(service.reconcile().toString().contains("WAREHOUSE_CONDITION_INVALID"));
        jdbc.update("UPDATE commerce_warehouse_condition SET quality_hold=-1 WHERE product_id=1");assertTrue(service.reconcile().toString().contains("WAREHOUSE_CONDITION_INVALID"));
    }
}

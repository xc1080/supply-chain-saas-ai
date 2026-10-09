package com.ruoyi.system.service;

import com.ruoyi.common.core.tenant.CommerceShopContext;
import com.ruoyi.common.exception.ServiceException;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static com.ruoyi.system.service.CommerceServiceTest.*;

public class CommerceDeliveryServiceTest {
    private CommerceServiceTest f;
    private CommerceDeliveryService delivery;
    @Before public void setup() throws Exception{f=new CommerceServiceTest();f.setup();delivery=new CommerceDeliveryService(f.jdbc.getDataSource());}
    @After public void clear(){CommerceShopContext.clear();}
    private void capacity(int qty){f.jdbc.update("UPDATE commerce_delivery_policy SET daily_item_capacity=?,dispatch_days=0 WHERE shop_id='default'",qty);}
    private long available(){return ((Number)delivery.quote().get("availableQuantity")).longValue();}
    private String paid(String key,int qty){String id=f.id(f.create(key,qty));f.transaction(()->f.service.pay(id,map("ownerId",OWNER,"paymentRequestId",key+"-pay","scenario","success")));return id;}
    private String after(String order,String kind,int qty){return (String)f.transaction(()->f.afterSales.apply(order,map("ownerId",OWNER,"requestKey",kind,"kind",kind,"reason","capacity lifecycle","items",Arrays.asList(map("productId",1,"quantity",qty))))).get("afterSalesId");}
    private void approveAndRefund(String id){f.transaction(()->f.afterSales.review(id,map("requestKey","approve","decision","APPROVE"),1));f.transaction(()->f.afterSales.refund(id,map("requestKey","refund","scenario","success"),1));}
    @Test public void parallelOrdersCannotPromiseMoreThanDailyCapacityAndExpiryReleasesExactlyOnce() throws Exception{
        capacity(3);ExecutorService pool=Executors.newFixedThreadPool(10);CountDownLatch start=new CountDownLatch(1);List<Future<String>> results=new ArrayList<>();
        try{
            for(int i=0;i<24;i++){final int index=i;results.add(pool.submit(()->{start.await();try{return f.id(f.create("quota:"+index,1));}catch(ServiceException e){assertEquals(Integer.valueOf(409),e.getCode());return null;}}));}
            start.countDown();List<String> winners=new ArrayList<>();for(Future<String> result:results){String id=result.get(20,TimeUnit.SECONDS);if(id!=null)winners.add(id);}
            assertEquals(3,winners.size());assertEquals(0,available());assertEquals(3L,f.count("commerce_order"));assertEquals(3L,f.count("commerce_delivery_hold"));
            String expired=winners.get(0);f.jdbc.update("UPDATE commerce_order SET expires_at=TIMESTAMPADD(SECOND,-2,CURRENT_TIMESTAMP) WHERE order_id=?",expired);
            assertTrue(f.transaction(()->f.service.expireOrder(expired)));assertFalse(f.transaction(()->f.service.expireOrder(expired)));assertEquals(1,available());
            for(String order:winners.subList(1,3)){f.transaction(()->f.service.cancel(order,OWNER));f.transaction(()->f.service.cancel(order,OWNER));}
            assertEquals(3,available());assertEquals(10L,f.service.inventory().get(0).get("availableStock"));
        }finally{pool.shutdownNow();}
    }
    @Test public void shipmentsConsumeTodayAndReturnsCannotRestoreUsedHandlingCapacity(){
        capacity(5);String order=paid("partial",4);assertEquals(1,available());
        f.transaction(()->f.service.ship(order,map("requestKey","batch","items",Arrays.asList(map("productId",1,"quantity",2))),1));assertEquals(1,available());
        f.transaction(()->f.service.ship(order,map("requestKey","batch","items",Arrays.asList(map("productId",1,"quantity",2))),1));assertEquals(1,available());
        String unshipped=after(order,"UNSHIPPED_REFUND",2);approveAndRefund(unshipped);assertEquals(3,available());
        String returned=after(order,"RETURN_REFUND",2);f.transaction(()->f.afterSales.review(returned,map("requestKey","approve","decision","APPROVE"),1));
        f.transaction(()->f.afterSales.acceptReturn(returned,map("requestKey","return","condition","SELLABLE"),1));f.transaction(()->f.afterSales.refund(returned,map("requestKey","refund","scenario","success"),1));
        assertEquals(3,available());assertEquals(10L,f.service.inventory().get(0).get("availableStock"));
        assertEquals(2L,(long)f.jdbc.queryForObject("SELECT consumed_quantity FROM commerce_delivery_bucket WHERE shop_id='default' AND dispatch_date=CURRENT_DATE",Long.class));
        assertEquals("DISPATCHED",delivery.orderPromise(order).get("status"));
    }
    @Test public void activitiesReserveHandlingCapacityAndCancellationReturnsToActivityUntilExpiry(){
        capacity(3);f.transaction(()->f.service.createActivity(map("activityId","rush","productId",1,"capacity",3,"perOwnerLimit",1,"price",99,"startsAt",LocalDateTime.now().minusMinutes(1).toString(),"endsAt",LocalDateTime.now().plusMinutes(5).toString())));
        assertEquals(0,available());f.rejected(409,()->f.create("normal",1));
        String order=(String)f.transaction(()->f.service.seckill("rush",map("ownerId",OWNER,"requestKey","rush-1","shippingAddress",address()))).get("orderId");
        assertEquals(0,available());f.transaction(()->f.service.cancel(order,OWNER));assertEquals(0,available());
        assertEquals(3L,(long)f.jdbc.queryForObject("SELECT remaining_quantity FROM commerce_delivery_hold WHERE subject_type='ACTIVITY' AND subject_id='rush'",Long.class));
        f.jdbc.update("UPDATE commerce_activity SET ends_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE activity_id='rush'");
        f.transaction(()->{f.service.expireActivities();return null;});f.transaction(()->{f.service.expireActivities();return null;});assertEquals(3,available());
        assertEquals(10L,f.service.inventory().get(0).get("availableStock"));
    }
    @Test public void unknownPolicyFailsClosedAndReadsNeverCreateBuckets(){
        f.jdbc.update("DELETE FROM commerce_delivery_policy");assertEquals(false,delivery.quote().get("capacityKnown"));f.rejected(409,()->f.create("unknown",1));
        assertEquals(0L,f.count("commerce_order"));assertEquals(0L,f.count("commerce_delivery_bucket"));
        capacity(3); // deleted policy stays absent, no implicit guessing.
        assertEquals(false,delivery.quote().get("capacityKnown"));
    }
    @Test public void failedStockReservationRollsBackDeliveryAndMismatchedRetryIsRejected(){
        capacity(20);f.rejected(409,()->f.create("stock-too-low",11));assertEquals(0L,f.count("commerce_delivery_hold"));assertEquals(20,available());
        String order=f.id(f.create("stable",3));assertEquals(17,available());
        f.rejected(409,()->f.transaction(()->{delivery.reserveOrder(order,4,null);return null;}));assertEquals(17,available());
        f.transaction(()->{delivery.releaseOrder(order,"manual",1L,false);return null;});assertEquals(18,available());
        f.rejected(409,()->f.transaction(()->{delivery.releaseOrder(order,"manual",2L,false);return null;}));assertEquals(18,available());
    }
    @Test public void legacyAdoptionPreservesOvercommitAndIsReplaySafe(){
        String order=f.id(f.create("legacy",4));f.jdbc.update("DELETE FROM commerce_delivery_event");f.jdbc.update("DELETE FROM commerce_delivery_hold");f.jdbc.update("DELETE FROM commerce_delivery_bucket");capacity(3);
        long stockEvents=f.count("commerce_stock_ledger");delivery.migrateLegacy();assertEquals(0,available());assertEquals(true,delivery.quote().get("overcommitted"));
        delivery.migrateLegacy();assertEquals(1L,f.count("commerce_delivery_hold"));assertEquals(stockEvents,f.count("commerce_stock_ledger"));
        f.transaction(()->f.service.cancel(order,OWNER));assertEquals(3,available());
    }
}

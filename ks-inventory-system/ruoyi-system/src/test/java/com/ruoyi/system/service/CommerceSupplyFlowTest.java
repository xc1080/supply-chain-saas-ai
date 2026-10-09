package com.ruoyi.system.service;

import com.ruoyi.common.core.tenant.CommerceShopContext;
import com.ruoyi.common.exception.ServiceException;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static com.ruoyi.system.service.CommerceServiceTest.map;

/** SQL concurrency and full supplier-confirmation/ERP evidence lifecycle. */
public class CommerceSupplyFlowTest {
    private CommercePlanningServiceTest fixture;
    @Before public void setup() throws Exception {
        fixture=new CommercePlanningServiceTest();fixture.setup();
        fixture.f.transaction(()->{fixture.f.merchants.listing(1,true);return null;});
        fixture.jdbc.update("UPDATE product SET lower_limit=12,upper_limit=20 WHERE product_id=1");
    }
    @After public void clear(){CommerceShopContext.clear();}
    private Map<String,Object> draft(String key,int quantity){return fixture.f.transaction(()->fixture.planning.createDraft(map("requestKey",key,"items",Arrays.asList(map("productId",1,"quantity",quantity))),1));}
    private Map<String,Object> review(String id,String decision,String key,long actor){return fixture.f.transaction(()->fixture.planning.reviewDraft(id,map("requestKey",key,"decision",decision,"note","Independent supplier verification"),actor));}
    private Map<String,Object> execution(String key){return map("requestKey",key,"warehouseId",1,"sourceReference","SUPPLIER-SAMPLE-001","expectedAt",LocalDateTime.now().plusDays(2).withNano(0).toString());}
    private Map<String,Object> fact(){return (Map<String,Object>)((List<?>)fixture.planning.replenishment(1).get("items")).get(0);}
    private void denied(int code,Runnable run){try{run.run();fail("Expected rejection");}catch(ServiceException e){assertEquals(Integer.valueOf(code),e.getCode());}}
    @Test public void parallelDistinctRequestsCannotReserveSameGapTwice() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(12);CountDownLatch start=new CountDownLatch(1);List<Future<String>> jobs=new ArrayList<>();
        try{
            for(int i=0;i<24;i++){final int index=i;jobs.add(pool.submit(()->{start.await();try{return (String)draft("parallel-"+index,2).get("draftId");}catch(ServiceException e){assertEquals(Integer.valueOf(409),e.getCode());return null;}}));}
            start.countDown();List<String> ids=new ArrayList<>();for(Future<String> job:jobs){String id=job.get(20,TimeUnit.SECONDS);if(id!=null)ids.add(id);}
            assertEquals(5,ids.size());assertEquals(10L,(long)fixture.jdbc.queryForObject("SELECT SUM(quantity) FROM commerce_supply_line WHERE state='PENDING_APPROVAL'",Long.class));
            assertEquals(0L,fact().get("suggestedQuantity"));assertEquals(10L,fact().get("committedSupplyQuantity"));
            List<Future<?>> reviews=new ArrayList<>();for(String id:ids)reviews.add(pool.submit(()->review(id,"APPROVE","approve:"+id,3)));
            for(Future<?> review:reviews)review.get(20,TimeUnit.SECONDS);
            assertEquals(5L,(long)fixture.jdbc.queryForObject("SELECT COUNT(*) FROM commerce_replenishment_draft WHERE status='APPROVED'",Long.class));
            denied(409,()->draft("one-more",1));
        }finally{pool.shutdownNow();}
    }
    @Test public void rejectAndCancelReleaseOnlyTheirOwnSupplyCommitment(){
        String a=(String)draft("a",6).get("draftId"),b=(String)draft("b",4).get("draftId");assertEquals(0L,fact().get("suggestedQuantity"));
        review(a,"REJECT","reject-a",3);assertEquals(6L,fact().get("suggestedQuantity"));
        Map<String,Object> body=map("requestKey","cancel-b","reason","Demand changed before supplier contact");
        Map<String,Object> cancelled=fixture.f.transaction(()->fixture.planning.cancelDraft(b,body,1));assertEquals("CANCELLED",cancelled.get("status"));
        assertEquals(cancelled,fixture.f.transaction(()->fixture.planning.cancelDraft(b,body,1)));assertEquals(10L,fact().get("suggestedQuantity"));
        assertEquals("PENDING_APPROVAL",draft("replacement",10).get("status"));
    }
    @Test public void ownerSelfApprovalAndExecutionOfLegacySelfApprovalAreRejected(){
        String id=(String)draft("self",10).get("draftId");denied(403,()->review(id,"APPROVE","self-approval",1));
        fixture.jdbc.update("UPDATE commerce_replenishment_draft SET status='APPROVED',reviewed_by=1 WHERE draft_id=?",id);
        fixture.jdbc.update("UPDATE commerce_supply_line SET state='APPROVED' WHERE draft_id=?",id);
        denied(409,()->fixture.f.transaction(()->fixture.planning.executeDraft(id,execution("unsafe-exec"),1)));
        assertEquals(0L,(long)fixture.jdbc.queryForObject("SELECT COUNT(*) FROM commerce_incoming",Long.class));
    }
    @Test public void approvedExecutionRetriesCreateExactlyOneIncomingAndNoStock() throws Exception {
        String id=(String)draft("execute",10).get("draftId");review(id,"APPROVE","independent",3);Map<String,Object> body=execution("supplier-confirmed");
        ExecutorService pool=Executors.newFixedThreadPool(8);List<Future<?>> jobs=new ArrayList<>();
        try{for(int i=0;i<12;i++)jobs.add(pool.submit(()->fixture.f.transaction(()->fixture.planning.executeDraft(id,body,1))));for(Future<?> job:jobs)job.get(20,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        assertEquals(1L,(long)fixture.jdbc.queryForObject("SELECT COUNT(*) FROM commerce_incoming",Long.class));
        assertEquals(1L,(long)fixture.jdbc.queryForObject("SELECT COUNT(*) FROM commerce_supply_command WHERE command_kind='EXECUTE'",Long.class));
        assertEquals(10L,fixture.stock.balances(1).get("availableStock"));assertEquals(0L,fact().get("suggestedQuantity"));
        fixture.f.transaction(()->fixture.planning.savePolicy(map("productId",1,"supplierLeadDays",5),1));
        assertEquals(0L,fact().get("suggestedQuantity"));assertEquals(10L,fact().get("incomingDueWithinLead")); // linked supply is not counted twice
        Map<String,Object> changed=new LinkedHashMap<>(body);changed.put("sourceReference","OTHER-SUPPLIER");
        denied(409,()->fixture.f.transaction(()->fixture.planning.executeDraft(id,changed,1)));
        denied(409,()->fixture.f.transaction(()->fixture.planning.cancelDraft(id,map("requestKey","late-cancel","reason","already confirmed"),1)));
    }
    @Test public void partialAndFinalPostedReceiptsCloseSupplyWithoutDoubleStock(){
        String id=(String)draft("receiving",10).get("draftId");review(id,"APPROVE","approved",3);
        Map<String,Object> result=fixture.f.transaction(()->fixture.planning.executeDraft(id,execution("exec"),1));
        String incoming=(String)((Map<?,?>)((List<?>)result.get("supplyLines")).get(0)).get("incomingId");
        postReceipt("ERP-PART",4);Map<String,Object> part=fixture.f.transaction(()->fixture.planning.receiveIncoming(incoming,map("receiptId","ERP-PART"),1));
        assertEquals(4L,((Number)part.get("receivedQuantity")).longValue());assertEquals(14L,fixture.stock.balances(1).get("availableStock"));
        fixture.f.transaction(()->fixture.planning.receiveIncoming(incoming,map("receiptId","ERP-PART"),1));assertEquals(14L,fixture.stock.balances(1).get("availableStock"));
        assertEquals(6L,fact().get("committedSupplyQuantity"));
        postReceipt("ERP-FINAL",6);fixture.f.transaction(()->fixture.planning.receiveIncoming(incoming,map("receiptId","ERP-FINAL"),1));
        assertEquals(20L,fixture.stock.balances(1).get("availableStock"));assertEquals("RECEIVED",fixture.planning.drafts(1).get(0).get("status"));
        assertEquals(0L,fact().get("committedSupplyQuantity"));assertEquals(2L,(long)fixture.jdbc.queryForObject("SELECT COUNT(*) FROM commerce_incoming_receipt",Long.class));
    }
    private void postReceipt(String receipt,int quantity){fixture.f.transaction(()->{
        fixture.jdbc.update("INSERT INTO head_receipt(systematic_receipt,receipt_category,receipt_type,receipt_status,warehousing_ids) VALUES (?,'1','1','2',1)",receipt);
        fixture.jdbc.update("INSERT INTO detail_receipt(systematic_receipt,product_id,warehousing_id,plan_quantity) VALUES (?,1,1,?)",receipt,quantity);
        fixture.jdbc.update("UPDATE inventory_product SET plan_quantity=plan_quantity+? WHERE product_id=1",quantity);fixture.jdbc.update("UPDATE product SET inventory_qty=inventory_qty+? WHERE product_id=1",quantity);
        fixture.stock.ensureStock(1,"Approved purchase receipt "+receipt);return null;
    });}
    @Test public void policyUpdatesRecordBeforeAfterAndRejectInvalidCapacityReduction(){
        fixture.f.transaction(()->fixture.planning.savePolicy(map("dailyItemCapacity",8,"dispatchDays",0),1));fixture.f.create("held",4);
        denied(409,()->fixture.f.transaction(()->fixture.planning.savePolicy(map("dailyItemCapacity",3,"dispatchDays",0),1)));
        assertEquals(8L,(long)fixture.jdbc.queryForObject("SELECT daily_item_capacity FROM commerce_delivery_policy WHERE shop_id='default'",Long.class));
        fixture.f.transaction(()->fixture.planning.savePolicy(map("dailyItemCapacity",10,"dispatchDays",1,"productId",1,"supplierLeadDays",5),1));
        List<Map<String,Object>> history=fixture.planning.policyHistory(1);assertEquals(3,history.size());
        assertTrue(history.stream().anyMatch(event->String.valueOf(event.get("beforeJson")).contains("daily_item_capacity")));
        assertTrue(history.stream().anyMatch(event->String.valueOf(event.get("afterJson")).contains("supplierLeadDays")));
    }
}

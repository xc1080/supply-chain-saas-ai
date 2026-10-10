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
    private String confirmed(String key){
        String id=(String)draft(key,10).get("draftId");review(id,"APPROVE",key+":approved",3);
        Map<String,Object> executed=fixture.f.transaction(()->fixture.planning.executeDraft(id,execution(key+":exec"),1));
        return (String)((Map<?,?>)((List<?>)executed.get("supplyLines")).get(0)).get("incomingId");
    }
    private Map<String,Object> proposal(String incoming,String key,String action,long actor){
        Map<String,Object> body=map("requestKey",key,"action",action,"reason","Supplier exception verified","sourceReference","NOTICE-"+key);
        if("DELAY".equals(action))body.put("expectedAt",LocalDateTime.now().plusDays(4).withNano(0).toString());
        return fixture.f.transaction(()->fixture.planning.proposeIncomingChange(incoming,body,actor));
    }
    private Map<String,Object> changeReview(String change,String decision,String key,long actor){return fixture.f.transaction(()->fixture.planning.reviewIncomingChange(change,map("requestKey",key,"decision",decision,"note","Independent exception assessment"),actor));}
    @Test public void overdueCommitmentRequiresExplicitCancellationBeforeReplacementProcurement(){
        String incoming=confirmed("overdue");fixture.jdbc.update("UPDATE commerce_incoming SET expected_at=TIMESTAMPADD(DAY,-1,CURRENT_TIMESTAMP) WHERE incoming_id=?",incoming);
        long stockEvents=fixture.jdbc.queryForObject("SELECT COUNT(*) FROM commerce_stock_ledger",Long.class);
        assertEquals(10L,fact().get("overdueIncoming"));assertEquals(0L,fact().get("suggestedQuantity"));assertEquals("OVERDUE_COMMITMENT",fact().get("supplyRisk"));
        Map<String,Object> change=proposal(incoming,"cancel-overdue","CANCEL_REMAINDER",1);
        assertEquals(0L,fact().get("suggestedQuantity"));assertEquals("CONFIRMED",fixture.planning.incoming(1).get(0).get("status"));
        String cid=(String)change.get("changeId");denied(403,()->changeReview(cid,"APPROVE","self-cancel",1));
        Map<String,Object> applied=changeReview(cid,"APPROVE","independent-cancel",3);
        assertEquals(applied,changeReview(cid,"APPROVE","independent-cancel",3));assertEquals("APPLIED",applied.get("status"));
        assertEquals(10L,fact().get("suggestedQuantity"));assertEquals(0L,fact().get("committedSupplyQuantity"));assertEquals(0L,fact().get("overdueIncoming"));
        assertEquals("CANCELLED",fixture.planning.drafts(1).get(0).get("status"));assertEquals(10L,fixture.stock.balances(1).get("availableStock"));
        assertEquals(stockEvents,(long)fixture.jdbc.queryForObject("SELECT COUNT(*) FROM commerce_stock_ledger",Long.class));
        assertEquals("PENDING_APPROVAL",draft("replacement-after-release",10).get("status"));
    }
    @Test public void delayedSupplyKeepsCommitmentAndAuditsBeforeAfter(){
        String incoming=confirmed("delay");Map<String,Object> change=proposal(incoming,"delay-request","DELAY",1);String cid=(String)change.get("changeId");
        denied(403,()->changeReview(cid,"APPROVE","delay-self",1));denied(403,()->changeReview(cid,"APPROVE","delay-viewer",2));
        denied(403,()->proposal(incoming,"viewer-proposal","DELAY",2));
        Map<String,Object> result=changeReview(cid,"APPROVE","delay-review",3);
        assertEquals(10L,fact().get("committedSupplyQuantity"));assertEquals(0L,fact().get("suggestedQuantity"));
        assertEquals(((Map<?,?>)result.get("after")).get("expectedAt"),fixture.planning.incoming(1).get(0).get("expectedAt"));
        assertEquals(1L,((Number)result.get("createdBy")).longValue());assertEquals(3L,((Number)result.get("reviewedBy")).longValue());
        assertNotEquals(((Map<?,?>)result.get("before")).get("expectedAt"),((Map<?,?>)result.get("after")).get("expectedAt"));
        denied(409,()->changeReview(cid,"REJECT","changed-decision",3));
        Map<String,Object> changed=map("requestKey","delay-request","action","DELAY","expectedAt",LocalDateTime.now().plusDays(5).withNano(0).toString(),"reason","Changed reason","sourceReference","NOTICE-delay-request");
        denied(409,()->fixture.f.transaction(()->fixture.planning.proposeIncomingChange(incoming,changed,1)));
    }
    @Test public void partialReceiptThenCancellationPreservesPhysicalQuantityAndReceiptEvidence(){
        fixture.jdbc.update("UPDATE product SET lower_limit=18 WHERE product_id=1");String incoming=confirmed("partial-cancel");postReceipt("ERP-PART-CANCEL",4);
        fixture.f.transaction(()->fixture.planning.receiveIncoming(incoming,map("receiptId","ERP-PART-CANCEL"),1));
        long ledger=fixture.jdbc.queryForObject("SELECT COUNT(*) FROM commerce_stock_ledger",Long.class);
        String cid=(String)proposal(incoming,"cancel-remaining-six","CANCEL_REMAINDER",1).get("changeId");changeReview(cid,"APPROVE","partial-review",3);
        Map<String,Object> row=fixture.planning.incoming(1).get(0);assertEquals("PARTIAL_CLOSED",row.get("status"));
        assertEquals(10L,row.get("quantity"));assertEquals(4L,row.get("receivedQuantity"));assertEquals(6L,row.get("cancelledQuantity"));assertEquals(0L,row.get("outstandingQuantity"));
        assertEquals("CLOSED_PARTIAL",fixture.planning.drafts(1).get(0).get("status"));assertEquals(6L,fact().get("suggestedQuantity"));
        assertEquals(14L,fixture.stock.balances(1).get("availableStock"));assertEquals(14L,(long)fixture.jdbc.queryForObject("SELECT SUM(plan_quantity) FROM inventory_product WHERE product_id=1",Long.class));
        assertEquals(ledger,(long)fixture.jdbc.queryForObject("SELECT COUNT(*) FROM commerce_stock_ledger",Long.class));
        assertEquals(row,fixture.f.transaction(()->fixture.planning.receiveIncoming(incoming,map("receiptId","ERP-PART-CANCEL"),1)));
        denied(409,()->fixture.f.transaction(()->fixture.planning.receiveIncoming(incoming,map("receiptId","ANOTHER-RECEIPT"),1)));
        assertEquals(1L,(long)fixture.jdbc.queryForObject("SELECT COUNT(*) FROM commerce_incoming_receipt WHERE incoming_id=?",Long.class,incoming));
    }
    @Test public void receiptDuringApprovalInvalidatesProposalAndRequiresIndependentReassessment(){
        String incoming=confirmed("stale-change");String cid=(String)proposal(incoming,"stale-cancel","CANCEL_REMAINDER",1).get("changeId");
        postReceipt("ERP-DURING-APPROVAL",4);fixture.f.transaction(()->fixture.planning.receiveIncoming(incoming,map("receiptId","ERP-DURING-APPROVAL"),1));
        denied(409,()->changeReview(cid,"APPROVE","stale-approve",3));assertEquals(6L,fact().get("committedSupplyQuantity"));
        assertEquals("PENDING_APPROVAL",fixture.planning.incomingChanges(1).get(0).get("status"));
        changeReview(cid,"REJECT","stale-reject",3);String next=(String)proposal(incoming,"fresh-cancel","CANCEL_REMAINDER",1).get("changeId");
        changeReview(next,"APPROVE","fresh-approve",3);assertEquals(6L,fixture.planning.incoming(1).get(0).get("cancelledQuantity"));
        assertEquals(4L,fixture.planning.incoming(1).get(0).get("receivedQuantity"));assertEquals(14L,fixture.stock.balances(1).get("availableStock"));
    }
    @Test public void changeOwnershipAndPendingRequestPreventCrossShopOrDuplicateResolution(){
        String incoming=confirmed("ownership");Map<String,Object> change=proposal(incoming,"first-proposal","CANCEL_REMAINDER",1);String cid=(String)change.get("changeId");
        assertEquals(change,proposal(incoming,"first-proposal","CANCEL_REMAINDER",1));denied(409,()->proposal(incoming,"second-proposal","DELAY",1));
        fixture.jdbc.update("INSERT INTO commerce_shop(shop_id,shop_name) VALUES ('other','Other shop')");fixture.jdbc.update("INSERT INTO commerce_shop_member VALUES ('other',1,'OWNER'),('other',3,'SUPPLY_REVIEWER')");
        CommerceShopContext.set("other");denied(404,()->proposal(incoming,"foreign-proposal","CANCEL_REMAINDER",1));denied(404,()->changeReview(cid,"APPROVE","foreign-review",3));
        assertTrue(fixture.planning.incomingChanges(1).isEmpty());CommerceShopContext.clear();assertEquals(10L,fact().get("committedSupplyQuantity"));
    }
    @Test public void parallelChangeApplicationsCancelOutstandingQuantityExactlyOnce() throws Exception {
        String incoming=confirmed("parallel-change");String cid=(String)proposal(incoming,"parallel-proposal","CANCEL_REMAINDER",1).get("changeId");
        ExecutorService pool=Executors.newFixedThreadPool(6);List<Future<?>> jobs=new ArrayList<>();
        try{for(int i=0;i<12;i++)jobs.add(pool.submit(()->changeReview(cid,"APPROVE","parallel-review",3)));for(Future<?> job:jobs)job.get(20,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        assertEquals(10L,(long)fixture.jdbc.queryForObject("SELECT cancelled_quantity FROM commerce_incoming_resolution WHERE incoming_id=?",Long.class,incoming));
        assertEquals(1L,(long)fixture.jdbc.queryForObject("SELECT COUNT(*) FROM commerce_supply_event WHERE action='REMAINDER_CANCELLED'",Long.class));assertEquals(10L,fact().get("suggestedQuantity"));
    }
    @Test public void directlyRegisteredIncomingAlsoBlocksDuplicateProcurementAndCountsDueOnce(){
        fixture.f.transaction(()->fixture.planning.savePolicy(map("productId",1,"supplierLeadDays",5),1));
        String incoming=(String)fixture.f.transaction(()->fixture.planning.registerIncoming(map("requestKey","direct-supplier","productId",1,"warehouseId",1,"quantity",4,"sourceReference","SUPPLIER-DIRECT-CONFIRMATION","expectedAt",LocalDateTime.now().plusDays(2).withNano(0).toString()),1)).get("incomingId");
        assertEquals(4L,fact().get("incomingDueWithinLead"));assertEquals(4L,fact().get("committedSupplyQuantity"));assertEquals(6L,fact().get("suggestedQuantity"));
        fixture.jdbc.update("UPDATE commerce_incoming SET expected_at=TIMESTAMPADD(DAY,-1,CURRENT_TIMESTAMP) WHERE incoming_id=?",incoming);
        assertEquals(4L,fact().get("overdueIncoming"));assertEquals(4L,fact().get("committedSupplyQuantity"));assertEquals(6L,fact().get("suggestedQuantity"));
        String change=(String)proposal(incoming,"direct-cancel","CANCEL_REMAINDER",1).get("changeId");changeReview(change,"APPROVE","direct-independent-review",3);
        assertEquals(0L,fact().get("committedSupplyQuantity"));assertEquals(10L,fact().get("suggestedQuantity"));assertEquals(10L,fixture.stock.balances(1).get("availableStock"));
    }
    @Test public void separateDirectPurchaseInvalidatesUnexecutedApprovedDraftAndAllowsCorrectReplacement(){
        String id=(String)draft("approved-before-direct-purchase",10).get("draftId");
        review(id,"APPROVE","approved-ten",3);
        fixture.f.transaction(()->fixture.planning.registerIncoming(map("requestKey","direct-four-after-approval","productId",1,"warehouseId",1,"quantity",4,"sourceReference","SEPARATE-DIRECT-PURCHASE","expectedAt",LocalDateTime.now().plusDays(2).withNano(0).toString()),1));
        assertEquals(14L,fact().get("committedSupplyQuantity"));
        denied(409,()->fixture.f.transaction(()->fixture.planning.executeDraft(id,execution("stale-execution"),1)));
        assertEquals("APPROVED",fixture.jdbc.queryForObject("SELECT status FROM commerce_replenishment_draft WHERE draft_id=?",String.class,id));
        assertEquals("APPROVED",fixture.jdbc.queryForObject("SELECT state FROM commerce_supply_line WHERE draft_id=?",String.class,id));
        assertEquals(1L,(long)fixture.jdbc.queryForObject("SELECT COUNT(*) FROM commerce_incoming",Long.class));
        assertEquals(0L,(long)fixture.jdbc.queryForObject("SELECT COUNT(*) FROM commerce_supply_command WHERE command_kind='EXECUTE'",Long.class));
        assertEquals(10L,fixture.stock.balances(1).get("availableStock"));
        fixture.f.transaction(()->fixture.planning.cancelDraft(id,map("requestKey","cancel-stale-approved","reason","Direct purchase reduced the procurement gap"),1));
        assertEquals(6L,fact().get("suggestedQuantity"));
        Map<String,Object> replacement=draft("replacement-six-after-direct",6);
        assertEquals("PENDING_APPROVAL",replacement.get("status"));
        assertEquals(10L,fact().get("committedSupplyQuantity"));
    }
    @Test public void executedCommandReplayRemainsValidAfterReceiptFillsTheEntireGap(){
        String id=(String)draft("replay-after-receipt",10).get("draftId");review(id,"APPROVE","replay-approved",3);
        Map<String,Object> body=execution("replay-confirmed");
        Map<String,Object> executed=fixture.f.transaction(()->fixture.planning.executeDraft(id,body,1));
        String incoming=(String)((Map<?,?>)((List<?>)executed.get("supplyLines")).get(0)).get("incomingId");
        postReceipt("ERP-REPLAY-FILLED",10);
        fixture.f.transaction(()->fixture.planning.receiveIncoming(incoming,map("receiptId","ERP-REPLAY-FILLED"),1));
        assertEquals(0L,fact().get("suggestedQuantity"));
        long stockEvents=fixture.jdbc.queryForObject("SELECT COUNT(*) FROM commerce_stock_ledger",Long.class);
        Map<String,Object> replay=fixture.f.transaction(()->fixture.planning.executeDraft(id,body,1));
        assertEquals("RECEIVED",replay.get("status"));
        assertEquals(incoming,((Map<?,?>)((List<?>)replay.get("supplyLines")).get(0)).get("incomingId"));
        assertEquals(1L,(long)fixture.jdbc.queryForObject("SELECT COUNT(*) FROM commerce_incoming",Long.class));
        assertEquals(1L,(long)fixture.jdbc.queryForObject("SELECT COUNT(*) FROM commerce_supply_command WHERE command_kind='EXECUTE'",Long.class));
        assertEquals(stockEvents,(long)fixture.jdbc.queryForObject("SELECT COUNT(*) FROM commerce_stock_ledger",Long.class));
        assertEquals(20L,fixture.stock.balances(1).get("availableStock"));
    }
    @Test public void concurrentErpReceiptMustCommitBeforeExecutionRechecksDemand() throws Exception {
        String id=(String)draft("race-with-receipt",10).get("draftId");review(id,"APPROVE","race-reviewed",3);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch posted=new CountDownLatch(1),release=new CountDownLatch(1),started=new CountDownLatch(1);
        try {
            Future<?> receipt=pool.submit(()->fixture.f.transaction(()->{
                fixture.jdbc.queryForList("SELECT product_id FROM product WHERE product_id=1 FOR UPDATE");
                postReceipt("ERP-CONCURRENT-FILLED",10);posted.countDown();
                try { assertTrue(release.await(5,TimeUnit.SECONDS)); } catch(InterruptedException ex) {throw new RuntimeException(ex);}
                return null;
            }));
            assertTrue(posted.await(2,TimeUnit.SECONDS));
            Future<?> execute=pool.submit(()->{started.countDown();denied(409,()->fixture.f.transaction(()->fixture.planning.executeDraft(id,execution("race-exec"),1)));});
            assertTrue(started.await(2,TimeUnit.SECONDS));
            try {execute.get(200,TimeUnit.MILLISECONDS);fail("Execution must wait for the concurrent receipt's SKU lock");}
            catch(TimeoutException waiting) { /* The persisted stock is not visible until the receipt commits. */ }
            release.countDown();receipt.get(3,TimeUnit.SECONDS);execute.get(3,TimeUnit.SECONDS);
            assertEquals(0L,(long)fixture.jdbc.queryForObject("SELECT COUNT(*) FROM commerce_incoming",Long.class));
            assertEquals(0L,(long)fixture.jdbc.queryForObject("SELECT COUNT(*) FROM commerce_supply_command WHERE command_kind='EXECUTE'",Long.class));
            assertEquals("APPROVED",fixture.jdbc.queryForObject("SELECT status FROM commerce_replenishment_draft WHERE draft_id=?",String.class,id));
            assertEquals(20L,fixture.stock.balances(1).get("availableStock"));
        } finally {release.countDown();pool.shutdownNow();}
    }
}

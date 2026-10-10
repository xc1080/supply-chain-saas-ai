package com.ruoyi.system.service;

import com.ruoyi.common.core.tenant.CommerceShopContext;
import com.ruoyi.common.core.tenant.TenantContext;
import com.ruoyi.common.exception.ServiceException;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;
import static com.ruoyi.system.service.CommerceServiceTest.map;

public class CommerceAgentTaskServiceTest {
    private CommercePlanningServiceTest fixture;
    private CommerceAgentTaskService tasks;
    private Clock clock;
    @Before public void setup() throws Exception {
        fixture=new CommercePlanningServiceTest();fixture.setup();
        fixture.f.transaction(()->{fixture.f.merchants.listing(1,true);return null;});
        fixture.jdbc.update("UPDATE product SET lower_limit=12,upper_limit=20 WHERE product_id=1");
        fixture.jdbc.execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,status VARCHAR(1),del_flag VARCHAR(1))");
        fixture.jdbc.update("INSERT INTO sys_user VALUES(1,'0','0'),(3,'0','0')");
        clock=Clock.fixed(Instant.now(),ZoneId.systemDefault());tasks=service(clock);tasks.initializeSchema();
    }
    @After public void clear(){TenantContext.clear();CommerceShopContext.clear();}
    private CommerceAgentTaskService service(Clock c){return new CommerceAgentTaskService(fixture.jdbc.getDataSource(),fixture.planning,fixture.f.merchants,c);}
    private Map<String,Object> create(String key,long quantity){return fixture.f.transaction(()->tasks.create(map("requestKey",key,"goal","按当前缺口备货，等待独立审批","plannerMode","rules","items",Arrays.asList(map("productId",1,"quantity",quantity))),1));}
    private String id(Map<String,Object> task){return String.valueOf(task.get("taskId"));}
    private Map<String,Object> approve(String id,long version,String key){return fixture.f.transaction(()->tasks.review(id,map("requestKey",key,"planVersion",version,"decision","APPROVE","note","独立核验采购任务与供应商交期"),3));}
    private Map<String,Object> execution(String key,long version){return map("requestKey",key,"planVersion",version,"warehouseId",1,"sourceReference","虚构供应确认，测试不付款","expectedAt",LocalDateTime.now().plusDays(3).withNano(0).toString());}
    private void incoming(String key,long qty){fixture.f.transaction(()->fixture.planning.registerIncoming(map("requestKey",key,"productId",1,"warehouseId",1,"quantity",qty,"sourceReference","独立批次改变采购事实","expectedAt",LocalDateTime.now().plusDays(2).withNano(0).toString()),1));}
    private void fails(int code,Runnable action){try{action.run();fail("Expected rejection");}catch(ServiceException ex){assertEquals(Integer.valueOf(code),ex.getCode());}}
    private long count(String table){return fixture.jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
    @Test public void taskPersistsAcrossServiceRestartAndNextDayRevalidatesWithoutAutoExecuting(){
        Map<String,Object> first=create("cross-day",10);String id=id(first);assertEquals("WAITING_APPROVAL",first.get("status"));assertEquals(0,count("commerce_replenishment_draft"));
        tasks=service(Clock.offset(clock,Duration.ofDays(1)));assertEquals(true,tasks.get(id,1).get("needsDateRefresh"));
        Map<String,Object> refreshed=fixture.f.transaction(()->tasks.refresh(id,map("requestKey","resume-next-day"),1));
        assertEquals(2L,refreshed.get("planVersion"));assertEquals("WAITING_APPROVAL",refreshed.get("status"));assertEquals(true,refreshed.get("replanRequired"));assertEquals(0,count("commerce_incoming"));assertEquals(2,count("commerce_agent_task_version"));
        fails(409,()->approve(id,1,"old-approval"));
    }
    @Test public void changedSupplyInvalidatesApprovedDraftAndRequiresNewIndependentApproval(){
        String id=id(create("supply-change",10));Map<String,Object> approved=approve(id,1,"review-v1");String oldDraft=(String)approved.get("draftId");incoming("new-supplier",4);
        Map<String,Object> refreshed=fixture.f.transaction(()->tasks.refresh(id,map("requestKey","refresh-changed"),1));
        assertEquals(2L,refreshed.get("planVersion"));assertEquals("WAITING_APPROVAL",refreshed.get("status"));assertNull(refreshed.get("approvedBy"));assertNull(refreshed.get("draftId"));assertEquals(6L,((Number)((Map<?,?>)((List<?>)refreshed.get("items")).get(0)).get("quantity")).longValue());
        assertEquals("CANCELLED",fixture.jdbc.queryForObject("SELECT status FROM commerce_replenishment_draft WHERE draft_id=?",String.class,oldDraft));
        Map<String,Object> next=approve(id,2,"review-v2");assertEquals("APPROVED",next.get("status"));assertNotEquals(oldDraft,next.get("draftId"));
    }
    @Test public void staleExecuteCommitsReplanInsteadOfRollingItBack(){
        String id=id(create("stale-execute",10));approve(id,1,"approve-before-change");incoming("extra-three",3);
        Map<String,Object> answer=fixture.f.transaction(()->tasks.execute(id,execution("execute-v1",1),1));
        assertEquals(true,answer.get("replanRequired"));assertEquals("WAITING_APPROVAL",tasks.get(id,1).get("status"));assertEquals(2L,tasks.get(id,1).get("planVersion"));assertEquals(1,count("commerce_incoming"));assertEquals(0,count("commerce_supply_command"));
    }
    @Test public void nextDayApprovedPlanIsNotExecutableWithoutReapproval(){
        String id=id(create("approved-day",1));approve(id,1,"day-review");tasks=service(Clock.offset(clock,Duration.ofDays(1)));
        Map<String,Object> result=fixture.f.transaction(()->tasks.execute(id,execution("next-day-execute",1),1));assertEquals("WAITING_APPROVAL",result.get("status"));assertEquals(2L,result.get("planVersion"));assertEquals(0,count("commerce_incoming"));
    }
    @Test public void responseLossAndRestartRetrySameBusinessKeyNeverCreateSecondIncoming(){
        String id=id(create("durable-execute",2));approve(id,1,"durable-review");Map<String,Object> body=execution("stable-command",1);
        Map<String,Object> first=fixture.f.transaction(()->tasks.execute(id,body,1));assertEquals("EXECUTED",first.get("status"));assertEquals(1,count("commerce_incoming"));
        tasks=service(Clock.offset(clock,Duration.ofDays(2)));Map<String,Object> retry=fixture.f.transaction(()->tasks.execute(id,body,1));
        assertEquals(first.get("result"),retry.get("result"));assertEquals(1,count("commerce_incoming"));assertEquals(1,count("commerce_supply_command"));assertEquals(10L,fixture.stock.balances(1).get("bookStock"));
        fails(409,()->fixture.f.transaction(()->tasks.execute(id,execution("changed-command",1),1)));
    }
    @Test public void creatorCannotSelfApproveAndRevokedCreatorCannotBorrowReviewerAuthority(){
        String id=id(create("separation",1));Map<String,Object> review=map("requestKey","self-review","planVersion",1,"decision","APPROVE","note","review");
        fails(403,()->fixture.f.transaction(()->tasks.review(id,review,1)));
        fixture.jdbc.update("UPDATE commerce_shop_member SET member_role='VIEWER' WHERE shop_id='default' AND user_id=1");fails(403,()->approve(id,1,"revoked-create"));assertEquals(0,count("commerce_replenishment_draft"));
        fixture.jdbc.update("UPDATE commerce_shop_member SET member_role='OWNER' WHERE shop_id='default' AND user_id=1");fixture.jdbc.update("UPDATE sys_user SET status='1' WHERE user_id=1");fails(403,()->approve(id,1,"disabled-create"));assertEquals(0,count("commerce_replenishment_draft"));
    }
    @Test public void foreignTenantShopAndViewerCannotContinueTasks(){
        String id=id(create("boundaries",1));fails(403,()->fixture.f.transaction(()->tasks.refresh(id,map("requestKey","viewer"),2)));
        TenantContext.set("studio");fails(404,()->tasks.get(id,1));TenantContext.clear();
        fixture.jdbc.update("INSERT INTO commerce_shop(shop_id,shop_name) VALUES('other','Other shop')");fixture.jdbc.update("INSERT INTO commerce_shop_member VALUES('other',1,'OWNER')");CommerceShopContext.set("other");fails(404,()->tasks.get(id,1));CommerceShopContext.clear();
    }
    @Test public void taskLinkedDraftCannotBypassTaskThroughLegacyControllerAndOrdinaryDraftCan(){
        String id=id(create("linked-guard",1));String draft=(String)approve(id,1,"linked-review").get("draftId");fails(409,()->tasks.assertStandaloneDraft(draft));tasks.assertStandaloneDraft("unrelated-draft");
        CommerceShopContext.set("other");tasks.assertStandaloneDraft(draft);CommerceShopContext.clear();
    }
    @Test public void initialProposalMustFitActualNeedAndRepeatedCreateCannotChangeGoalOrActor(){
        fails(409,()->create("oversized-model",11));Map<String,Object> first=create("same-create",2);assertEquals(first,create("same-create",2));fails(409,()->create("same-create",3));
        assertEquals("rules",first.get("plannerMode"));assertEquals(1,count("commerce_agent_task"));assertEquals(0,count("commerce_stock_ledger")-1);
    }
    @Test public void changedBeforeApprovalReturnsNewVersionAndNoProcurementCommitment(){
        String id=id(create("preapproval",10));incoming("already-coming",10);Map<String,Object> result=approve(id,1,"approval-now-no-need");
        assertEquals("NO_ACTION",result.get("status"));assertEquals(2L,result.get("planVersion"));assertEquals(true,result.get("replanRequired"));assertEquals(0,count("commerce_replenishment_draft"));
    }
    @Test public void rejectedAndCancelledTasksKeepVersionsAndAuditWithoutExecuting(){
        String id=id(create("reject-task",1));Map<String,Object> rejection=fixture.f.transaction(()->tasks.review(id,map("requestKey","reject","planVersion",1,"decision","REJECT","note","暂不采购"),3));assertEquals("REJECTED",rejection.get("status"));
        fails(409,()->fixture.f.transaction(()->tasks.execute(id,execution("rejected-exec",1),1)));
        String cancel=id(create("cancel-task",1));approve(cancel,1,"cancel-review");fixture.f.transaction(()->tasks.cancel(cancel,map("requestKey","cancel","reason","学习验收取消未执行承诺"),1));assertEquals("CANCELLED",tasks.get(cancel,1).get("status"));assertEquals(2,count("commerce_agent_task_version"));assertEquals(0,count("commerce_incoming"));
    }
    @Test public void failedExecutionRollsBackAndCanRetrySameTaskAfterCorrectingSupplierEvidence(){
        String id=id(create("failed-execution",1));approve(id,1,"failed-execution-review");Map<String,Object> bad=execution("failed-key",1);bad.put("expectedAt","not-a-date");fails(400,()->fixture.f.transaction(()->tasks.execute(id,bad,1)));
        assertEquals("APPROVED",tasks.get(id,1).get("status"));assertEquals(0,count("commerce_incoming"));assertEquals(0,count("commerce_supply_command"));
        assertEquals("EXECUTED",fixture.f.transaction(()->tasks.execute(id,execution("failed-key",1),1)).get("status"));
    }
    @Test public void ordinaryRequestsCannotPreoccupyTaskOrSupplierExecutionKeys(){
        String id=id(create("reserved-command",1));
        for(String reserved:Arrays.asList("BTPROP:"+id+":1","btprop:"+id+":1","BTREVIEW:"+id+":1","BTEXEC:"+id+":1","DRAFT:predictable-batch:1"))
            fails(400,()->tasks.assertStandaloneRequest(map("requestKey",reserved)));
        tasks.assertStandaloneRequest(map("requestKey","manual-procurement"));
        assertEquals("APPROVED",approve(id,1,"safe-approval").get("status"));
    }
    @Test public void executingOwnerCannotBorrowRevokedCreatorOrReviewerAuthorityButExactCompletedReplayWorks(){
        String id=id(create("revocation-after-approval",1));approve(id,1,"revocation-review");Map<String,Object> body=execution("revocation-command",1);
        fixture.jdbc.update("UPDATE commerce_shop_member SET member_role='VIEWER' WHERE user_id=3");fails(403,()->fixture.f.transaction(()->tasks.execute(id,body,1)));
        fixture.jdbc.update("UPDATE commerce_shop_member SET member_role='SUPPLY_REVIEWER' WHERE user_id=3");fixture.jdbc.update("UPDATE sys_user SET status='1' WHERE user_id=3");fails(403,()->fixture.f.transaction(()->tasks.execute(id,body,1)));
        fixture.jdbc.update("UPDATE sys_user SET status='0' WHERE user_id=3");fixture.jdbc.update("INSERT INTO sys_user VALUES(4,'0','0')");fixture.jdbc.update("INSERT INTO commerce_shop_member VALUES('default',4,'OWNER')");fixture.jdbc.update("UPDATE commerce_shop_member SET member_role='VIEWER' WHERE user_id=1");fails(403,()->fixture.f.transaction(()->tasks.execute(id,body,4)));
        assertEquals(0,count("commerce_incoming"));assertEquals("APPROVED",tasks.get(id,4).get("status"));
        fixture.jdbc.update("UPDATE commerce_shop_member SET member_role='OWNER' WHERE user_id=1");Map<String,Object> first=fixture.f.transaction(()->tasks.execute(id,body,1));
        fixture.jdbc.update("UPDATE commerce_shop_member SET member_role='VIEWER' WHERE user_id=3");assertEquals(first.get("result"),fixture.f.transaction(()->tasks.execute(id,body,1)).get("result"));assertEquals(1,count("commerce_incoming"));
    }
    @Test public void receiptWriterCannotChangeSelectedProductAfterFactsReadBeforeExecutionCommit() throws Exception {
        CountDownLatch factsRead=new CountDownLatch(1),resume=new CountDownLatch(1),receiptStarted=new CountDownLatch(1);AtomicBoolean pause=new AtomicBoolean(false);
        CommercePlanningService controlled=new CommercePlanningService(fixture.jdbc.getDataSource(),fixture.stock,fixture.f.merchants){
            @Override public Map<String,Object> replenishment(long actor){Map<String,Object> result=super.replenishment(actor);if(pause.compareAndSet(true,false)){factsRead.countDown();try{if(!resume.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Test facts gate timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}return result;}
        };
        tasks=new CommerceAgentTaskService(fixture.jdbc.getDataSource(),controlled,fixture.f.merchants,clock);String id=id(create("receipt-execution-race",1));approve(id,1,"race-review");Map<String,Object> body=execution("race-execution",1);pause.set(true);
        ExecutorService workers=Executors.newFixedThreadPool(2);
        try {
            Future<Map<String,Object>> execution=workers.submit(()->fixture.f.transaction(()->tasks.execute(id,body,1)));assertTrue(factsRead.await(5,TimeUnit.SECONDS));
            Future<?> receipt=workers.submit(()->fixture.f.transaction(()->{receiptStarted.countDown();fixture.jdbc.queryForList("SELECT product_id FROM product WHERE product_id=1 FOR UPDATE");fixture.jdbc.update("UPDATE product SET inventory_qty=inventory_qty+1 WHERE product_id=1");fixture.jdbc.update("UPDATE inventory_product SET plan_quantity=plan_quantity+1 WHERE product_id=1");fixture.stock.ensureStock(1,"受控并发入库证据");return null;}));
            assertTrue(receiptStarted.await(5,TimeUnit.SECONDS));try{receipt.get(150,TimeUnit.MILLISECONDS);fail("Receipt passed product lock before execution committed");}catch(TimeoutException expected){}
            resume.countDown();assertEquals("EXECUTED",execution.get(5,TimeUnit.SECONDS).get("status"));receipt.get(5,TimeUnit.SECONDS);assertEquals(11L,fixture.stock.balances(1).get("bookStock"));assertEquals(1,count("commerce_incoming"));
        } finally {resume.countDown();workers.shutdownNow();}
    }
    @Test public void sameTimestampAndScrambledEventIdsStillReturnBusinessTransitionOrder(){
        String id=id(create("audit-order",10));approve(id,1,"audit-review-v1");incoming("audit-supply-change",4);
        fixture.f.transaction(()->tasks.refresh(id,map("requestKey","audit-refresh"),1));approve(id,2,"audit-review-v2");fixture.f.transaction(()->tasks.execute(id,execution("audit-execute",2),1));
        // The injected fixed clock gives all task events one timestamp. Force
        // reverse UUID lexical order to prove timestamps/IDs cannot determine it.
        for(Object[] event:Arrays.asList(new Object[]{1,"CREATED","z-created"},new Object[]{1,"APPROVED","y-approved-v1"},new Object[]{2,"REPLANNED","x-replanned"},new Object[]{2,"APPROVED","w-approved-v2"},new Object[]{2,"EXECUTED","a-executed"}))
            fixture.jdbc.update("UPDATE commerce_agent_task_event SET event_id=? WHERE task_id=? AND plan_version=? AND action=?",event[2],id,event[0],event[1]);
        assertEquals(1L,(long)fixture.jdbc.queryForObject("SELECT COUNT(DISTINCT created_at) FROM commerce_agent_task_event WHERE task_id=?",Long.class,id));
        List<String> transitions=new ArrayList<>();for(Object raw:(List<?>)tasks.get(id,1).get("events")){Map<?,?> event=(Map<?,?>)raw;transitions.add(((Number)event.get("planVersion")).longValue()+":"+event.get("action"));}
        assertEquals(Arrays.asList("1:CREATED","1:APPROVED","2:REPLANNED","2:APPROVED","2:EXECUTED"),transitions);
    }
}

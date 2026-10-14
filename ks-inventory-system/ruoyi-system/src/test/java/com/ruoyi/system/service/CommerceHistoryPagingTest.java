package com.ruoyi.system.service;

import com.ruoyi.common.core.tenant.CommerceShopContext;
import com.ruoyi.common.core.tenant.TenantContext;
import com.ruoyi.common.exception.ServiceException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import java.util.*;
import java.util.function.Function;
import static org.junit.Assert.*;

/** Old unresolved rows remain actionable beyond the bounded Agent/smoke array lists. */
public class CommerceHistoryPagingTest {
    private CommercePlanningServiceTest fixture;
    private CommerceAgentTaskService tasks;
    private CommerceCostService costs;

    @Before public void setup()throws Exception {
        TenantContext.clear();CommerceShopContext.clear();fixture=new CommercePlanningServiceTest();fixture.setup();
        tasks=new CommerceAgentTaskService(fixture.jdbc.getDataSource(),fixture.planning,fixture.f.merchants);
        costs=new CommerceCostService(fixture.jdbc.getDataSource());
        seed(fixture.jdbc,"A","demo","default",135);
    }
    @After public void clear(){TenantContext.clear();CommerceShopContext.clear();}

    private static void seed(JdbcTemplate db,String prefix,String tenant,String shop,int count) {
        for(int i=0;i<count;i++){
            String id=prefix+String.format("%04d",i),state=i<5?"WAITING_APPROVAL":"EXECUTED";
            db.update("INSERT INTO commerce_agent_task(task_id,tenant_id,shop_id,request_key,request_hash,goal,requested_json,planner_mode,status,plan_version,fact_hash,fact_date,plan_json,created_by,created_at,updated_at) VALUES (?,?,?,?,?,'History task','[]','rules',?,1,?,CURRENT_DATE,'[]',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)","T"+id,tenant,shop,"T"+id,"hash",state,"hash");
            db.update("INSERT INTO commerce_replenishment_draft(draft_id,shop_id,request_key,request_hash,status,snapshot_json,actor_id,created_at) VALUES (?,?,?,? ,?,'[]',1,CURRENT_TIMESTAMP)","D"+id,shop,"D"+id,"hash",i<5?"PENDING_APPROVAL":"RECEIVED");
            db.update("INSERT INTO commerce_incoming(incoming_id,shop_id,request_key,request_hash,product_id,warehouse_id,source_reference,quantity,received_quantity,expected_at,status,actor_id,created_at) VALUES (?,?,?,?,1,1,'Supplier confirmation',10,?,TIMESTAMPADD(DAY,-1,CURRENT_TIMESTAMP),?,1,CURRENT_TIMESTAMP)","I"+id,shop,"I"+id,"hash",i<5?0:10,i<5?"CONFIRMED":"RECEIVED");
            db.update("INSERT INTO commerce_incoming_change(change_id,shop_id,incoming_id,action,request_key,request_hash,status,before_hash,before_json,after_json,reason,source_reference,actor_id,created_at) VALUES (?,?,?,'DELAY',?,?,?,?,'{}','{}','Delay','Supplier notice',1,CURRENT_TIMESTAMP)","C"+id,shop,"I"+id,"C"+id,"hash",i<5?"PENDING_APPROVAL":"APPLIED","hash");
            db.update("INSERT INTO commerce_money_reconciliation(reconciliation_id,shop_id,order_id,request_key,facts_hash,result_json,healthy,actor_id,created_at) VALUES (?,?,?,? ,?,'{}',?,1,CURRENT_TIMESTAMP)","R"+id,shop,"O"+id,"R"+id,"hash",i<5?0:1);
        }
    }
    private List<Function<Map<String,String>,Map<String,Object>>> readers(){return readers(tasks,fixture.planning,costs);}
    private static List<Function<Map<String,String>,Map<String,Object>>> readers(CommerceAgentTaskService tasks,CommercePlanningService planning,CommerceCostService costs){
        return Arrays.asList(q->tasks.page(1,q),q->planning.draftsPage(1,q),q->planning.incomingPage(1,q),q->planning.incomingChangesPage(1,q),q->costs.reconciliationsPage(1,q));
    }
    private static Map<String,String> query(String... values){Map<String,String> q=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)q.put(values[i],values[i+1]);return q;}
    private static List<Map<String,Object>> rows(Map<String,Object> page){return (List<Map<String,Object>>)page.get("rows");}
    private static void rejected(int code,Runnable action){try{action.run();fail("Expected rejection");}catch(ServiceException failure){assertEquals(Integer.valueOf(code),failure.getCode());}}

    @Test public void pagePastLegacyLimitsRetainsEveryRecordAndFiltersOldUnfinishedRowsInSql(){
        assertEquals(100,tasks.list(1).size());assertEquals(50,fixture.planning.drafts(1).size());assertEquals(100,fixture.planning.incoming(1).size());assertEquals(100,fixture.planning.incomingChanges(1).size());assertEquals(100,costs.reconciliations(1).size());
        String[] fields={"taskId","draftId","incomingId","changeId","reconciliationId"};String[] pending={"UNFINISHED","UNFINISHED","UNFINISHED","PENDING_APPROVAL","OPEN"};
        for(int i=0;i<readers().size();i++){
            Function<Map<String,String>,Map<String,Object>> read=readers().get(i);
            Map<String,Object> first=read.apply(query("pageNum","1","pageSize","100")),second=read.apply(query("pageNum","2","pageSize","100"));
            assertEquals(135L,first.get("total"));assertEquals(135L,second.get("total"));assertEquals(100,rows(first).size());assertEquals(35,rows(second).size());
            Set<Object> ids=new HashSet<>();for(Map<String,Object> row:rows(first))assertTrue(ids.add(row.get(fields[i])));for(Map<String,Object> row:rows(second))assertTrue(ids.add(row.get(fields[i])));assertEquals(135,ids.size());
            Map<String,Object> unresolved=read.apply(query("status",pending[i],"pageSize","2","pageNum","3"));
            assertEquals(5L,unresolved.get("total"));assertEquals(1,rows(unresolved).size());assertTrue(String.valueOf(rows(unresolved).get(0).get(fields[i])).endsWith("0000"));
            Map<String,Object> beyond=read.apply(query("pageNum","1000000","pageSize","100"));assertEquals(135L,beyond.get("total"));assertTrue(rows(beyond).isEmpty());
        }
        Map<String,Object> defaults=tasks.page(1,query());assertEquals(1,defaults.get("pageNum"));assertEquals(20,defaults.get("pageSize"));assertEquals(20,rows(defaults).size());
    }

    @Test public void computedResolutionAndOutstandingFiltersMatchTheirReturnedStates(){
        fixture.jdbc.update("INSERT INTO commerce_money_resolution VALUES ('RESOLVED','RA0000','default','resolve','hash','Evidence','Verified','{}',1,CURRENT_TIMESTAMP)");
        fixture.jdbc.update("INSERT INTO commerce_money_resolution VALUES ('FOREIGN','RA0001','other','foreign','hash','Evidence','Verified','{}',1,CURRENT_TIMESTAMP)");
        assertEquals(4L,costs.reconciliationsPage(1,query("status","OPEN")).get("total"));
        Map<String,Object> resolved=costs.reconciliationsPage(1,query("status","RESOLVED"));assertEquals(1L,resolved.get("total"));assertEquals("RESOLVED",rows(resolved).get(0).get("status"));
        assertEquals(130L,costs.reconciliationsPage(1,query("status","MATCHED")).get("total"));
        fixture.jdbc.update("INSERT INTO commerce_incoming_resolution VALUES ('IA0000','default',10,1,CURRENT_TIMESTAMP)");
        fixture.jdbc.update("UPDATE commerce_incoming SET expected_at=TIMESTAMPADD(DAY,1,CURRENT_TIMESTAMP) WHERE incoming_id='IA0001'");
        assertEquals(4L,fixture.planning.incomingPage(1,query("status","UNFINISHED")).get("total"));
        assertEquals(3L,fixture.planning.incomingPage(1,query("status","OVERDUE")).get("total"));
        assertEquals(true,rows(fixture.planning.incomingPage(1,query("status","UNFINISHED"))).get(0).get("pendingChange"));
    }

    @Test public void invalidPaginationStatusesAndCallerScopeOverridesAreRejected(){
        for(Function<Map<String,String>,Map<String,Object>> read:readers()){
            for(String value:Arrays.asList("0","-1","1.5","1e2"," 1","01","1000001","99999999999999999999"))rejected(400,()->read.apply(query("pageNum",value)));
            for(String value:Arrays.asList("0","101","2147483647",""))rejected(400,()->read.apply(query("pageSize",value)));
            for(Map<String,String> invalid:Arrays.asList(query("status","UNKNOWN"),query("status","OPEN' OR 1=1"),query("tenantId","studio"),query("shopId","other")))rejected(400,()->read.apply(invalid));
        }
        rejected(403,()->costs.reconciliationsPage(2,query()));
        CommerceShopContext.set("forbidden");for(Function<Map<String,String>,Map<String,Object>> read:readers())rejected(403,()->read.apply(query()));
    }

    @Test public void allPagedHistoriesRetainShopAuthorizationAndTenantDataSourceIsolation()throws Exception {
        fixture.jdbc.update("INSERT INTO commerce_shop(shop_id,shop_name) VALUES ('other','Other')");fixture.jdbc.update("INSERT INTO commerce_shop_member VALUES ('other',1,'OWNER')");seed(fixture.jdbc,"B","demo","other",3);
        for(Function<Map<String,String>,Map<String,Object>> read:readers())assertEquals(135L,read.apply(query()).get("total"));
        CommerceShopContext.set("other");for(Function<Map<String,String>,Map<String,Object>> read:readers())assertEquals(3L,read.apply(query()).get("total"));CommerceShopContext.clear();
        // Tasks also retain explicit tenant predicates even with a shared connection fixture.
        TenantContext.set("studio");assertEquals(0L,tasks.page(1,query()).get("total"));TenantContext.clear();
        CommercePlanningServiceTest other=new CommercePlanningServiceTest();other.setup();seed(other.jdbc,"S","studio","default",2);
        AbstractRoutingDataSource routing=new AbstractRoutingDataSource(){@Override protected Object determineCurrentLookupKey(){return TenantContext.id();}};
        Map<Object,Object> sources=new HashMap<>();sources.put("demo",fixture.jdbc.getDataSource());sources.put("studio",other.jdbc.getDataSource());routing.setTargetDataSources(sources);routing.afterPropertiesSet();
        CommerceMerchantService merchant=new CommerceMerchantService(routing);CommercePlanningService planning=new CommercePlanningService(routing,new CommerceInventoryService(routing),merchant);
        List<Function<Map<String,String>,Map<String,Object>>> routed=readers(new CommerceAgentTaskService(routing,planning,merchant),planning,new CommerceCostService(routing));
        for(Function<Map<String,String>,Map<String,Object>> read:routed)assertEquals(135L,read.apply(query()).get("total"));
        TenantContext.set("studio");for(Function<Map<String,String>,Map<String,Object>> read:routed){Map<String,Object> page=read.apply(query());assertEquals(2L,page.get("total"));assertTrue(rows(page).get(0).toString().contains("S0001"));}
        TenantContext.clear();for(Function<Map<String,String>,Map<String,Object>> read:routed)assertEquals(135L,read.apply(query()).get("total"));
    }
}

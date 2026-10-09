package com.ruoyi.system.service;

import com.ruoyi.common.core.tenant.CommerceShopContext;
import com.ruoyi.common.exception.ServiceException;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.util.StreamUtils;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;
import static com.ruoyi.system.service.CommerceServiceTest.map;

public class CommercePlanningServiceTest {
    protected CommerceServiceTest f;
    protected CommercePlanningService planning;
    protected CommerceInventoryService stock;
    protected JdbcTemplate jdbc;
    @Before public void setup() throws Exception {
        f=new CommerceServiceTest();f.setup();jdbc=f.jdbc;
        String sql=StreamUtils.copyToString(new ClassPathResource("db/commerce-planning.sql").getInputStream(),StandardCharsets.UTF_8).replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","");
        new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))).execute(jdbc.getDataSource());
        jdbc.execute("ALTER TABLE product ADD COLUMN lower_limit BIGINT DEFAULT 0");jdbc.execute("ALTER TABLE product ADD COLUMN upper_limit BIGINT DEFAULT 0");
        jdbc.execute("CREATE TABLE warehouse(warehouse_id BIGINT PRIMARY KEY)");jdbc.update("INSERT INTO warehouse VALUES (1)");
        stock=new CommerceInventoryService(jdbc.getDataSource());planning=new CommercePlanningService(jdbc.getDataSource(),stock,f.merchants);
        planning.initializeSupplySchema();
        jdbc.update("INSERT INTO commerce_shop_member VALUES ('default',3,'SUPPLY_REVIEWER')");
    }
    @After public void clear(){CommerceShopContext.clear();}
    private void publish(){f.transaction(()->{f.merchants.listing(1,true);return null;});}
    private Map<String,Object> quote(long quantity,long units){return planning.quote(map("items",Arrays.asList(map("productId",1,"quantity",quantity)),"units",units));}
    private Map<String,Object> change(String key,String from,String to,int qty){return f.transaction(()->planning.condition(map("requestKey",key,"productId",1,"warehouseId",1,"from",from,"to",to,"quantity",qty,"reason","inspection evidence"),1));}
    private void fails(int code,Runnable run){try{run.run();fail("Expected rejection");}catch(ServiceException e){assertEquals(Integer.valueOf(code),e.getCode());}}

    @Test public void quoteNeverBootstrapsAndAccessoryIsWholeBundleBottleneck(){
        jdbc.update("DELETE FROM commerce_delivery_policy WHERE shop_id='default'");
        long events=jdbc.queryForObject("SELECT COUNT(*) FROM commerce_stock_ledger",Long.class);
        assertEquals(0L,quote(1,1).get("promisableUnits"));assertEquals(events,(long)jdbc.queryForObject("SELECT COUNT(*) FROM commerce_stock_ledger",Long.class));
        publish();jdbc.update("INSERT INTO product(product_id,product_code,product_name,status,univalence,inventory_qty) VALUES (2,'HUB','Hub','0',50,2)");
        jdbc.update("INSERT INTO inventory_product(inventory_id,product_id,warehouse_id,plan_quantity) VALUES (2,2,1,2)");
        f.transaction(()->{f.merchants.listing(2,true);return null;});
        Map<String,Object> q=planning.quote(map("items",Arrays.asList(map("productId",1,"quantity",3),map("productId",2,"quantity",1)),"units",4));
        assertEquals(2L,q.get("promisableUnits"));assertEquals("1748.00",q.get("totalAmount").toString());
        assertEquals(10L,stock.balances(1).get("availableStock"));assertNull(q.get("deliveryCapacity"));assertNull(q.get("deliveryDate"));
        assertFalse(q.toString().contains("cost_price"));assertFalse(q.toString().contains("supplier"));
    }
    @Test public void capacityComesFromServerAndUnknownArrivalStaysUnknown(){
        publish();fails(400,()->planning.quote(map("items",Arrays.asList(map("productId",1,"quantity",1)),"deliveryCapacity",999)));
        f.transaction(()->planning.savePolicy(map("dailyItemCapacity",5,"dispatchDays",2),1));
        assertEquals(1L,quote(3,2).get("deliveryCapacity"));assertEquals(1L,quote(3,2).get("promisableUnits"));assertNull(quote(1,1).get("deliveryDate"));
        fails(403,()->f.transaction(()->planning.savePolicy(map("dailyItemCapacity",50,"dispatchDays",1),2)));
    }
    @Test public void conditionsPreservePhysicalStockAndExplainTransitionsIdempotently(){
        publish();Map<String,Object> first=change("quality-1","SELLABLE","QUALITY_HOLD",3);
        assertEquals(first,change("quality-1","SELLABLE","QUALITY_HOLD",3));
        fails(409,()->change("quality-1","SELLABLE","QUALITY_HOLD",4));
        change("damage-1","QUALITY_HOLD","DAMAGED",2);
        assertEquals(7L,quote(1,1).get("promisableUnits"));assertEquals(10L,(long)jdbc.queryForObject("SELECT SUM(plan_quantity) FROM inventory_product",Long.class));
        assertEquals(1L,(long)jdbc.queryForObject("SELECT quality_hold FROM commerce_warehouse_condition",Long.class));
        assertEquals(2L,(long)jdbc.queryForObject("SELECT damaged FROM commerce_warehouse_condition",Long.class));
        assertEquals(3L,(long)jdbc.queryForObject("SELECT unavailable FROM commerce_stock",Long.class));
        assertEquals(2,planning.conditions(1).size());change("repair-1","DAMAGED","SELLABLE",2);assertEquals(9L,quote(1,1).get("promisableUnits"));
        fails(409,()->change("bad-1","QUALITY_HOLD","SELLABLE",2));
    }
    @Test public void actualDispatchConsumesTodayAndReleasesFuturePromiseBucket(){
        publish();f.transaction(()->planning.savePolicy(map("dailyItemCapacity",8,"dispatchDays",1),1));
        String order=f.id(f.create("yesterday",4));
        f.transaction(()->f.service.pay(order,map("ownerId",CommerceServiceTest.OWNER,"paymentRequestId","pay","scenario","success")));
        jdbc.update("UPDATE commerce_order SET paid_time=TIMESTAMPADD(DAY,-1,CURRENT_TIMESTAMP) WHERE order_id=?",order);
        assertEquals(4L,quote(1,1).get("deliveryCapacity"));
        f.transaction(()->f.service.ship(order,map("requestKey","first","items",Arrays.asList(map("productId",1,"quantity",2))),1));
        assertEquals(6L,quote(1,1).get("deliveryCapacity"));
        f.transaction(()->f.service.ship(order,map("requestKey","second","items",Arrays.asList(map("productId",1,"quantity",2))),1));
        assertEquals(8L,quote(1,1).get("deliveryCapacity"));
        assertEquals(4L,(long)jdbc.queryForObject("SELECT consumed_quantity FROM commerce_delivery_bucket WHERE shop_id='default' AND dispatch_date=CURRENT_DATE",Long.class));
    }
    @Test public void unavailableCannotInvadeExistingOrderOrActivityHolds(){
        publish();f.create("test-hold",4);
        fails(409,()->change("too-many","SELLABLE","QUALITY_HOLD",7));
        assertEquals(0L,(long)jdbc.queryForObject("SELECT COUNT(*) FROM commerce_condition_event",Long.class));
        assertEquals(6L,quote(1,1).get("promisableUnits"));
    }
    @Test public void replenishmentDraftRequiresReviewAndDoesNotPostStock(){
        publish();jdbc.update("UPDATE product SET lower_limit=12,upper_limit=20 WHERE product_id=1");
        Map<String,Object> report=planning.replenishment(1);Map<String,Object> row=(Map<String,Object>)((List<?>)report.get("items")).get(0);
        assertEquals(10L,row.get("suggestedQuantity"));assertEquals(false,row.get("leadTimeKnown"));assertEquals(false,row.get("incomingKnown"));
        Map<String,Object> body=map("requestKey","draft-1","items",Arrays.asList(map("productId",1,"quantity",10)));
        Map<String,Object> draft=f.transaction(()->planning.createDraft(body,1));assertEquals("PENDING_APPROVAL",draft.get("status"));
        assertEquals(draft,f.transaction(()->planning.createDraft(body,1)));fails(403,()->f.transaction(()->planning.createDraft(map("requestKey","viewer","items",body.get("items")),2)));
        String id=(String)draft.get("draftId");Map<String,Object> review=map("requestKey","review-1","decision","APPROVE","note","人工核对供货交期后执行采购");
        fails(403,()->f.transaction(()->planning.reviewDraft(id,review,1)));
        Map<String,Object> approved=f.transaction(()->planning.reviewDraft(id,review,3));assertEquals("APPROVED",approved.get("status"));assertEquals("NOT_EXECUTED",approved.get("executionStatus"));
        assertEquals(approved,f.transaction(()->planning.reviewDraft(id,review,3)));assertEquals(10L,stock.balances(1).get("availableStock"));
        fails(409,()->f.transaction(()->planning.reviewDraft(id,map("requestKey","review-2","decision","REJECT","note","changed"),3)));
    }
    @Test public void incomingNeedsPostedReceiptAndNeverAddsPhysicalStockTwice(){
        publish();Map<String,Object> body=map("requestKey","incoming-1","productId",1,"warehouseId",1,"quantity",4,"sourceReference","supplier confirmation SAMPLE-001","expectedAt","2026-12-01T12:00:00");
        Map<String,Object> incoming=f.transaction(()->planning.registerIncoming(body,1));String id=(String)incoming.get("incomingId");
        assertEquals(incoming,f.transaction(()->planning.registerIncoming(body,1)));
        fails(409,()->f.transaction(()->planning.receiveIncoming(id,map("receiptId","NOT-POSTED"),1)));
        jdbc.update("INSERT INTO head_receipt(systematic_receipt,receipt_category,receipt_type,receipt_status,warehousing_ids) VALUES ('ERP-001','1','1','2',1)");
        jdbc.update("INSERT INTO detail_receipt(systematic_receipt,product_id,warehousing_id,plan_quantity) VALUES ('ERP-001',1,1,4)");
        Map<String,Object> received=f.transaction(()->planning.receiveIncoming(id,map("receiptId","ERP-001"),1));assertEquals("RECEIVED",received.get("status"));
        assertEquals(received,f.transaction(()->planning.receiveIncoming(id,map("receiptId","ERP-001"),1)));assertEquals(10L,stock.balances(1).get("availableStock"));
        assertEquals(1L,(long)jdbc.queryForObject("SELECT COUNT(*) FROM commerce_incoming_receipt",Long.class));
        CommerceShopContext.set("other");fails(403,()->planning.incoming(1));fails(404,()->quote(1,1));
    }
    @Test public void approvedDraftRechecksCurrentDemandInsteadOfTrustingOldSnapshot(){
        publish();jdbc.update("UPDATE product SET lower_limit=12,upper_limit=20 WHERE product_id=1");
        Map<String,Object> draft=f.transaction(()->planning.createDraft(map("requestKey","stale","items",Arrays.asList(map("productId",1,"quantity",10))),1));
        jdbc.update("UPDATE product SET lower_limit=5,upper_limit=10 WHERE product_id=1");
        fails(409,()->f.transaction(()->planning.reviewDraft((String)draft.get("draftId"),map("requestKey","stale-review","decision","APPROVE","note","approve"),3)));
        assertEquals("PENDING_APPROVAL",planning.drafts(1).get(0).get("status"));
    }
}

package com.ruoyi.system.service;

import com.ruoyi.common.core.tenant.CommerceShopContext;
import com.ruoyi.common.exception.ServiceException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.junit.Assert.*;

public class CommerceSettlementServiceTest {
    private JdbcTemplate jdbc;private CommerceSettlementService service;private TransactionTemplate tx;
    @Before public void setup(){
        JdbcDataSource source=new JdbcDataSource();source.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        jdbc=new JdbcTemplate(source);service=new CommerceSettlementService(source);
        new ResourceDatabasePopulator(new ClassPathResource("db/commerce-merchant.sql")).execute(source);service.initializeSchema();
        jdbc.execute("CREATE TABLE commerce_order(order_id VARCHAR(32) PRIMARY KEY,shop_id VARCHAR(32),status INT,total_amount DECIMAL(14,2),refunded_amount DECIMAL(14,2),paid_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE commerce_channel_entry(order_id VARCHAR(32),shop_id VARCHAR(32),movement VARCHAR(20),amount DECIMAL(14,2))");
        jdbc.execute("CREATE TABLE commerce_payment_operation(order_id VARCHAR(32),shop_id VARCHAR(32),local_status VARCHAR(20),kind VARCHAR(20),amount DECIMAL(14,2))");
        jdbc.execute("CREATE TABLE commerce_cost_entry(shop_id VARCHAR(32),order_id VARCHAR(32),event_type VARCHAR(20),source_id VARCHAR(40),cost_status VARCHAR(20),amount DECIMAL(18,4))");
        jdbc.execute("CREATE TABLE commerce_shipment(shipment_id VARCHAR(32),order_id VARCHAR(32),receipt_id VARCHAR(32))");
        jdbc.execute("CREATE TABLE commerce_after_sales_case(after_sales_id VARCHAR(32),shop_id VARCHAR(32),order_id VARCHAR(32),status VARCHAR(20),return_receipt_id VARCHAR(32),return_condition VARCHAR(20))");
        jdbc.execute("CREATE TABLE commerce_order_item(order_id VARCHAR(32),product_id BIGINT,product_code VARCHAR(40),product_name VARCHAR(40),spec VARCHAR(40),quantity BIGINT)");
        jdbc.execute("CREATE TABLE commerce_stock_hold(order_id VARCHAR(32),product_id BIGINT,quantity BIGINT,status VARCHAR(20))");
        jdbc.execute("CREATE TABLE commerce_warehouse_ledger(ledger_id BIGINT AUTO_INCREMENT PRIMARY KEY,event_key VARCHAR(80),operation VARCHAR(20),receipt_id VARCHAR(32),product_id BIGINT,warehouse_id BIGINT,delta_quantity BIGINT,before_quantity BIGINT,after_quantity BIGINT)");
        jdbc.update("INSERT INTO commerce_shop(shop_id,shop_name) VALUES ('default','学习店'),('other','其他店')");
        jdbc.update("INSERT INTO commerce_shop_member VALUES ('default',1,'OWNER'),('default',2,'VIEWER'),('default',3,'FINANCE_REVIEW'),('default',4,'FINANCE_EXECUTE')");
        jdbc.update("INSERT INTO commerce_order VALUES ('SC_TEST','default',3,300,95,CURRENT_TIMESTAMP),('SC_OTHER','other',3,100,0,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO commerce_channel_entry VALUES ('SC_TEST','default','CHARGE',300),('SC_TEST','default','REFUND',95)");
        jdbc.update("INSERT INTO commerce_payment_operation VALUES ('SC_TEST','default','SUCCEEDED','PAYMENT',300),('SC_TEST','default','SUCCEEDED','REFUND',95)");
        jdbc.update("INSERT INTO commerce_cost_entry VALUES ('default','SC_TEST','DISPATCH','SHIP1','KNOWN',-140),('default','SC_TEST','RETURN','AS1','KNOWN',20)");
        jdbc.update("INSERT INTO commerce_shipment VALUES ('SHIP1','SC_TEST','OUT1')");
        jdbc.update("INSERT INTO commerce_after_sales_case VALUES ('AS1','default','SC_TEST','REFUNDED','RT1','SELLABLE')");
        tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);CommerceShopContext.set("default");
    }
    @After public void cleanup(){CommerceShopContext.clear();}
    private <T>T run(Supplier<T> task){return tx.execute(status->task.get());}
    private static Map<String,Object> map(Object...pairs){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
    private void rejects(int code,Runnable action){try{action.run();fail("Expected rejection");}catch(ServiceException error){assertEquals(Integer.valueOf(code),error.getCode());}}
    private Map<String,Object> expense(String key,String amount){return map("requestKey",key,"category","LOGISTICS","amount",amount,"evidenceReference","FICTIONAL-INVOICE-"+key,"payee","学习物流");}
    private String create(String key,String amount){run(()->service.expense("SC_TEST",expense(key,amount),1));return jdbc.queryForObject("SELECT expense_id FROM commerce_order_expense WHERE request_key=?",String.class,key);}
    private void approve(String id){run(()->service.review(id,map("requestKey","review_"+id,"decision","APPROVE"),3));}
    private Map<String,Object> payment(String key,Object... allocations){return map("requestKey",key,"evidenceReference","LOCAL-SANDBOX-"+key,"allocations",Arrays.asList(allocations));}
    private long count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
    private void money(String expected,Object actual){assertEquals(0,new BigDecimal(expected).compareTo(new BigDecimal(String.valueOf(actual))));}

    @Test public void oneSandboxPaymentAllocatesTwoApprovedExpensesExactlyOnce(){
        String first=create("logistics","12"),second=create("packaging","5");approve(first);approve(second);
        Map<String,Object> body=payment("pay",map("expenseId",first,"amount","12"),map("expenseId",second,"amount","5"));
        Map<String,Object> result=run(()->service.pay("SC_TEST",body,4));run(()->service.pay("SC_TEST",body,4));
        assertEquals(1,count("commerce_expense_payment"));assertEquals(2,count("commerce_expense_allocation"));money("17",result.get("settledExpenseAmount"));money("0",result.get("outstandingExpenseAmount"));money("68",result.get("operatingResult"));assertEquals(false,result.get("externalChannel"));
        body.put("evidenceReference","changed");rejects(409,()->run(()->service.pay("SC_TEST",body,4)));
    }
    @Test public void rejectedAllocationRollsBackWholePaymentAndCanRetry(){
        String first=create("one","10"),second=create("two","3");approve(first);approve(second);
        rejects(409,()->run(()->service.pay("SC_TEST",payment("retry",map("expenseId",first,"amount","5"),map("expenseId",second,"amount","4")),4)));
        assertEquals(0,count("commerce_expense_payment"));assertEquals(0,count("commerce_expense_allocation"));
        run(()->service.pay("SC_TEST",payment("retry",map("expenseId",first,"amount","5"),map("expenseId",second,"amount","3")),4));
        money("8",service.preview("SC_TEST",4).get("settledExpenseAmount"));
    }
    @Test public void approvalAndExecutionHaveSeparateCapabilities(){
        rejects(403,()->run(()->service.expense("SC_TEST",expense("bad","1"),2)));
        String id=create("ok","1");rejects(403,()->run(()->service.review(id,map("requestKey","r","decision","APPROVE"),4)));approve(id);
        rejects(403,()->run(()->service.pay("SC_TEST",payment("p",map("expenseId",id,"amount","1")),3)));
        rejects(403,()->service.preview("SC_TEST",2));assertNotNull(service.preview("SC_TEST",4));
    }
    @Test public void expenseReplayIsImmutableAndAnotherShopIsInvisible(){
        String id=create("same","2");run(()->service.expense("SC_TEST",expense("same","2"),1));assertEquals(1,count("commerce_order_expense"));
        rejects(409,()->run(()->service.expense("SC_TEST",expense("same","3"),1)));
        rejects(404,()->service.preview("SC_OTHER",1));CommerceShopContext.set("other");rejects(403,()->service.preview("SC_TEST",1));CommerceShopContext.set("default");approve(id);
        rejects(409,()->run(()->service.review(id,map("requestKey","later","decision","REJECT"),1)));
    }
    @Test public void unapprovedExpenseCannotSettleOrFinalizeProfit(){
        String id=create("draft","10");assertNull(service.preview("SC_TEST",1).get("operatingResult"));
        rejects(409,()->run(()->service.pay("SC_TEST",payment("p",map("expenseId",id,"amount","10")),4)));
        run(()->service.review(id,map("requestKey","reject","decision","REJECT"),3));money("85",service.preview("SC_TEST",1).get("operatingResult"));
    }
    @Test public void snapshotsNeverRewriteHistoryWhenLaterExpensesAppear(){
        Map<String,Object> first=run(()->service.snapshot("SC_TEST",map("requestKey","v1"),1));money("85",first.get("operatingResult"));
        String id=create("later","5");approve(id);Map<String,Object> replay=run(()->service.snapshot("SC_TEST",map("requestKey","v1"),1));money("85",replay.get("operatingResult"));
        Map<String,Object> next=run(()->service.snapshot("SC_TEST",map("requestKey","v2"),1));money("80",next.get("operatingResult"));assertNotEquals(first.get("factsHash"),next.get("factsHash"));assertEquals(2,count("commerce_order_settlement_snapshot"));
    }
    @Test public void missingChannelOrReturnEvidenceNeverProducesConfirmedProfit(){
        jdbc.update("DELETE FROM commerce_channel_entry WHERE movement='REFUND'");Map<String,Object> missing=service.preview("SC_TEST",1);assertEquals(false,missing.get("moneyComplete"));assertNull(missing.get("operatingResult"));
        jdbc.update("INSERT INTO commerce_channel_entry VALUES ('SC_TEST','default','REFUND',95)");jdbc.update("DELETE FROM commerce_cost_entry WHERE event_type='RETURN'");missing=service.preview("SC_TEST",1);assertEquals(false,missing.get("costComplete"));assertNull(missing.get("operatingResult"));
    }
    @Test public void quarantineReturnKeepsCostResultProvisional(){jdbc.update("UPDATE commerce_after_sales_case SET return_condition='QUALITY_HOLD'");assertEquals(false,service.preview("SC_TEST",1).get("costComplete"));assertNull(service.preview("SC_TEST",1).get("operatingResult"));}
    @Test public void legacyPaidStatusWithNoPaymentTimeStillNeedsChannelEvidence(){jdbc.update("UPDATE commerce_order SET paid_time=NULL WHERE order_id='SC_TEST'");jdbc.update("DELETE FROM commerce_channel_entry");jdbc.update("DELETE FROM commerce_payment_operation");assertEquals(false,service.preview("SC_TEST",1).get("moneyComplete"));assertNull(service.preview("SC_TEST",1).get("operatingResult"));}
    private void lateCharge(boolean compensated){
        jdbc.update("INSERT INTO commerce_order VALUES ('SC_LATE','default',4,129,0,NULL)");
        jdbc.update("INSERT INTO commerce_channel_entry VALUES ('SC_LATE','default','CHARGE',129)");
        jdbc.update("INSERT INTO commerce_payment_operation VALUES ('SC_LATE','default',?,'PAYMENT',129),('SC_LATE','default',?,'COMPENSATION',129)",compensated?"COMPENSATED":"COMPENSATION_PENDING",compensated?"SUCCEEDED":"PENDING");
        if(compensated)jdbc.update("INSERT INTO commerce_channel_entry VALUES ('SC_LATE','default','REFUND',129)");
    }
    @Test public void completedLateChargeCompensationClosesWithBothGrossChannelMovements(){
        lateCharge(true);Map<String,Object> result=service.preview("SC_LATE",1);
        assertEquals(true,result.get("moneyComplete"));assertEquals("CLOSED",result.get("resultStatus"));money("129",result.get("grossPaid"));money("129",result.get("refundedAmount"));money("0",result.get("netReceipts"));money("0",result.get("operatingResult"));
        money("0",jdbc.queryForObject("SELECT refunded_amount FROM commerce_order WHERE order_id='SC_LATE'",BigDecimal.class));
    }
    @Test public void pendingLateChargeCompensationCannotConfirmResult(){
        lateCharge(false);Map<String,Object> result=service.preview("SC_LATE",1);
        assertEquals(false,result.get("moneyComplete"));assertEquals("PROVISIONAL",result.get("resultStatus"));assertNull(result.get("operatingResult"));assertNull(result.get("provisionalContribution"));
    }
    @Test public void compensationWithMissingGrossEvidenceIsIncompleteEvenWhenChannelNetIsZero(){
        lateCharge(true);jdbc.update("DELETE FROM commerce_channel_entry WHERE order_id='SC_LATE'");Map<String,Object> result=service.preview("SC_LATE",1);
        money("0",result.get("netReceipts"));assertEquals(false,result.get("moneyComplete"));assertNull(result.get("operatingResult"));
    }
    @Test public void partialBusinessRefundAndCompensationRemainDistinctAgainstActualChannel(){
        jdbc.update("INSERT INTO commerce_channel_entry VALUES ('SC_TEST','default','CHARGE',30),('SC_TEST','default','REFUND',30)");
        jdbc.update("INSERT INTO commerce_payment_operation VALUES ('SC_TEST','default','COMPENSATED','PAYMENT',30),('SC_TEST','default','SUCCEEDED','COMPENSATION',30)");
        Map<String,Object> result=service.preview("SC_TEST",1);assertEquals(true,result.get("moneyComplete"));money("330",result.get("grossPaid"));money("125",result.get("refundedAmount"));money("205",result.get("netReceipts"));money("85",result.get("operatingResult"));
        jdbc.update("UPDATE commerce_payment_operation SET amount=94 WHERE order_id='SC_TEST' AND kind='REFUND'");assertEquals(false,service.preview("SC_TEST",1).get("moneyComplete"));assertNull(service.preview("SC_TEST",1).get("operatingResult"));
    }
    @Test public void singleShipmentReturnWarehouseEvidenceIsIncludedAndPiiExcluded(){
        jdbc.update("INSERT INTO commerce_order_item VALUES ('SC_TEST',1,'LAB','灯','WiFi',2)");
        jdbc.update("INSERT INTO commerce_warehouse_ledger(event_key,operation,receipt_id,product_id,warehouse_id,delta_quantity,before_quantity,after_quantity) VALUES ('ship','DISPATCH','OUT1',1,1,-2,10,8),('return','RETURN','RT1',1,1,1,8,9),('other','RETURN','FOREIGN',1,1,1,0,1)");
        Map<String,Object> facts=service.stockExplanation("SC_TEST",2);assertEquals(2,((List<?>)facts.get("warehouseEvents")).size());assertFalse(facts.containsKey("shippingAddress"));
    }
    @Test public void concurrentPaymentsCannotOverAllocate()throws Exception{
        String id=create("concurrent","10");approve(id);ExecutorService pool=Executors.newFixedThreadPool(2);
        try{List<Future<Integer>> attempts=new ArrayList<>();for(int i=0;i<2;i++){final int number=i;attempts.add(pool.submit(()->{CommerceShopContext.set("default");try{run(()->service.pay("SC_TEST",payment("p"+number,map("expenseId",id,"amount","8")),4));return 200;}catch(ServiceException error){return error.getCode();}finally{CommerceShopContext.clear();}}));}
            List<Integer> outcomes=Arrays.asList(attempts.get(0).get(10,TimeUnit.SECONDS),attempts.get(1).get(10,TimeUnit.SECONDS));Collections.sort(outcomes);assertEquals(Arrays.asList(200,409),outcomes);money("8",service.preview("SC_TEST",1).get("settledExpenseAmount"));
        }finally{pool.shutdownNow();}
    }
    @Test public void invalidMoneyAndDuplicateAllocationLeaveNoEntries(){
        rejects(400,()->run(()->service.expense("SC_TEST",expense("bad","1.001"),1)));assertEquals(0,count("commerce_order_expense"));
        String id=create("valid","1");approve(id);rejects(400,()->run(()->service.pay("SC_TEST",payment("dup",map("expenseId",id,"amount","0.5"),map("expenseId",id,"amount","0.5")),4)));assertEquals(0,count("commerce_expense_payment"));
    }
}

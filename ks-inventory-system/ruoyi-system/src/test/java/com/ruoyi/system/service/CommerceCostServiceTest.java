package com.ruoyi.system.service;

import com.ruoyi.common.core.tenant.CommerceShopContext;
import com.ruoyi.common.exception.ServiceException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StreamUtils;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.Assert.*;

/** SQL evidence tests use their own source fixtures; they never rewrite the shared stock tests. */
public class CommerceCostServiceTest {
    private CommerceCostService costs;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    @Before public void setup() throws Exception {
        CommerceShopContext.clear();JdbcDataSource source=new JdbcDataSource();
        source.setURL("jdbc:h2:mem:cost"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc=new JdbcTemplate(source);costs=new CommerceCostService(source);
        for(String resource:Arrays.asList("db/commerce-merchant.sql","db/commerce-payment.sql","db/commerce-cost-reconciliation.sql","db/commerce-procurement.sql","db/commerce-procurement-cost.sql")){
            String sql=StreamUtils.copyToString(new ClassPathResource(resource).getInputStream(),StandardCharsets.UTF_8).replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","");
            new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))).execute(source);
        }
        jdbc.execute("CREATE TABLE product(product_id BIGINT PRIMARY KEY,product_code VARCHAR(64),product_name VARCHAR(64),cost_price DECIMAL(14,2),univalence DECIMAL(14,2))");
        jdbc.execute("CREATE TABLE head_receipt(systematic_receipt VARCHAR(32) PRIMARY KEY,receipt_category VARCHAR(1),receipt_type VARCHAR(1),receipt_status VARCHAR(1),warehousing_ids BIGINT)");
        jdbc.execute("CREATE TABLE detail_receipt(systematic_id BIGINT AUTO_INCREMENT PRIMARY KEY,systematic_receipt VARCHAR(32),product_id BIGINT,warehousing_id BIGINT,retrieval_id BIGINT,plan_quantity BIGINT,univalence DECIMAL(14,2),cost DECIMAL(14,2),discount DECIMAL(5,2))");
        jdbc.execute("CREATE TABLE commerce_order(order_id VARCHAR(32) PRIMARY KEY,shop_id VARCHAR(32),status SMALLINT,total_amount DECIMAL(14,2),refunded_amount DECIMAL(14,2))");
        jdbc.execute("CREATE TABLE commerce_shipment(shipment_id VARCHAR(40) PRIMARY KEY,order_id VARCHAR(32),receipt_id VARCHAR(32))");
        jdbc.execute("CREATE TABLE commerce_shipment_item(shipment_id VARCHAR(40),product_id BIGINT,quantity BIGINT)");
        jdbc.execute("CREATE TABLE commerce_after_sales_case(after_sales_id VARCHAR(32) PRIMARY KEY,shop_id VARCHAR(32),order_id VARCHAR(32),status VARCHAR(32),return_receipt_id VARCHAR(32),refunded_amount DECIMAL(14,2))");
        jdbc.execute("CREATE TABLE commerce_return_allocation(after_sales_id VARCHAR(32),source_receipt_id VARCHAR(32),product_id BIGINT,warehouse_id BIGINT,quantity BIGINT)");
        jdbc.update("INSERT INTO commerce_shop(shop_id,shop_name) VALUES ('default','Cost test'),('other','Other shop')");
        jdbc.update("INSERT INTO commerce_shop_member VALUES ('default',1,'OWNER'),('default',2,'VIEWER'),('default',3,'FINANCE_REVIEW'),('default',4,'CUSTOMER_SERVICE'),('other',1,'OWNER')");
        jdbc.update("INSERT INTO product VALUES (1,'COST-SKU','Document cost example',70,129)");
        jdbc.update("INSERT INTO commerce_product_shop VALUES (1,'default',1)");
        jdbc.update("INSERT INTO commerce_order VALUES ('ORDER_1','default',2,258,0)");
        tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    @After public void cleanup(){CommerceShopContext.clear();}
    private <T>T run(Supplier<T> task){return tx.execute(status->task.get());}
    private void hook(Runnable task){run(()->{task.run();return null;});}
    private static Map<String,Object> map(Object...pairs){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m;}
    private long count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
    private BigDecimal amount(String where){return jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM commerce_cost_entry WHERE "+where,BigDecimal.class);}
    private void rejected(int code,Runnable task){try{task.run();fail("Expected business rejection");}catch(ServiceException failure){assertEquals(Integer.valueOf(code),failure.getCode());}}
    private void purchase(String status){jdbc.update("INSERT INTO head_receipt VALUES ('PURCHASE_1','1','1',?,1)",status);jdbc.update("INSERT INTO detail_receipt(systematic_receipt,product_id,warehousing_id,plan_quantity,univalence,cost) VALUES ('PURCHASE_1',1,1,2,70,70)");}
    private void shipment(Object cost){jdbc.update("INSERT INTO commerce_shipment VALUES ('SHIP_1','ORDER_1','OUT_1')");jdbc.update("INSERT INTO commerce_shipment_item VALUES ('SHIP_1',1,2)");jdbc.update("INSERT INTO detail_receipt(systematic_receipt,product_id,retrieval_id,plan_quantity,univalence,cost) VALUES ('OUT_1',1,1,2,129,?)",cost);}
    private void returning(String id,long quantity){jdbc.update("INSERT INTO commerce_after_sales_case VALUES (?,'default','ORDER_1','RETURN_RECEIVED',?,0)",id,"RT_"+id);jdbc.update("INSERT INTO commerce_return_allocation VALUES (?,'OUT_1',1,1,?)",id,quantity);}
    private void payment(String operation,String kind,BigDecimal amount,String status){jdbc.update("INSERT INTO commerce_payment_operation(operation_id,shop_id,order_id,after_sales_id,kind,business_key,request_key,scenario,amount,actor_id,local_status,provider_status,provider_reference,created_at,updated_at) VALUES (?,'default','ORDER_1',?,?,?,?,?,?,1,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",operation,"REFUND".equals(kind)?"CASE_1":null,kind,operation,operation,"success",amount,status,status,"PROVIDER_"+operation);if("SUCCEEDED".equals(status))jdbc.update("INSERT INTO commerce_channel_entry VALUES (?,?,'default','ORDER_1',?,?,'CNY',CURRENT_TIMESTAMP)","CE_"+operation,operation,"PAYMENT".equals(kind)?"CHARGE":"REFUND",amount);}
    private Map<String,Object> observation(String key,String operation,Object amount){return run(()->costs.importObservation(map("requestKey",key,"operationId",operation,"status","SUCCEEDED","amount",amount,"sourceReference","SANDBOX-STATEMENT-"+key),1));}

    @Test public void purchaseApprovalEditDraftAndDeleteAppendReversalsWithoutRewritingHistory(){
        purchase("1");hook(()->costs.recordProcurementReceipt("PURCHASE_1",1));assertEquals(0,count("commerce_cost_entry"));
        jdbc.update("UPDATE head_receipt SET receipt_status='2'");hook(()->costs.recordProcurementReceipt("PURCHASE_1",1));hook(()->costs.recordProcurementReceipt("PURCHASE_1",1));
        assertEquals(1,count("commerce_cost_entry"));assertEquals(0,new BigDecimal("140").compareTo(amount("1=1")));
        jdbc.update("UPDATE detail_receipt SET univalence=75");hook(()->costs.recordProcurementReceipt("PURCHASE_1",1));
        assertEquals(3,count("commerce_cost_entry"));assertEquals(0,new BigDecimal("150").compareTo(amount("1=1")));
        assertEquals(new BigDecimal("70.0000"),jdbc.queryForObject("SELECT unit_cost FROM commerce_cost_entry WHERE entry_id=1",BigDecimal.class));
        jdbc.update("UPDATE head_receipt SET receipt_status='1'");hook(()->costs.recordProcurementReceipt("PURCHASE_1",1));assertEquals(4,count("commerce_cost_entry"));
        hook(()->costs.reverseProcurementReceipt("PURCHASE_1",1));assertEquals(4,count("commerce_cost_entry"));assertEquals(0,BigDecimal.ZERO.compareTo(amount("1=1")));
        jdbc.update("UPDATE head_receipt SET receipt_status='2'");hook(()->costs.recordProcurementReceipt("PURCHASE_1",1));
        jdbc.update("DELETE FROM detail_receipt");jdbc.update("DELETE FROM head_receipt");hook(()->costs.reverseProcurementReceipt("PURCHASE_1",1));
        assertEquals(6,count("commerce_cost_entry"));assertEquals(0,BigDecimal.ZERO.compareTo(amount("1=1")));
    }

    private String sourceLine(String receipt,long quantity,String price,String discount,int position,String type,String origin) {
        String sourceId=UUID.randomUUID().toString().replace("-","");
        jdbc.update("INSERT INTO detail_receipt(systematic_receipt,product_id,warehousing_id,retrieval_id,plan_quantity,univalence,cost,discount) VALUES (?,1,1,1,?,?,999,?)",receipt,quantity,price,discount);
        long detail=jdbc.queryForObject("SELECT MAX(systematic_id) FROM detail_receipt WHERE systematic_receipt=?",Long.class,receipt);
        jdbc.update("INSERT INTO commerce_purchase_receipt_line(receipt_line_id,receipt_id,detail_id,position_no,receipt_type,receipt_status,source_purchase_line_id,source_receipt_line_id,product_id,supplier_id,warehouse_id,quantity,unit_price,discount) VALUES (?,?,?,?,?,2,NULL,?,1,0,1,?,?,?)",sourceId,receipt,detail,position,Integer.parseInt(type),origin,quantity,price,"100".equals(discount)?"1":discount);
        return sourceId;
    }
    private void supplierReceipt(String id,String type){jdbc.update("INSERT INTO head_receipt VALUES (?,'1',?,'2',1)",id,type);}
    private void supplierReturn(String receipt,String origin,long quantity){supplierReceipt(receipt,"2");sourceLine(receipt,quantity,"999","1",0,"2",origin);}

    @Test public void discountedPurchaseAndSameSkuReturnsUseExactImmutableSourceEntries(){
        supplierReceipt("SOURCE_TWO_PRICES","1");
        String cheap=sourceLine("SOURCE_TWO_PRICES",2,"10","0.80",0,"1",null);
        String expensive=sourceLine("SOURCE_TWO_PRICES",2,"30","100",1,"1",null);
        hook(()->costs.recordProcurementReceipt("SOURCE_TWO_PRICES",1));hook(()->costs.recordProcurementReceipt("SOURCE_TWO_PRICES",1));
        assertEquals(2,count("commerce_cost_entry"));assertEquals(2,count("commerce_cost_source_line"));
        assertEquals(0,new BigDecimal("76").compareTo(amount("1=1")));
        jdbc.update("UPDATE product SET cost_price=555,univalence=999");
        supplierReturn("RETURN_CHEAP",cheap,1);supplierReturn("RETURN_EXPENSIVE",expensive,1);
        hook(()->costs.recordProcurementReceipt("RETURN_CHEAP",1));hook(()->costs.recordProcurementReceipt("RETURN_EXPENSIVE",1));hook(()->costs.recordProcurementReceipt("RETURN_CHEAP",1));
        assertEquals(4,count("commerce_cost_entry"));assertEquals(0,new BigDecimal("38").compareTo(amount("1=1")));
        assertEquals(new BigDecimal("8.0000"),jdbc.queryForObject("SELECT unit_cost FROM commerce_cost_entry WHERE event_type='SUPPLIER_RETURN' AND receipt_id='RETURN_CHEAP'",BigDecimal.class));
        assertEquals(new BigDecimal("30.0000"),jdbc.queryForObject("SELECT unit_cost FROM commerce_cost_entry WHERE event_type='SUPPLIER_RETURN' AND receipt_id='RETURN_EXPENSIVE'",BigDecimal.class));
        assertNotEquals(jdbc.queryForObject("SELECT origin_entry_id FROM commerce_cost_entry WHERE event_type='SUPPLIER_RETURN' AND receipt_id='RETURN_CHEAP'",Long.class),jdbc.queryForObject("SELECT origin_entry_id FROM commerce_cost_entry WHERE event_type='SUPPLIER_RETURN' AND receipt_id='RETURN_EXPENSIVE'",Long.class));
    }

    @Test public void supplierReturnReverseReapproveAndDeleteAppendSignedEntriesIdempotently(){
        supplierReceipt("SOURCE_LIFECYCLE","1");String original=sourceLine("SOURCE_LIFECYCLE",5,"17.25","0.80",0,"1",null);
        hook(()->costs.recordProcurementReceipt("SOURCE_LIFECYCLE",1));supplierReturn("RETURN_LIFECYCLE",original,2);
        hook(()->costs.recordProcurementReceipt("RETURN_LIFECYCLE",1));hook(()->costs.recordProcurementReceipt("RETURN_LIFECYCLE",1));
        assertEquals(2,count("commerce_cost_entry"));assertEquals(0,new BigDecimal("41.40").compareTo(amount("1=1")));
        jdbc.update("UPDATE head_receipt SET receipt_status='1' WHERE systematic_receipt='RETURN_LIFECYCLE'");
        hook(()->costs.recordProcurementReceipt("RETURN_LIFECYCLE",1));hook(()->costs.recordProcurementReceipt("RETURN_LIFECYCLE",1));
        assertEquals(3,count("commerce_cost_entry"));assertEquals(0,new BigDecimal("69").compareTo(amount("1=1")));
        assertEquals(2L,jdbc.queryForObject("SELECT quantity FROM commerce_cost_entry WHERE event_type='SUPPLIER_RETURN_REVERSAL'",Long.class).longValue());
        jdbc.update("UPDATE head_receipt SET receipt_status='2' WHERE systematic_receipt='RETURN_LIFECYCLE'");
        hook(()->costs.recordProcurementReceipt("RETURN_LIFECYCLE",1));assertEquals(4,count("commerce_cost_entry"));
        jdbc.update("DELETE FROM head_receipt WHERE systematic_receipt='RETURN_LIFECYCLE'");jdbc.update("DELETE FROM detail_receipt WHERE systematic_receipt='RETURN_LIFECYCLE'");jdbc.update("DELETE FROM commerce_purchase_receipt_line WHERE receipt_id='RETURN_LIFECYCLE'");
        hook(()->costs.reverseProcurementReceipt("RETURN_LIFECYCLE",1));hook(()->costs.reverseProcurementReceipt("RETURN_LIFECYCLE",1));
        assertEquals(5,count("commerce_cost_entry"));assertEquals(0,new BigDecimal("69").compareTo(amount("1=1")));
        assertEquals(new BigDecimal("13.8000"),jdbc.queryForObject("SELECT unit_cost FROM commerce_cost_entry WHERE event_type='PURCHASE'",BigDecimal.class));
        assertEquals(2L,jdbc.queryForObject("SELECT COUNT(*) FROM commerce_cost_entry WHERE event_type='SUPPLIER_RETURN' AND quantity=-2",Long.class).longValue());
    }

    @Test public void missingSourceCostSnapshotKeepsUnknownInsteadOfUsingReturnOrMasterPrice(){
        supplierReceipt("SOURCE_NO_COST","1");String original=sourceLine("SOURCE_NO_COST",3,"10","1",0,"1",null);
        supplierReturn("RETURN_UNKNOWN",original,1);
        hook(()->costs.recordProcurementReceipt("RETURN_UNKNOWN",1));hook(()->costs.recordProcurementReceipt("RETURN_UNKNOWN",1));
        assertEquals(1,count("commerce_cost_entry"));
        assertNull(jdbc.queryForObject("SELECT unit_cost FROM commerce_cost_entry",BigDecimal.class));assertNull(jdbc.queryForObject("SELECT amount FROM commerce_cost_entry",BigDecimal.class));
        assertEquals("UNKNOWN",jdbc.queryForObject("SELECT cost_status FROM commerce_cost_entry",String.class));
        assertEquals("MISSING_ORIGINAL_SNAPSHOT",jdbc.queryForObject("SELECT cost_basis FROM commerce_cost_entry",String.class));
        hook(()->costs.reverseProcurementReceipt("RETURN_UNKNOWN",1));assertEquals(2,count("commerce_cost_entry"));
        assertEquals(2L,jdbc.queryForObject("SELECT COUNT(*) FROM commerce_cost_entry WHERE cost_status='UNKNOWN' AND amount IS NULL",Long.class).longValue());
    }

    @Test public void legacySameSkuPurchaseCostDoesNotInventExactSourceMapping(){
        purchase("2");hook(()->costs.recordProcurementReceipt("PURCHASE_1",1));assertEquals(1,count("commerce_cost_entry"));
        String identity=UUID.randomUUID().toString().replace("-","");
        long detail=jdbc.queryForObject("SELECT systematic_id FROM detail_receipt WHERE systematic_receipt='PURCHASE_1'",Long.class);
        jdbc.update("INSERT INTO commerce_purchase_receipt_line(receipt_line_id,receipt_id,detail_id,position_no,receipt_type,receipt_status,product_id,supplier_id,warehouse_id,quantity,unit_price,discount) VALUES (?,'PURCHASE_1',?,0,1,2,1,0,1,2,70,1)",identity,detail);
        supplierReturn("RETURN_LEGACY_UNKNOWN",identity,1);hook(()->costs.recordProcurementReceipt("RETURN_LEGACY_UNKNOWN",1));
        assertEquals(0,count("commerce_cost_source_line"));
        assertNull(jdbc.queryForObject("SELECT origin_entry_id FROM commerce_cost_entry WHERE event_type='SUPPLIER_RETURN'",Long.class));
        assertEquals("UNKNOWN",jdbc.queryForObject("SELECT cost_status FROM commerce_cost_entry WHERE event_type='SUPPLIER_RETURN'",String.class));
    }

    @Test public void supplierReturnCostCannotExceedOriginalSnapshotAndReversalReleasesItsCapacity(){
        supplierReceipt("SOURCE_LIMIT","1");String original=sourceLine("SOURCE_LIMIT",2,"10","1",0,"1",null);
        hook(()->costs.recordProcurementReceipt("SOURCE_LIMIT",1));supplierReturn("RETURN_ONE",original,1);supplierReturn("RETURN_TWO",original,2);
        hook(()->costs.recordProcurementReceipt("RETURN_ONE",1));rejected(409,()->hook(()->costs.recordProcurementReceipt("RETURN_TWO",1)));
        assertEquals(2,count("commerce_cost_entry"));assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM commerce_cost_receipt WHERE receipt_id='RETURN_TWO'",Long.class).longValue());
        hook(()->costs.reverseProcurementReceipt("RETURN_ONE",1));hook(()->costs.recordProcurementReceipt("RETURN_TWO",1));
        assertEquals(4,count("commerce_cost_entry"));assertEquals(0,BigDecimal.ZERO.compareTo(amount("1=1")));
    }

    @Test public void dispatchAndReturnKeepOriginalCostAfterMasterAndSalesPriceChange(){
        shipment(70);hook(()->costs.recordShipment("SHIP_1",1));hook(()->costs.recordShipment("SHIP_1",1));assertEquals(1,count("commerce_cost_entry"));
        jdbc.update("UPDATE product SET cost_price=999,univalence=159");returning("CASE_1",1);hook(()->costs.recordReturn("CASE_1",1));hook(()->costs.recordReturn("CASE_1",1));
        assertEquals(2,count("commerce_cost_entry"));assertEquals(new BigDecimal("70.0000"),jdbc.queryForObject("SELECT unit_cost FROM commerce_cost_entry WHERE event_type='RETURN'",BigDecimal.class));
        assertEquals(1L,jdbc.queryForObject("SELECT origin_entry_id FROM commerce_cost_entry WHERE event_type='RETURN'",Long.class).longValue());
        assertEquals(0,new BigDecimal("-70").compareTo(amount("1=1")));assertEquals(new BigDecimal("258.00"),jdbc.queryForObject("SELECT total_amount FROM commerce_order",BigDecimal.class));
    }

    @Test public void partialReturnsCannotReverseMoreThanOriginalDispatchAndRollbackWholeHook(){
        shipment(70);hook(()->costs.recordShipment("SHIP_1",1));returning("CASE_1",1);returning("CASE_2",1);returning("CASE_3",1);
        hook(()->costs.recordReturn("CASE_1",1));hook(()->costs.recordReturn("CASE_2",1));rejected(409,()->hook(()->costs.recordReturn("CASE_3",1)));
        assertEquals(3,count("commerce_cost_entry"));assertEquals(0,BigDecimal.ZERO.compareTo(amount("1=1")));
    }

    @Test public void missingHistoricalOriginalCostIsUnknownAndNeverInventedFromCurrentMaster(){
        shipment(70);returning("CASE_1",1);hook(()->costs.recordReturn("CASE_1",1));
        assertNull(jdbc.queryForObject("SELECT unit_cost FROM commerce_cost_entry",BigDecimal.class));assertNull(jdbc.queryForObject("SELECT origin_entry_id FROM commerce_cost_entry",Long.class));
        assertEquals("MISSING_ORIGINAL_SNAPSHOT",jdbc.queryForObject("SELECT cost_basis FROM commerce_cost_entry",String.class));
        Map<String,Object> report=costs.preview("ORDER_1",1);Map<String,Object> summary=(Map<String,Object>)report.get("cost");assertEquals(false,summary.get("complete"));assertEquals(1L,summary.get("missingHistoricalShipments"));
    }

    @Test public void zeroUnconfiguredDispatchCostIsUnknownAndEvidenceMismatchRollsBack(){
        shipment(0);hook(()->costs.recordShipment("SHIP_1",1));assertEquals("UNKNOWN",jdbc.queryForObject("SELECT cost_status FROM commerce_cost_entry",String.class));assertNull(jdbc.queryForObject("SELECT amount FROM commerce_cost_entry",BigDecimal.class));
        jdbc.update("INSERT INTO commerce_shipment VALUES ('BAD_SHIP','ORDER_1','BAD_OUT')");jdbc.update("INSERT INTO commerce_shipment_item VALUES ('BAD_SHIP',1,3)");jdbc.update("INSERT INTO detail_receipt(systematic_receipt,product_id,retrieval_id,plan_quantity,cost) VALUES ('BAD_OUT',1,1,2,70)");
        rejected(409,()->hook(()->costs.recordShipment("BAD_SHIP",1)));assertEquals(1,count("commerce_cost_entry"));
    }

    @Test public void sandboxObservationDifferenceRequiresCorrectionAndRetainsImmutableMismatch(){
        payment("PAY_1","PAYMENT",new BigDecimal("258"),"SUCCEEDED");observation("wrong","PAY_1",257);
        Map<String,Object> mismatch=run(()->costs.reconcile(map("orderId","ORDER_1","requestKey","reconcile"),1));String id=(String)mismatch.get("reconciliationId");assertEquals("OPEN",mismatch.get("status"));
        assertEquals(id,run(()->costs.reconcile(map("orderId","ORDER_1","requestKey","reconcile"),1)).get("reconciliationId"));assertEquals(1,count("commerce_money_reconciliation"));
        Map<String,Object> resolution=map("requestKey","resolve","evidenceReference","CORRECTED-STATEMENT-002","note","Imported corrected observation after checking the sandbox operation");
        rejected(409,()->run(()->costs.resolve(id,resolution,1)));assertEquals(0,count("commerce_money_resolution"));
        observation("corrected","PAY_1",258);assertEquals(2,count("commerce_statement_observation"));assertEquals(true,costs.preview("ORDER_1",1).get("healthy"));
        Map<String,Object> resolved=run(()->costs.resolve(id,resolution,1));assertEquals("RESOLVED",resolved.get("status"));assertEquals(false,((Map<?,?>)resolved.get("snapshot")).get("healthy"));
        assertEquals(true,((Map<?,?>)((Map<?,?>)resolved.get("resolution")).get("verifiedSnapshot")).get("healthy"));
        run(()->costs.resolve(id,resolution,1));assertEquals(1,count("commerce_money_resolution"));
        assertEquals(new BigDecimal("258.00"),jdbc.queryForObject("SELECT amount FROM commerce_channel_entry",BigDecimal.class));assertEquals(new BigDecimal("258.00"),jdbc.queryForObject("SELECT total_amount FROM commerce_order",BigDecimal.class));
        rejected(409,()->run(()->costs.resolve(id,map("requestKey","resolve","evidenceReference","OTHER","note","Changed"),1)));
    }

    @Test public void moneyReconciliationChecksGrossPaymentRefundAndAfterSalesSeparately(){
        payment("PAY_1","PAYMENT",new BigDecimal("258"),"SUCCEEDED");payment("REF_1","REFUND",new BigDecimal("129"),"SUCCEEDED");
        jdbc.update("UPDATE commerce_order SET refunded_amount=129");jdbc.update("INSERT INTO commerce_after_sales_case VALUES ('CASE_1','default','ORDER_1','REFUNDED',null,129)");
        observation("payment","PAY_1",258);observation("refund","REF_1",129);assertEquals(true,costs.preview("ORDER_1",1).get("healthy"));
        // Simulate a broken upstream ledger only in this disposable SQL fixture. Live smoke uses imports.
        jdbc.update("UPDATE commerce_channel_entry SET amount=128 WHERE movement='REFUND'");
        Map<String,Object> bad=costs.preview("ORDER_1",1);assertEquals(false,bad.get("healthy"));assertTrue(((List<?>)bad.get("issues")).size()>=2);
        jdbc.update("UPDATE commerce_channel_entry SET amount=129 WHERE movement='REFUND'");jdbc.update("UPDATE commerce_after_sales_case SET refunded_amount=128");
        assertTrue(issueTypes(costs.preview("ORDER_1",1)).contains("ORDER_AFTER_SALES_REFUND"));
    }

    @Test public void unobservedAndPendingChannelRequestsCannotBeReportedAsFullyReconciled(){
        payment("PAY_1","PAYMENT",new BigDecimal("258"),"SUCCEEDED");Map<String,Object> missing=costs.preview("ORDER_1",1);assertEquals(false,missing.get("healthy"));assertEquals("PARTIAL",missing.get("observationCoverage"));
        observation("payment","PAY_1",258);jdbc.update("UPDATE commerce_payment_operation SET local_status='UNKNOWN'");assertEquals(false,costs.preview("ORDER_1",1).get("healthy"));
    }

    @Test public void zeroNetFullyRefundedLegacyOrderStillRequiresGrossPaymentAndRefundEvidence(){
        jdbc.update("UPDATE commerce_order SET refunded_amount=258");
        jdbc.update("INSERT INTO commerce_after_sales_case VALUES ('CASE_1','default','ORDER_1','REFUNDED',null,258)");
        Map<String,Object> report=costs.preview("ORDER_1",1);assertEquals(false,report.get("healthy"));
        Set<String> types=new HashSet<>();for(Object issue:(List<?>)report.get("issues"))types.add(String.valueOf(((Map<?,?>)issue).get("type")));
        assertTrue(types.contains("PAYMENT_EVIDENCE_MISSING"));assertTrue(types.contains("REFUND_EVIDENCE_MISSING"));
        assertTrue(types.contains("ORDER_CHANNEL_CHARGE"));assertTrue(types.contains("ORDER_CHANNEL_REFUND"));
    }

    private Set<String> issueTypes(Map<String,Object> result){Set<String> types=new HashSet<>();for(Object issue:(List<?>)result.get("issues"))types.add(String.valueOf(((Map<?,?>)issue).get("type")));return types;}

    @Test public void refundChannelVouchersCannotSwapAmountsEvenWhenAllGrossTotalsAgree(){
        payment("PAY_1","PAYMENT",new BigDecimal("258"),"SUCCEEDED");payment("REF_1","REFUND",new BigDecimal("100"),"SUCCEEDED");payment("REF_2","REFUND",new BigDecimal("158"),"SUCCEEDED");
        jdbc.update("UPDATE commerce_payment_operation SET after_sales_id='CASE_2' WHERE operation_id='REF_2'");
        jdbc.update("UPDATE commerce_order SET refunded_amount=258");
        jdbc.update("INSERT INTO commerce_after_sales_case VALUES ('CASE_1','default','ORDER_1','REFUNDED',null,100),('CASE_2','default','ORDER_1','REFUNDED',null,158)");
        observation("payment","PAY_1",258);observation("refund-one","REF_1",100);observation("refund-two","REF_2",158);assertEquals(true,costs.preview("ORDER_1",1).get("healthy"));
        jdbc.update("UPDATE commerce_channel_entry SET amount=CASE WHEN operation_id='REF_1' THEN 158 ELSE 100 END WHERE movement='REFUND'");
        Map<String,Object> report=costs.preview("ORDER_1",1);assertEquals(false,report.get("healthy"));
        assertEquals(new HashSet<>(Collections.singletonList("OPERATION_CHANNEL_MISMATCH")),issueTypes(report));
        assertEquals(2,((List<?>)report.get("issues")).size());
    }

    @Test public void successfulRefundMustReferenceItsActualRefundedAfterSalesCase(){
        payment("PAY_1","PAYMENT",new BigDecimal("258"),"SUCCEEDED");payment("REF_1","REFUND",new BigDecimal("129"),"SUCCEEDED");
        jdbc.update("UPDATE commerce_order SET refunded_amount=129");jdbc.update("INSERT INTO commerce_after_sales_case VALUES ('CASE_1','default','ORDER_1','REFUNDED',null,129)");
        observation("payment","PAY_1",258);observation("refund","REF_1",129);assertEquals(true,costs.preview("ORDER_1",1).get("healthy"));
        jdbc.update("UPDATE commerce_payment_operation SET after_sales_id='OTHER_CASE' WHERE operation_id='REF_1'");
        Map<String,Object> report=costs.preview("ORDER_1",1);assertEquals(false,report.get("healthy"));assertEquals(new HashSet<>(Collections.singletonList("REFUND_CASE_MISMATCH")),issueTypes(report));
    }

    @Test public void closedOrderLateChargeAndCompensationAreReconciledAsDistinctOperations(){
        payment("PAY_1","PAYMENT",new BigDecimal("258"),"SUCCEEDED");payment("COMP_1","COMPENSATION",new BigDecimal("258"),"SUCCEEDED");
        jdbc.update("UPDATE commerce_order SET status=4");jdbc.update("UPDATE commerce_payment_operation SET local_status='COMPENSATED' WHERE operation_id='PAY_1'");
        observation("payment","PAY_1",258);observation("compensation","COMP_1",258);
        Map<String,Object> report=costs.preview("ORDER_1",1);assertEquals(true,report.get("healthy"));assertEquals(0,BigDecimal.ZERO.compareTo((BigDecimal)report.get("channelNet")));
    }

    @Test public void privateCostAndObservationsRequireFinanceAuthorityAndShopScope(){
        purchase("2");hook(()->costs.recordProcurementReceipt("PURCHASE_1",1));assertEquals(1,((List<?>)costs.ledger(3,100).get("entries")).size());
        rejected(403,()->costs.ledger(2,100));rejected(403,()->costs.ledger(4,100));rejected(403,()->costs.reconciliations(2));
        payment("PAY_1","PAYMENT",new BigDecimal("258"),"SUCCEEDED");CommerceShopContext.set("other");
        assertTrue(((List<?>)costs.ledger(1,100).get("entries")).isEmpty());rejected(404,()->costs.preview("ORDER_1",1));
        rejected(404,()->run(()->costs.importObservation(map("requestKey","foreign","operationId","PAY_1","status","SUCCEEDED","amount",258,"sourceReference","FOREIGN"),1)));
    }

    @Test public void observationIdempotenceMoneyPrecisionAndEvidenceAreValidated(){
        payment("PAY_1","PAYMENT",new BigDecimal("258"),"SUCCEEDED");Map<String,Object> first=observation("once","PAY_1",258);assertEquals(first.get("observationId"),observation("once","PAY_1",258).get("observationId"));assertEquals(1,count("commerce_statement_observation"));
        rejected(409,()->observation("once","PAY_1",257));rejected(400,()->observation("precision","PAY_1","258.001"));rejected(400,()->observation("negative","PAY_1",-1));
        rejected(400,()->run(()->costs.importObservation(map("requestKey","empty-ref","operationId","PAY_1","status","SUCCEEDED","amount",258,"sourceReference"," "),1)));
        assertEquals(1,count("commerce_statement_observation"));
    }

    @Test public void actualOrderShipmentAndReturnHooksCommitCostWithPhysicalMovement() throws Exception {
        CommerceServiceTest fixture=new CommerceServiceTest();fixture.setup();
        CommerceCostService integrated=new CommerceCostService(fixture.jdbc.getDataSource());
        String order=fixture.id(fixture.create("cost-integrated",2));
        fixture.transaction(()->fixture.service.pay(order,map("ownerId",CommerceServiceTest.OWNER,"paymentRequestId","cost-integrated-pay","scenario","success")));
        Map<String,Object> shipped=fixture.transaction(()->fixture.service.ship(order,map("requestKey","cost-integrated-ship"),1));
        assertEquals(1L,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM commerce_cost_entry WHERE event_type='DISPATCH'",Long.class).longValue());
        fixture.jdbc.update("UPDATE product SET cost_price=999,univalence=159 WHERE product_id=1");
        String afterSales=(String)fixture.transaction(()->fixture.afterSales.apply(order,map("ownerId",CommerceServiceTest.OWNER,"requestKey","cost-integrated-case","reason","Cost hook verification"))).get("afterSalesId");
        fixture.transaction(()->fixture.afterSales.review(afterSales,map("requestKey","cost-integrated-approve","decision","APPROVE"),1));
        fixture.transaction(()->fixture.afterSales.acceptReturn(afterSales,map("requestKey","cost-integrated-return","condition","SELLABLE"),1));
        fixture.transaction(()->fixture.afterSales.refund(afterSales,map("requestKey","cost-integrated-refund","scenario","success"),1));
        assertEquals(new BigDecimal("70.0000"),fixture.jdbc.queryForObject("SELECT unit_cost FROM commerce_cost_entry WHERE event_type='RETURN'",BigDecimal.class));
        assertEquals(0,BigDecimal.ZERO.compareTo(fixture.jdbc.queryForObject("SELECT SUM(amount) FROM commerce_cost_entry WHERE order_id=?",BigDecimal.class,order)));
        assertEquals(true,((Map<?,?>)integrated.preview(order,1).get("cost")).get("complete"));
        assertEquals(10L,fixture.service.inventory().get(0).get("bookStock"));
        assertEquals(1,((List<?>)shipped.get("shipments")).size());
    }
}

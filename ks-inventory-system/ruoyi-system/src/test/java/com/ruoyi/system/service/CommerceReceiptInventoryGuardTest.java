package com.ruoyi.system.service;

import com.ruoyi.common.core.domain.entity.DetailReceipt;
import com.ruoyi.common.core.domain.entity.ReceiptFrom;
import com.ruoyi.common.core.domain.entity.HeadReceipt;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.mapper.DetailReceiptMapper;
import com.ruoyi.system.mapper.HeadReceiptMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Before;
import org.junit.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.junit.Assert.*;

/** Exercises the real MyBatis receipt XML and warehouse/product writes inside real SQL transactions. */
public class CommerceReceiptInventoryGuardTest {
    private CommerceReceiptInventoryGuard guard;
    private CommerceInventoryService inventory;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private HeadReceiptMapper heads;
    private DetailReceiptMapper details;

    @Before public void setup() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:erp" + UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000");
        jdbc = new JdbcTemplate(source);
        schema(source,"db/commerce-demo.sql"); schema(source,"db/commerce-inventory.sql"); schema(source,"db/commerce-merchant.sql");
        schema(source,"db/commerce-planning.sql");
        jdbc.execute("CREATE TABLE product(product_id BIGINT PRIMARY KEY,inventory_qty BIGINT,update_by VARCHAR(32),update_time DATETIME)");
        jdbc.execute("CREATE TABLE inventory_product(inventory_id BIGINT AUTO_INCREMENT PRIMARY KEY,product_id BIGINT,warehouse_id BIGINT,supplier_id BIGINT,plan_quantity BIGINT,univalence DECIMAL(14,2),discount DECIMAL(5,2),money DECIMAL(14,2),create_by VARCHAR(32),create_time DATETIME,update_by VARCHAR(32),update_time DATETIME)");
        jdbc.execute("CREATE TABLE warehouse(warehouse_id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE head_receipt(systematic_id BIGINT AUTO_INCREMENT PRIMARY KEY,systematic_receipt VARCHAR(32),original_receipt VARCHAR(64),receipt_category CHAR(1),receipt_type CHAR(1),receipt_status CHAR(1),invoice_date DATE,warehousing_ids BIGINT,retrieval_ids BIGINT,user_ids BIGINT,supplier_ids BIGINT,customer_ids BIGINT,plan_receipt VARCHAR(64),receipt_notes VARCHAR(128),deposit DECIMAL(14,2),total_amount DECIMAL(14,2),capitalize_total_amount VARCHAR(64),after_sales_installation BIGINT,finding_of_audit VARCHAR(1),review_comments VARCHAR(64),create_by VARCHAR(32),create_time DATETIME,update_by VARCHAR(32),update_time DATETIME)");
        jdbc.execute("CREATE TABLE detail_receipt(systematic_id BIGINT AUTO_INCREMENT PRIMARY KEY,systematic_receipt VARCHAR(32),product_id BIGINT,warehousing_id BIGINT,retrieval_id BIGINT,supplier_id BIGINT,customer_id BIGINT,product_specifications VARCHAR(32),measure_unit VARCHAR(16),current_inventory BIGINT,actual_inventory BIGINT,plan_quantity BIGINT,univalence DECIMAL(14,2),discount DECIMAL(5,2),money DECIMAL(14,2),cost DECIMAL(14,2),remarks VARCHAR(64))");
        jdbc.update("INSERT INTO product VALUES (1,10,null,null),(2,5,null,null)");
        jdbc.update("INSERT INTO warehouse VALUES (1),(2),(3)");
        jdbc.update("INSERT INTO inventory_product(product_id,warehouse_id,plan_quantity) VALUES (1,1,10),(2,1,5)");
        inventory = new CommerceInventoryService(source);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source); factory.setTypeAliasesPackage("com.ruoyi.common.core.domain.entity");
        factory.setMapperLocations(new Resource[]{new ClassPathResource("mapper/system/HeadReceiptMapper.xml"),new ClassPathResource("mapper/system/DetailReceiptMapper.xml")});
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        heads=session.getMapper(HeadReceiptMapper.class);details=session.getMapper(DetailReceiptMapper.class);
        guard = new CommerceReceiptInventoryGuard(source,inventory,heads,details);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source)); tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        work(() -> inventory.ensureStock(1));
    }

    private void schema(JdbcDataSource source,String resource) throws Exception {
        String sql=StreamUtils.copyToString(new ClassPathResource(resource).getInputStream(),StandardCharsets.UTF_8).replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","");
        new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))).execute(source);
    }
    private <T> T transaction(Supplier<T> work) {return tx.execute(status->work.get());}
    private void work(Runnable work) {transaction(()->{work.run();return null;});}
    private long physical() {return jdbc.queryForObject("SELECT inventory_qty FROM product WHERE product_id=1",Long.class);}
    private long warehouse(long warehouse) {return jdbc.queryForObject("SELECT COALESCE(SUM(plan_quantity),0) FROM inventory_product WHERE product_id=1 AND warehouse_id=?",Long.class,warehouse);}
    private long count(String table) {return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
    private long stock(String key) {return ((Number)inventory.balances(1).get(key)).longValue();}
    private ReceiptFrom receipt(String id,long type,long quantity) {
        ReceiptFrom receipt=new ReceiptFrom(); receipt.setSystematicReceipt(id); receipt.setReceiptStatus(2L); receipt.setReceiptType(type);
        receipt.setReceiptCategory(type<=2?1L:type<=4?2L:3L); receipt.setWarehousingIds(type==7?"2":"1"); receipt.setRetrievalIds("1");
        DetailReceipt detail=new DetailReceipt(); detail.setProductId("1"); detail.setPlanQuantity(String.valueOf(quantity));
        detail.setUnivalence("99"); detail.setDiscount("100"); detail.setMoney("99"); detail.setCost("50");
        receipt.setDetails(Collections.singletonList(detail)); return receipt;
    }
    private void save(ReceiptFrom receipt) {work(()->guard.save(receipt));}
    private void delete(String... ids) {
        List<DetailReceipt> request=new ArrayList<>();
        for(String id:ids) {DetailReceipt detail=new DetailReceipt();detail.setSystematicReceipt(id);request.add(detail);}
        work(()->guard.delete(request));
    }
    private void reserve(String id,long quantity) {
        work(()->{
            inventory.ensureStock(1);
            jdbc.update("INSERT INTO commerce_order(order_id,owner_id,request_key,request_hash,status,total_amount,create_time) VALUES (?,?,?,'hash',0,99,CURRENT_TIMESTAMP)",id,String.join("",Collections.nCopies(64,"a")),id);
            jdbc.update("INSERT INTO commerce_order_item(order_id,product_id,product_code,product_name,quantity,unit_price,amount) VALUES (?,1,'DEMO-LAMP-ZB','灯',?,99,99)",id,quantity);
            inventory.reserve(id,Collections.singletonMap(1L,quantity),null);
        });
    }
    private void rejected(int code,Runnable action) {try{action.run();fail("Expected rejection");}catch(ServiceException ex){assertEquals(Integer.valueOf(code),ex.getCode());}}

    @Test public void warehouseFiltersUseEachReceiptLineForMultiWarehouseDispatchAndReturn() {
        jdbc.execute("ALTER TABLE warehouse ADD warehouse_name VARCHAR(64)");
        jdbc.execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,user_name VARCHAR(64))");jdbc.update("INSERT INTO sys_user VALUES (1,'Operator')");
        jdbc.execute("CREATE TABLE supplier(supplier_id BIGINT PRIMARY KEY,supplier_name VARCHAR(64))");
        jdbc.execute("CREATE TABLE customer(customer_id BIGINT PRIMARY KEY,customer_name VARCHAR(64))");
        for(String column:Arrays.asList("product_code VARCHAR(64)","product_name VARCHAR(64)","product_type BIGINT","producer VARCHAR(64)"))jdbc.execute("ALTER TABLE product ADD "+column);
        jdbc.execute("CREATE TABLE product_type(product_type_id BIGINT PRIMARY KEY,parent_id BIGINT,ancestors VARCHAR(64),product_type_name VARCHAR(64))");
        jdbc.update("INSERT INTO product_type VALUES (1,0,'0','Lighting')");jdbc.update("UPDATE product SET product_type=1,product_code='LAMP',product_name='Lamp'");
        jdbc.update("INSERT INTO head_receipt(systematic_receipt,receipt_type,receipt_status,warehousing_ids,retrieval_ids,user_ids) VALUES ('multi-return','4','2',1,0,1),('multi-dispatch','3','2',0,1,1)");
        jdbc.update("INSERT INTO detail_receipt(systematic_receipt,product_id,warehousing_id,retrieval_id,plan_quantity) VALUES ('multi-return',1,1,0,1),('multi-return',1,2,0,2),('multi-dispatch',1,0,1,1),('multi-dispatch',1,0,2,2)");
        HeadReceipt head=new HeadReceipt();head.setWarehousingIds("2");
        assertEquals(1,heads.headReceiptQuery(head).size());assertEquals("multi-return",heads.headReceiptQuery(head).get(0).getSystematicReceipt());
        DetailReceipt line=new DetailReceipt();line.setWarehousingIds("2");
        assertEquals(1,details.SelectDetailReceiptQuery(line).size());assertEquals("2",details.SelectDetailReceiptQuery(line).get(0).getPlanQuantity());
        head.setWarehousingIds(null);head.setRetrievalIds("2");line.setWarehousingIds(null);line.setRetrievalIds("2");
        assertEquals("multi-dispatch",heads.headReceiptQuery(head).get(0).getSystematicReceipt());
        assertEquals(1,details.SelectDetailReceiptQuery(line).size());assertEquals("2",details.SelectDetailReceiptQuery(line).get(0).getRetrievalId());
        line.setRetrievalIds("1");assertEquals(1,details.SelectDetailReceiptQuery(line).size());assertEquals("1",details.SelectDetailReceiptQuery(line).get(0).getPlanQuantity());
    }

    @Test public void draftApprovalAndRepeatedApprovalPreserveOpeningStockAndApplyExactlyOnce() {
        ReceiptFrom purchase=receipt("purchase",1,4); purchase.setReceiptStatus(1L);
        save(purchase); assertEquals(10,physical()); assertEquals(0,count("commerce_warehouse_ledger"));
        purchase.setReceiptStatus(2L); save(purchase);
        assertEquals(14,physical()); assertEquals(14,warehouse(1)); assertEquals(14,stock("bookStock"));
        long entries=count("commerce_stock_ledger");
        save(purchase); assertEquals(14,physical()); assertEquals(entries,count("commerce_stock_ledger"));
        assertEquals(1,count("head_receipt")); assertEquals(1,count("commerce_warehouse_ledger"));
        assertEquals("ERP_SAVE:purchase",jdbc.queryForObject("SELECT reason FROM commerce_stock_ledger WHERE event_type='EXTERNAL_ADJUST'",String.class));
        assertEquals(true,inventory.reconcile().get("healthy"));
    }

    @Test public void allOutboundTypesRespectOrderAndCampaignReservationsAndRollbackDocumentWrites() {
        reserve("held",6);
        work(()->{
            jdbc.update("INSERT INTO commerce_activity VALUES ('campaign',1,'活动',99,2,2,1,?,?)",LocalDateTime.now().minusMinutes(1),LocalDateTime.now().plusHours(1));
            inventory.allocateActivity("campaign",1,2);
        });
        for(long type:Arrays.asList(2L,3L,6L)) rejected(409,()->save(receipt("blocked"+type,type,3)));
        assertEquals(0,count("head_receipt")); assertEquals(0,count("detail_receipt")); assertEquals(10,physical());
        save(receipt("allowed",3,2)); assertEquals(8,physical()); assertEquals(0,stock("availableStock"));
        assertEquals(6,stock("reservedStock")); assertEquals(2,stock("activityStock"));
        assertEquals(true,inventory.reconcile().get("healthy"));
    }

    @Test public void outboundCannotConsumeBlockedGoodsInOneWarehouseEvenWithAvailabilityElsewhere(){
        jdbc.update("UPDATE inventory_product SET plan_quantity=6 WHERE product_id=1 AND warehouse_id=1");
        jdbc.update("INSERT INTO inventory_product(product_id,warehouse_id,plan_quantity) VALUES (1,2,4)");
        work(()->inventory.adjustUnavailable(1,"quality",6,"质检隔离"));
        jdbc.update("INSERT INTO commerce_warehouse_condition(product_id,warehouse_id,quality_hold,damaged) VALUES (1,1,5,1)");
        assertEquals(4,stock("availableStock"));rejected(409,()->save(receipt("blocked-out",3,3)));
        assertEquals(6,warehouse(1));assertEquals(4,warehouse(2));assertEquals(0,count("head_receipt"));assertEquals(true,inventory.reconcile().get("healthy"));
    }

    @Test public void transferWritesTwoWarehouseMovementsWithoutChangingTotalStockOrReservations() {
        reserve("held",8);
        long stockEntries=count("commerce_stock_ledger");
        save(receipt("transfer",7,5));
        assertEquals(5,warehouse(1)); assertEquals(5,warehouse(2)); assertEquals(10,physical());
        assertEquals(8,stock("reservedStock")); assertEquals(2,stock("availableStock"));
        assertEquals(2,count("commerce_warehouse_ledger")); assertEquals(stockEntries,count("commerce_stock_ledger"));
        save(receipt("transfer",7,5)); assertEquals(2,count("commerce_warehouse_ledger"));
        delete("transfer"); assertEquals(10,warehouse(1)); assertEquals(0,warehouse(2)); assertEquals(10,physical());
        assertEquals(4,count("commerce_warehouse_ledger")); assertEquals(true,inventory.reconcile().get("healthy"));
    }

    @Test public void countingChecksLiveWarehouseBaselineAndProtectsReservedStock() {
        reserve("held",7);
        ReceiptFrom stale=receipt("stale-count",8,-2); stale.getDetails().get(0).setCurrentInventory("11"); stale.getDetails().get(0).setActualInventory("9");
        rejected(409,()->save(stale));
        ReceiptFrom conflict=receipt("bad-count",8,-4); conflict.getDetails().get(0).setCurrentInventory("10");conflict.getDetails().get(0).setActualInventory("6");
        rejected(409,()->save(conflict));
        ReceiptFrom valid=receipt("count",8,-2);valid.getDetails().get(0).setCurrentInventory("10");valid.getDetails().get(0).setActualInventory("8");
        save(valid); assertEquals(8,physical()); assertEquals(1,stock("availableStock"));
        save(valid); assertEquals(8,physical()); assertEquals(1,count("commerce_warehouse_ledger"));
        assertEquals(true,inventory.reconcile().get("healthy"));
    }

    @Test public void deletionReversesApprovedDocumentsAndCannotReclaimStockAlreadyReserved() {
        save(receipt("purchase",1,4)); reserve("held",12);
        rejected(409,()->delete("purchase")); assertEquals(1,count("head_receipt")); assertEquals(14,physical());
        work(()->{inventory.release("held","CUSTOMER_CANCEL",false);jdbc.update("UPDATE commerce_order SET status=4 WHERE order_id='held'");});
        delete("purchase"); assertEquals(10,physical()); assertEquals(0,count("head_receipt"));
        assertEquals(2,count("commerce_warehouse_ledger"));
        long ledger=count("commerce_stock_ledger"); delete("purchase"); assertEquals(ledger,count("commerce_stock_ledger"));
        assertEquals(true,inventory.reconcile().get("healthy"));
    }

    @Test public void editingApprovedReceiptAppliesDifferenceAndBatchReversalRetainsAllReceiptReferences() {
        save(receipt("inbound",1,5)); save(receipt("outbound",3,2)); assertEquals(13,physical());
        save(receipt("inbound",1,4)); assertEquals(12,physical());
        delete("inbound","outbound"); assertEquals(10,physical());
        assertEquals("inbound,outbound",jdbc.queryForObject("SELECT related_receipts FROM commerce_warehouse_ledger WHERE operation='DELETE'",String.class));
        assertEquals(true,inventory.reconcile().get("healthy"));
    }

    @Test public void initialApprovedReceiptAndReturnsWorkAcrossNewAndExistingWarehouseRows() {
        ReceiptFrom purchase=receipt("new-warehouse",1,3);purchase.setWarehousingIds("3"); save(purchase);
        assertEquals(3,warehouse(3)); assertEquals(13,physical());
        ReceiptFrom returned=receipt("return",4,2);returned.setWarehousingIds("3");save(returned);
        assertEquals(5,warehouse(3));assertEquals(15,physical());
        assertEquals(true,inventory.reconcile().get("healthy"));
    }

    @Test public void linkedCommerceDispatchReceiptsAndHistoricalImportCannotBypassFulfillment() {
        save(receipt("dispatch",3,2));
        jdbc.update("INSERT INTO commerce_order(order_id,owner_id,request_key,request_hash,status,total_amount,create_time,receipt_id) VALUES ('shop-order',?,'order','hash',2,99,CURRENT_TIMESTAMP,'dispatch')",String.join("",Collections.nCopies(64,"a")));
        rejected(409,()->delete("dispatch")); rejected(409,()->save(receipt("dispatch",3,1)));
        rejected(409,()->guard.rejectHistoricalImport()); assertEquals(8,physical());
    }

    @Test public void failureAfterWarehousePostingRollsBackOriginalReceiptsSnapshotAndBothJournals() {
        long entries=count("commerce_stock_ledger");
        try {work(()->{guard.save(receipt("rollback",3,2));throw new ServiceException("模拟事务失败",409);});fail("Expected rollback");}
        catch(ServiceException expected){assertEquals(Integer.valueOf(409),expected.getCode());}
        assertEquals(10,physical()); assertEquals(10,stock("bookStock")); assertEquals(0,count("head_receipt"));assertEquals(0,count("detail_receipt"));
        assertEquals(0,count("commerce_warehouse_ledger"));assertEquals(entries,count("commerce_stock_ledger"));
    }

    @Test public void concurrentCommerceReservationAndErpOutboundCannotSpendTheSameStock() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            Future<Boolean> mall=pool.submit(()->{start.await();try{reserve("competing",8);return true;}catch(ServiceException ex){assertEquals(Integer.valueOf(409),ex.getCode());return false;}});
            Future<Boolean> erp=pool.submit(()->{start.await();try{save(receipt("competing-erp",3,8));return true;}catch(ServiceException ex){assertEquals(Integer.valueOf(409),ex.getCode());return false;}});
            start.countDown();assertTrue(mall.get(10,TimeUnit.SECONDS)^erp.get(10,TimeUnit.SECONDS));
            assertEquals(2,stock("availableStock"));assertEquals(true,inventory.reconcile().get("healthy"));
        } finally {pool.shutdownNow();}
    }

    @Test public void concurrentIdenticalApprovedReceiptsMakeOneDocumentAndOnePosting() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            Future<?> first=pool.submit(()->{start.await();save(receipt("same",1,4));return null;});
            Future<?> second=pool.submit(()->{start.await();save(receipt("same",1,4));return null;});
            start.countDown();first.get(10,TimeUnit.SECONDS);second.get(10,TimeUnit.SECONDS);
            assertEquals(14,physical());assertEquals(1,count("head_receipt"));assertEquals(1,count("detail_receipt"));assertEquals(1,count("commerce_warehouse_ledger"));
            assertEquals(true,inventory.reconcile().get("healthy"));
        } finally {pool.shutdownNow();}
    }

    @Test public void masterDeletionRejectsLiveStockAndHistoricalReferencesButAllowsUnusedEmptyMasters() {
        rejected(409,()->work(()->guard.deleteProducts(new Long[]{1L})));
        rejected(409,()->work(()->guard.deleteWarehouses(new Long[]{1L})));
        jdbc.update("INSERT INTO product(product_id,inventory_qty) VALUES (3,0),(4,0),(5,0),(6,0),(7,0)");
        work(()->inventory.ensureStock(3));
        rejected(409,()->work(()->guard.deleteProducts(new Long[]{3L})));
        jdbc.update("INSERT INTO detail_receipt(systematic_receipt,product_id,warehousing_id,plan_quantity) VALUES ('history',4,2,1)");
        rejected(409,()->work(()->guard.deleteProducts(new Long[]{4L})));
        rejected(409,()->work(()->guard.deleteWarehouses(new Long[]{2L})));
        jdbc.update("INSERT INTO commerce_stock_hold(order_id,product_id,quantity,status,created_at,updated_at) VALUES ('old-release',5,1,'RELEASED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        rejected(409,()->work(()->guard.deleteProducts(new Long[]{5L})));
        jdbc.update("INSERT INTO commerce_product_shop VALUES (6,'shop',0)");
        rejected(409,()->work(()->guard.deleteProducts(new Long[]{6L})));
        assertEquals(1,transaction(()->guard.deleteProducts(new Long[]{7L})).intValue());
        assertEquals(1,transaction(()->guard.deleteWarehouses(new Long[]{3L})).intValue());
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE product_id=7",Long.class).longValue());
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM warehouse WHERE warehouse_id=3",Long.class).longValue());
    }

    @Test public void masterDeletionKeepsBatchAtomicAndWarehouseHistorySurvivesZeroBalance() {
        jdbc.update("INSERT INTO product(product_id,inventory_qty) VALUES (3,0)");
        rejected(409,()->work(()->guard.deleteProducts(new Long[]{3L,1L})));
        rejected(409,()->work(()->guard.deleteWarehouses(new Long[]{3L,1L})));
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE product_id=3",Long.class).longValue());
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM warehouse WHERE warehouse_id=3",Long.class).longValue());
        jdbc.update("INSERT INTO commerce_warehouse_ledger(event_key,receipt_id,related_receipts,operation,product_id,warehouse_id,delta_quantity,before_quantity,after_quantity,created_at) VALUES ('history','old','old','SAVE',3,3,-1,1,0,CURRENT_TIMESTAMP)");
        rejected(409,()->work(()->guard.deleteProducts(new Long[]{3L})));
        rejected(409,()->work(()->guard.deleteWarehouses(new Long[]{3L})));
        assertEquals(1,count("commerce_warehouse_ledger"));
    }

    @Test public void warehouseDeletionAndFirstInboundPostingCannotLeaveOrphanInventory() throws Exception {
        jdbc.update("INSERT INTO product(product_id,inventory_qty) VALUES (3,0)");
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            Future<Boolean> posting=pool.submit(()->{
                start.await();
                ReceiptFrom inbound=receipt("first-inbound",1,2);inbound.setWarehousingIds("3");inbound.getDetails().get(0).setProductId("3");
                try{save(inbound);return true;}catch(ServiceException ex){assertEquals(Integer.valueOf(404),ex.getCode());return false;}
            });
            Future<Boolean> deleting=pool.submit(()->{start.await();try{work(()->guard.deleteWarehouses(new Long[]{3L}));return true;}catch(ServiceException ex){assertEquals(Integer.valueOf(409),ex.getCode());return false;}});
            start.countDown();boolean posted=posting.get(10,TimeUnit.SECONDS),deleted=deleting.get(10,TimeUnit.SECONDS);
            assertTrue(posted^deleted);
            long masters=jdbc.queryForObject("SELECT COUNT(*) FROM warehouse WHERE warehouse_id=3",Long.class);
            long rows=jdbc.queryForObject("SELECT COUNT(*) FROM inventory_product WHERE product_id=3",Long.class);
            assertEquals(posted?1:0,masters);assertEquals(posted?1:0,rows);
        } finally {pool.shutdownNow();}
    }

    @Test public void productDeletionAndFirstInboundPostingCannotLeaveOrphanInventory() throws Exception {
        jdbc.update("INSERT INTO product(product_id,inventory_qty) VALUES (3,0)");
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            Future<Boolean> posting=pool.submit(()->{
                start.await();
                ReceiptFrom inbound=receipt("first-inbound",1,2);inbound.setWarehousingIds("3");inbound.getDetails().get(0).setProductId("3");
                try{save(inbound);return true;}catch(ServiceException ex){assertEquals(Integer.valueOf(404),ex.getCode());return false;}
            });
            Future<Boolean> deleting=pool.submit(()->{start.await();try{work(()->guard.deleteProducts(new Long[]{3L}));return true;}catch(ServiceException ex){assertEquals(Integer.valueOf(409),ex.getCode());return false;}});
            start.countDown();boolean posted=posting.get(10,TimeUnit.SECONDS),deleted=deleting.get(10,TimeUnit.SECONDS);
            assertTrue(posted^deleted);
            long masters=jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE product_id=3",Long.class);
            long rows=jdbc.queryForObject("SELECT COUNT(*) FROM inventory_product WHERE product_id=3",Long.class);
            assertEquals(posted?1:0,masters);assertEquals(posted?1:0,rows);
        } finally {pool.shutdownNow();}
    }
}

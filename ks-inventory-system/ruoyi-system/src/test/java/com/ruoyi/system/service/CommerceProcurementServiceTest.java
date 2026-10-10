package com.ruoyi.system.service;

import com.ruoyi.common.core.domain.entity.DetailOrderForm;
import com.ruoyi.common.core.domain.entity.DetailReceipt;
import com.ruoyi.common.core.domain.entity.HeadOrderForm;
import com.ruoyi.common.core.domain.entity.HeadReceipt;
import com.ruoyi.common.core.domain.entity.OrderFrom;
import com.ruoyi.common.core.domain.entity.ReceiptFrom;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.mapper.DetailOrderFormMapper;
import com.ruoyi.system.mapper.DetailReceiptMapper;
import com.ruoyi.system.mapper.HeadOrderFormMapper;
import com.ruoyi.system.mapper.HeadReceiptMapper;
import com.ruoyi.system.service.impl.SalesOrderProcessingServiceImpl;
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
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.Assert.*;

/** Real document XML, source allocation and stock writes share the same SQL transaction. */
public class CommerceProcurementServiceTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private CommerceProcurementService procurement;
    private CommerceReceiptInventoryGuard guard;
    private CommerceInventoryService inventory;
    private CommerceProcurementCommandService commands;
    private HeadOrderFormMapper orderHeads;
    private DetailOrderFormMapper orderDetails;
    private HeadReceiptMapper receiptHeads;

    @Before public void setup() throws Exception {
        JdbcDataSource source=new JdbcDataSource();
        source.setURL("jdbc:h2:mem:procurement"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000");
        jdbc=new JdbcTemplate(source);
        schema(source,"db/commerce-demo.sql");
        schema(source,"db/commerce-inventory.sql");
        schema(source,"db/commerce-merchant.sql");
        schema(source,"db/commerce-planning.sql");
        schema(source,"db/commerce-warehouse-allocation.sql");
        schema(source,"db/commerce-procurement-legacy-fixture.sql");
        schema(source,"db/commerce-procurement.sql");
        schema(source,"db/commerce-procurement-commands.sql");
        SqlSessionFactoryBean factory=new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.ruoyi.common.core.domain.entity");
        factory.setMapperLocations(new Resource[]{
                new ClassPathResource("mapper/system/HeadOrderFormMapper.xml"),
                new ClassPathResource("mapper/system/DetailOrderFormMapper.xml"),
                new ClassPathResource("mapper/system/HeadReceiptMapper.xml"),
                new ClassPathResource("mapper/system/DetailReceiptMapper.xml")});
        SqlSessionTemplate session=new SqlSessionTemplate(factory.getObject());
        orderHeads=session.getMapper(HeadOrderFormMapper.class);
        orderDetails=session.getMapper(DetailOrderFormMapper.class);
        procurement=new CommerceProcurementService(source,orderHeads,orderDetails);
        inventory=new CommerceInventoryService(source);
        receiptHeads=session.getMapper(HeadReceiptMapper.class);
        guard=new CommerceReceiptInventoryGuard(source,inventory,receiptHeads,session.getMapper(DetailReceiptMapper.class));
        guard.configureProcurement(procurement);
        commands=new CommerceProcurementCommandService(source,procurement,guard);
        tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction(()->inventory.ensureStock(1));
    }

    private void schema(JdbcDataSource source,String path) throws Exception {
        String sql=StreamUtils.copyToString(new ClassPathResource(path).getInputStream(),StandardCharsets.UTF_8)
                .replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","");
        new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))).execute(source);
    }
    private <T> T transaction(Supplier<T> action){return tx.execute(status->action.get());}
    private long count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
    private long physical(){return jdbc.queryForObject("SELECT inventory_qty FROM product WHERE product_id=1",Long.class);}
    private long number(Map<String,Object> row,String field){return ((Number)row.get(field)).longValue();}
    private Map<String,Object> progress(String order){return transaction(()->procurement.progress(order));}
    @SuppressWarnings("unchecked") private Map<String,Object> purchaseLine(String order){return ((List<Map<String,Object>>)progress(order).get("lines")).get(0);}
    private String purchaseLineId(String order){return String.valueOf(purchaseLine(order).get("purchaseLineId"));}
    private void rejected(int code,Runnable action){try{action.run();fail("Expected rejected business operation");}catch(ServiceException ex){assertEquals(Integer.valueOf(code),ex.getCode());}}

    private DetailOrderForm line(long quantity){
        DetailOrderForm line=new DetailOrderForm();
        line.setProductId("1");line.setWarehousingId("1");line.setSupplierId("1");
        line.setPlanQuantity(String.valueOf(quantity));line.setUnivalence("17.25");
        line.setDiscount("1");line.setMoney("172.50");line.setCost("17.25");
        line.setProductSpecifications("Zigbee/9W");line.setMeasureUnit("piece");
        return line;
    }
    private OrderFrom order(String id,long quantity,long status){
        OrderFrom order=new OrderFrom();order.setSystematicOrderForm(id);order.setOrderFormType(1L);order.setOrderFormStatus(status);
        order.setWarehousingIds("1");order.setSupplierIds("1");order.setUserIds("1");
        order.setOrderDate("2026-10-10");order.setDeliveryDate("2026-10-12");
        order.setOrderFormAmount("172.50");order.setCreateBy("procurement-test");order.setUpdateBy("procurement-test");
        order.setDetails(Collections.singletonList(line(quantity)));return order;
    }
    private void saveOrder(OrderFrom order){transaction(()->procurement.saveOrder(order));}
    private void createOrder(String id,long quantity){saveOrder(order(id,quantity,2));}
    private HeadOrderForm loadedOrder(String id){
        return transaction(()->{HeadOrderForm loaded=orderHeads.selectHeadOrderFormById(id);procurement.enrichOrder(loaded);return loaded;});
    }
    private void deleteOrder(String id){DetailOrderForm ref=new DetailOrderForm();ref.setSystematicOrderForm(id);transaction(()->procurement.deleteOrders(Collections.singletonList(ref)));}
    private ReceiptFrom receipt(String id,String orderId,long quantity,long status){
        ReceiptFrom receipt=new ReceiptFrom();receipt.setSystematicReceipt(id);receipt.setReceiptCategory(1L);
        receipt.setReceiptType(1L);receipt.setReceiptStatus(status);receipt.setWarehousingIds("1");receipt.setSupplierIds("1");receipt.setUserIds("1");
        DetailReceipt line=new DetailReceipt();line.setSourcePurchaseLineId(purchaseLineId(orderId));line.setProductId("1");
        line.setWarehousingId("1");line.setSupplierId("1");line.setPlanQuantity(String.valueOf(quantity));
        line.setUnivalence("17.25");line.setDiscount("1");line.setMoney("69.00");line.setCost("17.25");
        receipt.setDetails(Collections.singletonList(line));return receipt;
    }
    private void saveReceipt(ReceiptFrom receipt){transaction(()->guard.save(receipt));}
    private void deleteReceipt(String id){DetailReceipt ref=new DetailReceipt();ref.setSystematicReceipt(id);transaction(()->guard.delete(Collections.singletonList(ref)));}
    private Map<String,Object> receiptSource(String orderId,String receiptId){
        for(Map<String,Object> row:transaction(()->procurement.receiptSources(orderId)))if(receiptId.equals(row.get("receiptId")))return row;
        throw new AssertionError("Missing original posted purchase evidence "+receiptId);
    }
    private ReceiptFrom returned(String id,String orderId,String sourceReceipt,long quantity,long status){
        ReceiptFrom receipt=receipt(id,orderId,quantity,status);receipt.setReceiptType(2L);receipt.setRetrievalIds("1");
        receipt.getDetails().get(0).setRetrievalId("1");
        receipt.getDetails().get(0).setSourceReceiptLineId(String.valueOf(receiptSource(orderId,sourceReceipt).get("receiptLineId")));
        return receipt;
    }
    private void reserve(String order,long quantity){
        transaction(()->{
            jdbc.update("INSERT INTO commerce_order(order_id,owner_id,request_key,request_hash,status,total_amount,create_time) VALUES (?,?,?,'hash',0,99,CURRENT_TIMESTAMP)",order,String.join("",Collections.nCopies(64,"a")),order);
            jdbc.update("INSERT INTO commerce_order_item(order_id,product_id,product_code,product_name,quantity,unit_price,amount) VALUES (?,1,'TEST-LAMP','Lamp',?,99,99)",order,quantity);
            inventory.reserve(order,Collections.singletonMap(1L,quantity),null);return null;
        });
    }
    private Map<String,Object> commandBody(String order,String key,long quantity){
        Map<String,Object> line=new LinkedHashMap<>();line.put("purchaseLineId",purchaseLineId(order));line.put("quantity",quantity);
        Map<String,Object> body=new LinkedHashMap<>();body.put("requestKey",key);body.put("items",Collections.singletonList(line));return body;
    }
    private Map<String,Object> command(String order,Map<String,Object> body){return transaction(()->commands.createReceipt(order,body,1,"Operator",false));}
    private SalesOrderProcessingServiceImpl salesAdapter() throws Exception {
        SalesOrderProcessingServiceImpl adapter=new SalesOrderProcessingServiceImpl();
        for(Map.Entry<String,Object> entry:new LinkedHashMap<String,Object>(){{put("procurement",procurement);put("headOrderFormMapper",orderHeads);put("detailOrderFormMapper",orderDetails);}}.entrySet()){
            Field field=SalesOrderProcessingServiceImpl.class.getDeclaredField(entry.getKey());field.setAccessible(true);field.set(adapter,entry.getValue());
        }
        return adapter;
    }

    @Test public void draftReceiptsReservePurchaseCapacityWithoutChangingPhysicalInventory(){
        createOrder("PO-CAP",10);saveReceipt(receipt("IN-SIX","PO-CAP",6,1));
        assertEquals(10,physical());assertEquals(6,number(progress("PO-CAP"),"draftReceiptQuantity"));
        assertEquals(4,number(progress("PO-CAP"),"remainingQuantity"));
        rejected(409,()->saveReceipt(receipt("IN-OTHER-SIX","PO-CAP",6,1)));
        assertEquals(1,count("head_receipt"));assertEquals(1,count("detail_receipt"));assertEquals(0,count("commerce_warehouse_ledger"));
        deleteReceipt("IN-SIX");assertEquals(10,number(progress("PO-CAP"),"remainingQuantity"));
        saveReceipt(receipt("IN-ALL","PO-CAP",10,1));assertEquals(10,physical());
    }

    @Test public void simultaneousSixUnitDraftsCannotBothReserveTenUnitSourceOrder() throws Exception {
        createOrder("PO-RACE",10);
        ReceiptFrom one=receipt("RACE-ONE","PO-RACE",6,1),two=receipt("RACE-TWO","PO-RACE",6,1);
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try{
            Future<Boolean> left=pool.submit(()->{start.await();try{saveReceipt(one);return true;}catch(ServiceException ex){assertEquals(Integer.valueOf(409),ex.getCode());return false;}});
            Future<Boolean> right=pool.submit(()->{start.await();try{saveReceipt(two);return true;}catch(ServiceException ex){assertEquals(Integer.valueOf(409),ex.getCode());return false;}});
            start.countDown();assertTrue(left.get(15,TimeUnit.SECONDS)^right.get(15,TimeUnit.SECONDS));
            assertEquals(1,count("head_receipt"));assertEquals(6,number(progress("PO-RACE"),"draftReceiptQuantity"));
            assertEquals(4,number(progress("PO-RACE"),"remainingQuantity"));assertEquals(10,physical());
        }finally{pool.shutdownNow();}
    }

    @Test public void partialApprovalsAndRetriesPostFourPlusSixExactlyOnce(){
        createOrder("PO-PARTIAL",10);ReceiptFrom four=receipt("IN-FOUR","PO-PARTIAL",4,1);saveReceipt(four);
        four.setReceiptStatus(2L);saveReceipt(four);saveReceipt(four);
        assertEquals(14,physical());assertEquals(4,number(progress("PO-PARTIAL"),"receivedQuantity"));
        assertEquals(0,number(progress("PO-PARTIAL"),"draftReceiptQuantity"));
        ReceiptFrom six=receipt("IN-SIX","PO-PARTIAL",6,2);saveReceipt(six);saveReceipt(six);
        assertEquals(20,physical());assertEquals(10,number(progress("PO-PARTIAL"),"receivedQuantity"));
        assertEquals(0,number(progress("PO-PARTIAL"),"remainingQuantity"));assertEquals(2,count("commerce_warehouse_ledger"));
        assertEquals(Boolean.TRUE,inventory.reconcile().get("healthy"));
    }

    @Test public void supplierReturnUsesOriginalReceiptCapacityAndDoesNotReopenPurchaseCapacity(){
        createOrder("PO-RETURN",10);saveReceipt(receipt("IN-FIRST","PO-RETURN",4,2));saveReceipt(receipt("IN-SECOND","PO-RETURN",6,2));
        rejected(409,()->saveReceipt(returned("RETURN-FIVE","PO-RETURN","IN-FIRST",5,1)));
        ReceiptFrom two=returned("RETURN-TWO","PO-RETURN","IN-FIRST",2,2);saveReceipt(two);saveReceipt(two);
        assertEquals(18,physical());assertEquals(10,number(progress("PO-RETURN"),"receivedQuantity"));
        assertEquals(2,number(progress("PO-RETURN"),"returnedQuantity"));assertEquals(0,number(progress("PO-RETURN"),"remainingQuantity"));
        assertEquals(2,number(receiptSource("PO-RETURN","IN-FIRST"),"remainingReturnQuantity"));
        rejected(409,()->saveReceipt(returned("RETURN-OTHER-THREE","PO-RETURN","IN-FIRST",3,1)));
        rejected(409,()->saveReceipt(receipt("REPLACE-SILENTLY","PO-RETURN",2,2)));
        assertEquals(Boolean.TRUE,inventory.reconcile().get("healthy"));
    }

    @Test public void draftSupplierReturnsReserveTheirOriginalEvidenceUntilDeleted(){
        createOrder("PO-RETURN-DRAFT",10);saveReceipt(receipt("IN-SOURCE","PO-RETURN-DRAFT",4,2));
        saveReceipt(returned("RETURN-DRAFT","PO-RETURN-DRAFT","IN-SOURCE",3,1));
        assertEquals(14,physical());assertEquals(0,number(progress("PO-RETURN-DRAFT"),"returnedQuantity"));
        assertEquals(3,number(receiptSource("PO-RETURN-DRAFT","IN-SOURCE"),"draftReturnQuantity"));
        rejected(409,()->saveReceipt(returned("RETURN-OVERFLOW","PO-RETURN-DRAFT","IN-SOURCE",2,1)));
        deleteReceipt("RETURN-DRAFT");assertEquals(4,number(receiptSource("PO-RETURN-DRAFT","IN-SOURCE"),"remainingReturnQuantity"));
    }

    @Test public void concurrentSupplierReturnDraftsCannotSpendTheSameOriginalReceipt() throws Exception {
        createOrder("PO-RETURN-RACE",10);saveReceipt(receipt("IN-SOURCE","PO-RETURN-RACE",4,2));
        ReceiptFrom one=returned("RETURN-RACE-ONE","PO-RETURN-RACE","IN-SOURCE",3,1);
        ReceiptFrom two=returned("RETURN-RACE-TWO","PO-RETURN-RACE","IN-SOURCE",3,1);
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try{
            Future<Boolean> left=pool.submit(()->{start.await();try{saveReceipt(one);return true;}catch(ServiceException ex){assertEquals(Integer.valueOf(409),ex.getCode());return false;}});
            Future<Boolean> right=pool.submit(()->{start.await();try{saveReceipt(two);return true;}catch(ServiceException ex){assertEquals(Integer.valueOf(409),ex.getCode());return false;}});
            start.countDown();assertTrue(left.get(15,TimeUnit.SECONDS)^right.get(15,TimeUnit.SECONDS));
            assertEquals(2,count("head_receipt"));assertEquals(3,number(receiptSource("PO-RETURN-RACE","IN-SOURCE"),"draftReturnQuantity"));
            assertEquals(1,number(receiptSource("PO-RETURN-RACE","IN-SOURCE"),"remainingReturnQuantity"));assertEquals(14,physical());
        }finally{pool.shutdownNow();}
    }

    @Test public void cancellingSupplierReturnRestoresItsEvidenceCapacityAndPhysicalStock(){
        createOrder("PO-RETURN-CANCEL",10);saveReceipt(receipt("IN-SOURCE","PO-RETURN-CANCEL",4,2));
        saveReceipt(returned("RETURN-CANCEL","PO-RETURN-CANCEL","IN-SOURCE",2,2));assertEquals(12,physical());
        deleteReceipt("RETURN-CANCEL");assertEquals(14,physical());assertEquals(0,number(progress("PO-RETURN-CANCEL"),"returnedQuantity"));
        assertEquals(4,number(receiptSource("PO-RETURN-CANCEL","IN-SOURCE"),"remainingReturnQuantity"));
        assertEquals(6,number(progress("PO-RETURN-CANCEL"),"remainingQuantity"));assertEquals(Boolean.TRUE,inventory.reconcile().get("healthy"));
    }

    @Test public void sourceProductSupplierAndWarehouseCannotBeForged(){
        createOrder("PO-MATCH",10);
        ReceiptFrom wrongProduct=receipt("WRONG-PRODUCT","PO-MATCH",1,2);wrongProduct.getDetails().get(0).setProductId("2");
        ReceiptFrom wrongSupplier=receipt("WRONG-SUPPLIER","PO-MATCH",1,2);wrongSupplier.setSupplierIds("2");wrongSupplier.getDetails().get(0).setSupplierId("2");
        ReceiptFrom wrongWarehouse=receipt("WRONG-WAREHOUSE","PO-MATCH",1,2);wrongWarehouse.setWarehousingIds("2");wrongWarehouse.getDetails().get(0).setWarehousingId("2");
        rejected(409,()->saveReceipt(wrongProduct));rejected(409,()->saveReceipt(wrongSupplier));rejected(409,()->saveReceipt(wrongWarehouse));
        assertEquals(0,count("head_receipt"));assertEquals(10,number(progress("PO-MATCH"),"remainingQuantity"));assertEquals(10,physical());
    }

    @Test public void linkedReceiptCannotChangeTheApprovedPurchaseUnitPriceOrDiscount(){
        createOrder("PO-PRICE",10);
        ReceiptFrom wrongPrice=receipt("WRONG-PRICE","PO-PRICE",1,2);wrongPrice.getDetails().get(0).setUnivalence("0.01");
        ReceiptFrom wrongDiscount=receipt("WRONG-DISCOUNT","PO-PRICE",1,2);wrongDiscount.getDetails().get(0).setDiscount("0.50");
        rejected(409,()->saveReceipt(wrongPrice));rejected(409,()->saveReceipt(wrongDiscount));
        assertEquals(0,count("head_receipt"));assertEquals(10,physical());assertEquals(10,number(progress("PO-PRICE"),"remainingQuantity"));
    }

    @Test public void linkedReceiptCannotStripOrRebindItsExistingPurchaseSource(){
        createOrder("PO-LINK",10);createOrder("PO-OTHER",10);saveReceipt(receipt("IN-LINKED","PO-LINK",4,1));
        ReceiptFrom stripped=receipt("IN-LINKED","PO-LINK",4,1);stripped.getDetails().get(0).setSourcePurchaseLineId(null);
        rejected(409,()->saveReceipt(stripped));
        ReceiptFrom rebound=receipt("IN-LINKED","PO-OTHER",4,1);rejected(409,()->saveReceipt(rebound));
        ReceiptFrom changedType=receipt("IN-LINKED","PO-LINK",4,1);changedType.setReceiptType(5L);changedType.setReceiptCategory(3L);
        rejected(409,()->saveReceipt(changedType));
        assertEquals(4,number(progress("PO-LINK"),"draftReceiptQuantity"));assertEquals(0,number(progress("PO-OTHER"),"draftReceiptQuantity"));
        assertEquals(1,count("head_receipt"));assertEquals(10,physical());
    }

    @Test public void purchaseClassificationCannotBypassTheProcurementCostBook(){
        createOrder("PO-CATEGORY",10);ReceiptFrom forged=receipt("IN-WRONG-CATEGORY","PO-CATEGORY",4,2);forged.setReceiptCategory(3L);
        rejected(400,()->saveReceipt(forged));assertEquals(0,count("head_receipt"));assertEquals(10,physical());
        assertEquals(0,number(progress("PO-CATEGORY"),"receivedQuantity"));
    }

    @Test public void postedReceiptBusinessIdentitySurvivesRepeatedApprovalAndPhysicalRowRewrite(){
        createOrder("PO-RECEIPT-ID",10);ReceiptFrom incoming=receipt("IN-STABLE","PO-RECEIPT-ID",4,2);saveReceipt(incoming);
        String sourceIdentity=String.valueOf(receiptSource("PO-RECEIPT-ID","IN-STABLE").get("receiptLineId"));
        saveReceipt(incoming);assertEquals(sourceIdentity,receiptSource("PO-RECEIPT-ID","IN-STABLE").get("receiptLineId"));
        assertEquals(14,physical());assertEquals(1,count("commerce_warehouse_ledger"));
        saveReceipt(returned("RETURN-STABLE","PO-RECEIPT-ID","IN-STABLE",2,2));assertEquals(12,physical());
    }

    @Test public void changedLegacySourceFactsCannotBeHiddenBehindAnOldSnapshot(){
        createOrder("PO-LIVE-FACTS",10);ReceiptFrom incoming=receipt("IN-STALE-PO","PO-LIVE-FACTS",4,2);
        jdbc.update("UPDATE detail_order_form SET univalence=99 WHERE systematic_order_form='PO-LIVE-FACTS'");
        rejected(409,()->saveReceipt(incoming));assertEquals(0,count("head_receipt"));assertEquals(10,physical());
        jdbc.update("UPDATE detail_order_form SET univalence=17.25 WHERE systematic_order_form='PO-LIVE-FACTS'");
        saveReceipt(receipt("IN-CURRENT","PO-LIVE-FACTS",4,2));ReceiptFrom returned=returned("RETURN-STALE-IN","PO-LIVE-FACTS","IN-CURRENT",1,2);
        jdbc.update("UPDATE head_receipt SET receipt_status='1' WHERE systematic_receipt='IN-CURRENT'");
        rejected(409,()->saveReceipt(returned));assertEquals(1,count("head_receipt"));assertEquals(14,physical());
    }

    @Test public void sameSkuAtDifferentPricesHasIndependentStableSourceLines(){
        OrderFrom order=order("PO-TWO-PRICES",4,2);DetailOrderForm other=line(6);other.setUnivalence("19.50");
        order.setDetails(Arrays.asList(order.getDetails().get(0),other));saveOrder(order);
        HeadOrderForm loaded=loadedOrder("PO-TWO-PRICES");assertEquals(2,loaded.getDetails().size());
        String first=loaded.getDetails().get(0).getPurchaseLineId(),second=loaded.getDetails().get(1).getPurchaseLineId();
        assertNotNull(first);assertNotNull(second);assertNotEquals(first,second);
        ReceiptFrom four=receipt("IN-CHEAPER","PO-TWO-PRICES",4,2);four.getDetails().get(0).setSourcePurchaseLineId(first);saveReceipt(four);
        ReceiptFrom tooMuch=receipt("IN-CHEAPER-OVER","PO-TWO-PRICES",1,1);tooMuch.getDetails().get(0).setSourcePurchaseLineId(first);
        rejected(409,()->saveReceipt(tooMuch));
        ReceiptFrom six=receipt("IN-DEARER","PO-TWO-PRICES",6,2);six.getDetails().get(0).setSourcePurchaseLineId(second);six.getDetails().get(0).setUnivalence("19.50");saveReceipt(six);
        assertEquals(10,number(progress("PO-TWO-PRICES"),"receivedQuantity"));assertEquals(0,number(progress("PO-TWO-PRICES"),"remainingQuantity"));
        assertEquals(20,physical());assertEquals(2,transaction(()->procurement.receiptSources("PO-TWO-PRICES")).size());
    }

    @Test public void supplierReturnCannotChooseAnotherWarehouseOrOmitOriginalEvidence(){
        createOrder("PO-RETURN-MATCH",10);saveReceipt(receipt("IN-SOURCE","PO-RETURN-MATCH",4,2));
        ReceiptFrom wrongWarehouse=returned("RETURN-WRONG-WAREHOUSE","PO-RETURN-MATCH","IN-SOURCE",1,2);
        wrongWarehouse.setRetrievalIds("2");wrongWarehouse.getDetails().get(0).setRetrievalId("2");
        rejected(409,()->saveReceipt(wrongWarehouse));
        ReceiptFrom noSource=returned("RETURN-NO-SOURCE","PO-RETURN-MATCH","IN-SOURCE",1,2);noSource.getDetails().get(0).setSourceReceiptLineId(null);
        rejected(400,()->saveReceipt(noSource));assertEquals(14,physical());assertEquals(0,number(progress("PO-RETURN-MATCH"),"returnedQuantity"));
    }

    @Test public void purchaseOrderWithAnyDownstreamReceiptCannotBeUnapprovedOrDeleted(){
        createOrder("PO-FROZEN",10);saveReceipt(receipt("IN-DRAFT","PO-FROZEN",4,1));
        OrderFrom unapprove=order("PO-FROZEN",10,1);unapprove.getDetails().get(0).setPurchaseLineId(purchaseLineId("PO-FROZEN"));
        rejected(409,()->saveOrder(unapprove));rejected(409,()->deleteOrder("PO-FROZEN"));
        assertEquals("2",jdbc.queryForObject("SELECT order_form_status FROM head_order_form WHERE systematic_order_form='PO-FROZEN'",String.class));
        assertEquals(1,count("head_order_form"));assertEquals(1,count("detail_order_form"));assertEquals(4,number(progress("PO-FROZEN"),"draftReceiptQuantity"));
    }

    @Test public void originalPurchaseReceiptIsFrozenWhileSupplierReturnReferencesIt(){
        createOrder("PO-SOURCE-FROZEN",10);ReceiptFrom source=receipt("IN-SOURCE","PO-SOURCE-FROZEN",4,2);saveReceipt(source);
        saveReceipt(returned("RETURN-DRAFT","PO-SOURCE-FROZEN","IN-SOURCE",2,1));
        source.setReceiptStatus(1L);rejected(409,()->saveReceipt(source));rejected(409,()->deleteReceipt("IN-SOURCE"));
        assertEquals(14,physical());assertEquals(4,number(progress("PO-SOURCE-FROZEN"),"receivedQuantity"));
        assertEquals("2",jdbc.queryForObject("SELECT receipt_status FROM head_receipt WHERE systematic_receipt='IN-SOURCE'",String.class));
    }

    @Test public void blockedSupplierReturnRollsBackApprovalAndSourceCounters(){
        createOrder("PO-STOCK",10);saveReceipt(receipt("IN-SOURCE","PO-STOCK",4,2));
        ReceiptFrom returned=returned("RETURN-STOCK","PO-STOCK","IN-SOURCE",3,1);saveReceipt(returned);reserve("CUSTOMER-HOLD",12);
        returned.setReceiptStatus(2L);rejected(409,()->saveReceipt(returned));
        assertEquals(14,physical());assertEquals(12,((Number)inventory.balances(1).get("reservedStock")).longValue());
        assertEquals("1",jdbc.queryForObject("SELECT receipt_status FROM head_receipt WHERE systematic_receipt='RETURN-STOCK'",String.class));
        assertEquals(0,number(progress("PO-STOCK"),"returnedQuantity"));
        assertEquals(3,number(receiptSource("PO-STOCK","IN-SOURCE"),"draftReturnQuantity"));
        assertEquals(1,count("commerce_warehouse_ledger"));assertEquals(Boolean.TRUE,inventory.reconcile().get("healthy"));
    }

    @Test public void purchaseBusinessLineIdSurvivesDraftEditsAndRealMapperReload(){
        OrderFrom draft=order("PO-EDIT",10,1);saveOrder(draft);
        HeadOrderForm first=loadedOrder("PO-EDIT");assertEquals(1,first.getDetails().size());
        String stableId=first.getDetails().get(0).getPurchaseLineId();assertNotNull(stableId);assertFalse(stableId.isEmpty());
        OrderFrom edited=order("PO-EDIT",12,1);edited.getDetails().get(0).setPurchaseLineId(stableId);saveOrder(edited);
        assertEquals(stableId,loadedOrder("PO-EDIT").getDetails().get(0).getPurchaseLineId());
        assertEquals(12,number(progress("PO-EDIT"),"orderedQuantity"));
        edited.setOrderFormStatus(2L);saveOrder(edited);saveReceipt(receipt("IN-AFTER-EDIT","PO-EDIT",12,2));
        assertEquals(stableId,loadedOrder("PO-EDIT").getDetails().get(0).getPurchaseLineId());assertEquals(22,physical());
    }

    @Test public void transactionFailureRollsBackPurchaseHeadDetailsAndStableSourceRows(){
        try{transaction(()->{procurement.saveOrder(order("PO-ROLLBACK",10,2));throw new ServiceException("Simulated outer transaction failure",409);});fail("Expected rollback");}
        catch(ServiceException expected){assertEquals(Integer.valueOf(409),expected.getCode());}
        assertEquals(0,count("head_order_form"));assertEquals(0,count("detail_order_form"));assertEquals(0,count("commerce_purchase_order_line"));
        createOrder("PO-ROLLBACK",10);assertEquals(1,count("head_order_form"));assertEquals(10,number(progress("PO-ROLLBACK"),"orderedQuantity"));
    }

    @Test public void transactionFailureAfterPostingRollsBackPurchaseProgressReceiptAndWarehouseJournals(){
        createOrder("PO-POST-ROLLBACK",10);ReceiptFrom incoming=receipt("IN-ROLLBACK","PO-POST-ROLLBACK",4,2);
        long openingStockEvents=count("commerce_stock_ledger");
        try{transaction(()->{guard.save(incoming);throw new ServiceException("Simulated downstream failure",409);});fail("Expected rollback");}
        catch(ServiceException expected){assertEquals(Integer.valueOf(409),expected.getCode());}
        assertEquals(0,count("head_receipt"));assertEquals(0,count("detail_receipt"));assertEquals(0,count("commerce_purchase_receipt_line"));
        assertEquals(0,count("commerce_warehouse_ledger"));assertEquals(openingStockEvents,count("commerce_stock_ledger"));
        assertEquals(10,physical());assertEquals(0,number(progress("PO-POST-ROLLBACK"),"receivedQuantity"));assertEquals(10,number(progress("PO-POST-ROLLBACK"),"remainingQuantity"));
        saveReceipt(receipt("IN-ROLLBACK","PO-POST-ROLLBACK",4,2));assertEquals(14,physical());assertEquals(4,number(progress("PO-POST-ROLLBACK"),"receivedQuantity"));
    }

    @Test public void unapprovedPurchaseOrderCannotSupplyAnApprovedReceipt(){
        saveOrder(order("PO-NOT-APPROVED",10,1));rejected(409,()->saveReceipt(receipt("IN-EARLY","PO-NOT-APPROVED",4,2)));
        assertEquals(0,count("head_receipt"));assertEquals(10,physical());assertEquals(0,number(progress("PO-NOT-APPROVED"),"receivedQuantity"));
    }

    @Test public void humanCommandRetriesReturnTheSameReceiptWithoutAnotherDraft(){
        createOrder("PO-COMMAND",10);Map<String,Object> body=commandBody("PO-COMMAND","command-repeat",4);
        Map<String,Object> created=command("PO-COMMAND",body),retry=command("PO-COMMAND",body);
        assertEquals(created,retry);assertEquals(1,count("commerce_purchase_command"));assertEquals(1,count("head_receipt"));
        assertEquals(4,number(progress("PO-COMMAND"),"draftReceiptQuantity"));assertEquals(10,physical());
    }

    @Test public void reusedHumanCommandKeyCannotChangeItsQuantityOrSource(){
        createOrder("PO-COMMAND-CONFLICT",10);Map<String,Object> body=commandBody("PO-COMMAND-CONFLICT","command-conflict",4);
        String receiptId=String.valueOf(command("PO-COMMAND-CONFLICT",body).get("receiptId"));
        rejected(409,()->command("PO-COMMAND-CONFLICT",commandBody("PO-COMMAND-CONFLICT","command-conflict",3)));
        assertEquals(receiptId,command("PO-COMMAND-CONFLICT",body).get("receiptId"));
        assertEquals(1,count("head_receipt"));assertEquals(4,number(progress("PO-COMMAND-CONFLICT"),"draftReceiptQuantity"));
    }

    @Test public void humanCommandAfterApprovalReplaysItsOriginalPostedReceipt(){
        createOrder("PO-COMMAND-APPROVE",10);Map<String,Object> body=commandBody("PO-COMMAND-APPROVE","command-approve",4);
        String receiptId=String.valueOf(command("PO-COMMAND-APPROVE",body).get("receiptId"));
        saveReceipt(receipt(receiptId,"PO-COMMAND-APPROVE",4,2));
        Map<String,Object> replay=command("PO-COMMAND-APPROVE",body);assertEquals(receiptId,replay.get("receiptId"));assertEquals(2,number(replay,"receiptStatus"));
        assertEquals(14,physical());assertEquals(4,number(progress("PO-COMMAND-APPROVE"),"receivedQuantity"));
        assertEquals(0,number(progress("PO-COMMAND-APPROVE"),"draftReceiptQuantity"));assertEquals(1,count("head_receipt"));
    }

    @Test public void failedHumanCommandRollsBackItsMutexAndDraftThenAllowsFreshRetry(){
        createOrder("PO-COMMAND-FAIL",10);Map<String,Object> body=commandBody("PO-COMMAND-FAIL","command-rollback",4);
        try{transaction(()->{commands.createReceipt("PO-COMMAND-FAIL",body,1,"Operator",false);throw new ServiceException("Simulated command failure",409);});fail("Expected rollback");}
        catch(ServiceException expected){assertEquals(Integer.valueOf(409),expected.getCode());}
        assertEquals(0,count("commerce_purchase_command"));assertEquals(0,count("head_receipt"));assertEquals(0,count("detail_receipt"));
        assertEquals(0,count("commerce_purchase_receipt_line"));assertEquals(10,number(progress("PO-COMMAND-FAIL"),"remainingQuantity"));
        command("PO-COMMAND-FAIL",body);assertEquals(1,count("commerce_purchase_command"));assertEquals(1,count("head_receipt"));assertEquals(10,physical());
    }

    @Test public void rejectedCommandDoesNotPersistABlockedRequestKey(){
        createOrder("PO-COMMAND-REJECT",10);
        rejected(409,()->command("PO-COMMAND-REJECT",commandBody("PO-COMMAND-REJECT","command-rejected",11)));
        assertEquals(0,count("commerce_purchase_command"));assertEquals(0,count("head_receipt"));
        command("PO-COMMAND-REJECT",commandBody("PO-COMMAND-REJECT","command-rejected",10));
        assertEquals(1,count("head_receipt"));assertEquals(10,number(progress("PO-COMMAND-REJECT"),"draftReceiptQuantity"));
    }

    @Test public void concurrentIdenticalHumanCommandsCreateOneSourceLinkedDraft() throws Exception {
        createOrder("PO-COMMAND-RACE",10);Map<String,Object> body=commandBody("PO-COMMAND-RACE","command-concurrent",4);
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try{
            Future<Map<String,Object>> left=pool.submit(()->{start.await();return command("PO-COMMAND-RACE",body);});
            Future<Map<String,Object>> right=pool.submit(()->{start.await();return command("PO-COMMAND-RACE",body);});
            start.countDown();assertEquals(left.get(15,TimeUnit.SECONDS),right.get(15,TimeUnit.SECONDS));
            assertEquals(1,count("commerce_purchase_command"));assertEquals(1,count("head_receipt"));assertEquals(1,count("detail_receipt"));
            assertEquals(4,number(progress("PO-COMMAND-RACE"),"draftReceiptQuantity"));assertEquals(10,physical());
        }finally{pool.shutdownNow();}
    }

    @Test public void sourceCommandCreatesAndApprovesMultipleWarehouseLinesThroughRealReceiptXml(){
        OrderFrom order=order("PO-COMMAND-MULTI",4,2);DetailOrderForm other=line(6);other.setWarehousingId("2");
        order.setDetails(Arrays.asList(order.getDetails().get(0),other));saveOrder(order);
        List<Map<String,Object>> items=new java.util.ArrayList<>();
        for(DetailOrderForm detail:loadedOrder("PO-COMMAND-MULTI").getDetails()){
            Map<String,Object> item=new LinkedHashMap<>();item.put("purchaseLineId",detail.getPurchaseLineId());item.put("quantity",Long.parseLong(detail.getPlanQuantity()));
            items.add(item);
        }
        Map<String,Object> body=new LinkedHashMap<>();body.put("requestKey","command-multiple-lines");body.put("items",items);
        String id=String.valueOf(command("PO-COMMAND-MULTI",body).get("receiptId"));
        assertEquals(2,count("detail_receipt"));assertEquals(10,number(progress("PO-COMMAND-MULTI"),"draftReceiptQuantity"));assertEquals(10,physical());
        HeadReceipt loaded=transaction(()->{HeadReceipt head=receiptHeads.selectHeadReceiptById(id);procurement.enrichReceipt(head);return head;});
        assertEquals(2,loaded.getDetails().size());
        for(DetailReceipt detail:loaded.getDetails()){assertNotNull(detail.getPurchaseReceiptLineId());assertNotNull(detail.getSourcePurchaseLineId());}
        ReceiptFrom approved=new ReceiptFrom();approved.setSystematicReceipt(id);approved.setReceiptCategory(1L);approved.setReceiptType(1L);approved.setReceiptStatus(2L);
        approved.setWarehousingIds(loaded.getWarehousingIds());approved.setSupplierIds(loaded.getSupplierIds());approved.setUserIds("1");approved.setDetails(loaded.getDetails());
        saveReceipt(approved);assertEquals(20,physical());assertEquals(10,number(progress("PO-COMMAND-MULTI"),"receivedQuantity"));
        assertEquals(0,number(progress("PO-COMMAND-MULTI"),"draftReceiptQuantity"));assertEquals(2,count("commerce_warehouse_ledger"));
        assertEquals(6,jdbc.queryForObject("SELECT SUM(plan_quantity) FROM inventory_product WHERE product_id=1 AND warehouse_id=2",Long.class).longValue());
        assertEquals(Boolean.TRUE,inventory.reconcile().get("healthy"));
    }

    @Test public void supplierReturnCommandRetriesUseItsOriginalEvidenceAndSeparateCommandKind(){
        createOrder("PO-COMMAND-RETURN",10);Map<String,Object> incomingBody=commandBody("PO-COMMAND-RETURN","command-kind-key",4);
        String incoming=String.valueOf(command("PO-COMMAND-RETURN",incomingBody).get("receiptId"));saveReceipt(receipt(incoming,"PO-COMMAND-RETURN",4,2));
        Map<String,Object> item=new LinkedHashMap<>();item.put("sourceReceiptLineId",receiptSource("PO-COMMAND-RETURN",incoming).get("receiptLineId"));item.put("quantity",2L);
        Map<String,Object> body=new LinkedHashMap<>();body.put("requestKey","command-kind-key");body.put("items",Collections.singletonList(item));
        Map<String,Object> returned=transaction(()->commands.createReceipt("PO-COMMAND-RETURN",body,1,"Operator",true));
        assertEquals(returned,transaction(()->commands.createReceipt("PO-COMMAND-RETURN",body,1,"Operator",true)));
        assertNotEquals(incoming,returned.get("receiptId"));assertEquals(2,count("commerce_purchase_command"));
        assertEquals(2,number(receiptSource("PO-COMMAND-RETURN",incoming),"draftReturnQuantity"));assertEquals(14,physical());
        assertEquals(6,number(progress("PO-COMMAND-RETURN"),"remainingQuantity"));
    }

    @Test public void deletedHumanCommandDraftCannotBeSilentlyRecreatedWithTheSameKey(){
        createOrder("PO-COMMAND-DELETED",10);Map<String,Object> body=commandBody("PO-COMMAND-DELETED","command-deleted",4);
        deleteReceipt(String.valueOf(command("PO-COMMAND-DELETED",body).get("receiptId")));
        rejected(409,()->command("PO-COMMAND-DELETED",body));assertEquals(0,count("head_receipt"));
        assertEquals(1,count("commerce_purchase_command"));assertEquals(10,number(progress("PO-COMMAND-DELETED"),"remainingQuantity"));
    }

    @Test public void historicalSupplierReturnCanBeMaintainedButCannotExpandItsUnlinkedOutflow(){
        jdbc.update("INSERT INTO head_receipt(systematic_receipt,receipt_category,receipt_type,receipt_status,retrieval_ids,supplier_ids,user_ids) VALUES ('HISTORICAL-RETURN','1','2','1',1,1,1)");
        jdbc.update("INSERT INTO detail_receipt(systematic_receipt,product_id,retrieval_id,supplier_id,plan_quantity,univalence,discount,money,cost) VALUES ('HISTORICAL-RETURN',1,1,1,2,17.25,1,34.50,34.50)");
        ReceiptFrom legacy=new ReceiptFrom();legacy.setSystematicReceipt("HISTORICAL-RETURN");legacy.setReceiptCategory(1L);legacy.setReceiptType(2L);legacy.setReceiptStatus(1L);
        legacy.setRetrievalIds("1");legacy.setSupplierIds("1");legacy.setUserIds("1");
        DetailReceipt line=new DetailReceipt();line.setProductId("1");line.setRetrievalId("1");line.setSupplierId("1");line.setPlanQuantity("2");line.setUnivalence("17.25");line.setDiscount("1");
        legacy.setDetails(Collections.singletonList(line));saveReceipt(legacy);assertEquals(10,physical());
        line.setPlanQuantity("3");rejected(409,()->saveReceipt(legacy));line.setPlanQuantity("2");
        DetailReceipt additional=new DetailReceipt();additional.setProductId("1");additional.setRetrievalId("1");additional.setSupplierId("1");additional.setPlanQuantity("1");additional.setUnivalence("17.25");additional.setDiscount("1");
        legacy.setDetails(Arrays.asList(line,additional));rejected(409,()->saveReceipt(legacy));
        assertEquals(1,count("head_receipt"));assertEquals(1,count("detail_receipt"));assertEquals(10,physical());
    }

    @Test public void standaloneLegacyInboundWithUnknownSupplierAndPercentDiscountCanReturnAgainstRealEvidence(){
        ReceiptFrom incoming=new ReceiptFrom();incoming.setSystematicReceipt("LEGACY-SUPPLIER-ZERO");incoming.setReceiptCategory(1L);
        incoming.setReceiptType(1L);incoming.setReceiptStatus(2L);incoming.setWarehousingIds("1");incoming.setSupplierIds("0");incoming.setUserIds("1");
        DetailReceipt line=new DetailReceipt();line.setProductId("1");line.setWarehousingId("1");line.setSupplierId("0");
        line.setPlanQuantity("4");line.setUnivalence("17.25");line.setDiscount("100");incoming.setDetails(Collections.singletonList(line));
        saveReceipt(incoming);saveReceipt(incoming);assertEquals(14,physical());assertEquals(1,count("commerce_warehouse_ledger"));
        HeadReceipt loaded=transaction(()->{HeadReceipt head=receiptHeads.selectHeadReceiptById("LEGACY-SUPPLIER-ZERO");procurement.enrichReceipt(head);return head;});
        assertNotNull(loaded.getDetails().get(0).getPurchaseReceiptLineId());assertNull(loaded.getDetails().get(0).getSourcePurchaseLineId());
        ReceiptFrom returned=new ReceiptFrom();returned.setSystematicReceipt("RETURN-UNKNOWN-SUPPLIER");returned.setReceiptCategory(1L);
        returned.setReceiptType(2L);returned.setReceiptStatus(2L);returned.setRetrievalIds("1");returned.setSupplierIds("0");returned.setUserIds("1");
        DetailReceipt returnLine=new DetailReceipt();returnLine.setSourceReceiptLineId(loaded.getDetails().get(0).getPurchaseReceiptLineId());
        returnLine.setProductId("1");returnLine.setRetrievalId("1");returnLine.setSupplierId("0");returnLine.setPlanQuantity("2");
        returnLine.setUnivalence("17.25");returnLine.setDiscount("100");returned.setDetails(Collections.singletonList(returnLine));
        saveReceipt(returned);assertEquals(12,physical());assertEquals(2,count("commerce_warehouse_ledger"));
        assertEquals(Boolean.TRUE,inventory.reconcile().get("healthy"));
    }

    @Test public void salesAdapterCannotUseAnUnvalidatedDetailOrderNumberToMutatePurchaseRows() throws Exception {
        createOrder("PO-SALES-BYPASS",10);SalesOrderProcessingServiceImpl sales=salesAdapter();
        OrderFrom existing=order("SO-EXISTING",2,1);existing.setOrderFormType(2L);
        transaction(()->sales.saveSalesOrderForm(existing));
        assertEquals("SO-EXISTING",existing.getDetails().get(0).getSystematicOrderForm());
        OrderFrom overwriting=order("SO-EXISTING",3,1);overwriting.setOrderFormType(2L);overwriting.getDetails().get(0).setSystematicOrderForm("PO-SALES-BYPASS");
        rejected(400,()->transaction(()->sales.saveSalesOrderForm(overwriting)));
        OrderFrom appending=order("SO-NEW",3,1);appending.setOrderFormType(2L);appending.getDetails().get(0).setSystematicOrderForm("PO-SALES-BYPASS");
        rejected(400,()->transaction(()->sales.saveSalesOrderForm(appending)));
        assertEquals(10,jdbc.queryForObject("SELECT plan_quantity FROM detail_order_form WHERE systematic_order_form='PO-SALES-BYPASS'",Long.class).longValue());
        assertEquals(2,jdbc.queryForObject("SELECT plan_quantity FROM detail_order_form WHERE systematic_order_form='SO-EXISTING'",Long.class).longValue());
        assertEquals(2,count("head_order_form"));assertEquals(2,count("detail_order_form"));
        assertEquals(10,number(progress("PO-SALES-BYPASS"),"orderedQuantity"));
    }

    @Test public void salesAdapterNormalizesMissingDetailOrderNumberAndRejectsEmptyDetails() throws Exception {
        SalesOrderProcessingServiceImpl sales=salesAdapter();OrderFrom order=order("SO-NORMALIZE",2,1);order.setOrderFormType(2L);
        assertNull(order.getDetails().get(0).getSystematicOrderForm());transaction(()->sales.saveSalesOrderForm(order));
        assertEquals("SO-NORMALIZE",order.getDetails().get(0).getSystematicOrderForm());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM detail_order_form WHERE systematic_order_form='SO-NORMALIZE'",Long.class).longValue());
        OrderFrom empty=order("SO-EMPTY",2,1);empty.setOrderFormType(2L);empty.setDetails(Collections.emptyList());
        rejected(400,()->transaction(()->sales.saveSalesOrderForm(empty)));
        assertEquals(1,count("head_order_form"));assertEquals(1,count("detail_order_form"));
    }
}

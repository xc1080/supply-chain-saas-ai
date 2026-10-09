package com.ruoyi.system.service;

import com.ruoyi.common.exception.ServiceException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Before;
import org.junit.After;
import com.ruoyi.common.core.tenant.CommerceShopContext;
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
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.junit.Assert.*;

/** Real SQL/transaction tests. A separate live MySQL smoke verifies the deployed path. */
public class CommerceServiceTest {
    protected CommerceService service;
    protected CommerceMerchantService merchants;
    protected CommerceAfterSalesService afterSales;
    protected CommerceReceiptInventoryGuard warehouse;
    protected JdbcTemplate jdbc;
    protected TransactionTemplate tx;
    protected static final String OWNER = String.join("", Collections.nCopies(64, "a"));
    protected static final String FOREIGN_OWNER = String.join("", Collections.nCopies(64, "b"));

    @Before
    public void setup() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:commerce" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        jdbc = new JdbcTemplate(source);
        String script = StreamUtils.copyToString(new ClassPathResource("db/commerce-demo.sql").getInputStream(), StandardCharsets.UTF_8);
        script = script.replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "");
        new ResourceDatabasePopulator(new ByteArrayResource(script.getBytes(StandardCharsets.UTF_8))).execute(source);
        jdbc.execute("CREATE TABLE product(product_id BIGINT PRIMARY KEY,product_code VARCHAR(64),product_name VARCHAR(64),product_specifications VARCHAR(16),measure_unit VARCHAR(16),status VARCHAR(1),univalence DECIMAL(10,2),cost_price DECIMAL(10,2),inventory_qty BIGINT,update_by VARCHAR(32),update_time DATETIME,notes VARCHAR(128),product_type BIGINT)");
        jdbc.execute("CREATE TABLE product_type(product_type_id BIGINT PRIMARY KEY,product_type_name VARCHAR(64))");
        jdbc.execute("CREATE TABLE inventory_product(inventory_id BIGINT PRIMARY KEY,product_id BIGINT,warehouse_id BIGINT,plan_quantity BIGINT,update_by VARCHAR(32),update_time DATETIME)");
        jdbc.execute("CREATE TABLE detail_receipt(systematic_id BIGINT AUTO_INCREMENT PRIMARY KEY,systematic_receipt VARCHAR(32),product_id BIGINT,product_specifications VARCHAR(16),measure_unit VARCHAR(16),warehousing_id BIGINT,retrieval_id BIGINT,supplier_id BIGINT,customer_id BIGINT,current_inventory BIGINT,actual_inventory BIGINT,plan_quantity BIGINT,univalence DECIMAL(14,2),discount DECIMAL(5,2),money DECIMAL(14,2),cost DECIMAL(14,2),remarks VARCHAR(64))");
        jdbc.execute("CREATE TABLE head_receipt(systematic_id BIGINT AUTO_INCREMENT PRIMARY KEY,systematic_receipt VARCHAR(32),original_receipt VARCHAR(64),receipt_category VARCHAR(1),receipt_type VARCHAR(1),receipt_status VARCHAR(1),invoice_date DATE,warehousing_ids BIGINT,retrieval_ids BIGINT,user_ids BIGINT,supplier_ids BIGINT,customer_ids BIGINT,deposit DECIMAL(14,2),total_amount DECIMAL(14,2),receipt_notes VARCHAR(128),create_by VARCHAR(32),create_time DATETIME)");
        jdbc.update("INSERT INTO product(product_id,product_code,product_name,product_specifications,measure_unit,status,univalence,cost_price,inventory_qty) VALUES (1,'DEMO-LAMP-ZB','卧室柔光智能灯','Zigbee/9W','件','0',129,70,10)");
        jdbc.update("INSERT INTO inventory_product(inventory_id,product_id,warehouse_id,plan_quantity) VALUES (1,1,1,10)");
        service = new CommerceService(source);
        merchants=new CommerceMerchantService(source);
        for(String resource:Arrays.asList("db/commerce-merchant.sql","db/commerce-inventory.sql","db/commerce-after-sales.sql","db/commerce-planning.sql","db/commerce-payment.sql","db/commerce-delivery.sql")) {
            String schema=StreamUtils.copyToString(new ClassPathResource(resource).getInputStream(),StandardCharsets.UTF_8).replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","");
            new ResourceDatabasePopulator(new ByteArrayResource(schema.getBytes(StandardCharsets.UTF_8))).execute(source);
        }
        jdbc.execute("ALTER TABLE commerce_order ADD COLUMN shop_id VARCHAR(32) DEFAULT 'default'");
        jdbc.execute("ALTER TABLE commerce_activity ADD COLUMN shop_id VARCHAR(32) DEFAULT 'default'");
        jdbc.update("INSERT INTO commerce_shop(shop_id,shop_name) VALUES ('default','Test shop')");
        jdbc.update("INSERT INTO commerce_delivery_policy VALUES ('default',1000000,0,1,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO commerce_shop_member VALUES ('default',1,'OWNER'),('default',2,'VIEWER')");
        jdbc.update("INSERT INTO commerce_product_shop VALUES (1,'default',1)");
        afterSales=new CommerceAfterSalesService(source);
        warehouse=new CommerceReceiptInventoryGuard(source,new CommerceInventoryService(source),null,null);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @After public void clearShop() { CommerceShopContext.clear(); }

    @Test public void inventoryReadsNeverBootstrapAndPublishingCreatesSnapshotInWriteTransaction() {
        jdbc.update("INSERT INTO product_type VALUES (1,'Smart lighting')");
        jdbc.update("UPDATE product SET product_type=1,notes='Gateway required' WHERE product_id=1");
        Map<String,Object> before=service.inventory().get(0);
        assertEquals(false,before.get("snapshotReady")); assertEquals(0L,before.get("availableStock"));
        assertEquals(0,count("commerce_stock")); assertEquals(0,count("commerce_stock_ledger"));
        transaction(()->{merchants.listing(1,true);return null;});
        Map<String,Object> published=service.inventory().get(0);
        assertEquals(true,published.get("snapshotReady")); assertEquals(10L,published.get("availableStock"));
        assertEquals("Zigbee/9W",published.get("spec")); assertEquals(new BigDecimal("129.00"),published.get("price"));
        assertEquals("Smart lighting",published.get("categoryName")); assertEquals("Gateway required",published.get("description"));
        jdbc.update("INSERT INTO product(product_id,product_code,product_name,status,univalence,inventory_qty) VALUES (2,'NEW-SKU','Newly published','0',50,4)");
        jdbc.update("INSERT INTO inventory_product(inventory_id,product_id,warehouse_id,plan_quantity) VALUES (2,2,1,4)");
        transaction(()->{merchants.listing(2,true);return null;});
        Map<String,Object> newSku=service.inventory().get(1);
        assertEquals(2L,newSku.get("productId")); assertEquals(true,newSku.get("snapshotReady")); assertEquals(4L,newSku.get("availableStock"));
        // Evidence/reconciliation remains explicit; displaying a mismatch must not repair it.
        jdbc.update("UPDATE inventory_product SET plan_quantity=9 WHERE product_id=1");
        jdbc.update("UPDATE product SET inventory_qty=9 WHERE product_id=1");
        assertEquals(10L,service.inventory().get(0).get("bookStock"));
        assertEquals(2,count("commerce_stock_ledger")); assertEquals(false,service.reconcile().get("healthy"));
    }

    @Test public void inventoryReadDoesNotWaitForCheckoutProductWarehouseAndSnapshotLocks() throws Exception {
        transaction(()->{merchants.listing(1,true);return null;});
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1);
        try {
            Future<?> writer=pool.submit(()->transaction(()->{
                jdbc.queryForList("SELECT product_id FROM product WHERE product_id=1 FOR UPDATE");
                jdbc.queryForList("SELECT inventory_id FROM inventory_product WHERE product_id=1 FOR UPDATE");
                jdbc.queryForList("SELECT product_id FROM commerce_stock WHERE product_id=1 FOR UPDATE");
                locked.countDown();
                try { assertTrue(release.await(5,TimeUnit.SECONDS)); } catch(InterruptedException ex) { throw new RuntimeException(ex); }
                return null;
            }));
            assertTrue(locked.await(2,TimeUnit.SECONDS));
            Future<List<Map<String,Object>>> read=pool.submit(()->service.inventory());
            assertEquals(10L,read.get(2,TimeUnit.SECONDS).get(0).get("availableStock"));
            release.countDown(); writer.get(2,TimeUnit.SECONDS);
        } finally {release.countDown();pool.shutdownNow();}
    }

    @Test public void inventoryDisplayDoesNotReleaseEndedActivityAndMaintenanceStillDoes() {
        activity("ending",3);
        jdbc.update("UPDATE commerce_activity SET ends_at=TIMESTAMPADD(SECOND,-5,CURRENT_TIMESTAMP) WHERE activity_id='ending'");
        long events=count("commerce_stock_ledger");
        assertEquals(3L,service.inventory().get(0).get("activityStock"));
        assertEquals(events,count("commerce_stock_ledger"));
        transaction(()->{service.expireActivities();return null;});
        assertEquals(0L,service.inventory().get(0).get("activityStock"));
        assertEquals(10L,service.inventory().get(0).get("availableStock"));
    }

    @Test public void activityParticipationIsOwnerAndShopScopedAndIgnoresCancelledOrders() {
        activity("summary",5);
        jdbc.update("UPDATE commerce_activity SET per_owner_limit=3 WHERE activity_id='summary'");
        String first=id(rush("summary",OWNER,"summary-first")),second=id(rush("summary",OWNER,"summary-second"));
        jdbc.update("UPDATE commerce_order SET create_time=TIMESTAMPADD(SECOND,-5,CURRENT_TIMESTAMP) WHERE order_id=?",first);
        String foreign=id(rush("summary",FOREIGN_OWNER,"summary-foreign"));
        Map<String,Object> own=service.activityParticipation(OWNER).get(0);
        assertEquals(new HashSet<>(Arrays.asList("activityId","participationCount","myOrderId","myOrderStatus")),own.keySet());
        assertEquals(2L,own.get("participationCount")); assertEquals(second,own.get("myOrderId")); assertEquals(0,((Number)own.get("myOrderStatus")).intValue());
        assertEquals(foreign,service.activityParticipation(FOREIGN_OWNER).get(0).get("myOrderId"));
        transaction(()->service.cancel(second,OWNER));
        assertEquals(first,service.activityParticipation(OWNER).get(0).get("myOrderId"));
        transaction(()->service.cancel(first,OWNER));
        Map<String,Object> cancelled=service.activityParticipation(OWNER).get(0);
        assertEquals(0L,cancelled.get("participationCount")); assertNull(cancelled.get("myOrderId")); assertNull(cancelled.get("myOrderStatus"));
        rejected(400,()->service.activityParticipation("forged"));
        CommerceShopContext.set("another-shop");
        assertTrue(service.activityParticipation(OWNER).isEmpty()); assertTrue(service.inventory().isEmpty());
    }

    @Test public void participationSummaryUsesSameBoundedActivityWindowAsPublicList() {
        for(int i=0;i<105;i++)
            jdbc.update("INSERT INTO commerce_activity(activity_id,product_id,title,price,capacity,remaining,per_owner_limit,starts_at,ends_at,shop_id) VALUES (?,1,'Window',99,1,0,1,CURRENT_TIMESTAMP,TIMESTAMPADD(SECOND,60,CURRENT_TIMESTAMP),'default')",String.format("window-%03d",i));
        List<Map<String,Object>> publicRows=service.activities(),privateRows=service.activityParticipation(OWNER);
        assertEquals(100,privateRows.size()); assertEquals(publicRows.size(),privateRows.size());
        for(int i=0;i<publicRows.size();i++) {
            assertEquals(publicRows.get(i).get("activityId"),privateRows.get(i).get("activityId"));
            assertEquals(0L,privateRows.get(i).get("participationCount")); assertNull(privateRows.get(i).get("myOrderId"));
        }
    }

    @Test public void shopMembershipAndSkuOwnershipCannotBeForged() {
        String defaultOrder=id(create("default-order",2));
        assertEquals(1,merchants.shops(2).size());
        rejected(403,()->merchants.requireShop("default",3,false));
        rejected(403,()->merchants.requireShop("default",2,true));
        assertNotNull(merchants.requireShop("default",2,false));
        String other=String.valueOf(transaction(()->merchants.create("Second shop",3)).get("shopId"));
        jdbc.update("INSERT INTO commerce_delivery_policy VALUES (?,1000000,0,3,CURRENT_TIMESTAMP)",other);
        CommerceShopContext.set(other);
        assertEquals(0L,service.list(null,null,1,20).get("total"));
        rejected(404,()->service.detail(defaultOrder,OWNER));
        rejected(404,()->transaction(()->service.ship(defaultOrder,map(),3)));
        rejected(404,()->create("not-my-product",1));
        rejected(409,()->transaction(()->{merchants.listing(1,true);return null;}));
        jdbc.update("INSERT INTO product(product_id,product_code,product_name,status,univalence,cost_price,inventory_qty) VALUES (2,'SHOP-PRIVATE','Private SKU','0',50,20,5)");
        jdbc.update("INSERT INTO inventory_product(inventory_id,product_id,warehouse_id,plan_quantity) VALUES (2,2,1,5)");
        transaction(()->{merchants.listing(2,true);return null;});
        String otherOrder=id(transaction(()->service.create(map("ownerId",OWNER,"requestKey","other-order","shopId","default","shippingAddress",address(),"items",Collections.singletonList(map("productId",2,"quantity",2))))));
        assertEquals(other,service.detail(otherOrder,OWNER).get("shopId"));
        assertEquals(2L,transaction(()->service.inventory()).get(0).get("reservedStock"));
        CommerceShopContext.clear();
        rejected(404,()->service.detail(otherOrder,OWNER));
        rejected(404,()->transaction(()->service.stockLedger(2,20)));
        rejected(404,()->transaction(()->service.create(map("ownerId",OWNER,"requestKey","forged","shopId",other,"items",Collections.singletonList(map("productId",2,"quantity",1))))));
        assertEquals(1L,service.list(null,null,1,20).get("total"));
    }

    @Test public void ledgerAndOriginalOutboundRollbackTogetherAndReconcile() {
        String order=id(create("journal",2));
        transaction(()->service.pay(order,map("ownerId",OWNER,"paymentRequestId","journal-pay","scenario","success")));
        transaction(()->service.ship(order,map(),1));
        transaction(()->service.ship(order,map(),1));
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM commerce_stock_ledger WHERE event_type='DISPATCH'",Long.class).longValue());
        assertEquals(8L,transaction(()->service.inventory()).get(0).get("bookStock"));
        assertEquals(true,service.reconcile().get("healthy"));
        assertEquals(3,service.stockLedger(1,100).size());
    }

    protected <T> T transaction(Supplier<T> work) { return tx.execute(status -> work.get()); }
    protected Map<String, Object> create(String key, int quantity) { return transaction(() -> service.create(request(key, quantity))); }
    protected Map<String, Object> request(String key, Object quantity) { return map("ownerId", OWNER, "requestKey", key, "shippingAddress",address(),"items", Collections.singletonList(map("productId", 1, "quantity", quantity, "unitPrice", 0.01))); }
    protected static Map<String,Object> address() {return map("addressee","测试收件人","phone","13800138000","address","广东省深圳市测试路 10 号");}
    protected static Map<String, Object> map(Object... pairs) { Map<String, Object> result = new LinkedHashMap<>(); for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]); return result; }
    protected String id(Map<String, Object> order) { return String.valueOf(order.get("orderId")); }
    protected long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    protected long book() { return jdbc.queryForObject("SELECT plan_quantity FROM inventory_product WHERE inventory_id=1", Long.class); }
    protected void rejected(int code, Runnable task) { try { task.run(); fail("Expected rejected operation"); } catch (ServiceException ex) { assertEquals(Integer.valueOf(code), ex.getCode()); } }

    @Test
    public void createReservesWithServerPriceAndIdempotentNormalizedRequest() {
        Map<String, Object> first = create("first", 2);
        assertEquals(new BigDecimal("258.00"), first.get("totalAmount"));
        assertEquals(10, book());
        Map<String, Object> stock = transaction(() -> service.inventory()).get(0);
        assertEquals(2L, stock.get("reservedStock")); assertEquals(8L, stock.get("availableStock"));
        jdbc.update("UPDATE product SET univalence=999 WHERE product_id=1");
        Map<String, Object> split = map("ownerId", OWNER, "requestKey", "first", "shippingAddress",address(),"items", Arrays.asList(map("productId", 1, "quantity", 1), map("productId", 1, "quantity", 1)));
        assertEquals(id(first), id(transaction(() -> service.create(split))));
        assertEquals(new BigDecimal("258.00"), transaction(() -> service.create(split)).get("totalAmount"));
        assertEquals(1, count("commerce_order"));
        rejected(409, () -> create("first", 3));
    }

    @Test
    public void insufficientStockAndMalformedQuantitiesLeaveNoPartialOrder() {
        rejected(409, () -> create("oversold", 11));
        assertEquals(0, count("commerce_order")); assertEquals(0, count("commerce_request"));
        for (Object invalid : Arrays.asList(0, -1, true, 1.2, "1.0", 100)) {
            rejected(400, () -> transaction(() -> service.create(request("bad", invalid))));
        }
        assertEquals(10, book());
    }

    @Test
    public void paymentFailureRetryShipmentAndReceiptAreIdempotent() {
        String orderId = id(create("flow", 2));
        Map<String, Object> failed = transaction(() -> service.pay(orderId, map("ownerId", OWNER, "paymentRequestId", "failed", "scenario", "failure")));
        assertEquals(0, failed.get("orderStatus")); assertEquals("FAILED", failed.get("paymentOutcome"));
        rejected(409, () -> transaction(() -> service.ship(orderId, map("carrier", "演示物流"), 1)));
        rejected(409, () -> transaction(() -> service.pay(orderId, map("ownerId", OWNER, "paymentRequestId", "failed", "scenario", "success"))));
        Map<String, Object> paid = transaction(() -> service.pay(orderId, map("ownerId", OWNER, "paymentRequestId", "success", "scenario", "success")));
        assertEquals(1, paid.get("orderStatus")); assertEquals(10, book());
        assertEquals(2L, transaction(() -> service.inventory()).get(0).get("reservedStock"));
        Map<String, Object> shipped = transaction(() -> service.ship(orderId, map("carrier", "演示物流"), 1));
        assertEquals(2, shipped.get("orderStatus")); assertEquals(8, book());
        assertEquals(8L, jdbc.queryForObject("SELECT inventory_qty FROM product WHERE product_id=1", Long.class).longValue());
        assertEquals(0L, transaction(() -> service.inventory()).get(0).get("reservedStock"));
        assertEquals("3", jdbc.queryForObject("SELECT receipt_type FROM head_receipt", String.class));
        assertEquals(orderId, jdbc.queryForObject("SELECT original_receipt FROM head_receipt", String.class));
        assertEquals(shipped.get("receiptId"), transaction(() -> service.ship(orderId, map("carrier", "重复请求"), 1)).get("receiptId"));
        assertEquals(1, count("head_receipt")); assertEquals(1, count("detail_receipt")); assertEquals(8, book());
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM commerce_warehouse_ledger WHERE operation='DISPATCH'",Long.class).longValue());
        rejected(409, () -> transaction(() -> service.cancel(orderId, OWNER)));
        assertEquals(3, transaction(() -> service.receive(orderId, OWNER)).get("orderStatus"));
        assertEquals(3, transaction(() -> service.receive(orderId, OWNER)).get("orderStatus"));
    }

    @Test
    public void cancelReleasesReservationAndOwnerCannotReadOrMutateOtherOrder() {
        String orderId = id(create("cancel", 8));
        rejected(404, () -> service.detail(orderId, FOREIGN_OWNER));
        rejected(404, () -> transaction(() -> service.cancel(orderId, FOREIGN_OWNER)));
        rejected(404, () -> transaction(() -> service.pay(orderId, map("ownerId", FOREIGN_OWNER, "paymentRequestId", "other", "scenario", "success"))));
        assertEquals(4, transaction(() -> service.cancel(orderId, OWNER)).get("orderStatus"));
        assertEquals(4, transaction(() -> service.cancel(orderId, OWNER)).get("orderStatus"));
        assertEquals(10L, transaction(() -> service.inventory()).get(0).get("availableStock"));
        rejected(409, () -> transaction(() -> service.pay(orderId, map("ownerId", OWNER, "paymentRequestId", "late", "scenario", "success"))));
    }

    @Test
    public void failedShipmentRollsBackEveryStockAndReceiptWrite() {
        jdbc.update("INSERT INTO product(product_id,product_code,product_name,product_specifications,measure_unit,status,univalence,cost_price,inventory_qty) VALUES (2,'DEMO-LAMP-WIFI','WiFi智能台灯','WiFi/12W','件','0',199,110,10)");
        jdbc.update("INSERT INTO inventory_product(inventory_id,product_id,warehouse_id,plan_quantity) VALUES (2,2,1,10)");
        jdbc.update("INSERT INTO commerce_product_shop VALUES (2,'default',1)");
        String orderId = id(transaction(() -> service.create(map("ownerId", OWNER, "requestKey", "rollback", "shippingAddress",address(),"items", Arrays.asList(map("productId", 1, "quantity", 2), map("productId", 2, "quantity", 2))))));
        transaction(() -> service.pay(orderId, map("ownerId", OWNER, "paymentRequestId", "pay", "scenario", "success")));
        jdbc.update("UPDATE inventory_product SET plan_quantity=0 WHERE product_id=2");
        rejected(409, () -> transaction(() -> service.ship(orderId, map("carrier", "演示物流"), 1)));
        assertEquals(10, book()); assertEquals(0, count("detail_receipt")); assertEquals(0, count("head_receipt"));
        assertEquals(1, service.detail(orderId, OWNER).get("orderStatus"));
    }

    @Test
    public void concurrentCheckoutCannotOversellAndConcurrentRetriesMakeOneOrder() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Callable<Boolean> left = () -> { start.await(); try { create("left", 7); return true; } catch (ServiceException ex) { assertEquals(Integer.valueOf(409), ex.getCode()); return false; } };
            Callable<Boolean> right = () -> { start.await(); try { create("right", 7); return true; } catch (ServiceException ex) { assertEquals(Integer.valueOf(409), ex.getCode()); return false; } };
            Future<Boolean> one = pool.submit(left), two = pool.submit(right); start.countDown();
            assertTrue(one.get(10, TimeUnit.SECONDS) ^ two.get(10, TimeUnit.SECONDS));
            assertEquals(7L, transaction(() -> service.inventory()).get(0).get("reservedStock"));
            List<?> orders = (List<?>) service.list(OWNER, null, 1, 20).get("rows");
            String orderId = String.valueOf(((Map<?, ?>) orders.get(0)).get("orderId"));
            transaction(() -> service.cancel(orderId, OWNER));
            Future<String> retryOne = pool.submit(() -> id(create("same", 2))), retryTwo = pool.submit(() -> id(create("same", 2)));
            assertEquals(retryOne.get(10, TimeUnit.SECONDS), retryTwo.get(10, TimeUnit.SECONDS));
            assertEquals(2, count("commerce_order"));
            assertEquals(2L, transaction(() -> service.inventory()).get(0).get("reservedStock"));
        } finally { pool.shutdownNow(); }
    }
    @Test
    public void timeoutClosesOnlyUnpaidOrdersAndReleasesExactlyOnce() {
        String unpaid = id(create("expiry", 3));
        jdbc.update("UPDATE commerce_order SET expires_at=TIMESTAMPADD(SECOND,-5,CURRENT_TIMESTAMP) WHERE order_id=?", unpaid);
        assertTrue(transaction(() -> service.expireOrder(unpaid)));
        assertFalse(transaction(() -> service.expireOrder(unpaid)));
        assertEquals("PAYMENT_TIMEOUT", service.detail(unpaid, OWNER).get("closeReason"));
        assertEquals(10L, transaction(() -> service.inventory()).get(0).get("availableStock")); assertEquals(10, book());
        String paid = id(create("protected", 2));
        transaction(() -> service.pay(paid, map("ownerId", OWNER, "paymentRequestId", "paid", "scenario", "success")));
        jdbc.update("UPDATE commerce_order SET expires_at=TIMESTAMPADD(SECOND,-5,CURRENT_TIMESTAMP) WHERE order_id=?", paid);
        assertFalse(transaction(() -> service.expireOrder(paid)));
        assertEquals(1, service.detail(paid, OWNER).get("orderStatus"));
    }

    @Test
    public void latePaymentAtomicallyClosesOrderWithoutPaymentAttempt() {
        String unpaid = id(create("late", 3));
        jdbc.update("UPDATE commerce_order SET expires_at=TIMESTAMPADD(SECOND,-5,CURRENT_TIMESTAMP) WHERE order_id=?", unpaid);
        Map<String, Object> result = transaction(() -> service.pay(unpaid, map("ownerId", OWNER, "paymentRequestId", "late", "scenario", "success")));
        assertEquals(4, result.get("orderStatus")); assertEquals("EXPIRED", result.get("paymentOutcome"));
        assertEquals(0, count("commerce_payment_attempt")); assertEquals(10L, transaction(() -> service.inventory()).get(0).get("availableStock"));
    }

    @Test
    public void expiryAndPaymentRaceCannotProducePaidOrderWithoutReservation() throws Exception {
        String unpaid = id(create("race-expiry", 3));
        jdbc.update("UPDATE commerce_order SET expires_at=TIMESTAMPADD(SECOND,-5,CURRENT_TIMESTAMP) WHERE order_id=?", unpaid);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> closing = pool.submit(() -> transaction(() -> service.expireOrder(unpaid)));
            Future<?> payment = pool.submit(() -> { try { transaction(() -> service.pay(unpaid, map("ownerId", OWNER, "paymentRequestId", "racing", "scenario", "success"))); } catch (ServiceException ex) { assertEquals(Integer.valueOf(409), ex.getCode()); } });
            closing.get(10, TimeUnit.SECONDS); payment.get(10, TimeUnit.SECONDS);
            assertEquals(4, service.detail(unpaid, OWNER).get("orderStatus")); assertEquals(10L, transaction(() -> service.inventory()).get(0).get("availableStock"));
            assertEquals(0, count("commerce_payment_attempt"));
        } finally { pool.shutdownNow(); }
    }

    private void activity(String id, int capacity) {
        transaction(() -> service.createActivity(map("activityId",id,"productId",1,"capacity",capacity,"perOwnerLimit",1,"price",99,"startsAt",java.time.LocalDateTime.now().minusMinutes(1).toString(),"endsAt",java.time.LocalDateTime.now().plusMinutes(10).toString())));
    }
    private Map<String,Object> rush(String activity, String owner, String key) {
        return transaction(() -> service.seckill(activity, map("ownerId", owner, "requestKey", key,"shippingAddress",address())));
    }
    @Test public void activityAllocationCannotBeSoldNormallyAndCancellationRestoresQuota() {
        activity("rush", 3); assertEquals(7L, transaction(() -> service.inventory()).get(0).get("availableStock"));
        rejected(409, () -> create("normal-too-many",8));
        Map<String,Object> first=rush("rush", OWNER, "rush-key");
        assertEquals(new BigDecimal("99.00"),first.get("totalAmount"));
        assertEquals(id(first),id(rush("rush",OWNER,"rush-key")));
        rejected(409, () -> rush("rush",OWNER,"another-key"));
        assertEquals(7L,transaction(() -> service.inventory()).get(0).get("availableStock"));
        transaction(() -> service.cancel(id(first), OWNER));
        assertEquals(3L, service.activities().get(0).get("remaining"));
        assertEquals(7L,transaction(() -> service.inventory()).get(0).get("availableStock"));
        Map<String,Object> retry=rush("rush",OWNER,"new-key");
        jdbc.update("UPDATE commerce_order SET expires_at=TIMESTAMPADD(SECOND,-5,CURRENT_TIMESTAMP) WHERE order_id=?",id(retry));
        transaction(() -> service.expireOrder(id(retry)));
        assertEquals(3L,service.activities().get(0).get("remaining")); assertEquals(10,book());
    }
    @Test public void concurrentActivityBuyersCannotOversellAndExpiredActivityRejectsNewOrders() throws Exception {
        activity("scarce", 1); ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> one=() -> { try {rush("scarce",OWNER,"one"); return true;} catch(ServiceException ex) {assertEquals(Integer.valueOf(409),ex.getCode());return false;} };
            Callable<Boolean> two=() -> { try {rush("scarce",FOREIGN_OWNER,"two"); return true;} catch(ServiceException ex) {assertEquals(Integer.valueOf(409),ex.getCode());return false;} };
            Future<Boolean> a=pool.submit(one),b=pool.submit(two); assertTrue(a.get(10,TimeUnit.SECONDS)^b.get(10,TimeUnit.SECONDS));
            assertEquals(1,count("commerce_order")); assertEquals(0L,service.activities().get(0).get("remaining"));
            jdbc.update("UPDATE commerce_activity SET ends_at=TIMESTAMPADD(SECOND,-5,CURRENT_TIMESTAMP)");
            rejected(409, () -> rush("scarce",OWNER,"late-rush"));
        } finally {pool.shutdownNow();}
    }

}

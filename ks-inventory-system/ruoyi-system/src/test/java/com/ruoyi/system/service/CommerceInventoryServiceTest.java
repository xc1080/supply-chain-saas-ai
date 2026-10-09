package com.ruoyi.system.service;

import com.ruoyi.common.exception.ServiceException;
import org.h2.jdbcx.JdbcDataSource;
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

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.junit.Assert.*;

/** SQL transactions, retained histories, cross-campaign allocations and contention. */
public class CommerceInventoryServiceTest {
    private CommerceInventoryService service;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;

    @Before public void setup() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:inventory" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000");
        jdbc = new JdbcTemplate(source);
        schema(source, "db/commerce-demo.sql");
        schema(source, "db/commerce-inventory.sql");
        schema(source, "db/commerce-planning.sql");
        schema(source, "db/commerce-warehouse-allocation.sql");
        jdbc.execute("CREATE TABLE product(product_id BIGINT PRIMARY KEY,inventory_qty BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE inventory_product(inventory_id BIGINT PRIMARY KEY,product_id BIGINT NOT NULL,plan_quantity BIGINT NOT NULL,warehouse_id BIGINT DEFAULT 1)");
        jdbc.update("INSERT INTO product VALUES (1,10),(2,5)");
        jdbc.update("INSERT INTO inventory_product(inventory_id,product_id,plan_quantity) VALUES (1,1,7),(2,1,3),(3,2,5)");
        service = new CommerceInventoryService(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    private void schema(JdbcDataSource source, String name) throws Exception {
        String sql = StreamUtils.copyToString(new ClassPathResource(name).getInputStream(), StandardCharsets.UTF_8)
                .replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "");
        new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))).execute(source);
    }
    private <T> T transaction(Supplier<T> work) { return tx.execute(status -> work.get()); }
    private void work(Runnable work) { transaction(() -> { work.run(); return null; }); }
    private void ensure() { work(() -> service.ensureStock(1)); }
    private long stock(String key) { return ((Number) service.balances(1).get(key)).longValue(); }
    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    private void physical(long delta) {
        jdbc.update("UPDATE product SET inventory_qty=inventory_qty+? WHERE product_id=1", delta);
        jdbc.update("UPDATE inventory_product SET plan_quantity=plan_quantity+? WHERE inventory_id=1", delta);
    }
    private void order(String id, int status, String activity, long quantity) {
        jdbc.update("INSERT INTO commerce_order(order_id,owner_id,request_key,request_hash,status,total_amount,create_time,activity_id) VALUES (?,?,?,'hash',?,99,CURRENT_TIMESTAMP,?)", id, String.join("", Collections.nCopies(64,"a")), id, status, activity);
        jdbc.update("INSERT INTO commerce_order_item(order_id,product_id,product_code,product_name,quantity,unit_price,amount) VALUES (?,1,'DEMO-LAMP-ZB','灯',?,99,99)", id, quantity);
    }
    private void activity(String id, long capacity, long remaining, boolean active) {
        jdbc.update("INSERT INTO commerce_activity VALUES (?,1,'活动',99,?,?,1,?,?)", id, capacity, remaining,
                LocalDateTime.now().minusHours(1), active ? LocalDateTime.now().plusHours(1) : LocalDateTime.now().minusMinutes(1));
    }
    private void allocate(String id, long capacity) {
        work(() -> {
            service.ensureStock(1);
            activity(id, capacity, capacity, true);
            service.allocateActivity(id, 1, capacity);
        });
    }
    private void reserve(String id, long quantity, String activity) {
        work(() -> {
            service.ensureStock(1);
            order(id, 0, activity, quantity);
            service.reserve(id, Collections.singletonMap(1L, quantity), activity);
            if (activity != null) jdbc.update("UPDATE commerce_activity SET remaining=remaining-? WHERE activity_id=?", quantity, activity);
        });
    }
    private void cancel(String id, String activity, boolean active) {
        work(() -> {
            service.release(id, "CUSTOMER_CANCEL", active);
            jdbc.update("UPDATE commerce_order SET status=4 WHERE order_id=?", id);
            if (activity != null) jdbc.update("UPDATE commerce_activity SET remaining=remaining+1 WHERE activity_id=?", activity);
        });
    }
    private void rejected(int code, Runnable work) {
        try { work.run(); fail("Expected rejected operation"); }
        catch (ServiceException ex) { assertEquals(Integer.valueOf(code), ex.getCode()); }
    }

    @Test public void bootstrapRetainsHistoricalUnshippedOrdersAndActiveAllocationsOnly() {
        order("old-unpaid", 0, null, 2);
        order("old-paid", 1, "active", 1);
        order("old-shipped", 2, null, 4);
        order("old-cancelled", 4, null, 2);
        activity("active", 3, 2, true);
        activity("ended", 2, 2, false);
        ensure();
        assertEquals(10, stock("bookStock")); assertEquals(3, stock("reservedStock"));
        assertEquals(2, stock("activityStock")); assertEquals(5, stock("availableStock"));
        assertEquals(2, count("commerce_stock_hold"));
        long version = stock("version"), entries = count("commerce_stock_ledger");
        ensure(); assertEquals(version, stock("version")); assertEquals(entries, count("commerce_stock_ledger"));
        assertEquals(true, service.reconcile().get("healthy"));
        cancel("old-unpaid", null, false);
        assertEquals(1, stock("reservedStock")); assertEquals(7, stock("availableStock"));
    }

    @Test public void ordinaryReservationRetriesAndCancellationOnlyMoveReservationOnce() {
        reserve("ordinary", 3, null);
        work(() -> service.reserve("ordinary", Collections.singletonMap(1L,3L), null));
        assertEquals(3, stock("reservedStock")); assertEquals(7, stock("availableStock"));
        assertEquals(10, jdbc.queryForObject("SELECT inventory_qty FROM product WHERE product_id=1", Long.class).longValue());
        rejected(409, () -> work(() -> service.reserve("ordinary", Collections.singletonMap(1L,4L), null)));
        cancel("ordinary", null, false);
        long count = count("commerce_stock_ledger");
        work(() -> service.release("ordinary", "PAYMENT_TIMEOUT", false));
        assertEquals(count, count("commerce_stock_ledger")); assertEquals(10, stock("availableStock"));
        rejected(409, () -> work(() -> service.reserve("ordinary", Collections.singletonMap(1L,3L), null)));
        assertEquals(true, service.reconcile().get("healthy"));
    }

    @Test public void activityReserveTransfersAllocationAndActiveCancellationReturnsIt() {
        allocate("rush", 4);
        work(() -> service.allocateActivity("rush", 1, 4));
        assertEquals(4, stock("activityStock")); assertEquals(6, stock("availableStock"));
        reserve("rush-order", 1, "rush");
        assertEquals(1, stock("reservedStock")); assertEquals(3, stock("activityStock")); assertEquals(6, stock("availableStock"));
        cancel("rush-order", "rush", true);
        assertEquals(0, stock("reservedStock")); assertEquals(4, stock("activityStock"));
        rejected(409, () -> work(() -> service.allocateActivity("rush", 1, 5)));
        assertEquals(true, service.reconcile().get("healthy"));
    }

    @Test public void endedCampaignReleasesOnlyItsUnclaimedStockAndLateCancellationReturnsNormalStock() {
        allocate("one", 4); allocate("two", 3);
        reserve("waiting", 1, "one");
        jdbc.update("UPDATE commerce_activity SET ends_at=? WHERE activity_id='one'", LocalDateTime.now().minusSeconds(1));
        work(() -> service.releaseExpiredActivity("one", 1, 3));
        assertEquals(3, stock("activityStock")); assertEquals(1, stock("reservedStock")); assertEquals(6, stock("availableStock"));
        long entries = count("commerce_stock_ledger");
        work(() -> service.releaseExpiredActivity("one", 1, 3));
        assertEquals(entries, count("commerce_stock_ledger"));
        // A stale caller flag is overridden by the server clock and expiration ledger marker.
        cancel("waiting", "one", true);
        assertEquals(3, stock("activityStock")); assertEquals(0, stock("reservedStock")); assertEquals(7, stock("availableStock"));
        assertEquals(true, service.reconcile().get("healthy"));
    }

    @Test public void lateCancellationBeforeCampaignSweepDoesNotFreeAnotherCampaignAllocation() {
        allocate("one", 4); allocate("two", 3); reserve("waiting", 1, "one");
        jdbc.update("UPDATE commerce_activity SET ends_at=? WHERE activity_id='one'", LocalDateTime.now().minusSeconds(1));
        cancel("waiting", "one", false);
        work(() -> service.releaseExpiredActivity("one", 1, 4));
        assertEquals(3, stock("activityStock")); assertEquals(7, stock("availableStock"));
        assertEquals(true, service.reconcile().get("healthy"));
    }

    @Test public void dispatchAndWarehouseDeductionCommitTogetherAndRetriedDispatchDoesNotDeductAgain() {
        reserve("shipping", 3, null);
        jdbc.update("UPDATE commerce_order SET status=1 WHERE order_id='shipping'");
        work(() -> {
            service.dispatch("shipping"); physical(-3);
            jdbc.update("UPDATE commerce_order SET status=2 WHERE order_id='shipping'");
        });
        assertEquals(7, stock("bookStock")); assertEquals(0, stock("reservedStock")); assertEquals(7, stock("availableStock"));
        long entries = count("commerce_stock_ledger");
        work(() -> service.dispatch("shipping")); assertEquals(entries, count("commerce_stock_ledger"));
        rejected(409, () -> work(() -> service.release("shipping", "CUSTOMER_CANCEL", false)));
        assertEquals(true, service.reconcile().get("healthy"));
    }

    @Test public void failedPhysicalShipmentRollsBackLedgerAndReservationChanges() {
        reserve("rollback", 3, null);
        long entries = count("commerce_stock_ledger");
        try {
            work(() -> { service.dispatch("rollback"); physical(-3); throw new ServiceException("出库失败",409); });
            fail("Expected rollback");
        } catch (ServiceException expected) { assertEquals(Integer.valueOf(409), expected.getCode()); }
        assertEquals(entries, count("commerce_stock_ledger")); assertEquals(10, stock("bookStock")); assertEquals(3, stock("reservedStock"));
        assertEquals("RESERVED", jdbc.queryForObject("SELECT status FROM commerce_stock_hold WHERE order_id='rollback'", String.class));
        assertEquals(true, service.reconcile().get("healthy"));
    }

    @Test public void externalReceiptsAndSafeAdjustmentsHaveExplicitLedgerEntriesWithoutResettingHolds() {
        reserve("held", 3, null);
        physical(5);
        assertEquals(false, service.reconcile().get("healthy"));
        ensure(); assertEquals(15, stock("bookStock")); assertEquals(3, stock("reservedStock"));
        physical(-7); ensure(); assertEquals(8, stock("bookStock")); assertEquals(3, stock("reservedStock"));
        assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM commerce_stock_ledger WHERE event_type='EXTERNAL_ADJUST'", Long.class).longValue());
        long entries = count("commerce_stock_ledger"); ensure(); assertEquals(entries, count("commerce_stock_ledger"));
        assertEquals(true, service.reconcile().get("healthy"));
    }

    @Test public void externalInventoryConflictsAreReportedAndCannotSilentlyDestroyReservations() {
        reserve("held", 7, null); allocate("allocated", 2);
        physical(-2);
        long entries = count("commerce_stock_ledger");
        rejected(409, this::ensure);
        assertEquals(entries, count("commerce_stock_ledger")); assertEquals(10, stock("bookStock"));
        assertEquals(false, service.reconcile().get("healthy"));
        assertEquals(7, stock("reservedStock")); assertEquals(2, stock("activityStock"));
    }

    @Test public void physicalProductMismatchAndMutationsOutsideTransactionsAreRejected() {
        rejected(409, () -> service.ensureStock(1));
        jdbc.update("UPDATE product SET inventory_qty=11 WHERE product_id=1");
        rejected(409, this::ensure); assertEquals(0, count("commerce_stock"));
    }

    @Test public void concurrentReservationsCannotOversellAndLedgerBalancesFormAnUnbrokenChain() throws Exception {
        ensure();
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i=0;i<30;i++) {
                final String id = "concurrent-"+i;
                results.add(pool.submit(() -> { start.await(); try { reserve(id,1,null); return true; } catch (ServiceException ex) { assertEquals(Integer.valueOf(409),ex.getCode()); return false; } }));
            }
            start.countDown(); int accepted=0;
            for (Future<Boolean> result:results) if(result.get(20,TimeUnit.SECONDS)) accepted++;
            assertEquals(10,accepted); assertEquals(10,count("commerce_order")); assertEquals(10,stock("reservedStock")); assertEquals(0,stock("availableStock"));
            long hand=0,reserved=0,activity=0,version=0;
            for(Map<String,Object> row:jdbc.queryForList("SELECT * FROM commerce_stock_ledger WHERE product_id=1 ORDER BY ledger_id")) {
                assertEquals(hand,((Number)row.get("before_on_hand")).longValue());
                assertEquals(reserved,((Number)row.get("before_reserved")).longValue());
                assertEquals(activity,((Number)row.get("before_activity")).longValue());
                hand=((Number)row.get("after_on_hand")).longValue(); reserved=((Number)row.get("after_reserved")).longValue(); activity=((Number)row.get("after_activity")).longValue();
                assertEquals(++version,((Number)row.get("stock_version")).longValue());
            }
            assertEquals(stock("bookStock"),hand); assertEquals(stock("reservedStock"),reserved);
            assertEquals(true,service.reconcile().get("healthy"));
        } finally {pool.shutdownNow();}
    }
}

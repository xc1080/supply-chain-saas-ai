package com.ruoyi.system.service;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.core.tenant.CommerceShopContext;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Before;
import org.junit.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.util.StreamUtils;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Real SQL claim/ack and crash-window tests. Checkout itself has CommerceServiceTest coverage. */
public class CommerceQueueServiceTest {
    private JdbcTemplate jdbc;
    private Checkout checkout;
    private CommerceQueueService queue;
    private static final String OWNER = String.join("", Collections.nCopies(64, "a"));
    private static final String OTHER = String.join("", Collections.nCopies(64, "b"));

    @Before public void setup() throws Exception {
        CommerceShopContext.clear();
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:queue" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        jdbc = new JdbcTemplate(source);
        String sql = StreamUtils.copyToString(new ClassPathResource("db/commerce-queue.sql").getInputStream(), StandardCharsets.UTF_8)
                .replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "");
        new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))).execute(source);
        jdbc.execute("CREATE TABLE commerce_activity(activity_id VARCHAR(32) PRIMARY KEY,shop_id VARCHAR(32) DEFAULT 'default')");
        jdbc.execute("CREATE TABLE commerce_order(order_id VARCHAR(32) PRIMARY KEY, owner_id CHAR(64), request_key VARCHAR(80), activity_id VARCHAR(32),shop_id VARCHAR(32) DEFAULT 'default', UNIQUE(owner_id,request_key))");
        jdbc.update("INSERT INTO commerce_activity(activity_id) VALUES ('rush'),('other')");
        checkout = new Checkout(source);
        queue = new CommerceQueueService(source, checkout);
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
    private static Map<String,Object> request(String key) {return map("ownerId",OWNER,"requestKey",key,"shippingAddress",CommerceServiceTest.address());}
    private Map<String, Object> submit(String key) { return queue.submit("rush", request(key)); }
    private String id(Map<String, Object> result) { return String.valueOf(result.get("jobId")); }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private int pending() { return jdbc.queryForObject("SELECT pending_count FROM commerce_checkout_gate WHERE gate_id=1", Integer.class); }
    private void due() { jdbc.update("UPDATE commerce_checkout_job SET next_attempt_at=TIMESTAMPADD(SECOND,-2,CURRENT_TIMESTAMP)"); }
    private void rejected(int code, Runnable action) {
        try { action.run(); fail("Expected rejection"); } catch (ServiceException ex) { assertEquals(Integer.valueOf(code), ex.getCode()); }
    }

    @Test public void acceptanceIsAtomicIdempotentAndOwnerScoped() {
        String id = id(submit("first"));
        assertEquals("PENDING", queue.status(id, OWNER).get("state"));
        assertNull(queue.status(id, OWNER).get("orderId"));
        assertEquals(0, count("commerce_order"));
        assertEquals(id, id(submit("first")));
        assertEquals(1, count("commerce_checkout_job")); assertEquals(1, count("commerce_checkout_outbox")); assertEquals(1, pending());
        rejected(409, () -> queue.submit("other", request("first")));
        rejected(404, () -> queue.status(id, OTHER));
        rejected(400, () -> queue.submit("rush", map("ownerId", "forged", "requestKey", "invalid")));
        rejected(404, () -> queue.submit("absent", request("absent")));
        assertEquals(1, pending());
    }

    @Test public void unsupportedPromotionIsRejectedBeforeQueueCapacityOrCheckoutIsConsumed() {
        Map<String,Object> body=request("promotion");body.put("promotionId","server-promotion");
        rejected(400,()->queue.submit("rush",body));
        assertEquals(0,count("commerce_checkout_job"));assertEquals(0,count("commerce_checkout_outbox"));assertEquals(0,count("commerce_order"));assertEquals(0,pending());
        assertEquals(0,queue.processBatch(1));assertEquals(0,checkout.calls.get());
        body.remove("promotionId");assertEquals("PENDING",queue.submit("rush",body).get("state"));assertEquals(1,pending());
    }

    @Test public void concurrentSameRequestAcceptsOneJobAndOneOutboxEvent() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> tasks = new ArrayList<>();
            for (int i = 0; i < 16; i++) tasks.add(pool.submit(() -> { start.await(); return id(submit("duplicate")); }));
            start.countDown();
            Set<String> ids = new HashSet<>(); for (Future<String> task : tasks) ids.add(task.get(15, TimeUnit.SECONDS));
            assertEquals(1, ids.size()); assertEquals(1, count("commerce_checkout_outbox")); assertEquals(1, pending());
        } finally { pool.shutdownNow(); }
    }

    @Test public void shippingSnapshotIsRequiredImmutableAndSurvivesQueuedProcessing() {
        rejected(400,()->queue.submit("rush",map("ownerId",OWNER,"requestKey","missing-address")));
        assertEquals(0,count("commerce_checkout_job"));assertEquals(0,pending());
        Map<String,Object> request=request("snapshot");String id=id(queue.submit("rush",request));
        assertEquals(CommerceService.shippingSnapshot(CommerceServiceTest.address(),true),jdbc.queryForObject("SELECT shipping_address FROM commerce_checkout_job WHERE job_id=?",String.class,id));
        @SuppressWarnings("unchecked") Map<String,Object> changed=(Map<String,Object>)request.get("shippingAddress");
        changed.put("address","广东省深圳市更换路 20 号");
        rejected(409,()->queue.submit("rush",request));assertEquals(1,pending());
        assertEquals(1,queue.processBatch(1));assertEquals("SUCCEEDED",queue.status(id,OWNER).get("state"));
        assertEquals(1,checkout.calls.get());assertEquals(0,pending());
    }

    @Test public void concurrentWorkersClaimOnlyOnceAndAckReleasesCapacity() throws Exception {
        String id = id(submit("workers"));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) tasks.add(pool.submit(() -> { start.await(); return queue.processBatch(1); }));
            start.countDown();
            int claimed = 0; for (Future<Integer> task : tasks) claimed += task.get(15, TimeUnit.SECONDS);
            assertEquals(1, claimed); assertEquals(1, checkout.calls.get()); assertEquals(1, count("commerce_order"));
            assertEquals("SUCCEEDED", queue.status(id, OWNER).get("state")); assertEquals(0, pending());
            assertEquals("DONE", jdbc.queryForObject("SELECT state FROM commerce_checkout_outbox", String.class));
            assertEquals(0, queue.processBatch(50));
        } finally { pool.shutdownNow(); }
    }

    @Test public void expiredLeaseRecoversButLiveLeaseIsNotStolen() {
        String id = id(submit("recovery"));
        jdbc.update("UPDATE commerce_checkout_job SET state='PROCESSING',attempts=1,lease_token='dead-worker',lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP) WHERE job_id=?", id);
        assertEquals(0, queue.processBatch(1));
        jdbc.update("UPDATE commerce_checkout_job SET lease_until=TIMESTAMPADD(SECOND,-2,CURRENT_TIMESTAMP) WHERE job_id=?", id);
        assertEquals(1, queue.processBatch(1));
        assertEquals("SUCCEEDED", queue.status(id, OWNER).get("state")); assertEquals(2, queue.status(id, OWNER).get("attempts"));
        assertEquals(1, checkout.calls.get()); assertEquals(0, pending());
    }

    @Test public void committedOrderAfterWorkerCrashIsAcknowledgedWithoutSecondCheckout() {
        String id = id(submit("crashed"));
        jdbc.update("INSERT INTO commerce_order(order_id,owner_id,request_key,activity_id) VALUES ('SC-COMMITTED',?,?,'rush')", OWNER, "crashed");
        jdbc.update("UPDATE commerce_checkout_job SET state='PROCESSING',attempts=5,lease_token='lost-worker',lease_until=TIMESTAMPADD(SECOND,-2,CURRENT_TIMESTAMP) WHERE job_id=?", id);
        assertEquals(1, queue.processBatch(1));
        assertEquals("SUCCEEDED", queue.status(id, OWNER).get("state"));
        assertEquals("SC-COMMITTED", queue.status(id, OWNER).get("orderId"));
        assertEquals(0, checkout.calls.get()); assertEquals(1, count("commerce_order")); assertEquals(0, pending());
    }

    @Test public void staleWorkerCannotExecuteOrAcknowledgeAfterLeaseIsReclaimed() throws Exception {
        String id = id(submit("fenced"));
        java.lang.reflect.Method claim = CommerceQueueService.class.getDeclaredMethod("claim", String.class);
        claim.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, Object> old = (Map<String, Object>) claim.invoke(queue, id);
        jdbc.update("UPDATE commerce_checkout_job SET lease_until=TIMESTAMPADD(SECOND,-2,CURRENT_TIMESTAMP) WHERE job_id=?", id);
        @SuppressWarnings("unchecked") Map<String, Object> current = (Map<String, Object>) claim.invoke(queue, id);
        assertNotEquals(old.get("lease_token"), current.get("lease_token"));
        java.lang.reflect.Method process = CommerceQueueService.class.getDeclaredMethod("process", Map.class);
        process.setAccessible(true);
        process.invoke(queue, old);
        assertEquals(0, checkout.calls.get()); assertEquals("PROCESSING", queue.status(id, OWNER).get("state"));
        process.invoke(queue, current);
        assertEquals(1, checkout.calls.get()); assertEquals("SUCCEEDED", queue.status(id, OWNER).get("state"));
        java.lang.reflect.Method finish = CommerceQueueService.class.getDeclaredMethod("finish", String.class, String.class, String.class, Integer.class, String.class);
        finish.setAccessible(true);
        finish.invoke(queue, id, old.get("lease_token"), null, 409, "stale rejection");
        assertEquals("SUCCEEDED", queue.status(id, OWNER).get("state")); assertEquals(0, pending());
    }

    @Test public void businessRejectionsNeverRetryAndFinishOutbox() {
        checkout.rejection = new ServiceException("活动库存已抢完", 409);
        String id = id(submit("soldout"));
        queue.processBatch(1);
        Map<String, Object> result = queue.status(id, OWNER);
        assertEquals("REJECTED", result.get("state")); assertEquals(409, result.get("errorCode"));
        assertEquals(1, result.get("attempts")); assertNull(result.get("orderId"));
        assertEquals(0, queue.processBatch(1)); assertEquals(1, checkout.calls.get()); assertEquals(0, pending());
    }

    @Test public void transientFailureBacksOffThenSucceedsAndExhaustionIsTerminal() {
        checkout.failures.set(1);
        String id = id(submit("retry"));
        queue.processBatch(1);
        assertEquals("PENDING", queue.status(id, OWNER).get("state")); assertEquals(1, pending());
        assertEquals(0, queue.processBatch(1));
        due(); queue.processBatch(1);
        assertEquals("SUCCEEDED", queue.status(id, OWNER).get("state")); assertEquals(2, checkout.calls.get());
        checkout.failures.set(10);
        String exhausted = id(submit("exhausted"));
        for (int i = 0; i < 5; i++) { due(); queue.processBatch(1); }
        assertEquals("REJECTED", queue.status(exhausted, OWNER).get("state"));
        assertEquals(503, queue.status(exhausted, OWNER).get("errorCode"));
        assertEquals(5, queue.status(exhausted, OWNER).get("attempts")); assertEquals(0, pending());
        assertEquals(0, queue.processBatch(50));
    }

    @Test public void concurrentSubmitHonorsQueueCapacityAndDuplicatesRemainReadableWhenFull() throws Exception {
        java.lang.reflect.Field field = CommerceQueueService.class.getDeclaredField("capacity"); field.setAccessible(true); field.set(queue, 3);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 20; i++) { final int index = i; tasks.add(pool.submit(() -> { try { submit("capacity" + index); return true; } catch (ServiceException ex) { assertEquals(Integer.valueOf(429), ex.getCode()); return false; } })); }
            int accepted = 0; for (Future<Boolean> task : tasks) if (task.get(15, TimeUnit.SECONDS)) accepted++;
            assertEquals(3, accepted); assertEquals(3, pending()); assertEquals(3, count("commerce_checkout_outbox"));
            String key = jdbc.queryForObject("SELECT request_key FROM commerce_checkout_job LIMIT 1", String.class);
            assertEquals("PENDING", submit(key).get("state"));
            assertEquals(3, queue.processBatch(50)); assertEquals(0, pending());
            assertEquals("PENDING", submit("available-again").get("state")); assertEquals(1, pending());
        } finally { pool.shutdownNow(); }
    }

    @Test public void shopBoundaryHidesJobsAndWorkerRestoresContext() {
        String defaultJob = id(submit("default-job"));
        jdbc.update("INSERT INTO commerce_activity(activity_id,shop_id) VALUES ('shop-rush','studio-shop')");
        CommerceShopContext.set("studio-shop");
        try {
            rejected(404, () -> queue.status(defaultJob, OWNER));
            rejected(409, () -> queue.submit("shop-rush", request("default-job")));
            rejected(404, () -> submit("wrong-activity"));
            String studioJob = id(queue.submit("shop-rush", request("studio-job")));
            CommerceShopContext.set("caller-shop");
            assertEquals(2, queue.processBatch(50));
            assertEquals("caller-shop", CommerceShopContext.id());
            rejected(404, () -> queue.status(studioJob, OWNER));
            CommerceShopContext.set("studio-shop");
            assertEquals("SUCCEEDED", queue.status(studioJob, OWNER).get("state"));
            assertEquals(1L, queue.metrics().get("SUCCEEDED")); assertEquals(0L, queue.metrics().get("PENDING"));
            assertEquals("studio-shop", jdbc.queryForObject("SELECT shop_id FROM commerce_order WHERE request_key='studio-job'", String.class));
        } finally { CommerceShopContext.clear(); }
        assertEquals("SUCCEEDED", queue.status(defaultJob, OWNER).get("state"));
        assertEquals(1L, queue.metrics().get("SUCCEEDED"));
    }

    private static class Checkout extends CommerceService {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();
        volatile ServiceException rejection;
        private final JdbcTemplate jdbc;
        Checkout(DataSource source) { super(source); jdbc = new JdbcTemplate(source); }
        @Override public Map<String, Object> seckill(String activity, Map<String, Object> request) {
            calls.incrementAndGet();
            assertEquals(CommerceServiceTest.address(),request.get("shippingAddress"));
            if (rejection != null) throw rejection;
            if (failures.getAndDecrement() > 0) throw new TransientDataAccessResourceException("Temporary database failure: must never reach client");
            String order = "SC" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
            jdbc.update("INSERT INTO commerce_order(order_id,owner_id,request_key,activity_id,shop_id) VALUES (?,?,?,?,?)", order, request.get("ownerId"), request.get("requestKey"), activity, CommerceShopContext.id());
            return map("orderId", order);
        }
    }
}

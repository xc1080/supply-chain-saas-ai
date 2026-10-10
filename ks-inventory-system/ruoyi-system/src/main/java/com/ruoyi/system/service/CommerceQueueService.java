package com.ruoyi.system.service;

import com.ruoyi.common.exception.ServiceException;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.core.tenant.CommerceShopContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Durable, bounded checkout inbox. Acceptance is not a stock reservation.
 * The SQL outbox is polled locally; no broker or exactly-once transport is implied.
 * TenantContext must be bound by the authenticated request or worker before calling.
 */
@Service
@Profile({"local","commerce"})
public class CommerceQueueService {
    private static final Logger log = LoggerFactory.getLogger(CommerceQueueService.class);
    private static final int MAX_ATTEMPTS = 5;
    private static final int LEASE_SECONDS = 30;
    @Value("${commerce.checkout-queue-capacity:1000}") private int capacity = 1000;
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TransactionTemplate orderTx;
    private final CommerceService commerce;

    public CommerceQueueService(DataSource dataSource, CommerceService commerce) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
        this.commerce = commerce;
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        this.tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(3);
        this.orderTx = new TransactionTemplate(manager);
        orderTx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        orderTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        orderTx.setTimeout(20);
    }

    public void initializeSchema() {
        ResourceDatabasePopulator script = new ResourceDatabasePopulator(new ClassPathResource("db/commerce-queue.sql"));
        script.setSqlScriptEncoding("UTF-8");
        script.execute(dataSource);
        try (java.sql.Connection connection = dataSource.getConnection();
             java.sql.ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), null, "commerce_checkout_job", "shop_id")) {
            if (!columns.next()) jdbc.execute("ALTER TABLE commerce_checkout_job ADD COLUMN shop_id VARCHAR(32) NOT NULL DEFAULT 'default', ADD INDEX commerce_checkout_shop(shop_id,state)");
        } catch (java.sql.SQLException ex) { throw new IllegalStateException("Checkout queue migration failed", ex); }
        try(java.sql.Connection c=dataSource.getConnection();java.sql.ResultSet columns=c.getMetaData().getColumns(c.getCatalog(),null,"commerce_checkout_job","shipping_address")) {
            if(!columns.next())jdbc.execute("ALTER TABLE commerce_checkout_job ADD COLUMN shipping_address VARCHAR(2000) NULL");
        } catch(java.sql.SQLException ex) {throw new IllegalStateException("Checkout address migration failed",ex);}
    }

    public Map<String, Object> submit(String activityId, Map<String, Object> request) {
        require(activityId != null && activityId.matches("[A-Za-z0-9_-]{1,32}"), "活动不存在", 404);
        require(request != null, "请求内容错误", 400);
        // The queue schema persists activity/address only. Do not silently discard a pricing choice.
        require(request.get("promotionId")==null,"排队秒杀暂不支持叠加店铺优惠，请取消优惠后重试",400);
        String owner = owner(request.get("ownerId"));
        String key = requestKey(request.get("requestKey"));
        String shop = CommerceShopContext.id();
        String address=CommerceService.shippingSnapshot(request.get("shippingAddress"),false);
        String fingerprint = hash(address==null?activityId:activityId+":"+address);
        return tx.execute(transaction -> {
            // One short gate lock bounds all accepted outstanding work, including concurrent submitters.
            Integer pending = jdbc.queryForObject("SELECT pending_count FROM commerce_checkout_gate WHERE gate_id=1 FOR UPDATE", Integer.class);
            List<Map<String, Object>> old = jdbc.queryForList("SELECT * FROM commerce_checkout_job WHERE owner_id=? AND request_key=?", owner, key);
            if (!old.isEmpty()) {
                require(shop.equals(old.get(0).get("shop_id")), "该请求编号已用于其他店铺，请使用新的请求编号", 409);
                require(fingerprint.equals(old.get(0).get("fingerprint")), "同一请求编号不能改变活动或收货地址", 409);
                return shape(old.get(0));
            }
            require(address!=null,"请提供收货地址",400);
            require(pending != null && pending < Math.max(1, capacity), "当前排队人数较多，请稍后重试", 429);
            Long shopPending=jdbc.queryForObject("SELECT COUNT(*) FROM commerce_checkout_job WHERE shop_id=? AND state IN ('PENDING','PROCESSING')",Long.class,shop);
            require(shopPending < Math.min(250,Math.max(1,capacity)),"当前店铺排队人数较多，请稍后重试",429);
            // Invalid activities are rejected before consuming queue capacity. Stock is checked only by final checkout.
            require(!jdbc.queryForList("SELECT activity_id FROM commerce_activity WHERE activity_id=? AND shop_id=?", activityId, shop).isEmpty(), "活动不存在", 404);
            String id = random();
            jdbc.update("INSERT INTO commerce_checkout_job(job_id,shop_id,activity_id,owner_id,request_key,fingerprint,shipping_address,state,attempts,next_attempt_at,created_at,updated_at) VALUES (?,?,?,?,?,?,?,'PENDING',0,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))", id, shop, activityId, owner, key, fingerprint,address);
            jdbc.update("INSERT INTO commerce_checkout_outbox(event_id,job_id,event_type,state,created_at) VALUES (?,?,'CHECKOUT_REQUESTED','PENDING',CURRENT_TIMESTAMP(3))", random(), id);
            jdbc.update("UPDATE commerce_checkout_gate SET pending_count=pending_count+1 WHERE gate_id=1");
            return status(id, owner);
        });
    }

    public Map<String, Object> status(String jobId, String ownerId) {
        require(jobId != null && jobId.matches("[a-f0-9]{32}"), "排队记录不存在", 404);
        String owner = owner(ownerId);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM commerce_checkout_job WHERE job_id=? AND owner_id=? AND shop_id=?", jobId, owner, CommerceShopContext.id());
        require(!rows.isEmpty(), "排队记录不存在", 404);
        return shape(rows.get(0));
    }

    /** Shop-scoped operational counters; no customer identifiers or private order data. */
    public Map<String, Object> metrics() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String state : Arrays.asList("PENDING", "PROCESSING", "SUCCEEDED", "REJECTED")) result.put(state, 0L);
        for (Map<String, Object> row : jdbc.queryForList("SELECT state,COUNT(*) AS total FROM commerce_checkout_job WHERE shop_id=? GROUP BY state", CommerceShopContext.id())) {
            result.put(String.valueOf(row.get("state")), ((Number) row.get("total")).longValue());
        }
        Long oldest = jdbc.queryForObject("SELECT COALESCE(MAX(TIMESTAMPDIFF(SECOND,created_at,CURRENT_TIMESTAMP(3))),0) FROM commerce_checkout_job WHERE shop_id=? AND state IN ('PENDING','PROCESSING')", Long.class, CommerceShopContext.id());
        result.put("oldestWaitingSeconds", Math.max(0L, oldest == null ? 0L : oldest));
        result.put("tenantQueueCapacity", Math.max(1, capacity));
        result.put("shopQueueCapacity",Math.min(250,Math.max(1,capacity)));
        return result;
    }

    /** Small bounded batches; concurrent nodes compete with CAS instead of SKIP LOCKED (MySQL 5.7). */
    public int processBatch(int limit) {
        require(limit >= 1 && limit <= 50, "消费批次需为1至50", 400);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT j.job_id FROM commerce_checkout_job j INNER JOIN commerce_checkout_outbox o ON o.job_id=j.job_id AND o.state='PENDING' " +
                "WHERE (j.state='PENDING' AND j.next_attempt_at<=CURRENT_TIMESTAMP(3)) OR (j.state='PROCESSING' AND j.lease_until<=CURRENT_TIMESTAMP(3)) " +
                "ORDER BY j.created_at,j.job_id LIMIT ?", limit);
        int claimed = 0;
        for (Map<String, Object> row : rows) {
            Map<String, Object> job = claim(String.valueOf(row.get("job_id")));
            if (job == null) continue;
            claimed++;
            process(job);
        }
        return claimed;
    }

    private Map<String, Object> claim(String id) {
        String token = random();
        return tx.execute(transaction -> {
            int changed = jdbc.update("UPDATE commerce_checkout_job SET state='PROCESSING',attempts=attempts+1,lease_token=?,lease_until=?,updated_at=CURRENT_TIMESTAMP(3) " +
                    "WHERE job_id=? AND ((state='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP(3)) OR (state='PROCESSING' AND lease_until<=CURRENT_TIMESTAMP(3)))", token, LocalDateTime.now().plusSeconds(LEASE_SECONDS), id);
            return changed == 1 ? jdbc.queryForMap("SELECT * FROM commerce_checkout_job WHERE job_id=? AND lease_token=?", id, token) : null;
        });
    }

    private void process(Map<String, Object> job) {
        String previous = CommerceShopContext.id();
        CommerceShopContext.set(String.valueOf(job.get("shop_id")));
        try { processOwned(job); }
        finally {
            if ("default".equals(previous)) CommerceShopContext.clear();
            else CommerceShopContext.set(previous);
        }
    }

    private void processOwned(Map<String, Object> job) {
        String id = String.valueOf(job.get("job_id"));
        String token = String.valueOf(job.get("lease_token"));
        int attempts = ((Number) job.get("attempts")).intValue();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ownerId", job.get("owner_id"));
            body.put("requestKey", job.get("request_key"));
            if(job.get("shipping_address")!=null)body.put("shippingAddress",JSON.parseObject(String.valueOf(job.get("shipping_address"))));
            // This transaction contains only authoritative checkout, never waiting for HTTP clients or a broker.
            Map<String, Object> result = orderTx.execute(transaction -> {
                Map<String, Object> lease = jdbc.queryForMap("SELECT state,lease_token FROM commerce_checkout_job WHERE job_id=? FOR UPDATE", id);
                if (!"PROCESSING".equals(lease.get("state")) || !token.equals(lease.get("lease_token"))) return null;
                // The row lock fences a worker whose lease elapsed while final checkout was still committing.
                // The order transaction may also have committed before a worker died during inbox acknowledgement.
                String committed = committedOrder(job);
                if (committed != null) return Collections.singletonMap("orderId", committed);
                if (attempts > MAX_ATTEMPTS) return Collections.emptyMap();
                return commerce.seckill(String.valueOf(job.get("activity_id")), body);
            });
            if (result == null) return; // Another worker already owns this lease; it is responsible for acknowledgement.
            if (result.isEmpty()) { finish(id, token, null, 503, "系统繁忙，处理次数已达上限，请重新参与"); return; }
            require(result.get("orderId") != null, "下单结果缺失", 500);
            finish(id, token, String.valueOf(result.get("orderId")), null, null);
        } catch (ServiceException ex) {
            int code = ex.getCode() == null ? 500 : ex.getCode();
            if (code >= 400 && code < 500) finish(id, token, null, code, safe(ex.getMessage()));
            else retry(job, "下单服务暂不可用，请稍后查看结果");
        } catch (TransientDataAccessException | RecoverableDataAccessException | CannotGetJdbcConnectionException | TransactionException ex) {
            retry(job, "库存处理繁忙，正在重试");
        } catch (RuntimeException ex) {
            // Do not expose SQL, infrastructure addresses, credentials or internal exception text.
            log.warn("Checkout job {} failed: {}", id, ex.getClass().getSimpleName());
            String committed = committedOrder(job);
            finish(id, token, committed, committed == null ? 500 : null, committed == null ? "下单处理异常，请重新参与" : null);
        }
    }

    private String committedOrder(Map<String, Object> job) {
        List<Map<String, Object>> old = jdbc.queryForList("SELECT order_id FROM commerce_order WHERE owner_id=? AND request_key=? AND activity_id=? AND shop_id=?", job.get("owner_id"), job.get("request_key"), job.get("activity_id"), job.get("shop_id"));
        return old.isEmpty() ? null : String.valueOf(old.get(0).get("order_id"));
    }

    private void retry(Map<String, Object> job, String message) {
        String id = String.valueOf(job.get("job_id"));
        String token = String.valueOf(job.get("lease_token"));
        int attempts = ((Number) job.get("attempts")).intValue();
        // A committed order always wins over retry exhaustion, including commit/ack connection failures.
        String committed = committedOrder(job);
        if (committed != null) { finish(id, token, committed, null, null); return; }
        if (attempts >= MAX_ATTEMPTS) { finish(id, token, null, 503, "系统繁忙，处理次数已达上限，请重新参与"); return; }
        int seconds = Math.min(30, 1 << (attempts - 1));
        tx.execute(transaction -> jdbc.update("UPDATE commerce_checkout_job SET state='PENDING',next_attempt_at=?,lease_until=NULL,lease_token=NULL,error_code=503,error_message=?,updated_at=CURRENT_TIMESTAMP(3) WHERE job_id=? AND state='PROCESSING' AND lease_token=?", LocalDateTime.now().plusSeconds(seconds), message, id, token));
    }

    private void finish(String id, String token, String orderId, Integer code, String message) {
        tx.execute(transaction -> {
            int changed = jdbc.update("UPDATE commerce_checkout_job SET state=?,result_order_id=?,error_code=?,error_message=?,lease_until=NULL,lease_token=NULL,updated_at=CURRENT_TIMESTAMP(3) WHERE job_id=? AND state='PROCESSING' AND lease_token=?", orderId == null ? "REJECTED" : "SUCCEEDED", orderId, code, message, id, token);
            if (changed == 1) {
                jdbc.update("UPDATE commerce_checkout_outbox SET state='DONE',completed_at=CURRENT_TIMESTAMP(3) WHERE job_id=? AND state='PENDING'", id);
                jdbc.update("UPDATE commerce_checkout_gate SET pending_count=pending_count-1 WHERE gate_id=1 AND pending_count>0");
            }
            return changed;
        });
    }

    private static Map<String, Object> shape(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobId", row.get("job_id")); result.put("state", row.get("state")); result.put("shopId", row.get("shop_id"));
        result.put("orderId", row.get("result_order_id")); result.put("errorCode", row.get("error_code"));
        result.put("errorMessage", row.get("error_message")); result.put("attempts", row.get("attempts"));
        result.put("queuedAt", row.get("created_at")); result.put("updatedAt", row.get("updated_at"));
        return result;
    }

    private static String owner(Object value) { require(value instanceof String && ((String) value).matches("[a-f0-9]{64}"), "访客标识错误", 400); return (String) value; }
    private static String requestKey(Object value) { require(value instanceof String && ((String) value).matches("[A-Za-z0-9_-]{1,80}"), "requestKey格式错误", 400); return (String) value; }
    private static String random() { return UUID.randomUUID().toString().replace("-", ""); }
    private static String safe(String value) { return value == null ? "无法参与本活动" : value.substring(0, Math.min(200, value.length())); }
    private static void require(boolean condition, String message, int code) { if (!condition) throw new ServiceException(message, code); }
    private static String hash(String activityId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(("seckill:" + activityId).getBytes(StandardCharsets.UTF_8));
            StringBuilder text = new StringBuilder(); for (byte item : digest) text.append(String.format("%02x", item & 0xff)); return text.toString();
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}

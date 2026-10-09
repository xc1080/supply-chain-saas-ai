package com.ruoyi.system.service;

import com.ruoyi.common.exception.ServiceException;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.*;

/**
 * Transactional sellable-stock subledger. Original inventory documents remain the physical source.
 * Callers lock an order/activity first, then product IDs in ascending order; this service never locks
 * an activity. Bootstrap must precede insertion of a new order/activity. Dispatch must precede the
 * original warehouse/product deduction in the SAME transaction. No method resets existing balances.
 */
@Service
@Profile("local")
public class CommerceInventoryService {
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public CommerceInventoryService(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** Invoke after commerce-demo.sql for every tenant, before enabling commerce requests. */
    public void initializeSchema() {
        ResourceDatabasePopulator script = new ResourceDatabasePopulator(new ClassPathResource("db/commerce-inventory.sql"));
        script.setSqlScriptEncoding("UTF-8");
        script.execute(dataSource);
        addColumn("commerce_stock","unavailable","BIGINT NOT NULL DEFAULT 0");
        for(String column:Arrays.asList("delta_unavailable","before_unavailable","after_unavailable"))addColumn("commerce_stock_ledger",column,"BIGINT NOT NULL DEFAULT 0");
    }
    private void addColumn(String table,String column,String definition) {
        try(Connection connection=dataSource.getConnection();ResultSet columns=connection.getMetaData().getColumns(connection.getCatalog(),null,table,column)) {
            if(!columns.next())jdbc.execute("ALTER TABLE "+table+" ADD COLUMN "+column+" "+definition);
        }catch(java.sql.SQLException exception){throw new IllegalStateException("Partial inventory migration failed",exception);}
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> ensureStock(long productId) {
        return ensureStock(productId, "原进销存账面变动");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> ensureStock(long productId, String adjustmentReason) {
        transactionRequired();
        require(adjustmentReason != null && adjustmentReason.length() <= 80, "库存变动原因无效", 400);
        lockProducts(Collections.singleton(productId));
        Map<String, Object> product = jdbc.queryForMap("SELECT inventory_qty FROM product WHERE product_id=? FOR UPDATE", productId);
        long physical = physicalStock(productId, true);
        require(physical >= 0 && physical == number(product.get("inventory_qty")), "仓库库存与货品汇总不一致，请先核对原业务库存", 409);
        List<Map<String, Object>> stocks = jdbc.queryForList("SELECT * FROM commerce_stock WHERE product_id=? FOR UPDATE", productId);
        if (stocks.isEmpty()) {
            bootstrap(productId, physical);
        } else if (physical != number(stocks.get(0).get("on_hand"))) {
            Map<String, Object> stock = stocks.get(0);
            require(physical >= number(stock.get("reserved")) + number(stock.get("activity_reserved")) + number(stock.get("unavailable")), "原业务库存变动侵占商城预留或不可售库存，请先处理库存冲突", 409);
            // Every external receipt/adjustment becomes an explicit immutable ledger entry.
            change(stock, "EXTERNAL:" + productId + ":" + number(stock.get("version")), "EXTERNAL_ADJUST", null, null,
                    adjustmentReason, physical - number(stock.get("on_hand")), 0, 0, Math.abs(physical - number(stock.get("on_hand"))));
        }
        return balances(productId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void reserve(String orderId, Map<Long, Long> quantities, String activityId) {
        transactionRequired();
        reference(orderId, "订单");
        if (activityId != null) reference(activityId, "活动");
        SortedMap<Long, Long> items = quantities(quantities);
        lockOrder(orderId);
        lockProducts(items.keySet());
        for (Long product : items.keySet()) ensureStock(product);
        List<Map<String, Object>> existing = holds(orderId);
        if (!existing.isEmpty()) {
            require(existing.size() == items.size(), "同一订单不能改变预留明细", 409);
            for (Map<String, Object> hold : existing) {
                long product = number(hold.get("product_id"));
                require(items.containsKey(product) && items.get(product) == number(hold.get("quantity"))
                        && Objects.equals(activityId, hold.get("activity_id")), "同一订单不能改变预留明细", 409);
                require("RESERVED".equals(hold.get("status")), "已结束的订单预留不能重新启用", 409);
            }
            return;
        }
        for (Map.Entry<Long, Long> item : items.entrySet()) {
            long product = item.getKey(), quantity = item.getValue();
            Map<String, Object> stock = lockedStock(product);
            if (activityId == null) {
                require(available(stock) >= quantity, "可售库存不足", 409);
            } else {
                require(activityIsActive(activityId) && activityBalance(activityId, product) >= quantity, "活动预留配额不足或已结束", 409);
            }
            change(stock, "RESERVE:" + orderId + ":" + product, "RESERVE", orderId, activityId, "订单预留", 0, quantity,
                    activityId == null ? 0 : -quantity, quantity);
            jdbc.update("INSERT INTO commerce_stock_hold(order_id,product_id,quantity,activity_id,status,created_at,updated_at) VALUES (?,?,?,?,'RESERVED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", orderId, product, quantity, activityId);
            prepareLine(orderId,product);
        }
    }

    /** Active campaign cancellations return to its allocation; ended campaigns return to normal sale. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void release(String orderId, String reason, boolean activityStillActive) {
        lockOrder(orderId);SortedMap<Long,Long> remaining=new TreeMap<>();
        for(Map<String,Object> hold:holds(orderId)) {
            long product=number(hold.get("product_id"));Map<String,Object> line=prepareLine(orderId,product);
            require(number(line.get("shipped"))==0,"已出库的订单不能直接释放全部预留",409);
            long quantity=number(hold.get("quantity"))-number(line.get("released"));if(quantity>0)remaining.put(product,quantity);
        }
        if(!remaining.isEmpty())release(orderId,remaining,"RELEASE:"+orderId,reason,activityStillActive);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void release(String orderId, String reason) { release(orderId, reason, true); }

    /** Does not write original warehouse rows: caller must deduct those before committing. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void dispatch(String orderId) {
        lockOrder(orderId);SortedMap<Long,Long> remaining=new TreeMap<>();
        for(Map<String,Object> hold:holds(orderId)) {
            long product=number(hold.get("product_id"));Map<String,Object> line=prepareLine(orderId,product);
            require(number(line.get("released"))==0,"已释放的订单不能出库",409);
            long quantity=number(hold.get("quantity"))-number(line.get("shipped"));if(quantity>0)remaining.put(product,quantity);
        }
        if(!remaining.isEmpty())dispatch(orderId,remaining,"DISPATCH:"+orderId);
    }

    /** Caller restores physical quantities AFTER this call in the SAME transaction. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void acceptReturn(String orderId,String afterSalesId) {
        lockOrder(orderId);SortedMap<Long,Long> remaining=new TreeMap<>();
        for(Map<String,Object> hold:holds(orderId)) {
            long product=number(hold.get("product_id"));Map<String,Object> line=prepareLine(orderId,product);
            long quantity=number(line.get("shipped"))-number(line.get("returned"));if(quantity>0)remaining.put(product,quantity);
        }
        if(!remaining.isEmpty())acceptReturn(orderId,afterSalesId,remaining);
    }

    public Map<String,Object> prepareLine(String orderId,long product) {
        transactionRequired();
        List<Map<String,Object>> existing=jdbc.queryForList("SELECT * FROM commerce_fulfillment_line WHERE order_id=? AND product_id=?",orderId,product);
        if(!existing.isEmpty())return existing.get(0);
        List<Map<String,Object>> old=jdbc.queryForList("SELECT quantity,status FROM commerce_stock_hold WHERE order_id=? AND product_id=?",orderId,product);
        long shipped=0,returned=0,released=0;
        if(!old.isEmpty()) {
            String state=String.valueOf(old.get(0).get("status"));long quantity=number(old.get(0).get("quantity"));
            if("DISPATCHED".equals(state)||"RETURNED".equals(state))shipped=quantity;
            if("RETURNED".equals(state))returned=quantity;if("RELEASED".equals(state))released=quantity;
        }
        jdbc.update("INSERT INTO commerce_fulfillment_line(order_id,product_id,shipped,returned,released,refunded) VALUES (?,?,?,?,?,0)",orderId,product,shipped,returned,released);
        return jdbc.queryForMap("SELECT * FROM commerce_fulfillment_line WHERE order_id=? AND product_id=?",orderId,product);
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void dispatch(String orderId,Map<Long,Long> input,String eventPrefix) { move(orderId,input,eventPrefix,"DISPATCH","商城分批出库",false); }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void release(String orderId,Map<Long,Long> input,String eventPrefix,String reason,boolean restoreActivity) {move(orderId,input,eventPrefix,"RELEASE",reason,restoreActivity);}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void acceptReturn(String orderId,String afterSalesId,Map<Long,Long> input) {move(orderId,input,"RETURN:"+afterSalesId,"RETURN_ACCEPT","售后实物验收",false);}

    private void move(String orderId,Map<Long,Long> input,String eventPrefix,String type,String reason,boolean restoreActivity) {
        transactionRequired();reference(orderId,"订单");require(eventPrefix!=null && eventPrefix.length()<=120,"库存事件编号无效",400);
        require(reason!=null && !reason.trim().isEmpty() && reason.length()<=80,"库存原因无效",400);
        lockOrder(orderId);SortedMap<Long,Long> items=quantities(input);lockProducts(items.keySet());
        for(Long product:items.keySet())ensureStock(product);
        for(Map.Entry<Long,Long> item:items.entrySet()) {
            long product=item.getKey(),quantity=item.getValue();String event=eventPrefix+":"+product;
            List<Map<String,Object>> prior=jdbc.queryForList("SELECT event_quantity,event_type FROM commerce_stock_ledger WHERE event_key=?",event);
            if(!prior.isEmpty()){require(quantity==number(prior.get(0).get("event_quantity"))&&type.equals(prior.get(0).get("event_type")),"同一库存事件不能更改数量",409);continue;}
            List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM commerce_stock_hold WHERE order_id=? AND product_id=?",orderId,product);
            require(rows.size()==1,"原订单预留不存在",409);Map<String,Object> hold=rows.get(0),line=prepareLine(orderId,product);
            long shipped=number(line.get("shipped")),returned=number(line.get("returned")),released=number(line.get("released"));
            long remaining=number(hold.get("quantity"))-shipped-released;
            String activity=(String)hold.get("activity_id");
            if("RETURN_ACCEPT".equals(type)) {
                require(shipped-returned>=quantity,"验收数量超过尚未退回的已发商品",409);
                change(lockedStock(product),event,type,orderId,activity,reason,quantity,0,0,quantity);returned+=quantity;
            }else {
                require(remaining>=quantity,"操作数量超过剩余未发货预留",409);
                boolean restore="RELEASE".equals(type)&&activity!=null&&restoreActivity&&activityIsActive(activity)&&!eventExists(expiryKey(activity,product));
                change(lockedStock(product),event,type,orderId,activity,reason,"DISPATCH".equals(type)?-quantity:0,-quantity,restore?quantity:0,quantity);
                if("DISPATCH".equals(type))shipped+=quantity;else released+=quantity;
            }
            jdbc.update("UPDATE commerce_fulfillment_line SET shipped=?,returned=?,released=? WHERE order_id=? AND product_id=?",shipped,returned,released,orderId,product);
            String state=number(hold.get("quantity"))-shipped-released>0?"RESERVED":shipped==0?"RELEASED":returned==shipped?"RETURNED":"DISPATCHED";
            jdbc.update("UPDATE commerce_stock_hold SET status=?,updated_at=CURRENT_TIMESTAMP WHERE order_id=? AND product_id=?",state,orderId,product);
        }
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void adjustUnavailable(long productId,String eventKey,long delta,String reason) {
        transactionRequired();require(eventKey!=null&&eventKey.length()<=160&&delta!=0,"不可售库存事件无效",400);
        ensureStock(productId);List<Map<String,Object>> old=jdbc.queryForList("SELECT product_id,delta_unavailable FROM commerce_stock_ledger WHERE event_key=?",eventKey);
        if(!old.isEmpty()){require(number(old.get(0).get("product_id"))==productId&&number(old.get(0).get("delta_unavailable"))==delta,"同一不可售事件不能改变数量",409);return;}
        change(lockedStock(productId),eventKey,"CONDITION_CHANGE",null,null,reason,0,0,0,Math.abs(delta),delta);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void allocateActivity(String activityId, long productId, long capacity) {
        transactionRequired();
        reference(activityId, "活动");
        require(capacity > 0, "活动配额必须大于零", 400);
        ensureStock(productId);
        String event = allocationKey(activityId, productId);
        List<Map<String, Object>> old = jdbc.queryForList("SELECT event_quantity FROM commerce_stock_ledger WHERE event_key=?", event);
        if (!old.isEmpty()) {
            require(capacity == number(old.get(0).get("event_quantity")), "同一活动不能改变已分配配额", 409);
            return;
        }
        Map<String, Object> stock = lockedStock(productId);
        require(available(stock) >= capacity, "可分配库存不足", 409);
        change(stock, event, "ACTIVITY_ALLOCATE", null, activityId, "活动库存分配", 0, 0, capacity, capacity);
    }

    /** remaining can include late cancelled units, so only the still allocated ledger balance is freed. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void releaseExpiredActivity(String activityId, long productId, long remaining) {
        transactionRequired();
        reference(activityId, "活动");
        require(remaining >= 0, "活动剩余配额无效", 400);
        ensureStock(productId);
        String event = expiryKey(activityId, productId);
        if (eventExists(event)) return;
        require(!activityIsActive(activityId), "活动尚未结束，不能释放活动库存", 409);
        long allocated = activityBalance(activityId, productId);
        require(allocated >= 0 && allocated <= remaining, "活动剩余配额与库存流水不一致", 409);
        change(lockedStock(productId), event, "ACTIVITY_EXPIRE", null, activityId, "活动结束释放未占用配额", 0, 0, -allocated, allocated);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> balances(long productId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM commerce_stock WHERE product_id=?", productId);
        require(!rows.isEmpty(), "商城库存尚未初始化", 409);
        Map<String, Object> stock = rows.get(0);
        return map("productId", productId, "bookStock", number(stock.get("on_hand")), "onHand", number(stock.get("on_hand")),
                "reservedStock", number(stock.get("reserved")), "activityStock", number(stock.get("activity_reserved")),
                "unavailableStock",number(stock.get("unavailable")),"availableStock", available(stock), "version", number(stock.get("version")));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> ledger(long productId, int limit) {
        require(limit >= 1 && limit <= 200, "流水条数需为1至200", 400);
        return jdbc.queryForList("SELECT * FROM commerce_stock_ledger WHERE product_id=? ORDER BY ledger_id DESC LIMIT ?", productId, limit);
    }

    /** Read-only evidence: never repairs/reset balances or erases ledger records. */
    @Transactional(readOnly = true)
    public Map<String, Object> reconcile() {
        List<Map<String, Object>> issues = new ArrayList<>();
        List<Map<String, Object>> stocks = jdbc.queryForList("SELECT * FROM commerce_stock ORDER BY product_id");
        for (Map<String, Object> stock : stocks) {
            long product = number(stock.get("product_id"));
            long physical = physicalStock(product, false);
            List<Long> productTotals = jdbc.queryForList("SELECT inventory_qty FROM product WHERE product_id=?", Long.class, product);
            if (productTotals.isEmpty()) { issues.add(map("productId", product, "type", "PRODUCT_MISSING")); continue; }
            difference(issues, product, "WAREHOUSE_PRODUCT_MISMATCH", physical, productTotals.get(0));
            difference(issues, product, "PHYSICAL_SNAPSHOT_MISMATCH", physical, number(stock.get("on_hand")));
            long held = jdbc.queryForObject("SELECT COALESCE(SUM(h.quantity-COALESCE(f.shipped,0)-COALESCE(f.released,0)),0) FROM commerce_stock_hold h LEFT JOIN commerce_fulfillment_line f ON f.order_id=h.order_id AND f.product_id=h.product_id WHERE h.product_id=? AND h.status='RESERVED'", Long.class, product);
            difference(issues, product, "HOLD_SNAPSHOT_MISMATCH", held, number(stock.get("reserved")));
            long ordered = jdbc.queryForObject("SELECT COALESCE(SUM(i.quantity-COALESCE(f.shipped,0)-COALESCE(f.released,0)),0) FROM commerce_order_item i JOIN commerce_order o ON o.order_id=i.order_id LEFT JOIN commerce_fulfillment_line f ON f.order_id=i.order_id AND f.product_id=i.product_id WHERE i.product_id=? AND o.status IN (0,1) AND COALESCE(o.after_sales_status,'')<>'REFUNDED'", Long.class, product);
            difference(issues, product, "ORDER_HOLD_MISMATCH", ordered, held);
            long allocated = jdbc.queryForObject("SELECT COALESCE(SUM(remaining),0) FROM commerce_activity WHERE product_id=? AND ends_at>CURRENT_TIMESTAMP", Long.class, product);
            difference(issues, product, "ACTIVITY_SNAPSHOT_MISMATCH", allocated, number(stock.get("activity_reserved")));
            long journalOnHand = sumLedger(product, "delta_on_hand"), journalReserved = sumLedger(product, "delta_reserved"), journalActivity = sumLedger(product, "delta_activity");
            difference(issues, product, "LEDGER_ON_HAND_MISMATCH", journalOnHand, number(stock.get("on_hand")));
            difference(issues, product, "LEDGER_RESERVED_MISMATCH", journalReserved, number(stock.get("reserved")));
            difference(issues, product, "LEDGER_ACTIVITY_MISMATCH", journalActivity, number(stock.get("activity_reserved")));
            difference(issues,product,"LEDGER_UNAVAILABLE_MISMATCH",sumLedger(product,"delta_unavailable"),number(stock.get("unavailable")));
            long warehouseUnavailable=0;
            for(Map<String,Object> condition:jdbc.queryForList("SELECT warehouse_id,quality_hold,damaged FROM commerce_warehouse_condition WHERE product_id=? ORDER BY warehouse_id",product)) {
                long warehouse=number(condition.get("warehouse_id")),quality=number(condition.get("quality_hold")),damaged=number(condition.get("damaged"));
                long book=jdbc.queryForObject("SELECT COALESCE(SUM(plan_quantity),0) FROM inventory_product WHERE product_id=? AND warehouse_id=?",Long.class,product,warehouse);
                if(quality<0||damaged<0||quality+damaged>book)issues.add(map("productId",product,"warehouseId",warehouse,"type","WAREHOUSE_CONDITION_INVALID","qualityHold",quality,"damaged",damaged,"bookStock",book));
                warehouseUnavailable=Math.addExact(warehouseUnavailable,Math.addExact(quality,damaged));
            }
            difference(issues,product,"WAREHOUSE_UNAVAILABLE_MISMATCH",warehouseUnavailable,number(stock.get("unavailable")));
            if (available(stock) < 0 || number(stock.get("reserved")) < 0 || number(stock.get("activity_reserved")) < 0)
                issues.add(map("productId", product, "type", "NEGATIVE_BALANCE"));
        }
        return map("healthy", issues.isEmpty(), "checkedProducts", stocks.size(), "issues", issues);
    }

    private void bootstrap(long product, long physical) {
        List<Map<String, Object>> orders = jdbc.queryForList("SELECT i.order_id,i.quantity,o.activity_id FROM commerce_order_item i JOIN commerce_order o ON o.order_id=i.order_id WHERE i.product_id=? AND o.status IN (0,1) AND COALESCE(o.after_sales_status,'')<>'REFUNDED' ORDER BY i.order_id", product);
        List<Map<String, Object>> activities = jdbc.queryForList("SELECT activity_id,remaining,capacity FROM commerce_activity WHERE product_id=? AND ends_at>CURRENT_TIMESTAMP ORDER BY activity_id", product);
        long reserved = 0, allocated = 0;
        for (Map<String, Object> order : orders) reserved = Math.addExact(reserved, number(order.get("quantity")));
        for (Map<String, Object> activity : activities) allocated = Math.addExact(allocated, number(activity.get("remaining")));
        require(reserved >= 0 && allocated >= 0 && physical >= reserved + allocated, "历史商城预留超过原业务库存，请先处理库存冲突", 409);
        jdbc.update("INSERT INTO commerce_stock(product_id,on_hand,reserved,activity_reserved,version,updated_at) VALUES (?,0,0,0,0,CURRENT_TIMESTAMP)", product);
        change(lockedStock(product), "BOOTSTRAP:" + product, "BOOTSTRAP", null, null, "从原业务账面及未发货订单初始化", physical, reserved, 0, physical);
        for (Map<String, Object> order : orders) {
            jdbc.update("INSERT INTO commerce_stock_hold(order_id,product_id,quantity,activity_id,status,created_at,updated_at) VALUES (?,?,?,?,'RESERVED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", order.get("order_id"), product, order.get("quantity"), order.get("activity_id"));
        }
        for (Map<String, Object> activity : activities) {
            String id = String.valueOf(activity.get("activity_id"));
            change(lockedStock(product), allocationKey(id, product), "ACTIVITY_ALLOCATE", null, id, "历史活动剩余配额初始化", 0, 0,
                    number(activity.get("remaining")), number(activity.get("capacity")));
        }
    }

    private void change(Map<String, Object> stock, String eventKey, String type, String orderId, String activityId,
                        String reason, long onHandDelta, long reservedDelta, long activityDelta, long eventQuantity) {
        change(stock,eventKey,type,orderId,activityId,reason,onHandDelta,reservedDelta,activityDelta,eventQuantity,0);
    }
    private void change(Map<String, Object> stock, String eventKey, String type, String orderId, String activityId,
                        String reason, long onHandDelta, long reservedDelta, long activityDelta, long eventQuantity,long unavailableDelta) {
        long product = number(stock.get("product_id")), version = number(stock.get("version"));
        long beforeHand = number(stock.get("on_hand")), beforeReserved = number(stock.get("reserved")), beforeActivity = number(stock.get("activity_reserved"));
        long afterHand = Math.addExact(beforeHand, onHandDelta), afterReserved = Math.addExact(beforeReserved, reservedDelta), afterActivity = Math.addExact(beforeActivity, activityDelta);
        long beforeUnavailable=number(stock.get("unavailable")),afterUnavailable=Math.addExact(beforeUnavailable,unavailableDelta);
        require(afterHand >= 0 && afterReserved >= 0 && afterActivity >= 0 && afterUnavailable>=0 && afterHand >= afterReserved + afterActivity + afterUnavailable, "库存余额不足或预留/不可售冲突", 409);
        int updated = jdbc.update("UPDATE commerce_stock SET on_hand=?,reserved=?,activity_reserved=?,unavailable=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE product_id=? AND version=? AND on_hand=? AND reserved=? AND activity_reserved=? AND unavailable=?", afterHand, afterReserved, afterActivity,afterUnavailable, product, version, beforeHand, beforeReserved, beforeActivity,beforeUnavailable);
        require(updated == 1, "库存余额已变化，请重新提交", 409);
        jdbc.update("INSERT INTO commerce_stock_ledger(event_key,product_id,event_type,order_id,activity_id,reason,event_quantity,delta_on_hand,delta_reserved,delta_activity,before_on_hand,after_on_hand,before_reserved,after_reserved,before_activity,after_activity,stock_version,delta_unavailable,before_unavailable,after_unavailable,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)", eventKey, product, type, orderId, activityId, reason, eventQuantity, onHandDelta, reservedDelta, activityDelta, beforeHand, afterHand, beforeReserved, afterReserved, beforeActivity, afterActivity, version + 1,unavailableDelta,beforeUnavailable,afterUnavailable);
    }

    private Map<String, Object> lockedStock(long product) {
        return jdbc.queryForMap("SELECT * FROM commerce_stock WHERE product_id=? FOR UPDATE", product);
    }

    private void lockProducts(Collection<Long> products) {
        for (Long product : new TreeSet<>(products)) {
            require(product != null && product > 0, "货品编号无效", 400);
            require(!jdbc.queryForList("SELECT product_id FROM product WHERE product_id=? FOR UPDATE", product).isEmpty(), "货品不存在", 404);
        }
    }

    private void lockOrder(String orderId) {
        require(!jdbc.queryForList("SELECT order_id FROM commerce_order WHERE order_id=? FOR UPDATE", orderId).isEmpty(), "订单不存在", 404);
    }

    private List<Map<String, Object>> holds(String orderId) {
        return jdbc.queryForList("SELECT * FROM commerce_stock_hold WHERE order_id=? ORDER BY product_id", orderId);
    }

    private SortedSet<Long> productIds(List<Map<String, Object>> holds) {
        SortedSet<Long> products = new TreeSet<>();
        for (Map<String, Object> hold : holds) products.add(number(hold.get("product_id")));
        return products;
    }

    private long physicalStock(long product, boolean lock) {
        long quantity = 0;
        for (Long value : jdbc.queryForList("SELECT plan_quantity FROM inventory_product WHERE product_id=? ORDER BY inventory_id" + (lock ? " FOR UPDATE" : ""), Long.class, product))
            quantity = Math.addExact(quantity, value == null ? 0 : value);
        return quantity;
    }

    private boolean activityIsActive(String activity) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM commerce_activity WHERE activity_id=? AND ends_at>CURRENT_TIMESTAMP", Long.class, activity) > 0;
    }

    private long activityBalance(String activity, long product) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(delta_activity),0) FROM commerce_stock_ledger WHERE activity_id=? AND product_id=?", Long.class, activity, product);
    }

    private boolean eventExists(String event) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM commerce_stock_ledger WHERE event_key=?", Long.class, event) > 0;
    }

    private long sumLedger(long product, String column) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(" + column + "),0) FROM commerce_stock_ledger WHERE product_id=?", Long.class, product);
    }

    private static SortedMap<Long, Long> quantities(Map<Long, Long> input) {
        require(input != null && !input.isEmpty() && input.size() <= 30, "预留明细需为1至30个货品", 400);
        SortedMap<Long, Long> items = new TreeMap<>();
        for (Map.Entry<Long, Long> item : input.entrySet()) {
            require(item.getKey() != null && item.getKey() > 0 && item.getValue() != null && item.getValue() > 0, "预留数量必须是正整数", 400);
            items.put(item.getKey(), item.getValue());
        }
        return items;
    }

    private static void difference(List<Map<String, Object>> issues, long product, String type, long expected, long actual) {
        if (expected != actual) issues.add(map("productId", product, "type", type, "expected", expected, "actual", actual));
    }

    private static long available(Map<String, Object> stock) { return number(stock.get("on_hand")) - number(stock.get("reserved")) - number(stock.get("activity_reserved")) - number(stock.get("unavailable")); }
    private static String allocationKey(String activity, long product) { return "ACTIVITY_ALLOCATE:" + activity + ":" + product; }
    private static String expiryKey(String activity, long product) { return "ACTIVITY_EXPIRE:" + activity + ":" + product; }
    private static long number(Object value) { return value == null ? 0 : ((Number) value).longValue(); }
    private static void reference(String value, String type) { require(value != null && value.matches("[A-Za-z0-9_-]{1,32}"), type + "编号无效", 400); }
    private static void transactionRequired() { require(TransactionSynchronizationManager.isActualTransactionActive(), "库存变动必须在业务事务内执行", 409); }
    private static void require(boolean condition, String message, int code) { if (!condition) throw new ServiceException(message, code); }
    private static Map<String, Object> map(Object... pairs) { Map<String, Object> result = new LinkedHashMap<>(); for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]); return result; }
}

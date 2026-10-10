package com.ruoyi.system.service;

import com.ruoyi.common.core.tenant.CommerceShopContext;
import com.ruoyi.common.exception.ServiceException;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Warehouse commitments are subordinate to the existing order/product transaction locks. */
@Service
@Profile({"local","commerce"})
public class CommerceWarehouseAllocationService {
    /** One invariant, one message: no stock movement may leave a warehouse short of the orders committed to it. */
    public static final String ENCROACH_MESSAGE="指定仓库出库或冲销将侵占商城订单仓占用";
    private final DataSource source;
    private final JdbcTemplate jdbc;
    public CommerceWarehouseAllocationService(DataSource source) {this.source=source;this.jdbc=new JdbcTemplate(source);}

    public void initializeSchema() {
        ResourceDatabasePopulator script=new ResourceDatabasePopulator(new ClassPathResource("db/commerce-warehouse-allocation.sql"));
        script.setSqlScriptEncoding("UTF-8");script.execute(source);
    }

    /** Migration/bootstrap calls this in a transaction before exposing the new read model. */
    @Transactional
    public void allocateExisting() {
        transaction();
        for(Long product:jdbc.queryForList("SELECT DISTINCT product_id FROM commerce_stock_hold WHERE status='RESERVED' ORDER BY product_id",Long.class)) {
            jdbc.queryForList("SELECT product_id FROM product WHERE product_id=? FOR UPDATE",product);
            jdbc.queryForList("SELECT inventory_id FROM inventory_product WHERE product_id=? ORDER BY warehouse_id,inventory_id FOR UPDATE",product);
            ensureProduct(product);
        }
    }

    public void requireWarehouseCondition(long product,long warehouse,long blocked) {
        transaction();ensureProduct(product);
        long physical=jdbc.queryForObject("SELECT COALESCE(SUM(plan_quantity),0) FROM inventory_product WHERE product_id=? AND warehouse_id=?",Long.class,product,warehouse);
        require(physical>=blocked+pending(product,warehouse),"指定仓库不可售变更将侵占订单仓占用",409);
    }

    /** Caller has locked the product. Legacy remaining holds receive explicit commitments once. */
    public void ensureProduct(long product) {
        transaction();
        Map<Long,Long> free=new TreeMap<>();
        for(Map<String,Object> row:jdbc.queryForList("SELECT warehouse_id,SUM(plan_quantity) quantity FROM inventory_product WHERE product_id=? GROUP BY warehouse_id ORDER BY warehouse_id",product)) {
            long warehouse=num(row.get("warehouse_id"));
            free.put(warehouse,num(row.get("quantity"))-unavailable(product,warehouse)-pending(product,warehouse));
        }
        List<Map<String,Object>> holds=jdbc.queryForList("SELECT h.order_id,h.quantity-COALESCE(f.shipped,0)-COALESCE(f.released,0) remaining FROM commerce_stock_hold h LEFT JOIN commerce_fulfillment_line f ON f.order_id=h.order_id AND f.product_id=h.product_id WHERE h.product_id=? AND h.status='RESERVED' ORDER BY h.created_at,h.order_id",product);
        for(Map<String,Object> hold:holds) {
            String order=String.valueOf(hold.get("order_id"));long need=num(hold.get("remaining"));
            long assigned=jdbc.queryForObject("SELECT COALESCE(SUM(quantity-shipped-released),0) FROM commerce_warehouse_allocation WHERE order_id=? AND product_id=?",Long.class,order,product);
            require(assigned<=need,"仓分配数量与订单剩余占用不一致",409);need-=assigned;
            for(Map.Entry<Long,Long> entry:free.entrySet()) {
                long take=Math.min(need,Math.max(0,entry.getValue()));if(take==0)continue;
                long warehouse=entry.getKey();
                jdbc.update("INSERT INTO commerce_warehouse_allocation(order_id,product_id,warehouse_id,quantity,created_at,updated_at) VALUES (?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE quantity=quantity+VALUES(quantity),updated_at=CURRENT_TIMESTAMP",order,product,warehouse,take);
                jdbc.update("INSERT INTO commerce_warehouse_allocation_event(event_key,order_id,product_id,warehouse_id,event_type,quantity,created_at) VALUES (?,?,?,?,'ALLOCATE',?,CURRENT_TIMESTAMP)","ALLOCATE:"+order+":"+product,order,product,warehouse,take);
                entry.setValue(entry.getValue()-take);need-=take;if(need==0)break;
            }
            require(need==0,"各仓可用库存不能覆盖订单占用，请先核对仓库存",409);
        }
    }

    /** Returns this event's exact warehouse deduction. A product lock serializes every claimant. */
    public Map<Long,Map<Long,Long>> dispatch(String order,Map<Long,Long> quantities,String event,Long selectedWarehouse) {
        transaction();if(selectedWarehouse!=null)require(selectedWarehouse>0,"仓库编号无效",400);
        Map<Long,Map<Long,Long>> result=new TreeMap<>();
        for(Map.Entry<Long,Long> item:quantities.entrySet()) {
            long product=item.getKey(),remaining=item.getValue();ensureProduct(product);
            List<Map<String,Object>> prior=events(order,product,event,"DISPATCH");
            Map<Long,Long> parts=new TreeMap<>();
            if(!prior.isEmpty()) {
                long total=0;for(Map<String,Object> row:prior){long warehouse=num(row.get("warehouse_id"));require(selectedWarehouse==null||warehouse==selectedWarehouse,"同一发货事件不能改变仓库",409);long qty=num(row.get("quantity"));parts.put(warehouse,qty);total+=qty;}
                require(total==remaining,"同一发货事件不能改变数量",409);result.put(product,parts);continue;
            }
            for(Map<String,Object> row:jdbc.queryForList("SELECT * FROM commerce_warehouse_allocation WHERE order_id=? AND product_id=? ORDER BY warehouse_id",order,product)) {
                long warehouse=num(row.get("warehouse_id"));if(selectedWarehouse!=null&&warehouse!=selectedWarehouse)continue;
                long take=Math.min(remaining,num(row.get("quantity"))-num(row.get("shipped"))-num(row.get("released")));if(take==0)continue;
                long physical=jdbc.queryForObject("SELECT COALESCE(SUM(plan_quantity),0) FROM inventory_product WHERE product_id=? AND warehouse_id=?",Long.class,product,warehouse);
                require(physical-unavailable(product,warehouse)>=pending(product,warehouse),"仓库存已变化，不能侵占其他订单占用",409);
                jdbc.update("UPDATE commerce_warehouse_allocation SET shipped=shipped+?,updated_at=CURRENT_TIMESTAMP WHERE order_id=? AND product_id=? AND warehouse_id=?",take,order,product,warehouse);
                record(order,product,warehouse,event,"DISPATCH",take);parts.put(warehouse,take);remaining-=take;if(remaining==0)break;
            }
            require(remaining==0,"指定仓库的本订单可发数量不足",409);result.put(product,parts);
        }
        return result;
    }

    public void release(String order,long product,long quantity,String event) {
        transaction();ensureProduct(product);List<Map<String,Object>> prior=events(order,product,event,"RELEASE");
        if(!prior.isEmpty()){require(prior.stream().mapToLong(r->num(r.get("quantity"))).sum()==quantity,"同一释放事件不能改变数量",409);return;}
        long remaining=quantity;
        // Release the final warehouse first, retaining the earliest warehouse for partial fulfillment.
        for(Map<String,Object> row:jdbc.queryForList("SELECT * FROM commerce_warehouse_allocation WHERE order_id=? AND product_id=? ORDER BY warehouse_id DESC",order,product)) {
            long take=Math.min(remaining,num(row.get("quantity"))-num(row.get("shipped"))-num(row.get("released")));if(take==0)continue;
            long warehouse=num(row.get("warehouse_id"));
            jdbc.update("UPDATE commerce_warehouse_allocation SET released=released+?,updated_at=CURRENT_TIMESTAMP WHERE order_id=? AND product_id=? AND warehouse_id=?",take,order,product,warehouse);
            record(order,product,warehouse,event,"RELEASE",take);remaining-=take;if(remaining==0)break;
        }
        require(remaining==0,"释放数量超过订单仓占用",409);
    }

    /**
     * Re-pins unshipped commitments after a stock move that only changed warehouses, so goods and order
     * commitments travel together. Capacities are this change's post-move room per warehouse; warehouses
     * receiving the goods are the preferred destinations. Warehouses this change does not touch keep their
     * commitments unless they hold spare room. Fails when no warehouse can absorb what no longer fits.
     */
    public void followMove(long product, Map<Long,Long> capacityAfter, SortedSet<Long> incoming) {
        followMove(product,capacityAfter,incoming,null);
    }

    /** Reference joins both sides of a relocation to the triggering ERP document(s). */
    public void followMove(long product, Map<Long,Long> capacityAfter, SortedSet<Long> incoming,String reference) {
        transaction();
        String movementReference=movementReference(reference);
        SortedSet<Long> candidates=new TreeSet<>(capacityAfter.keySet());
        candidates.addAll(jdbc.queryForList("SELECT DISTINCT warehouse_id FROM commerce_warehouse_allocation WHERE product_id=?",Long.class,product));
        Map<Long,Long> shortage=new TreeMap<>(),spare=new TreeMap<>();
        for(Long warehouse:candidates) {
            long room=(capacityAfter.containsKey(warehouse)?capacityAfter.get(warehouse):capacity(product,warehouse))-pending(product,warehouse);
            if(room<0&&capacityAfter.containsKey(warehouse)) shortage.put(warehouse,-room);
            else if(room>0) spare.put(warehouse,room);
        }
        if(shortage.isEmpty()) return;
        long room=0;for(long value:spare.values())room=Math.addExact(room,value);
        long needed=0;for(long value:shortage.values())needed=Math.addExact(needed,value);
        require(room>=needed,ENCROACH_MESSAGE,409);
        List<Long> targets=new ArrayList<>(spare.keySet());
        targets.sort(Comparator.comparingLong((Long warehouse)->incoming.contains(warehouse)?0:1).thenComparingLong(Long::longValue));
        for(Map.Entry<Long,Long> deficit:shortage.entrySet()) {
            long need=deficit.getValue();
            for(Long target:targets) {
                long take=Math.min(need,spare.get(target));if(take==0)continue;
                move(product,deficit.getKey(),target,take,movementReference);
                spare.put(target,spare.get(target)-take);need-=take;if(need==0)break;
            }
            require(need==0,ENCROACH_MESSAGE,409);
        }
    }

    /** Moves unshipped commitment off one warehouse onto another, oldest order first. Caller holds the product lock. */
    private void move(long product,long from,long to,long quantity,String reference) {
        long remaining=quantity;
        String event="MOVE:"+reference+":"+UUID.randomUUID().toString().replace("-","");
        for(Map<String,Object> row:jdbc.queryForList("SELECT order_id,quantity,shipped,released FROM commerce_warehouse_allocation WHERE product_id=? AND warehouse_id=? ORDER BY order_id",product,from)) {
            long take=Math.min(remaining,num(row.get("quantity"))-num(row.get("shipped"))-num(row.get("released")));if(take<=0)continue;
            String order=String.valueOf(row.get("order_id"));
            jdbc.update("UPDATE commerce_warehouse_allocation SET quantity=quantity-?,updated_at=CURRENT_TIMESTAMP WHERE order_id=? AND product_id=? AND warehouse_id=?",take,order,product,from);
            jdbc.update("INSERT INTO commerce_warehouse_allocation(order_id,product_id,warehouse_id,quantity,created_at,updated_at) VALUES (?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE quantity=quantity+VALUES(quantity),updated_at=CURRENT_TIMESTAMP",order,product,to,take);
            // Quantities stay positive; direction and a common key make the reservation journal balanced.
            record(order,product,from,event+":"+order,"MOVE_OUT",take);
            record(order,product,to,event+":"+order,"MOVE_IN",take);
            remaining-=take;if(remaining==0)break;
        }
        require(remaining==0,ENCROACH_MESSAGE,409);
    }

    private static String movementReference(String reference) {
        if(reference==null)return "UNREFERENCED";
        require(!reference.isEmpty()&&reference.length()<=4000&&reference.matches("[A-Za-z0-9_:,\\-]+"),"仓占用迁移单据关联无效",400);
        if(reference.length()<=80)return reference;
        // A batch deletion can name 100 receipts; retain its first IDs plus a hash of the complete list.
        try {
            byte[] bytes=MessageDigest.getInstance("SHA-256").digest(reference.getBytes(StandardCharsets.UTF_8));StringBuilder digest=new StringBuilder();
            for(int i=0;i<16;i++)digest.append(String.format("%02x",bytes[i]&255));
            return reference.substring(0,47)+":"+digest;
        }catch(java.security.NoSuchAlgorithmException failure){throw new IllegalStateException(failure);}
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> allocations(String order) {
        require(jdbc.queryForObject("SELECT COUNT(*) FROM commerce_order WHERE order_id=? AND shop_id=?",Long.class,order,CommerceShopContext.id())==1,"订单不存在",404);
        return jdbc.queryForList("SELECT product_id productId,warehouse_id warehouseId,quantity allocatedQuantity,shipped shippedQuantity,released releasedQuantity,quantity-shipped-released reservedQuantity FROM commerce_warehouse_allocation WHERE order_id=? ORDER BY product_id,warehouse_id",order);
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> allocationEvents(String order) {
        require(jdbc.queryForObject("SELECT COUNT(*) FROM commerce_order WHERE order_id=? AND shop_id=?",Long.class,order,CommerceShopContext.id())==1,"订单不存在",404);
        return jdbc.queryForList("SELECT event_id eventId,event_key eventKey,product_id productId,warehouse_id warehouseId,event_type eventType,quantity,created_at createdAt FROM commerce_warehouse_allocation_event WHERE order_id=? ORDER BY event_id",order);
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> warehouses(long product) {
        require(jdbc.queryForObject("SELECT COUNT(*) FROM commerce_product_shop WHERE product_id=? AND shop_id=?",Long.class,product,CommerceShopContext.id())==1,"货品不存在",404);
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> row:jdbc.queryForList("SELECT warehouse_id,SUM(plan_quantity) quantity FROM inventory_product WHERE product_id=? GROUP BY warehouse_id ORDER BY warehouse_id",product)) {
            long warehouse=num(row.get("warehouse_id")),onHand=num(row.get("quantity")),blocked=unavailable(product,warehouse),held=pending(product,warehouse);
            Map<String,Object> fact=new LinkedHashMap<>();fact.put("warehouseId",warehouse);fact.put("onHand",onHand);fact.put("unavailable",blocked);fact.put("orderReserved",held);fact.put("uncommitted",onHand-blocked-held);result.add(fact);
        }
        return result;
    }

    public long pending(long product,long warehouse) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(quantity-shipped-released),0) FROM commerce_warehouse_allocation WHERE product_id=? AND warehouse_id=?",Long.class,product,warehouse);
    }
    private long unavailable(long product,long warehouse) {return jdbc.queryForObject("SELECT COALESCE(SUM(quality_hold+damaged),0) FROM commerce_warehouse_condition WHERE product_id=? AND warehouse_id=?",Long.class,product,warehouse);}
    private long capacity(long product,long warehouse) {return jdbc.queryForObject("SELECT COALESCE(SUM(plan_quantity),0) FROM inventory_product WHERE product_id=? AND warehouse_id=?",Long.class,product,warehouse)-unavailable(product,warehouse);}
    private List<Map<String,Object>> events(String order,long product,String event,String type) {return jdbc.queryForList("SELECT warehouse_id,quantity,event_type FROM commerce_warehouse_allocation_event WHERE event_key=? AND order_id=? AND product_id=? AND event_type=? ORDER BY warehouse_id",event,order,product,type);}
    private void record(String order,long product,long warehouse,String event,String type,long quantity) {jdbc.update("INSERT INTO commerce_warehouse_allocation_event(event_key,order_id,product_id,warehouse_id,event_type,quantity,created_at) VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP)",event,order,product,warehouse,type,quantity);}
    private static long num(Object value) {return value==null?0:((Number)value).longValue();}
    private static void transaction() {require(TransactionSynchronizationManager.isActualTransactionActive(),"仓分配必须在订单库存事务内执行",409);}
    private static void require(boolean value,String message,int code) {if(!value)throw new ServiceException(message,code);}
}

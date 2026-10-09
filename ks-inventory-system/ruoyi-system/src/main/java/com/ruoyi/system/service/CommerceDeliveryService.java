package com.ruoyi.system.service;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.core.tenant.CommerceShopContext;
import com.ruoyi.common.exception.ServiceException;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.sql.DataSource;
import java.time.*;
import java.sql.Date;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** A dated dispatch promise. Delivery arrival remains a carrier fact, never an AI promise.
 * Caller lock order: order/activity row -> delivery bucket(s) -> product/warehouse/stock.
 * Every method participates in the caller's stock/order transaction. */
@Service
@Profile({"local","commerce"})
public class CommerceDeliveryService {
    private final DataSource source;
    private final JdbcTemplate jdbc;
    public CommerceDeliveryService(DataSource source){this.source=source;this.jdbc=new JdbcTemplate(source);}
    public void initializeSchema(){ResourceDatabasePopulator script=new ResourceDatabasePopulator(new ClassPathResource("db/commerce-delivery.sql"));script.setSqlScriptEncoding("UTF-8");script.execute(source);}

    @Transactional(readOnly=true)
    public Map<String,Object> quote(){
        List<Map<String,Object>> policies=jdbc.queryForList("SELECT * FROM commerce_delivery_policy WHERE shop_id=?",shop());
        if(policies.isEmpty())return map("capacityKnown",false,"dispatchDate",null,"availableQuantity",null,"arrivalDate",null);
        LocalDate date=LocalDate.now().plusDays(n(policies.get(0).get("dispatch_days")));
        List<Map<String,Object>> buckets=jdbc.queryForList("SELECT * FROM commerce_delivery_bucket WHERE shop_id=? AND dispatch_date=?",shop(),Date.valueOf(date));
        long cap=n(policies.get(0).get("daily_item_capacity")),reserved=0,consumed=0;
        if(!buckets.isEmpty()){reserved=n(buckets.get(0).get("reserved_quantity"));consumed=n(buckets.get(0).get("consumed_quantity"));}
        return map("capacityKnown",true,"dispatchDate",date.toString(),"capacity",cap,"reservedQuantity",reserved,"consumedQuantity",consumed,
                "availableQuantity",Math.max(0,cap-reserved-consumed),"overcommitted",cap<reserved+consumed,"arrivalDate",null);
    }

    @Transactional
    public void reserveOrder(String order,long quantity,String activity){
        require(quantity>0,"发货件数必须大于零",400);
        List<Map<String,Object>> existing=find("ORDER",order,false);
        if(!existing.isEmpty()){require(n(existing.get(0).get("total_quantity"))==quantity&&Objects.equals(activity,existing.get(0).get("activity_id")),"订单发货承诺不能改变",409);return;}
        if(activity==null){LocalDate date=promiseDate(LocalDate.now());Map<String,Object> bucket=lockBucket(date,true);allocate(bucket,quantity);insertHold("ORDER",order,date,quantity,null);event("RESERVE:ORDER:"+order,"ORDER",order,date,"RESERVE",quantity,Arrays.asList(quantity));}
        else {
            Map<String,Object> activityHold=owned("ACTIVITY",activity,false);LocalDate date=date(activityHold);lockBucket(date,false);activityHold=owned("ACTIVITY",activity,true);
            require(n(activityHold.get("remaining_quantity"))>=quantity,"活动发货配额不足",409);
            jdbc.update("UPDATE commerce_delivery_hold SET remaining_quantity=remaining_quantity-? WHERE subject_type='ACTIVITY' AND subject_id=?",quantity,activity);
            insertHold("ORDER",order,date,quantity,activity);event("RESERVE:ORDER:"+order,"ORDER",order,date,"TRANSFER_FROM_ACTIVITY",quantity,Arrays.asList(quantity,activity));
        }
    }
    @Transactional
    public void reserveActivity(String activity,long quantity,LocalDateTime starts){
        require(quantity>0,"活动发货配额必须大于零",400);LocalDate date=promiseDate(starts.toLocalDate().isBefore(LocalDate.now())?LocalDate.now():starts.toLocalDate());
        List<Map<String,Object>> existing=find("ACTIVITY",activity,false);
        if(!existing.isEmpty()){require(n(existing.get(0).get("total_quantity"))==quantity&&date.equals(date(existing.get(0))),"活动发货承诺不能改变",409);return;}
        Map<String,Object> bucket=lockBucket(date,true);allocate(bucket,quantity);insertHold("ACTIVITY",activity,date,quantity,null);
        event("RESERVE:ACTIVITY:"+activity,"ACTIVITY",activity,date,"RESERVE",quantity,Arrays.asList(quantity,date.toString()));
    }
    @Transactional
    public void releaseOrder(String order,String key,boolean restoreActivity){releaseOrder(order,key,null,restoreActivity);}
    @Transactional
    public void releaseOrder(String order,String key,Long quantity,boolean restoreActivity){
        Map<String,Object> hold=owned("ORDER",order,false);LocalDate day=date(hold);lockBucket(day,false);hold=owned("ORDER",order,true);
        Object payload=Arrays.asList(quantity==null?"ALL":quantity,restoreActivity);String eventKey="RELEASE:ORDER:"+order+":"+key;
        if(replay(eventKey,payload))return;
        long remaining=n(hold.get("remaining_quantity")),release=quantity==null?remaining:quantity;
        require(release>=0&&release<=remaining,"待发货释放数量超出剩余承诺",409);
        if(restoreActivity && hold.get("activity_id")!=null){
            Map<String,Object> activity=owned("ACTIVITY",String.valueOf(hold.get("activity_id")),true);
            require(day.equals(date(activity)),"活动发货日期不一致",409);
            jdbc.update("UPDATE commerce_delivery_hold SET remaining_quantity=remaining_quantity+? WHERE subject_type='ACTIVITY' AND subject_id=?",release,hold.get("activity_id"));
        }else jdbc.update("UPDATE commerce_delivery_bucket SET reserved_quantity=reserved_quantity-? WHERE shop_id=? AND dispatch_date=? AND reserved_quantity>=?",release,shop(),Date.valueOf(day),release);
        jdbc.update("UPDATE commerce_delivery_hold SET remaining_quantity=remaining_quantity-?,released_quantity=released_quantity+? WHERE subject_type='ORDER' AND subject_id=?",release,release,order);
        event(eventKey,"ORDER",order,day,restoreActivity?"RETURN_TO_ACTIVITY":"RELEASE",release,payload);
    }
    @Transactional
    public void releaseActivity(String activity){
        Map<String,Object> hold=owned("ACTIVITY",activity,false);LocalDate day=date(hold);lockBucket(day,false);hold=owned("ACTIVITY",activity,true);
        String key="RELEASE:ACTIVITY:"+activity;if(replay(key,"EXPIRED"))return;long remaining=n(hold.get("remaining_quantity"));
        jdbc.update("UPDATE commerce_delivery_bucket SET reserved_quantity=reserved_quantity-? WHERE shop_id=? AND dispatch_date=? AND reserved_quantity>=?",remaining,shop(),Date.valueOf(day),remaining);
        jdbc.update("UPDATE commerce_delivery_hold SET remaining_quantity=0,released_quantity=released_quantity+? WHERE subject_type='ACTIVITY' AND subject_id=?",remaining,activity);
        event(key,"ACTIVITY",activity,day,"ACTIVITY_EXPIRE",remaining,"EXPIRED");
    }
    @Transactional public void releaseActivities(Collection<String> activities){
        SortedSet<LocalDate> days=new TreeSet<>();for(String activity:activities)days.add(date(owned("ACTIVITY",activity,false)));
        for(LocalDate day:days)lockBucket(day,false);
        for(String activity:activities)releaseActivity(activity);
    }
    @Transactional
    public void consumeOrder(String order,String shipmentKey,long quantity){
        require(quantity>0,"实际发货件数必须大于零",400);Map<String,Object> hold=owned("ORDER",order,false);LocalDate promised=date(hold),actual=LocalDate.now();
        for(LocalDate day:new TreeSet<>(Arrays.asList(promised,actual)))lockBucket(day,false);
        hold=owned("ORDER",order,true);String key="SHIP:ORDER:"+order+":"+shipmentKey;if(replay(key,quantity))return;
        require(n(hold.get("remaining_quantity"))>=quantity,"实际发货超出剩余承诺",409);
        if(promised.equals(actual))jdbc.update("UPDATE commerce_delivery_bucket SET reserved_quantity=reserved_quantity-?,consumed_quantity=consumed_quantity+? WHERE shop_id=? AND dispatch_date=?",quantity,quantity,shop(),Date.valueOf(actual));
        else {
            Map<String,Object> bucket=jdbc.queryForMap("SELECT * FROM commerce_delivery_bucket WHERE shop_id=? AND dispatch_date=?",shop(),Date.valueOf(actual));
            require(n(bucket.get("capacity"))-n(bucket.get("reserved_quantity"))-n(bucket.get("consumed_quantity"))>=quantity,"今日处理配额不足，请安排下一次发货",409);
            jdbc.update("UPDATE commerce_delivery_bucket SET reserved_quantity=reserved_quantity-? WHERE shop_id=? AND dispatch_date=?",quantity,shop(),Date.valueOf(promised));
            jdbc.update("UPDATE commerce_delivery_bucket SET consumed_quantity=consumed_quantity+? WHERE shop_id=? AND dispatch_date=?",quantity,shop(),Date.valueOf(actual));
        }
        jdbc.update("UPDATE commerce_delivery_hold SET remaining_quantity=remaining_quantity-?,consumed_quantity=consumed_quantity+? WHERE subject_type='ORDER' AND subject_id=?",quantity,quantity,order);
        event(key,"ORDER",order,actual,"DISPATCH",quantity,quantity);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> orderPromise(String order){List<Map<String,Object>> holds=find("ORDER",order,false);if(holds.isEmpty())return map("status","LEGACY_UNPLANNED","arrivalDate",null);Map<String,Object> h=holds.get(0);long remaining=n(h.get("remaining_quantity"));String status=remaining==0?(n(h.get("consumed_quantity"))>0?"DISPATCHED":"RELEASED"):date(h).isBefore(LocalDate.now())?"OVERDUE":"RESERVED";return map("dispatchDate",date(h).toString(),"remainingQuantity",remaining,"consumedQuantity",h.get("consumed_quantity"),"releasedQuantity",h.get("released_quantity"),"status",status,"arrivalDate",null);}

    /** Policy reduction cannot silently invalidate accepted promises. Called after the policy shop lock. */
    @Transactional public void updateCapacity(long capacity){
        for(Map<String,Object> row:jdbc.queryForList("SELECT * FROM commerce_delivery_bucket WHERE shop_id=? AND dispatch_date>=? ORDER BY dispatch_date FOR UPDATE",shop(),Date.valueOf(LocalDate.now())))
            require(n(row.get("reserved_quantity"))+n(row.get("consumed_quantity"))<=capacity,"新处理能力低于已有订单或活动承诺",409);
        jdbc.update("UPDATE commerce_delivery_bucket SET capacity=? WHERE shop_id=? AND dispatch_date>=?",capacity,shop(),Date.valueOf(LocalDate.now()));
    }
    /** One-time non-destructive adoption. Existing overcommitments remain visible and block new sales. */
    public void migrateLegacy(){new TransactionTemplate(new DataSourceTransactionManager(source)).execute(tx->{
        if(jdbc.queryForObject("SELECT COUNT(*) FROM commerce_delivery_migration WHERE migration_id='DELIVERY_V1'",Long.class)>0)return null;
        String prior=shop();try{for(String id:jdbc.queryForList("SELECT shop_id FROM commerce_shop ORDER BY shop_id",String.class)){
            CommerceShopContext.set(id);LocalDate promised=legacyDate();
            for(Map<String,Object> order:jdbc.queryForList("SELECT o.order_id,o.activity_id,COALESCE(SUM(i.quantity-COALESCE(f.shipped,0)-COALESCE(f.released,0)),0) AS remaining FROM commerce_order o JOIN commerce_order_item i ON i.order_id=o.order_id LEFT JOIN commerce_fulfillment_line f ON f.order_id=i.order_id AND f.product_id=i.product_id WHERE o.shop_id=? AND o.status IN(0,1) GROUP BY o.order_id,o.activity_id",shop())){
                long quantity=n(order.get("remaining"));if(quantity<=0 || !find("ORDER",String.valueOf(order.get("order_id")),false).isEmpty())continue;
                lockBucket(promised,false);jdbc.update("UPDATE commerce_delivery_bucket SET reserved_quantity=reserved_quantity+? WHERE shop_id=? AND dispatch_date=?",quantity,shop(),Date.valueOf(promised));insertHold("ORDER",String.valueOf(order.get("order_id")),promised,quantity,order.get("activity_id")==null?null:String.valueOf(order.get("activity_id")));
            }
            for(Map<String,Object> a:jdbc.queryForList("SELECT a.* FROM commerce_activity a WHERE a.shop_id=? AND a.remaining>0 AND (a.ends_at>CURRENT_TIMESTAMP OR NOT EXISTS(SELECT 1 FROM commerce_stock_ledger l WHERE l.event_key=CONCAT('ACTIVITY_EXPIRE:',a.activity_id,':',a.product_id)))",shop())){
                String aid=String.valueOf(a.get("activity_id"));if(!find("ACTIVITY",aid,false).isEmpty())continue;LocalDate day=legacyDate();long qty=n(a.get("remaining"));lockBucket(day,false);jdbc.update("UPDATE commerce_delivery_bucket SET reserved_quantity=reserved_quantity+? WHERE shop_id=? AND dispatch_date=?",qty,shop(),Date.valueOf(day));insertHold("ACTIVITY",aid,day,qty,null);
            }
            long dispatched=jdbc.queryForObject("SELECT COALESCE(SUM(l.event_quantity),0) FROM commerce_stock_ledger l JOIN commerce_order o ON o.order_id=l.order_id WHERE o.shop_id=? AND l.event_type='DISPATCH' AND l.created_at>=?",Long.class,shop(),java.sql.Timestamp.valueOf(LocalDate.now().atStartOfDay()));
            if(dispatched>0){lockBucket(LocalDate.now(),false);jdbc.update("UPDATE commerce_delivery_bucket SET consumed_quantity=consumed_quantity+? WHERE shop_id=? AND dispatch_date=?",dispatched,shop(),Date.valueOf(LocalDate.now()));}
        }}finally{CommerceShopContext.set(prior);}
        jdbc.update("INSERT INTO commerce_delivery_migration VALUES ('DELIVERY_V1',CURRENT_TIMESTAMP)");return null;
    });}

    private LocalDate legacyDate(){List<Integer> days=jdbc.queryForList("SELECT dispatch_days FROM commerce_delivery_policy WHERE shop_id=?",Integer.class,shop());return LocalDate.now().plusDays(days.isEmpty()?0:days.get(0));}
    private LocalDate promiseDate(LocalDate start){List<Map<String,Object>> p=jdbc.queryForList("SELECT * FROM commerce_delivery_policy WHERE shop_id=?",shop());require(!p.isEmpty(),"商家尚未配置发货处理能力",409);return start.plusDays(n(p.get(0).get("dispatch_days")));}
    private Map<String,Object> lockBucket(LocalDate day,boolean policyRequired){
        List<Long> policy=jdbc.queryForList("SELECT daily_item_capacity FROM commerce_delivery_policy WHERE shop_id=? FOR UPDATE",Long.class,shop());require(!policyRequired||!policy.isEmpty(),"商家尚未配置发货处理能力",409);
        long capacity=policy.isEmpty()?0:policy.get(0);
        jdbc.update("INSERT INTO commerce_delivery_bucket(shop_id,dispatch_date,capacity) VALUES (?,?,?) ON DUPLICATE KEY UPDATE dispatch_date=VALUES(dispatch_date)",shop(),Date.valueOf(day),capacity);
        return jdbc.queryForMap("SELECT * FROM commerce_delivery_bucket WHERE shop_id=? AND dispatch_date=? FOR UPDATE",shop(),Date.valueOf(day));
    }
    private void allocate(Map<String,Object> bucket,long quantity){require(n(bucket.get("capacity"))-n(bucket.get("reserved_quantity"))-n(bucket.get("consumed_quantity"))>=quantity,"预计发货日期处理配额不足",409);jdbc.update("UPDATE commerce_delivery_bucket SET reserved_quantity=reserved_quantity+? WHERE shop_id=? AND dispatch_date=?",quantity,shop(),bucket.get("dispatch_date"));}
    private void insertHold(String type,String id,LocalDate day,long quantity,String activity){jdbc.update("INSERT INTO commerce_delivery_hold(subject_type,subject_id,shop_id,dispatch_date,total_quantity,remaining_quantity,activity_id,created_at) VALUES (?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",type,id,shop(),Date.valueOf(day),quantity,quantity,activity);}
    private List<Map<String,Object>> find(String type,String id,boolean lock){return jdbc.queryForList("SELECT * FROM commerce_delivery_hold WHERE subject_type=? AND subject_id=? AND shop_id=?"+(lock?" FOR UPDATE":""),type,id,shop());}
    private Map<String,Object> owned(String type,String id,boolean lock){List<Map<String,Object>> rows=find(type,id,lock);require(!rows.isEmpty(),"发货承诺不存在或不属于当前店铺",409);return rows.get(0);}
    private boolean replay(String key,Object payload){List<String> rows=jdbc.queryForList("SELECT payload_hash FROM commerce_delivery_event WHERE event_key=?",String.class,key);if(rows.isEmpty())return false;require(hash(payload).equals(rows.get(0)),"同一发货配额请求不能改变数量或动作",409);return true;}
    private void event(String key,String type,String id,LocalDate day,String action,long quantity,Object payload){jdbc.update("INSERT INTO commerce_delivery_event VALUES (?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",key,shop(),type,id,Date.valueOf(day),action,quantity,hash(payload));}
    private static LocalDate date(Map<String,Object> row){Object value=row.get("dispatch_date");return value instanceof Date?((Date)value).toLocalDate():LocalDate.parse(value.toString());}
    private static String shop(){return CommerceShopContext.id();}
    private static long n(Object value){return value==null?0:((Number)value).longValue();}
    private static String hash(Object value){try{byte[] digest=MessageDigest.getInstance("SHA-256").digest(JSON.toJSONString(value).getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte b:digest)s.append(String.format("%02x",b));return s.toString();}catch(Exception ex){throw new IllegalStateException(ex);}}
    private static Map<String,Object> map(Object... values){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]);return result;}
    private static void require(boolean ok,String message,int code){if(!ok)throw new ServiceException(message,code);}
}

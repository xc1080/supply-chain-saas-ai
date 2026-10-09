package com.ruoyi.system.service;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.core.tenant.CommerceShopContext;
import com.ruoyi.common.exception.ServiceException;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/** Business facts stay in Java. Quotes and approved plans never post stock or spend money. */
@Service
@Profile("local")
public class CommercePlanningService {
    private final DataSource source;
    private final JdbcTemplate jdbc;
    private final CommerceInventoryService stock;
    private final CommerceMerchantService merchants;
    public CommercePlanningService(DataSource source,CommerceInventoryService stock,CommerceMerchantService merchants) {
        this.source=source;this.jdbc=new JdbcTemplate(source);this.stock=stock;this.merchants=merchants;
    }
    public void initializeSchema() {
        ResourceDatabasePopulator script=new ResourceDatabasePopulator(new ClassPathResource("db/commerce-planning.sql"));
        script.setSqlScriptEncoding("UTF-8");script.execute(source);
    }

    /** A public listed catalog quote: no supplier, cost, contacts or warehouse secrets. Never ensureStock here. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> quote(Map<String,Object> body) {
        require(!body.containsKey("deliveryCapacity"),"发货能力由商家配置，不能由客户端承诺",400);
        SortedMap<Long,Long> requested=items(body);
        long units=positive(body.getOrDefault("units",1),999,"套数");
        List<Map<String,Object>> lines=new ArrayList<>();List<String> missing=new ArrayList<>();
        long atp=Long.MAX_VALUE,perBundleItems=0;BigDecimal unitTotal=BigDecimal.ZERO;
        for(Map.Entry<Long,Long> entry:requested.entrySet()) {
            Map<String,Object> p=listedProduct(entry.getKey());
            long available=n(p.get("available_stock")),quantity=entry.getValue();
            boolean ready=p.get("stock_id")!=null;
            if(!ready)missing.add("INVENTORY_NOT_READY:"+entry.getKey());
            atp=Math.min(atp,available/quantity);perBundleItems+=quantity;
            BigDecimal price=money(p.get("univalence"));unitTotal=unitTotal.add(price.multiply(BigDecimal.valueOf(quantity)));
            lines.add(map("productId",entry.getKey(),"productName",p.get("product_name"),"productCode",p.get("product_code"),
                "unitPrice",price,"quantity",quantity,"availableStock",available,
                "shortage",Math.max(0,quantity*units-available),"lineTotal",price.multiply(BigDecimal.valueOf(quantity*units))));
        }
        Long delivery=null;String dispatchDate=null;
        List<Map<String,Object>> policy=jdbc.queryForList("SELECT * FROM commerce_delivery_policy WHERE shop_id=?",shop());
        if(policy.isEmpty())missing.add("DISPATCH_CAPACITY_UNKNOWN");
        else {
            Map<String,Object> p=policy.get(0);
            long backlog=jdbc.queryForObject("SELECT COALESCE(SUM(i.quantity-COALESCE(f.released,0)-COALESCE(f.shipped,0)),0) FROM commerce_order_item i JOIN commerce_order o ON o.order_id=i.order_id LEFT JOIN commerce_fulfillment_line f ON f.order_id=i.order_id AND f.product_id=i.product_id WHERE o.shop_id=? AND o.status IN (1,2,3)",Long.class,shop());
            long dispatchedToday=jdbc.queryForObject("SELECT COALESCE(SUM(l.event_quantity),0) FROM commerce_stock_ledger l JOIN commerce_order o ON o.order_id=l.order_id WHERE o.shop_id=? AND l.event_type='DISPATCH' AND l.created_at>=?",Long.class,shop(),Timestamp.valueOf(LocalDate.now().atStartOfDay()));
            long committed=backlog+dispatchedToday;
            delivery=Math.max(0,n(p.get("daily_item_capacity"))-committed)/perBundleItems;
            dispatchDate=LocalDate.now().plusDays(n(p.get("dispatch_days"))).toString();
        }
        missing.add("CARRIER_ARRIVAL_DATE_UNKNOWN");
        long promisable=delivery==null?atp:Math.min(atp,delivery);
        return map("status","DRAFT","items",lines,"unitTotal",unitTotal.setScale(2),"totalAmount",unitTotal.multiply(BigDecimal.valueOf(units)).setScale(2),
            "requestedUnits",units,"inventoryPromisableUnits",atp,"promisableUnits",promisable,"deliveryCapacity",delivery,
            "deliveryDate",null,"dispatchDate",dispatchDate,"deliveryStatus","UNKNOWN",
            "deliveryBasis","发货处理能力按已付款商品件数扣除；未锁定配额，未承诺承运商到达日期", "missing",missing,
            "quoteBinding",false,"stockReserved",false);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> replenishment(long actor) {
        merchant(actor,false);
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> p:jdbc.queryForList("SELECT p.product_id,p.product_code,p.product_name,p.lower_limit,p.upper_limit,s.on_hand,s.reserved,s.activity_reserved,s.unavailable,sp.supplier_lead_days FROM product p JOIN commerce_product_shop ps ON ps.product_id=p.product_id LEFT JOIN commerce_stock s ON s.product_id=p.product_id LEFT JOIN commerce_supply_policy sp ON sp.product_id=p.product_id AND sp.shop_id=ps.shop_id WHERE ps.shop_id=? AND ps.listed=1 AND p.status='0' ORDER BY p.product_id",shop())) {
            long id=n(p.get("product_id")),onHand=n(p.get("on_hand")),reserved=n(p.get("reserved")),activity=n(p.get("activity_reserved")),unavailable=n(p.get("unavailable"));
            long available=Math.max(0,onHand-reserved-activity-unavailable),lower=n(p.get("lower_limit")),target=Math.max(lower,n(p.get("upper_limit")));
            Object leadValue=p.get("supplier_lead_days");boolean leadKnown=leadValue!=null;
            int lead=leadKnown?(int)n(leadValue):0;
            long incoming=jdbc.queryForObject("SELECT COALESCE(SUM(quantity-received_quantity),0) FROM commerce_incoming WHERE shop_id=? AND product_id=? AND status='CONFIRMED'",Long.class,shop(),id);
            long due=leadKnown?jdbc.queryForObject("SELECT COALESCE(SUM(quantity-received_quantity),0) FROM commerce_incoming WHERE shop_id=? AND product_id=? AND status='CONFIRMED' AND expected_at>=CURRENT_TIMESTAMP AND expected_at<=?",Long.class,shop(),id,Timestamp.valueOf(LocalDateTime.now().plusDays(lead))):0;
            long overdue=jdbc.queryForObject("SELECT COALESCE(SUM(quantity-received_quantity),0) FROM commerce_incoming WHERE shop_id=? AND product_id=? AND status='CONFIRMED' AND expected_at<CURRENT_TIMESTAMP",Long.class,shop(),id);
            long sales=Math.max(0,jdbc.queryForObject("SELECT COALESCE(SUM(CASE WHEN event_type='DISPATCH' THEN event_quantity WHEN event_type='RETURN_ACCEPT' THEN -event_quantity ELSE 0 END),0) FROM commerce_stock_ledger WHERE product_id=? AND created_at>=?",Long.class,id,Timestamp.valueOf(LocalDateTime.now().minusDays(7))));
            long demand=leadKnown?(long)Math.ceil(sales*lead/7.0):0,projected=available+due-demand;
            long suggestion=projected<lower?Math.max(0,target-projected):0;
            result.add(map("productId",id,"productCode",p.get("product_code"),"productName",p.get("product_name"),
                "onHandStock",onHand,"reservedStock",reserved,"activityStock",activity,"unavailableStock",unavailable,"availableStock",available,
                "incomingStock",incoming,"incomingDueWithinLead",due,"overdueIncoming",overdue,"incomingKnown",false,"incomingCoverage","REGISTERED_ONLY",
                "salesUnits",sales,"salesWindowDays",7,"salesBasis","NET_DISPATCHED","reorderPoint",lower,"targetStock",target,
                "supplierLeadDays",leadKnown?lead:null,"leadTimeKnown",leadKnown,"forecastDemand",demand,"projectedStock",projected,"suggestedQuantity",suggestion,
                "reason",leadKnown?"可售+交期内确认在途-交期预测需求；低于下限时补至目标":"交期未核实，仅按现有可售与补货下限提示缺口；审批前需补查交期"));
        }
        return map("status","DRAFT","items",result,"assumptions",Arrays.asList("销量采用近7日净出库，不能替代完整需求预测", "在途只统计商家登记的确认批次，逾期批次不计入可承诺供给", "备货建议与批准草稿不产生采购付款或库存入账"));
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> savePolicy(Map<String,Object> body,long actor) {
        merchant(actor,true);
        if(body.containsKey("dailyItemCapacity")) {
            long capacity=positive(body.get("dailyItemCapacity"),1000000,"日处理件数");long days=nonnegative(body.get("dispatchDays"),30,"发货处理天数");
            jdbc.update("INSERT INTO commerce_delivery_policy VALUES (?,?,?,?,CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE daily_item_capacity=VALUES(daily_item_capacity),dispatch_days=VALUES(dispatch_days),actor_id=VALUES(actor_id),updated_at=CURRENT_TIMESTAMP",shop(),capacity,days,actor);
        }
        if(body.containsKey("productId")) {
            long product=positive(body.get("productId"),Long.MAX_VALUE,"商品");merchants.requireProducts(Collections.singleton(product));
            long lead=nonnegative(body.get("supplierLeadDays"),365,"供货交期");
            jdbc.update("INSERT INTO commerce_supply_policy VALUES (?,?,?,?,CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE supplier_lead_days=VALUES(supplier_lead_days),actor_id=VALUES(actor_id),updated_at=CURRENT_TIMESTAMP",product,shop(),lead,actor);
        }
        require(body.containsKey("dailyItemCapacity")||body.containsKey("productId"),"没有可保存的经营配置",400);
        return map("status","SAVED");
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> condition(Map<String,Object> body,long actor) {
        merchant(actor,true);String key=key(body),reason=text(body.get("reason"),160,"变更原因");
        long product=positive(body.get("productId"),Long.MAX_VALUE,"商品"),warehouse=positive(body.get("warehouseId"),Long.MAX_VALUE,"仓库"),quantity=positive(body.get("quantity"),1000000,"数量");
        String from=String.valueOf(body.get("from")),to=String.valueOf(body.get("to"));
        List<String> states=Arrays.asList("SELLABLE","QUALITY_HOLD","DAMAGED");require(states.contains(from)&&states.contains(to)&&!from.equals(to),"库存状态无效",400);
        String hash=hash(Arrays.asList(product,warehouse,quantity,from,to,reason));
        lockShop();merchants.requireProducts(Collections.singleton(product));
        List<Map<String,Object>> old=jdbc.queryForList("SELECT * FROM commerce_condition_event WHERE shop_id=? AND request_key=?",shop(),key);
        if(!old.isEmpty()){require(hash.equals(old.get(0).get("request_hash")),"同一请求不能变更库存条件",409);return conditionView(old.get(0));}
        stock.ensureStock(product);
        List<Map<String,Object>> physical=jdbc.queryForList("SELECT plan_quantity FROM inventory_product WHERE product_id=? AND warehouse_id=? ORDER BY inventory_id FOR UPDATE",product,warehouse);
        require(!physical.isEmpty(),"该商品在仓库没有库存记录",404);long total=physical.stream().mapToLong(p->n(p.get("plan_quantity"))).sum();
        jdbc.update("INSERT INTO commerce_warehouse_condition VALUES (?,?,0,0) ON DUPLICATE KEY UPDATE product_id=VALUES(product_id)",product,warehouse);
        Map<String,Object> prior=jdbc.queryForMap("SELECT * FROM commerce_warehouse_condition WHERE product_id=? AND warehouse_id=? FOR UPDATE",product,warehouse);
        long quality=n(prior.get("quality_hold")),damaged=n(prior.get("damaged"));
        long availableFrom="SELLABLE".equals(from)?total-quality-damaged:"QUALITY_HOLD".equals(from)?quality:damaged;
        require(availableFrom>=quantity,"来源状态库存不足",409);
        long afterQuality=quality+("QUALITY_HOLD".equals(to)?quantity:0)-("QUALITY_HOLD".equals(from)?quantity:0);
        long afterDamaged=damaged+("DAMAGED".equals(to)?quantity:0)-("DAMAGED".equals(from)?quantity:0);
        require(afterQuality+afterDamaged<=total,"不可售数量不能超过仓库实物",409);
        long delta=afterQuality+afterDamaged-quality-damaged;String event=id("IC");
        if(delta!=0)stock.adjustUnavailable(product,"CONDITION:"+event,delta,"库存条件："+from+"→"+to);
        jdbc.update("UPDATE commerce_warehouse_condition SET quality_hold=?,damaged=? WHERE product_id=? AND warehouse_id=?",afterQuality,afterDamaged,product,warehouse);
        jdbc.update("INSERT INTO commerce_condition_event VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",event,shop(),key,hash,product,warehouse,from,to,quantity,quality,afterQuality,damaged,afterDamaged,reason,actor);
        return conditionView(jdbc.queryForMap("SELECT * FROM commerce_condition_event WHERE event_id=?",event));
    }

    public List<Map<String,Object>> conditions(long actor) {
        merchant(actor,false);
        return jdbc.queryForList("SELECT e.event_id AS eventId,e.product_id AS productId,e.warehouse_id AS warehouseId,e.from_state AS fromState,e.to_state AS toState,e.quantity,e.reason,e.actor_id AS actorId,e.created_at AS createdAt FROM commerce_condition_event e WHERE e.shop_id=? ORDER BY e.created_at DESC,e.event_id DESC LIMIT 100",shop());
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> registerIncoming(Map<String,Object> body,long actor) {
        merchant(actor,true);String key=key(body),reference=text(body.get("sourceReference"),80,"供货批次依据");
        long product=positive(body.get("productId"),Long.MAX_VALUE,"商品"),warehouse=positive(body.get("warehouseId"),Long.MAX_VALUE,"仓库"),quantity=positive(body.get("quantity"),1000000,"在途数量");
        LocalDateTime expected;try{expected=LocalDateTime.parse(String.valueOf(body.get("expectedAt")));}catch(Exception ex){throw new ServiceException("预计到货时间格式错误",400);}
        String hash=hash(Arrays.asList(product,warehouse,quantity,reference,expected.toString()));lockShop();merchants.requireProducts(Collections.singleton(product));
        List<Map<String,Object>> old=jdbc.queryForList("SELECT * FROM commerce_incoming WHERE shop_id=? AND request_key=?",shop(),key);
        if(!old.isEmpty()){require(hash.equals(old.get(0).get("request_hash")),"同一在途请求不能改变批次",409);return incomingView(old.get(0));}
        require(jdbc.queryForObject("SELECT COUNT(*) FROM warehouse WHERE warehouse_id=?",Long.class,warehouse)>0,"仓库不存在",404);
        String incoming=id("IN");jdbc.update("INSERT INTO commerce_incoming VALUES (?,?,?,?,?,?,?,?,0,?,'CONFIRMED',?,CURRENT_TIMESTAMP)",incoming,shop(),key,hash,product,warehouse,reference,quantity,Timestamp.valueOf(expected),actor);
        return incomingView(jdbc.queryForMap("SELECT * FROM commerce_incoming WHERE incoming_id=?",incoming));
    }

    /** Link posted ERP evidence, never post the same physical stock a second time. */
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> receiveIncoming(String incoming,Map<String,Object> body,long actor) {
        merchant(actor,true);String receipt=text(body.get("receiptId"),32,"采购入库单");lockShop();
        Map<String,Object> row=incomingOwned(incoming,true);long product=n(row.get("product_id")),warehouse=n(row.get("warehouse_id"));
        // The receipt guard uses this lock as well, so evidence cannot disappear while being linked.
        jdbc.update("INSERT INTO commerce_receipt_lock VALUES (?) ON DUPLICATE KEY UPDATE receipt_id=VALUES(receipt_id)",receipt);
        jdbc.queryForList("SELECT receipt_id FROM commerce_receipt_lock WHERE receipt_id=? FOR UPDATE",receipt);
        List<Map<String,Object>> linked=jdbc.queryForList("SELECT * FROM commerce_incoming_receipt WHERE receipt_id=? AND product_id=? AND warehouse_id=?",receipt,product,warehouse);
        if(!linked.isEmpty()){require(incoming.equals(linked.get(0).get("incoming_id")),"入库凭证已关联其他批次",409);return incomingView(row);}
        long quantity=jdbc.queryForObject("SELECT COALESCE(SUM(d.plan_quantity),0) FROM detail_receipt d JOIN head_receipt h ON h.systematic_receipt=d.systematic_receipt WHERE h.systematic_receipt=? AND h.receipt_status='2' AND h.receipt_category='1' AND h.receipt_type='1' AND d.product_id=? AND COALESCE(d.warehousing_id,h.warehousing_ids)=?",Long.class,receipt,product,warehouse);
        require(quantity>0,"找不到该商品、仓库已审核的采购入库凭证",409);
        require(quantity<=n(row.get("quantity"))-n(row.get("received_quantity")),"入库数量超过该在途批次剩余数量",409);
        jdbc.update("INSERT INTO commerce_incoming_receipt VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP)",receipt,product,warehouse,incoming,quantity,actor);
        long received=n(row.get("received_quantity"))+quantity;
        jdbc.update("UPDATE commerce_incoming SET received_quantity=?,status=? WHERE incoming_id=?",received,received==n(row.get("quantity"))?"RECEIVED":"CONFIRMED",incoming);
        return incomingView(incomingOwned(incoming,false));
    }
    public List<Map<String,Object>> incoming(long actor) {
        merchant(actor,false);List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> row:jdbc.queryForList("SELECT * FROM commerce_incoming WHERE shop_id=? ORDER BY created_at DESC,incoming_id DESC LIMIT 100",shop()))result.add(incomingView(row));return result;
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> createDraft(Map<String,Object> body,long actor) {
        merchant(actor,true);String key=key(body);SortedMap<Long,Long> requested=items(body);String hash=hash(requested);lockShop();
        List<Map<String,Object>> old=jdbc.queryForList("SELECT * FROM commerce_replenishment_draft WHERE shop_id=? AND request_key=?",shop(),key);
        if(!old.isEmpty()){require(hash.equals(old.get(0).get("request_hash")),"同一草稿请求不能修改数量",409);return draftView(old.get(0));}
        Map<Long,Map<String,Object>> facts=new HashMap<>();for(Object raw:(List<?>)replenishment(actor).get("items")){Map<String,Object> p=(Map<String,Object>)raw;facts.put(n(p.get("productId")),p);}
        List<Map<String,Object>> lines=new ArrayList<>();
        for(Map.Entry<Long,Long> item:requested.entrySet()){
            Map<String,Object> p=facts.get(item.getKey());require(p!=null,"商品未在当前店铺上架",404);
            require(n(p.get("suggestedQuantity"))>0&&item.getValue()<=n(p.get("suggestedQuantity")),"补货数量超出当前建议，请刷新经营数据",409);
            Map<String,Object> line=new LinkedHashMap<>(p);line.put("quantity",item.getValue());lines.add(line);
        }
        String id=id("RP");jdbc.update("INSERT INTO commerce_replenishment_draft(draft_id,shop_id,request_key,request_hash,status,snapshot_json,actor_id,created_at) VALUES (?,?,?,?,'PENDING_APPROVAL',?,?,CURRENT_TIMESTAMP)",id,shop(),key,hash,JSON.toJSONString(lines),actor);
        return draftView(draftOwned(id,false));
    }
    public List<Map<String,Object>> drafts(long actor) {
        merchant(actor,false);List<Map<String,Object>> result=new ArrayList<>();for(Map<String,Object> row:jdbc.queryForList("SELECT * FROM commerce_replenishment_draft WHERE shop_id=? ORDER BY created_at DESC,draft_id DESC LIMIT 50",shop()))result.add(draftView(row));return result;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> reviewDraft(String draft,Map<String,Object> body,long actor) {
        merchant(actor,true);String key=key(body),decision=String.valueOf(body.get("decision")),note=text(body.get("note"),160,"审批意见");
        require("APPROVE".equals(decision)||"REJECT".equals(decision),"审批决策无效",400);String hash=hash(Arrays.asList(decision,note));
        Map<String,Object> row=draftOwned(draft,true);
        if(!"PENDING_APPROVAL".equals(row.get("status"))){require(key.equals(row.get("review_key"))&&hash.equals(row.get("review_hash")),"该草稿已经审批，不能改变结论",409);return draftView(row);}
        if("APPROVE".equals(decision)){
            Map<Long,Long> current=new HashMap<>();for(Object raw:(List<?>)replenishment(actor).get("items")){Map<String,Object> p=(Map<String,Object>)raw;current.put(n(p.get("productId")),n(p.get("suggestedQuantity")));}
            for(Object raw:JSON.parseArray(String.valueOf(row.get("snapshot_json")))){Map<String,Object> line=(Map<String,Object>)raw;require(current.getOrDefault(n(line.get("productId")),0L)>=n(line.get("quantity")),"当前缺口已变化，请重新生成草稿",409);}
        }
        jdbc.update("UPDATE commerce_replenishment_draft SET status=?,reviewed_by=?,reviewed_at=CURRENT_TIMESTAMP,review_key=?,review_hash=?,review_note=? WHERE draft_id=?","APPROVE".equals(decision)?"APPROVED":"REJECTED",actor,key,hash,note,draft);
        return draftView(draftOwned(draft,false));
    }

    private Map<String,Object> listedProduct(long id){
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT p.product_id,p.product_name,p.product_code,p.univalence,s.product_id AS stock_id,GREATEST(0,COALESCE(s.on_hand-s.reserved-s.activity_reserved-s.unavailable,0)) AS available_stock FROM product p JOIN commerce_product_shop ps ON ps.product_id=p.product_id JOIN commerce_shop sh ON sh.shop_id=ps.shop_id LEFT JOIN commerce_stock s ON s.product_id=p.product_id WHERE p.product_id=? AND ps.shop_id=? AND ps.listed=1 AND sh.status='ENABLED' AND p.status='0'",id,shop());
        require(!rows.isEmpty(),"商品未在当前店铺上架",404);return rows.get(0);
    }
    private Map<String,Object> incomingOwned(String id,boolean lock){List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM commerce_incoming WHERE incoming_id=? AND shop_id=?"+(lock?" FOR UPDATE":""),id,shop());require(!rows.isEmpty(),"在途批次不存在",404);return rows.get(0);}
    private Map<String,Object> draftOwned(String id,boolean lock){List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM commerce_replenishment_draft WHERE draft_id=? AND shop_id=?"+(lock?" FOR UPDATE":""),id,shop());require(!rows.isEmpty(),"备货草稿不存在",404);return rows.get(0);}
    private Map<String,Object> incomingView(Map<String,Object> r){return map("incomingId",r.get("incoming_id"),"productId",r.get("product_id"),"warehouseId",r.get("warehouse_id"),"sourceReference",r.get("source_reference"),"quantity",r.get("quantity"),"receivedQuantity",r.get("received_quantity"),"expectedAt",r.get("expected_at"),"status",r.get("status"),"actorId",r.get("actor_id"));}
    private Map<String,Object> conditionView(Map<String,Object> r){return map("eventId",r.get("event_id"),"productId",r.get("product_id"),"warehouseId",r.get("warehouse_id"),"fromState",r.get("from_state"),"toState",r.get("to_state"),"quantity",r.get("quantity"),"qualityHold",r.get("after_quality"),"damaged",r.get("after_damaged"),"reason",r.get("reason"),"actorId",r.get("actor_id"));}
    private Map<String,Object> draftView(Map<String,Object> r){return map("draftId",r.get("draft_id"),"status",r.get("status"),"items",JSON.parseArray(String.valueOf(r.get("snapshot_json"))),"createdBy",r.get("actor_id"),"createdAt",r.get("created_at"),"reviewedBy",r.get("reviewed_by"),"reviewedAt",r.get("reviewed_at"),"reviewNote",r.get("review_note"),"executionStatus","NOT_EXECUTED","stockPosted",false);}
    private void merchant(long actor,boolean write){merchants.requireShop(shop(),actor,write);}
    private void lockShop(){jdbc.queryForList("SELECT shop_id FROM commerce_shop WHERE shop_id=? FOR UPDATE",shop());}
    private static String shop(){return CommerceShopContext.id();}
    private static SortedMap<Long,Long> items(Map<String,Object> body){
        Object raw=body.get("items");require(raw instanceof List&&!((List<?>)raw).isEmpty()&&((List<?>)raw).size()<=20,"商品清单需为1至20行",400);SortedMap<Long,Long> result=new TreeMap<>();
        for(Object entry:(List<?>)raw){require(entry instanceof Map,"商品清单格式错误",400);Map<?,?> item=(Map<?,?>)entry;long product=positive(item.get("productId"),Long.MAX_VALUE,"商品"),quantity=positive(item.get("quantity"),999,"数量");require(result.put(product,quantity)==null,"商品不能重复",400);}return result;
    }
    private static long positive(Object v,long max,String name){long value=nonnegative(v,max,name);require(value>0,name+"需大于零",400);return value;}
    private static long nonnegative(Object v,long max,String name){String s=String.valueOf(v);require(s.matches("[0-9]{1,18}"),name+"必须为整数",400);long value=Long.parseLong(s);require(value<=max,name+"超出范围",400);return value;}
    private static String text(Object v,int max,String name){require(v instanceof String&&!((String)v).trim().isEmpty()&&((String)v).trim().length()<=max,name+"格式错误",400);return ((String)v).trim();}
    private static String key(Map<String,Object> body){String value=text(body.get("requestKey"),80,"请求标识");require(value.matches("[A-Za-z0-9_:-]+"),"请求标识格式错误",400);return value;}
    private static long n(Object value){return value==null?0:((Number)value).longValue();}
    private static BigDecimal money(Object value){require(value!=null,"商品价格尚未配置",409);BigDecimal price=new BigDecimal(value.toString()).setScale(2,RoundingMode.UNNECESSARY);require(price.signum()>=0,"商品价格错误",409);return price;}
    private static String id(String prefix){return prefix+UUID.randomUUID().toString().replace("-","").substring(0,28);}
    private static String hash(Object value){try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(JSON.toJSONString(value).getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format("%02x",b));return s.toString();}catch(Exception ex){throw new IllegalStateException(ex);}}
    private static Map<String,Object> map(Object... values){Map<String,Object> r=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)r.put((String)values[i],values[i+1]);return r;}
    private static void require(boolean ok,String message,int code){if(!ok)throw new ServiceException(message,code);}
}

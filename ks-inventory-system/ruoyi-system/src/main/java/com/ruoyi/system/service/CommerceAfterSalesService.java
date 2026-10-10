package com.ruoyi.system.service;

import com.ruoyi.common.core.tenant.CommerceShopContext;
import com.ruoyi.common.core.tenant.TenantContext;
import com.ruoyi.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Order -> case -> activity/receipt -> ascending products -> warehouse/stock lock order.
 * Cases reserve distinct line quantities and paid money; physical acceptance and refund are separate. */
@Service
@Profile({"local","commerce"})
public class CommerceAfterSalesService {
    private final JdbcTemplate jdbc;
    private final CommerceInventoryService stock;
    private final CommerceMerchantService merchants;
    private final CommercePaymentService payments;
    private final CommerceDeliveryService delivery;
    private final CommerceOrderAmountService amounts;
    private CommerceCostService costs;
    @Autowired public void configureCosts(CommerceCostService costs) { this.costs=costs; }
    private CommerceLogisticsService logistics;
    @Autowired public void configureLogistics(CommerceLogisticsService logistics) { this.logistics=logistics; }
    public CommerceAfterSalesService(DataSource source){this(source,new CommerceInventoryService(source),new CommerceMerchantService(source));}
    public CommerceAfterSalesService(DataSource source,CommerceInventoryService stock,CommerceMerchantService merchants){this(source,stock,merchants,new CommercePaymentService(source));}
    public CommerceAfterSalesService(DataSource source,CommerceInventoryService stock,CommerceMerchantService merchants,CommercePaymentService payments){this(source,stock,merchants,payments,new CommerceDeliveryService(source));}
    @Autowired public CommerceAfterSalesService(DataSource source,CommerceInventoryService stock,CommerceMerchantService merchants,CommercePaymentService payments,CommerceDeliveryService delivery){this.jdbc=new JdbcTemplate(source);this.stock=stock;this.merchants=merchants;this.payments=payments;this.delivery=delivery;this.amounts=new CommerceOrderAmountService(source,merchants);}

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> apply(String orderId,Map<String,Object> request) {
        String owner=owner(request.get("ownerId")),key=key(request.get("requestKey")),reason=text(request.get("reason"),1,200,"售后原因");
        Map<String,Object> order=order(orderId,owner,true);
        SortedMap<Long,Long> requested=request.get("items")==null?null:CommerceService.normalizeItems(request.get("items"));
        String kind=request.get("kind")==null?null:String.valueOf(request.get("kind"));
        require(kind==null||"UNSHIPPED_REFUND".equals(kind)||"RETURN_REFUND".equals(kind),"售后类型无效",400);
        List<Map<String,Object>> old=jdbc.queryForList("SELECT * FROM commerce_after_sales_case WHERE order_id=? AND request_key=?",orderId,key);
        if(!old.isEmpty()) {
            Map<String,Object> previous=old.get(0);SortedMap<Long,Long> prior=quantities(items(String.valueOf(previous.get("after_sales_id"))));
            require(reason.equals(previous.get("reason"))&&(kind==null||kind.equals(previous.get("kind")))&&(requested==null||requested.equals(prior)),"同一售后请求不能更改原因、类型或商品数量",409);
            return detail(String.valueOf(previous.get("after_sales_id")),owner);
        }
        int state=(int)number(order.get("status"));
        require(state>=1&&state<=3&&decimal(order.get("refunded_amount")).compareTo(decimal(order.get("total_amount")))<0,"只有已付款且仍有可退款余额的订单可以申请售后",409);
        List<Map<String,Object>> facts=CommercePartialSupport.lines(jdbc,orderId);long unsent=0,sent=0;
        for(Map<String,Object> fact:facts){unsent+=number(fact.get("unshipped_available"));sent+=number(fact.get("return_available"));}
        if(kind==null){require(!(unsent>0&&sent>0),"订单已部分发货，请将未发货退款与已发货退货分别申请",409);kind=unsent>0?"UNSHIPPED_REFUND":"RETURN_REFUND";}
        boolean returning="RETURN_REFUND".equals(kind);
        Map<Long,Map<String,Object>> index=new TreeMap<>();SortedMap<Long,Long> chosen=new TreeMap<>();
        for(Map<String,Object> fact:facts){long product=number(fact.get("product_id")),available=number(fact.get(returning?"return_available":"unshipped_available"));index.put(product,fact);if(requested==null&&available>0)chosen.put(product,available);}
        if(requested!=null)chosen.putAll(requested);
        require(!chosen.isEmpty(),"没有符合该售后类型的剩余商品数量",409);
        for(Map.Entry<Long,Long> entry:chosen.entrySet()){
            Map<String,Object> fact=index.get(entry.getKey());require(fact!=null&&number(fact.get(returning?"return_available":"unshipped_available"))>=entry.getValue(),"售后数量超过剩余可申请数量或已被其他售后锁定",409);
        }
        SortedMap<Long,BigDecimal> refunds=amounts.refundAmounts(orderId,chosen);
        BigDecimal amount=BigDecimal.ZERO;for(BigDecimal refund:refunds.values())amount=amount.add(refund);
        BigDecimal pending=jdbc.queryForObject("SELECT COALESCE(SUM(refund_amount),0) FROM commerce_after_sales_case WHERE order_id=? AND status NOT IN('REFUNDED','REJECTED')",BigDecimal.class,orderId);
        require(decimal(order.get("total_amount")).subtract(decimal(order.get("refunded_amount"))).subtract(pending).compareTo(amount)>=0,"售后金额超过尚未被其他申请占用的已付余额",409);
        String id=newId("AS");
        jdbc.update("INSERT INTO commerce_after_sales_case(after_sales_id,order_id,shop_id,owner_id,request_key,request_hash,kind,reason,status,original_order_status,return_required,refund_amount,created_at) VALUES (?,?,?,?,?,?,?,?,'REQUESTED',?,?,?,CURRENT_TIMESTAMP)",id,orderId,CommerceShopContext.id(),owner,key,CommerceService.requestHash(chosen),kind,reason,state,returning?1:0,amount);
        if(logistics!=null)jdbc.update("UPDATE commerce_after_sales_case SET return_evidence_version=1 WHERE after_sales_id=?",id);
        for(Map.Entry<Long,Long> entry:chosen.entrySet()){
            Map<String,Object> fact=index.get(entry.getKey());BigDecimal price=decimal(fact.get("unit_price"));
            jdbc.update("INSERT INTO commerce_after_sales_item(after_sales_id,product_id,product_code,product_name,spec,quantity,unit_price,amount) VALUES (?,?,?,?,?,?,?,?)",id,entry.getKey(),fact.get("product_code"),fact.get("product_name"),fact.get("spec"),entry.getValue(),price,refunds.get(entry.getKey()));
        }
        amounts.reserveRefund(orderId,id,chosen);
        header(orderId,id,"REQUESTED");event(id,"REQUEST","REQUESTED",null,reason);return detail(id,owner);
    }

    @Transactional(readOnly=true)
    public Map<String,Object> list(String ownerId,String status,int pageNum,int pageSize){
        require(pageNum>=1&&pageNum<=100000&&pageSize>=1&&pageSize<=100,"分页参数错误",400);
        String where=" WHERE shop_id=?";List<Object> args=new ArrayList<>();args.add(CommerceShopContext.id());
        if(ownerId!=null){where+=" AND owner_id=?";args.add(owner(ownerId));}if(status!=null){require(states().containsKey(status),"售后状态错误",400);where+=" AND status=?";args.add(status);}
        Long total=jdbc.queryForObject("SELECT COUNT(*) FROM commerce_after_sales_case"+where,Long.class,args.toArray());args.add(pageSize);args.add((pageNum-1)*pageSize);
        List<Map<String,Object>> result=new ArrayList<>();for(Map<String,Object> row:jdbc.queryForList("SELECT * FROM commerce_after_sales_case"+where+" ORDER BY created_at DESC,after_sales_id DESC LIMIT ? OFFSET ?",args.toArray()))result.add(shape(row,false));
        return map("rows",result,"total",total);
    }
    @Transactional(readOnly=true)public Map<String,Object> detail(String id,String ownerId){return shape(find(id,ownerId,false),true);}

    public Map<String,Object> registerReturnParcel(String id,Map<String,Object> request){
        require(logistics!=null,"寄回凭证服务尚未就绪",503);return logistics.registerReturnParcel(id,request);
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> review(String id,Map<String,Object> request,long actor){
        merchants.requireCapability(CommerceShopContext.id(),actor,CommerceCapability.REFUND_REVIEW);String key=key(request.get("requestKey")),decision=String.valueOf(request.get("decision"));require("APPROVE".equals(decision)||"REJECT".equals(decision),"审核决定无效",400);
        String note=request.get("note")==null?"":text(request.get("note"),0,200,"审核备注");Map<String,Object> row=lock(id);
        if(row.get("review_key")!=null){require(key.equals(row.get("review_key"))&&("REJECT".equals(decision)=="REJECTED".equals(row.get("status"))),"同一售后不能重复执行不同审核",409);return detail(id,null);}
        require("REQUESTED".equals(row.get("status")),"当前售后不能审核",409);
        String state="REJECT".equals(decision)?"REJECTED":number(row.get("return_required"))==1?"AWAITING_RETURN":"APPROVED";
        jdbc.update("UPDATE commerce_after_sales_case SET status=?,review_key=?,review_note=?,reviewed_at=CURRENT_TIMESTAMP WHERE after_sales_id=?",state,key,note,id);header(String.valueOf(row.get("order_id")),id,state);event(id,"REVIEW",state,actor,note);return detail(id,null);
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> acceptReturn(String id,Map<String,Object> request,long actor){
        merchants.requireCapability(CommerceShopContext.id(),actor,CommerceCapability.FULFILMENT);String key=key(request.get("requestKey")),condition=String.valueOf(request.get("condition"));
        require(Arrays.asList("SELLABLE","QUALITY_HOLD","DAMAGED").contains(condition),"退货验收状态无效",409);
        Map<String,Object> row=lock(id);if(row.get("return_key")!=null){require(key.equals(row.get("return_key"))&&condition.equals(row.get("return_condition")),"退货已验收，不能更改请求编号或验收状态",409);if(logistics!=null&&request.get("receiptEvidence")!=null)logistics.requireReceiptEvidence(row,request,actor);return detail(id,null);}
        require("AWAITING_RETURN".equals(row.get("status"))&&number(row.get("return_required"))==1,"只有审核通过的已发货售后可以验收返库",409);
        if(number(row.get("return_evidence_version"))>0)require(logistics!=null,"寄回凭证服务尚未就绪",503);
        if(logistics!=null)logistics.requireReceiptEvidence(row,request,actor);
        String orderId=String.valueOf(row.get("order_id")),receipt="RT"+id.substring(2);List<Map<String,Object>> caseItems=items(id);SortedMap<Long,Long> requested=quantities(caseItems);
        List<Map<String,Object>> dispatch=jdbc.queryForList("SELECT l.receipt_id,l.product_id,l.warehouse_id,-SUM(l.delta_quantity) AS quantity FROM commerce_warehouse_ledger l JOIN head_receipt h ON h.systematic_receipt=l.receipt_id WHERE h.original_receipt=? AND h.receipt_type='3' AND l.operation='DISPATCH' GROUP BY l.receipt_id,l.product_id,l.warehouse_id ORDER BY MIN(l.ledger_id),l.product_id,l.warehouse_id",orderId);
        SortedMap<Long,Long> remaining=new TreeMap<>(requested);List<Map<String,Object>> allocations=new ArrayList<>();Set<String> sources=new LinkedHashSet<>();
        for(Map<String,Object> part:dispatch){
            long product=number(part.get("product_id"));if(!remaining.containsKey(product)||remaining.get(product)==0)continue;
            long warehouse=number(part.get("warehouse_id"));require(warehouse>0&&number(part.get("quantity"))>0,"原出库仓库流水无效",409);
            long returned=jdbc.queryForObject("SELECT COALESCE(SUM(quantity),0) FROM commerce_return_allocation WHERE source_receipt_id=? AND product_id=? AND warehouse_id=?",Long.class,part.get("receipt_id"),product,warehouse);
            long available=number(part.get("quantity"))-returned;require(available>=0,"历史退货数量超过原出库流水",409);long taking=Math.min(available,remaining.get(product));
            if(taking>0){allocations.add(map("source",part.get("receipt_id"),"product",product,"warehouse",warehouse,"quantity",taking));remaining.put(product,remaining.get(product)-taking);sources.add(String.valueOf(part.get("receipt_id")));}
        }
        require(remaining.values().stream().allMatch(quantity->quantity==0),"原出库仓库流水不完整或可退数量不足，不能自动返库",409);
        jdbc.update("INSERT INTO commerce_receipt_lock(receipt_id) VALUES (?) ON DUPLICATE KEY UPDATE receipt_id=VALUES(receipt_id)",receipt);require(jdbc.queryForList("SELECT systematic_id FROM head_receipt WHERE systematic_receipt=? FOR UPDATE",receipt).isEmpty(),"退货单号已存在，请核对历史流水",409);
        Map<Long,Map<String,Object>> products=new TreeMap<>(),orderItems=new TreeMap<>();for(Map<String,Object> item:caseItems)orderItems.put(number(item.get("product_id")),item);
        for(Long product:requested.keySet()){List<Map<String,Object>> found=jdbc.queryForList("SELECT * FROM product WHERE product_id=? FOR UPDATE",product);require(found.size()==1,"原商品不存在，不能自动返库",409);products.put(product,found.get(0));}
        for(Long product:requested.keySet())stock.ensureStock(product);
        SortedMap<String,Map<String,Object>> targets=new TreeMap<>();
        for(Map<String,Object> part:allocations){long product=number(part.get("product")),warehouse=number(part.get("warehouse"));String targetKey=product+":"+warehouse;
            if(!targets.containsKey(targetKey)){List<Map<String,Object>> inventory=jdbc.queryForList("SELECT inventory_id,plan_quantity FROM inventory_product WHERE product_id=? AND warehouse_id=? ORDER BY inventory_id FOR UPDATE",product,warehouse);require(!inventory.isEmpty(),"原发货仓库库存记录不存在",409);long before=0;for(Map<String,Object> entry:inventory){require(entry.get("plan_quantity")!=null&&number(entry.get("plan_quantity"))>=0,"原仓库库存数量未知或异常",409);before=Math.addExact(before,number(entry.get("plan_quantity")));}targets.put(targetKey,map("product",product,"warehouse",warehouse,"quantity",0L,"before",before,"inventoryId",inventory.get(0).get("inventory_id")));}
            Map<String,Object> target=targets.get(targetKey);target.put("quantity",Math.addExact(number(target.get("quantity")),number(part.get("quantity"))));
        }
        stock.acceptReturn(orderId,id,requested);String related=String.join(",",sources)+","+receipt;
        for(Map<String,Object> target:targets.values()){
            long product=number(target.get("product")),warehouse=number(target.get("warehouse")),quantity=number(target.get("quantity")),before=number(target.get("before"));Map<String,Object> item=orderItems.get(product),metadata=products.get(product);BigDecimal price=decimal(item.get("unit_price"));
            jdbc.update("UPDATE inventory_product SET plan_quantity=plan_quantity+?,update_by='commerce-return',update_time=CURRENT_TIMESTAMP WHERE inventory_id=?",quantity,target.get("inventoryId"));
            jdbc.update("INSERT INTO detail_receipt(systematic_receipt,product_id,product_specifications,measure_unit,warehousing_id,retrieval_id,supplier_id,customer_id,current_inventory,actual_inventory,plan_quantity,univalence,discount,money,cost,remarks) VALUES (?,?,?,?,?,0,0,0,?,?,?,?,100,?,?,?)",receipt,product,item.get("spec"),metadata.get("measure_unit"),warehouse,before,Math.addExact(before,quantity),quantity,price,price.multiply(BigDecimal.valueOf(quantity)),metadata.get("cost_price"),"商城部分退货验收："+condition);
            jdbc.update("INSERT INTO commerce_warehouse_ledger(event_key,receipt_id,related_receipts,operation,product_id,warehouse_id,delta_quantity,before_quantity,after_quantity,created_at) VALUES (?,?,?,'RETURN_ACCEPT',?,?,?,?,?,CURRENT_TIMESTAMP)","RETURN:"+id+":"+product+":"+warehouse,receipt,related,product,warehouse,quantity,before,Math.addExact(before,quantity));
        }
        for(Map<String,Object> part:allocations)jdbc.update("INSERT INTO commerce_return_allocation(after_sales_id,source_receipt_id,product_id,warehouse_id,quantity) VALUES (?,?,?,?,?)",id,part.get("source"),part.get("product"),part.get("warehouse"),part.get("quantity"));
        for(Map.Entry<Long,Long> item:requested.entrySet())jdbc.update("UPDATE product SET inventory_qty=inventory_qty+?,update_by='commerce-return',update_time=CURRENT_TIMESTAMP WHERE product_id=?",item.getValue(),item.getKey());
        // All original physical rows now match the aggregate return. Block non-sellable units
        // before commit; other requests cannot observe temporary availability inside this transaction.
        for(Map<String,Object> target:targets.values())recordReturnCondition(id,target,condition,actor);
        String original=sources.size()==1?sources.iterator().next():orderId;
        jdbc.update("INSERT INTO head_receipt(systematic_receipt,original_receipt,receipt_category,receipt_type,receipt_status,invoice_date,warehousing_ids,retrieval_ids,user_ids,supplier_ids,customer_ids,deposit,total_amount,receipt_notes,create_by,create_time) VALUES (?,?,'2','4','2',CURRENT_DATE,?,0,?,0,0,0,?,?,'commerce-return',CURRENT_TIMESTAMP)",receipt,original,targets.values().iterator().next().get("warehouse"),actor,row.get("refund_amount"),"商城部分退货验收；按原批次原仓返库，退款另行执行");
        jdbc.update("UPDATE commerce_after_sales_case SET status='RETURN_RECEIVED',return_key=?,return_receipt_id=?,return_condition=?,returned_at=CURRENT_TIMESTAMP WHERE after_sales_id=?",key,receipt,condition,id);header(orderId,id,"RETURN_RECEIVED");event(id,"RETURN","RETURN_RECEIVED",actor,condition+"：按原发货批次仓库返库");
        if(costs!=null)costs.recordReturn(id,actor);
        return detail(id,null);
    }

    private void recordReturnCondition(String id,Map<String,Object> target,String condition,long actor){
        long product=number(target.get("product")),warehouse=number(target.get("warehouse")),quantity=number(target.get("quantity"));
        jdbc.update("INSERT INTO commerce_warehouse_condition(product_id,warehouse_id,quality_hold,damaged) VALUES (?,?,0,0) ON DUPLICATE KEY UPDATE product_id=VALUES(product_id)",product,warehouse);
        Map<String,Object> prior=jdbc.queryForMap("SELECT quality_hold,damaged FROM commerce_warehouse_condition WHERE product_id=? AND warehouse_id=? FOR UPDATE",product,warehouse);
        long quality=number(prior.get("quality_hold")),damaged=number(prior.get("damaged"));
        require(quality>=0&&damaged>=0&&Math.addExact(quality,damaged)<=number(target.get("before")),"原仓不可售库存异常，不能验收返库",409);
        long afterQuality=Math.addExact(quality,"QUALITY_HOLD".equals(condition)?quantity:0),afterDamaged=Math.addExact(damaged,"DAMAGED".equals(condition)?quantity:0);
        String event=newId("IC"),request="RETURN:"+id+":"+warehouse+":"+product;
        if(!"SELLABLE".equals(condition))stock.adjustUnavailable(product,"CONDITION:"+event,quantity,"退货验收："+condition);
        jdbc.update("UPDATE commerce_warehouse_condition SET quality_hold=?,damaged=? WHERE product_id=? AND warehouse_id=?",afterQuality,afterDamaged,product,warehouse);
        jdbc.update("INSERT INTO commerce_condition_event(event_id,shop_id,request_key,request_hash,product_id,warehouse_id,from_state,to_state,quantity,before_quality,after_quality,before_damaged,after_damaged,reason,actor_id,created_at) VALUES (?,?,?,?,?,?,'RETURNED',?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",event,CommerceShopContext.id(),request,conditionHash(request,condition,quantity),product,warehouse,condition,quantity,quality,afterQuality,damaged,afterDamaged,"售后 "+id+" 按原仓验收",actor);
    }
    private static String conditionHash(String request,String condition,long quantity){
        try {byte[] digest=MessageDigest.getInstance("SHA-256").digest((request+":"+condition+":"+quantity).getBytes(StandardCharsets.UTF_8));StringBuilder value=new StringBuilder();for(byte b:digest)value.append(String.format("%02x",b&0xff));return value.toString();}
        catch(java.security.NoSuchAlgorithmException exception){throw new IllegalStateException(exception);}
    }

    public Map<String,Object> refund(String id,Map<String,Object> request,long actor){
        merchants.requireCapability(CommerceShopContext.id(),actor,CommerceCapability.REFUND_EXECUTE);
        String key=key(request.get("requestKey")),scenario=String.valueOf(request.get("scenario"));
        Map<String,Object> operation=payments.refund(id,key,scenario,actor,()->{
            Map<String,Object> row=lock(id);if(!"REFUNDED".equals(row.get("status")))validateRefund(row);return row;
        },this::providerRefundSucceeded);
        Map<String,Object> result=refundResult(id,String.valueOf(operation.get("local_status")));
        if(operation.get("operationId")!=null)result.put("refundOperation",operation);return result;
    }

    private void validateRefund(Map<String,Object> row){
        require("APPROVED".equals(row.get("status"))||"RETURN_RECEIVED".equals(row.get("status")),"退款前须完成审核及必要的退货验收",409);
        require(number(row.get("return_required"))==0||"RETURN_RECEIVED".equals(row.get("status")),"已发货商品必须先验收返库",409);
        String orderId=String.valueOf(row.get("order_id"));Map<String,Object> order=order(orderId,null,false);BigDecimal amount=decimal(row.get("refund_amount"));
        require(decimal(order.get("refunded_amount")).add(amount).compareTo(decimal(order.get("total_amount")))<=0,"累计退款金额超过已付订单金额",409);
    }

    /** Called only by the verified provider result processor, in its atomic business transaction. */
    public void providerRefundSucceeded(String id,String refundId,long actor){
        Map<String,Object> row=lock(id);if("REFUNDED".equals(row.get("status")))return;
        validateRefund(row);String orderId=String.valueOf(row.get("order_id"));Map<String,Object> order=order(orderId,null,false);BigDecimal amount=decimal(row.get("refund_amount"));
            if(number(row.get("return_required"))==0)releaseUnshipped(row);
            SortedMap<Long,Long> requested=quantities(items(id));for(Map.Entry<Long,Long> part:requested.entrySet()){
                stock.prepareLine(orderId,part.getKey());require(jdbc.update("UPDATE commerce_fulfillment_line SET refunded=refunded+? WHERE order_id=? AND product_id=? AND refunded+?<=shipped+released",part.getValue(),orderId,part.getKey(),part.getValue())==1,"商品累计退款数量超过履约数量",409);
            }
            jdbc.update("UPDATE commerce_after_sales_case SET status='REFUNDED',refunded_amount=refund_amount,refund_id=?,refunded_at=CURRENT_TIMESTAMP WHERE after_sales_id=?",refundId,id);
            require(jdbc.update("UPDATE commerce_order SET refunded_amount=refunded_amount+? WHERE order_id=? AND refunded_amount+?<=total_amount",amount,orderId,amount)==1,"订单退款余额已变化",409);
            boolean full=decimal(order.get("refunded_amount")).add(amount).compareTo(decimal(order.get("total_amount")))==0;header(orderId,id,full?"REFUNDED":"PARTIALLY_REFUNDED");
            long unshipped=jdbc.queryForObject("SELECT COALESCE(SUM(i.quantity-f.shipped-f.released),0) FROM commerce_order_item i JOIN commerce_fulfillment_line f ON f.order_id=i.order_id AND f.product_id=i.product_id WHERE i.order_id=?",Long.class,orderId);
            long shipped=jdbc.queryForObject("SELECT COALESCE(SUM(shipped),0) FROM commerce_fulfillment_line WHERE order_id=?",Long.class,orderId);if(unshipped==0&&shipped>0)jdbc.update("UPDATE commerce_order SET status=2 WHERE order_id=? AND status=1",orderId);
            event(id,"REFUND","REFUNDED",actor,"本地沙箱退款成功；按本售后金额累计");
    }

    private void releaseUnshipped(Map<String,Object> row){
        String orderId=String.valueOf(row.get("order_id"));Map<String,Object> order=order(orderId,null,false);boolean active=false;SortedMap<Long,Long> requested=quantities(items(String.valueOf(row.get("after_sales_id"))));
        if(order.get("activity_id")!=null){
            List<Map<String,Object>> activities=jdbc.queryForList("SELECT * FROM commerce_activity WHERE activity_id=? AND shop_id=? FOR UPDATE",order.get("activity_id"),CommerceShopContext.id());require(activities.size()==1,"原活动不存在",409);
            Object end=activities.get(0).get("ends_at");LocalDateTime deadline=end instanceof java.sql.Timestamp?((java.sql.Timestamp)end).toLocalDateTime():LocalDateTime.parse(String.valueOf(end).replace(' ','T'));active=deadline.isAfter(LocalDateTime.now());
            long quantity=requested.values().stream().mapToLong(Long::longValue).sum();require(jdbc.update("UPDATE commerce_activity SET remaining=remaining+? WHERE activity_id=? AND remaining+?<=capacity",quantity,order.get("activity_id"),quantity)==1,"原活动剩余配额异常",409);
        }
        delivery.releaseOrder(orderId,"REFUND:"+row.get("after_sales_id"),requested.values().stream().mapToLong(Long::longValue).sum(),active);
        stock.release(orderId,requested,"REFUND:"+row.get("after_sales_id"),"AFTER_SALES_REFUND",active);
    }
    private Map<String,Object> refundResult(String id,String outcome){Map<String,Object> result=detail(id,null);result.put("refundOutcome",outcome);return result;}
    private void merchant(long actor){merchants.requireShop(CommerceShopContext.id(),actor,true);}
    private Map<String,Object> lock(String id){Map<String,Object> row=find(id,null,false);order(String.valueOf(row.get("order_id")),null,true);return find(id,null,true);}
    private Map<String,Object> order(String id,String owner,boolean lock){require(id!=null&&id.matches("SC[0-9]{14}[A-F0-9]{10}"),"订单不存在",404);List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM commerce_order WHERE order_id=? AND shop_id=?"+(lock?" FOR UPDATE":""),id,CommerceShopContext.id());require(rows.size()==1&&(owner==null||owner.equals(rows.get(0).get("owner_id"))),"订单不存在",404);return rows.get(0);}
    private Map<String,Object> find(String id,String ownerId,boolean lock){require(id!=null&&id.matches("AS[0-9]{14}[A-F0-9]{10}"),"售后不存在",404);String owner=ownerId==null?null:owner(ownerId);List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM commerce_after_sales_case WHERE after_sales_id=? AND shop_id=?"+(lock?" FOR UPDATE":""),id,CommerceShopContext.id());require(rows.size()==1&&(owner==null||owner.equals(rows.get(0).get("owner_id"))),"售后不存在",404);return rows.get(0);}
    private List<Map<String,Object>> items(String id){return jdbc.queryForList("SELECT * FROM commerce_after_sales_item WHERE after_sales_id=? ORDER BY product_id",id);}
    private SortedMap<Long,Long> quantities(List<Map<String,Object>> items){SortedMap<Long,Long> result=new TreeMap<>();for(Map<String,Object> item:items)result.put(number(item.get("product_id")),number(item.get("quantity")));return result;}
    private void header(String order,String id,String state){jdbc.update("UPDATE commerce_order SET after_sales_id=?,after_sales_status=? WHERE order_id=?",id,state,order);}
    private void event(String id,String action,String status,Long actor,String note){jdbc.update("INSERT INTO commerce_after_sales_event(event_key,after_sales_id,event_type,actor_user_id,note,created_at) VALUES (?,?,?,?,?,CURRENT_TIMESTAMP)",action+":"+id,id,status,actor,note);}
    private Map<String,Object> shape(Map<String,Object> row,boolean history){
        String id=String.valueOf(row.get("after_sales_id")),status=String.valueOf(row.get("status"));List<Map<String,Object>> lines=new ArrayList<>();
        for(Map<String,Object> item:items(id))lines.add(map("productId",item.get("product_id"),"productCode",item.get("product_code"),"productName",item.get("product_name"),"spec",item.get("spec"),"quantity",item.get("quantity"),"unitPrice",item.get("unit_price"),"amount",item.get("amount")));
        List<String> actions=new ArrayList<>();if("REQUESTED".equals(status))actions.add("REVIEW");if("AWAITING_RETURN".equals(status))actions.add("ACCEPT_RETURN");if("APPROVED".equals(status)||"RETURN_RECEIVED".equals(status))actions.add("SANDBOX_REFUND");
        Map<String,Object> result=map("afterSalesId",id,"orderId",row.get("order_id"),"tenantId",TenantContext.id(),"shopId",row.get("shop_id"),"status",status,"statusName",states().get(status),"scope","ORDER_LINES","kind",row.get("kind"),"reason",row.get("reason"),"returnRequired",number(row.get("return_required"))==1,"returnCondition",row.get("return_condition"),"originalOrderStatus",row.get("original_order_status"),"refundAmount",row.get("refund_amount"),"refundedAmount",row.get("refunded_amount"),"reviewNote",row.get("review_note"),"returnReceiptId",row.get("return_receipt_id"),"refundId",row.get("refund_id"),"createdAt",time(row.get("created_at")),"reviewedAt",time(row.get("reviewed_at")),"returnedAt",time(row.get("returned_at")),"refundedAt",time(row.get("refunded_at")),"items",lines,"availableActions",actions,"paymentProvider","LOCAL_SANDBOX");
        Map<String,Object> parcel=logistics==null?map("registered",false,"status",number(row.get("return_required"))==1?"NOT_REGISTERED":"NOT_REQUIRED","carrierCode",null,"trackingNo",null,"registeredAt",null):logistics.parcelSummary(row);
        result.put("returnParcel",parcel);result.put("returnEvidenceRequired",number(row.get("return_required"))==1&&number(row.get("return_evidence_version"))>0);
        result.put("receiptEvidence",logistics==null?null:logistics.receiptEvidence(id));
        if("AWAITING_RETURN".equals(status)&&!Boolean.TRUE.equals(parcel.get("registered")))actions.add("REGISTER_RETURN");
        List<String> refundIds=jdbc.queryForList("SELECT operation_id FROM commerce_payment_operation WHERE after_sales_id=? AND kind='REFUND' ORDER BY CASE WHEN provider_reference=? THEN 0 WHEN local_status IN('PREPARED','PENDING','UNKNOWN') THEN 1 ELSE 2 END,created_at DESC,operation_id DESC LIMIT 1",String.class,id,row.get("refund_id"));
        if(!refundIds.isEmpty()) {
            Map<String,Object> refund=payments.detail(refundIds.get(0));result.put("refundOperation",refund);result.put("refundOutcome",refund.get("outcome"));
            if(Arrays.asList("PREPARED","PENDING","UNKNOWN").contains(refund.get("outcome"))){actions.remove("SANDBOX_REFUND");actions.add("QUERY_REFUND");}
        }
        if(history)result.put("events",jdbc.queryForList("SELECT event_type AS eventType,actor_user_id AS actorUserId,note,created_at AS createdAt FROM commerce_after_sales_event WHERE after_sales_id=? ORDER BY event_id",id));return result;
    }
    private static Map<String,String> states(){Map<String,String> result=new LinkedHashMap<>();result.put("REQUESTED","待商家审核");result.put("APPROVED","审核通过·待沙箱退款");result.put("AWAITING_RETURN","审核通过·待退货验收");result.put("RETURN_RECEIVED","已验收返库·待沙箱退款");result.put("REFUNDED","沙箱退款完成");result.put("REJECTED","商家已拒绝");return result;}
    private static String newId(String prefix){return prefix+LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))+UUID.randomUUID().toString().replace("-","").substring(0,10).toUpperCase(Locale.ROOT);}
    private static String owner(Object value){require(value instanceof String&&((String)value).matches("[a-f0-9]{64}"),"访客标识错误",400);return (String)value;}
    private static String key(Object value){require(value instanceof String&&((String)value).matches("[A-Za-z0-9_-]{1,80}"),"请求编号格式错误",400);return (String)value;}
    private static String text(Object value,int min,int max,String label){require(value instanceof String,label+"格式错误",400);String result=((String)value).trim();require(result.length()>=min&&result.length()<=max,label+"长度无效",400);return result;}
    private static String time(Object value){return value==null?null:String.valueOf(value).replace(".0","");}
    private static long number(Object value){return value==null?0:((Number)value).longValue();}
    private static BigDecimal decimal(Object value){return new BigDecimal(String.valueOf(value));}
    private static void require(boolean condition,String message,int code){if(!condition)throw new ServiceException(message,code);}
    private static Map<String,Object> map(Object...pairs){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
}

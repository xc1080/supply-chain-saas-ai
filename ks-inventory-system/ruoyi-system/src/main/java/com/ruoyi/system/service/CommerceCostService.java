package com.ruoyi.system.service;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.core.tenant.CommerceShopContext;
import com.ruoyi.common.exception.ServiceException;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Document cost snapshots and a sandbox statement reconciliation. This is not a valuation engine
 * or accounting general ledger. Hooks join the caller's stock transaction; reads never backfill
 * historical cost from today's master data. Statement imports never alter payment/order records. */
@Service
@Profile({"local","commerce"})
public class CommerceCostService {
    private final DataSource source;
    private final JdbcTemplate jdbc;
    private final CommerceMerchantService merchants;

    public CommerceCostService(DataSource source) {
        this.source=source;this.jdbc=new JdbcTemplate(source);this.merchants=new CommerceMerchantService(source);
    }

    public void initializeSchema() {
        ResourceDatabasePopulator script=new ResourceDatabasePopulator(new ClassPathResource("db/commerce-cost-reconciliation.sql"));
        script.setSqlScriptEncoding("UTF-8");script.execute(source);
    }

    /** V12 extension; the already-applied V10 resource stays byte-for-byte unchanged. */
    public void initializeProcurementSchema() {
        ResourceDatabasePopulator script=new ResourceDatabasePopulator(new ClassPathResource("db/commerce-procurement-cost.sql"));
        script.setSqlScriptEncoding("UTF-8");script.execute(source);
    }

    /** Call after ERP SAVE, including an approved -> draft transition. Drafts create no purchase. */
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void recordProcurementReceipt(String receiptId,long actor) {
        receiptId=id(receiptId,32,"入库单号");
        List<Map<String,Object>> heads=jdbc.queryForList("SELECT receipt_category,receipt_type,receipt_status FROM head_receipt WHERE systematic_receipt=?",receiptId);
        if(!heads.isEmpty()&&"1".equals(String.valueOf(heads.get(0).get("receipt_category")))
                &&"2".equals(String.valueOf(heads.get(0).get("receipt_type")))&&"2".equals(String.valueOf(heads.get(0).get("receipt_status")))) {
            recordSupplierReturn(receiptId,actor);return;
        }
        boolean posted=!heads.isEmpty()&&"1".equals(String.valueOf(heads.get(0).get("receipt_category")))
                &&"1".equals(String.valueOf(heads.get(0).get("receipt_type")))&&"2".equals(String.valueOf(heads.get(0).get("receipt_status")));
        if(!posted){reverseProcurementReceipt(receiptId,actor);return;}
        boolean sourceSchema=hasProcurementSources();
        String sourceSelect=sourceSchema?",pl.receipt_line_id":"",sourceJoin=sourceSchema?" LEFT JOIN commerce_purchase_receipt_line pl ON pl.detail_id=d.systematic_id AND pl.receipt_id=d.systematic_receipt":"";
        List<Map<String,Object>> lines=jdbc.queryForList("SELECT d.*,COALESCE(d.warehousing_id,h.warehousing_ids) AS warehouse_id,ps.shop_id"+sourceSelect+" FROM detail_receipt d JOIN head_receipt h ON h.systematic_receipt=d.systematic_receipt JOIN commerce_product_shop ps ON ps.product_id=d.product_id"+sourceJoin+" WHERE d.systematic_receipt=? ORDER BY d.product_id,warehouse_id,"+(sourceSchema?"pl.receipt_line_id":"d.systematic_id"),receiptId);
        if(lines.isEmpty())return; // Private ERP goods outside the commerce catalog have no commerce cost book.
        List<Map<String,Object>> canonical=new ArrayList<>();
        for(Map<String,Object> line:lines){Map<String,Object> snapshot=map("shop",line.get("shop_id"),"product",line.get("product_id"),"warehouse",line.get("warehouse_id"),"quantity",line.get("plan_quantity"),"unitCost",purchaseCost(line));
            if(line.get("receipt_line_id")!=null)snapshot.put("sourceLineId",line.get("receipt_line_id"));canonical.add(snapshot);}
        String hash=hash(canonical);
        List<Map<String,Object>> states=jdbc.queryForList("SELECT * FROM commerce_cost_receipt WHERE receipt_id=? FOR UPDATE",receiptId);
        if(!states.isEmpty()&&number(states.get(0).get("active"))==1&&hash.equals(states.get(0).get("content_hash")))return;
        long revision=states.isEmpty()?1:Math.addExact(number(states.get(0).get("revision")),1);
        if(!states.isEmpty()&&number(states.get(0).get("active"))==1)reverseRevision(receiptId,revision,actor);
        for(int index=0;index<lines.size();index++){
            Map<String,Object> line=lines.get(index);long quantity=number(line.get("plan_quantity"));
            require(quantity>0,"已审核采购单数量无效",409);
            String event="PURCHASE:"+receiptId+":"+revision+":"+index;
            insert(event,String.valueOf(line.get("shop_id")),"PURCHASE",receiptId,receiptId,null,null,
                    number(line.get("product_id")),number(line.get("warehouse_id")),quantity,purchaseCost(line),"PURCHASE_DOCUMENT",actor);
            if(line.get("receipt_line_id")!=null)jdbc.update("INSERT INTO commerce_cost_source_line(receipt_line_id,receipt_revision,entry_id) VALUES (?,?,?)",line.get("receipt_line_id"),revision,jdbc.queryForObject("SELECT entry_id FROM commerce_cost_entry WHERE event_key=?",Long.class,event));
        }
        if(states.isEmpty())jdbc.update("INSERT INTO commerce_cost_receipt VALUES (?,?,?,1,CURRENT_TIMESTAMP)",receiptId,revision,hash);
        else jdbc.update("UPDATE commerce_cost_receipt SET revision=?,content_hash=?,active=1,updated_at=CURRENT_TIMESTAMP WHERE receipt_id=?",revision,hash,receiptId);
    }

    /** Call after ERP DELETE; old immutable entries retain enough evidence to reverse deleted rows. */
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void reverseProcurementReceipt(String receiptId,long actor) {
        receiptId=id(receiptId,32,"入库单号");
        List<Map<String,Object>> states=jdbc.queryForList("SELECT * FROM commerce_cost_receipt WHERE receipt_id=? FOR UPDATE",receiptId);
        if(states.isEmpty()||number(states.get(0).get("active"))==0)return;
        long revision=Math.addExact(number(states.get(0).get("revision")),1);
        reverseRevision(receiptId,revision,actor);
        jdbc.update("UPDATE commerce_cost_receipt SET revision=?,active=0,updated_at=CURRENT_TIMESTAMP WHERE receipt_id=?",revision,receiptId);
    }

    private void reverseRevision(String receipt,long revision,long actor) {
        for(Map<String,Object> entry:jdbc.queryForList("SELECT e.* FROM commerce_cost_entry e WHERE e.receipt_id=? AND e.event_type IN ('PURCHASE','SUPPLIER_RETURN') AND NOT EXISTS(SELECT 1 FROM commerce_cost_entry r WHERE r.origin_entry_id=e.entry_id AND r.event_type IN ('PURCHASE_REVERSAL','SUPPLIER_RETURN_REVERSAL')) ORDER BY e.entry_id",receipt)) {
            String type=String.valueOf(entry.get("event_type"))+"_REVERSAL";
            insert(type+":"+receipt+":"+revision+":"+entry.get("entry_id"),String.valueOf(entry.get("shop_id")),type,receipt,receipt,null,number(entry.get("entry_id")),
                    number(entry.get("product_id")),number(entry.get("warehouse_id")),-number(entry.get("quantity")),knownCost(entry.get("unit_cost")),"REVERSE_DOCUMENT",actor);
        }
    }

    private void recordSupplierReturn(String receipt,long actor) {
        boolean sources=hasProcurementSources();
        String sourceSelect=sources?",pl.receipt_line_id,pl.source_receipt_line_id":"",sourceJoin=sources?" LEFT JOIN commerce_purchase_receipt_line pl ON pl.detail_id=d.systematic_id AND pl.receipt_id=d.systematic_receipt":"";
        List<Map<String,Object>> lines=jdbc.queryForList("SELECT d.*,d.retrieval_id AS warehouse_id,ps.shop_id"+sourceSelect+" FROM detail_receipt d JOIN commerce_product_shop ps ON ps.product_id=d.product_id"+sourceJoin+" WHERE d.systematic_receipt=? ORDER BY "+(sources?"pl.receipt_line_id":"d.systematic_id"),receipt);
        if(lines.isEmpty())return;
        List<Map<String,Object>> canonical=new ArrayList<>();
        List<Map<String,Object>> origins=new ArrayList<>();
        for(Map<String,Object> line:lines) {
            List<Map<String,Object>> evidence=sources&&line.get("source_receipt_line_id")!=null
                    ?jdbc.queryForList("SELECT e.* FROM commerce_cost_source_line s JOIN commerce_cost_entry e ON e.entry_id=s.entry_id JOIN commerce_cost_receipt c ON c.receipt_id=e.receipt_id AND c.revision=s.receipt_revision AND c.active=1 WHERE s.receipt_line_id=? AND e.event_type='PURCHASE' AND NOT EXISTS(SELECT 1 FROM commerce_cost_entry r WHERE r.origin_entry_id=e.entry_id AND r.event_type='PURCHASE_REVERSAL')",line.get("source_receipt_line_id"))
                    :Collections.emptyList();
            require(evidence.size()<=1,"采购来源成本快照存在重复，请先核对",409);
            Map<String,Object> origin=evidence.isEmpty()?null:evidence.get(0);
            if(origin!=null)require(Objects.equals(line.get("shop_id"),origin.get("shop_id"))&&number(line.get("product_id"))==number(origin.get("product_id"))&&number(line.get("warehouse_id"))==number(origin.get("warehouse_id")),"退供来源成本凭证与货品及原仓不一致",409);
            origins.add(origin);
            Map<String,Object> snapshot=map("shop",line.get("shop_id"),"product",line.get("product_id"),"warehouse",line.get("warehouse_id"),"quantity",line.get("plan_quantity"),"sourceLineId",line.get("receipt_line_id"),"originSourceLineId",line.get("source_receipt_line_id"),"originEntryId",origin==null?null:origin.get("entry_id"));
            canonical.add(snapshot);
        }
        SortedSet<Long> originIds=new TreeSet<>();
        for(Map<String,Object> origin:origins)if(origin!=null)originIds.add(number(origin.get("entry_id")));
        for(Long origin:originIds)jdbc.queryForList("SELECT entry_id FROM commerce_cost_entry WHERE entry_id=? FOR UPDATE",origin);
        String content=hash(canonical);
        List<Map<String,Object>> states=jdbc.queryForList("SELECT * FROM commerce_cost_receipt WHERE receipt_id=? FOR UPDATE",receipt);
        if(!states.isEmpty()&&number(states.get(0).get("active"))==1&&content.equals(states.get(0).get("content_hash")))return;
        long revision=states.isEmpty()?1:Math.addExact(number(states.get(0).get("revision")),1);
        if(!states.isEmpty()&&number(states.get(0).get("active"))==1)reverseRevision(receipt,revision,actor);
        for(int position=0;position<lines.size();position++) {
            Map<String,Object> line=lines.get(position),origin=origins.get(position);long quantity=number(line.get("plan_quantity"));
            require(quantity>0,"已审核退供单数量无效",409);
            if(origin!=null) {
                long committed=jdbc.queryForObject("SELECT -COALESCE(SUM(e.quantity+COALESCE((SELECT SUM(r.quantity) FROM commerce_cost_entry r WHERE r.origin_entry_id=e.entry_id AND r.event_type='SUPPLIER_RETURN_REVERSAL'),0)),0) FROM commerce_cost_entry e WHERE e.event_type='SUPPLIER_RETURN' AND e.origin_entry_id=?",Long.class,origin.get("entry_id"));
                require(Math.addExact(committed,quantity)<=number(origin.get("quantity")),"退供成本数量超过原采购快照",409);
            }
            insert("SUPPLIER_RETURN:"+receipt+":"+revision+":"+position,String.valueOf(line.get("shop_id")),"SUPPLIER_RETURN",receipt,receipt,null,origin==null?null:number(origin.get("entry_id")),
                    number(line.get("product_id")),number(line.get("warehouse_id")),-quantity,origin==null?null:knownCost(origin.get("unit_cost")),origin==null?"MISSING_ORIGINAL_SNAPSHOT":"ORIGINAL_PURCHASE",actor);
        }
        if(states.isEmpty())jdbc.update("INSERT INTO commerce_cost_receipt VALUES (?,?,?,1,CURRENT_TIMESTAMP)",receipt,revision,content);
        else jdbc.update("UPDATE commerce_cost_receipt SET revision=?,content_hash=?,active=1,updated_at=CURRENT_TIMESTAMP WHERE receipt_id=?",revision,content,receipt);
    }

    private boolean hasProcurementSources() {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>)connection->{
            try(java.sql.ResultSet sources=connection.getMetaData().getColumns(connection.getCatalog(),null,"commerce_purchase_receipt_line","receipt_line_id");
                java.sql.ResultSet mappings=connection.getMetaData().getColumns(connection.getCatalog(),null,"commerce_cost_source_line","entry_id")) {
                return sources.next()&&mappings.next();
            }
        }));
    }

    private static BigDecimal purchaseCost(Map<String,Object> line) {
        BigDecimal price=knownCost(line.get("univalence"));if(price==null)return null;
        try {
            BigDecimal discount=line.get("discount")==null?BigDecimal.ONE:new BigDecimal(String.valueOf(line.get("discount")));
            if(discount.compareTo(new BigDecimal("100"))==0)discount=BigDecimal.ONE;
            if(discount.signum()<0||discount.compareTo(BigDecimal.ONE)>0)return null;
            return knownCost(price.multiply(discount));
        }catch(NumberFormatException invalid){return null;}
    }

    /** Shipment receipt.cost is captured once. Later sales price or master cost changes cannot rewrite it. */
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void recordShipment(String shipmentId,long actor) {
        shipmentId=id(shipmentId,40,"发货编号");
        List<Map<String,Object>> shipments=jdbc.queryForList("SELECT s.*,o.shop_id FROM commerce_shipment s JOIN commerce_order o ON o.order_id=s.order_id WHERE s.shipment_id=?",shipmentId);
        require(!shipments.isEmpty(),"发货记录不存在",404);Map<String,Object> shipment=shipments.get(0);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM commerce_cost_entry WHERE event_type='DISPATCH' AND source_id=?",Long.class,shipmentId)>0)return;
        String receipt=String.valueOf(shipment.get("receipt_id")),shop=String.valueOf(shipment.get("shop_id"));
        List<Map<String,Object>> lines=jdbc.queryForList("SELECT systematic_id,product_id,retrieval_id AS warehouse_id,plan_quantity,cost FROM detail_receipt WHERE systematic_receipt=? ORDER BY systematic_id",receipt);
        require(!lines.isEmpty(),"发货单缺少成本快照凭证",409);
        Map<Long,Long> quantities=new TreeMap<>();int index=0;
        for(Map<String,Object> line:lines){long quantity=number(line.get("plan_quantity")),product=number(line.get("product_id"));require(quantity>0,"发货单数量无效",409);
            quantities.put(product,Math.addExact(quantities.getOrDefault(product,0L),quantity));
            insert("COST_DISPATCH:"+shipmentId+":"+index++,shop,"DISPATCH",shipmentId,receipt,String.valueOf(shipment.get("order_id")),null,product,
                    number(line.get("warehouse_id")),-quantity,knownCost(line.get("cost")),"DISPATCH_DOCUMENT",actor);
        }
        Map<Long,Long> expected=new TreeMap<>();
        for(Map<String,Object> item:jdbc.queryForList("SELECT product_id,quantity FROM commerce_shipment_item WHERE shipment_id=?",shipmentId))expected.put(number(item.get("product_id")),number(item.get("quantity")));
        require(expected.equals(quantities),"发货单成本凭证与发货数量不一致",409);
    }

    /** Return uses original dispatch entries, never the current product cost or return receipt.cost. */
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void recordReturn(String afterSalesId,long actor) {
        afterSalesId=id(afterSalesId,32,"售后编号");
        List<Map<String,Object>> cases=jdbc.queryForList("SELECT * FROM commerce_after_sales_case WHERE after_sales_id=?",afterSalesId);
        require(!cases.isEmpty(),"售后记录不存在",404);Map<String,Object> row=cases.get(0);
        require(row.get("return_receipt_id")!=null,"退货尚未验收入库",409);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM commerce_cost_entry WHERE event_type='RETURN' AND source_id=?",Long.class,afterSalesId)>0)return;
        String shop=String.valueOf(row.get("shop_id")),order=String.valueOf(row.get("order_id")),receipt=String.valueOf(row.get("return_receipt_id"));int missing=0;
        for(Map<String,Object> allocation:jdbc.queryForList("SELECT * FROM commerce_return_allocation WHERE after_sales_id=? ORDER BY source_receipt_id,product_id,warehouse_id",afterSalesId)){
            long remaining=number(allocation.get("quantity")),product=number(allocation.get("product_id")),warehouse=number(allocation.get("warehouse_id"));
            List<Map<String,Object>> originals=jdbc.queryForList("SELECT * FROM commerce_cost_entry WHERE shop_id=? AND order_id=? AND event_type='DISPATCH' AND receipt_id=? AND product_id=? AND warehouse_id=? ORDER BY entry_id",shop,order,allocation.get("source_receipt_id"),product,warehouse);
            for(Map<String,Object> original:originals){
                long returned=jdbc.queryForObject("SELECT COALESCE(SUM(quantity),0) FROM commerce_cost_entry WHERE event_type='RETURN' AND origin_entry_id=?",Long.class,original.get("entry_id"));
                long take=Math.min(remaining,-number(original.get("quantity"))-returned);if(take<=0)continue;
                insert("COST_RETURN:"+afterSalesId+":"+original.get("entry_id"),shop,"RETURN",afterSalesId,receipt,order,number(original.get("entry_id")),product,warehouse,take,knownCost(original.get("unit_cost")),"ORIGINAL_DISPATCH",actor);remaining-=take;if(remaining==0)break;
            }
            if(remaining>0){require(originals.isEmpty(),"退货数量超过原出库成本快照",409);
                insert("COST_RETURN:"+afterSalesId+":UNKNOWN:"+missing++,shop,"RETURN",afterSalesId,receipt,order,null,product,warehouse,remaining,null,"MISSING_ORIGINAL_SNAPSHOT",actor);
            }
        }
    }

    @Transactional(readOnly=true)
    public Map<String,Object> ledger(long actor,int limit) {
        authority(actor);require(limit>=1&&limit<=200,"成本查询数量需为1至200",400);
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT e.entry_id AS entryId,e.event_type AS eventType,e.source_id AS sourceId,e.receipt_id AS receiptId,e.order_id AS orderId,e.origin_entry_id AS originEntryId,e.product_id AS productId,p.product_code AS productCode,p.product_name AS productName,e.warehouse_id AS warehouseId,e.quantity,e.unit_cost AS unitCost,e.amount,e.cost_status AS costStatus,e.cost_basis AS costBasis,e.actor_id AS actorId,e.created_at AS createdAt FROM commerce_cost_entry e LEFT JOIN product p ON p.product_id=e.product_id WHERE e.shop_id=? ORDER BY e.entry_id DESC LIMIT ?",shop(),limit);
        for(Map<String,Object> row:rows)row.put("createdAt",time(row.get("createdAt")));
        long unknown=jdbc.queryForObject("SELECT COUNT(*) FROM commerce_cost_entry WHERE shop_id=? AND cost_status='UNKNOWN'",Long.class,shop());
        return map("basis","DOCUMENT_SNAPSHOT","currency","CNY","entries",rows,"unknownEntries",unknown,"historicalCoverage","FROM_MIGRATION_FORWARD_NO_MASTER_BACKFILL");
    }

    /** Append a sandbox statement observation. Re-import a correction using a new requestKey. */
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> importObservation(Map<String,Object> body,long actor) {
        authority(actor);require(body!=null,"导入内容不能为空",400);String key=id(body.get("requestKey"),80,"请求编号"),operation=id(body.get("operationId"),40,"支付操作编号");
        String observed=text(body.get("status"),20,"观测状态"),reference=text(body.get("sourceReference"),200,"对账资料编号");
        require(Arrays.asList("SUCCEEDED","FAILED","PENDING","MISSING").contains(observed),"观测状态错误",400);
        BigDecimal amount=money(body.get("amount"));String payload=hash(map("operationId",operation,"status",observed,"amount",amount,"sourceReference",reference));
        Map<String,Object> op=operation(operation,true);
        List<Map<String,Object>> old=jdbc.queryForList("SELECT * FROM commerce_statement_observation WHERE shop_id=? AND request_key=?",shop(),key);
        if(!old.isEmpty()){require(payload.equals(old.get(0).get("payload_hash")),"同一导入请求不能修改观测内容",409);return observation(old.get(0));}
        long revision=jdbc.queryForObject("SELECT COALESCE(MAX(sequence_no),0)+1 FROM commerce_statement_observation WHERE operation_id=?",Long.class,operation);
        String id=newId("OBS");
        try {jdbc.update("INSERT INTO commerce_statement_observation VALUES (?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",id,shop(),op.get("order_id"),operation,key,payload,observed,amount,reference,actor,revision);}
        catch(DuplicateKeyException duplicate){
            List<Map<String,Object>> previous=jdbc.queryForList("SELECT * FROM commerce_statement_observation WHERE shop_id=? AND request_key=?",shop(),key);
            require(!previous.isEmpty()&&payload.equals(previous.get(0).get("payload_hash")),"同一导入请求不能修改观测内容",409);return observation(previous.get(0));
        }
        return observation(jdbc.queryForMap("SELECT * FROM commerce_statement_observation WHERE observation_id=?",id));
    }

    @Transactional(readOnly=true)
    public Map<String,Object> preview(String orderId,long actor) {authority(actor);order(orderId,false);return facts(orderId);}

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> reconcile(Map<String,Object> body,long actor) {
        authority(actor);require(body!=null,"核对内容不能为空",400);String orderId=id(body.get("orderId"),32,"订单编号"),key=id(body.get("requestKey"),80,"请求编号");order(orderId,true);
        List<Map<String,Object>> old=jdbc.queryForList("SELECT * FROM commerce_money_reconciliation WHERE shop_id=? AND request_key=?",shop(),key);
        if(!old.isEmpty()){require(orderId.equals(old.get(0).get("order_id")),"同一核对请求不能更换订单",409);return reconciliation(old.get(0));}
        Map<String,Object> result=facts(orderId);String id=newId("REC");
        try {jdbc.update("INSERT INTO commerce_money_reconciliation VALUES (?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",id,shop(),orderId,key,hash(result),JSON.toJSONString(result),Boolean.TRUE.equals(result.get("healthy"))?1:0,actor);}
        catch(DuplicateKeyException duplicate){
            List<Map<String,Object>> previous=jdbc.queryForList("SELECT * FROM commerce_money_reconciliation WHERE shop_id=? AND request_key=?",shop(),key);
            require(!previous.isEmpty()&&orderId.equals(previous.get(0).get("order_id")),"同一核对请求不能更换订单",409);return reconciliation(previous.get(0));
        }
        return reconciliation(jdbc.queryForMap("SELECT * FROM commerce_money_reconciliation WHERE reconciliation_id=?",id));
    }

    /** Resolving a discrepancy requires healthy current books and a corrected imported observation.
     * The original mismatch snapshot remains immutable. This does not post a financial adjustment. */
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> resolve(String reconciliationId,Map<String,Object> body,long actor) {
        authority(actor);require(body!=null,"处理证据不能为空",400);reconciliationId=id(reconciliationId,40,"核对记录编号");String key=id(body.get("requestKey"),80,"请求编号"),reference=text(body.get("evidenceReference"),200,"处理证据编号"),note=text(body.get("note"),200,"处理说明");
        String payload=hash(map("reconciliationId",reconciliationId,"evidenceReference",reference,"note",note));
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM commerce_money_reconciliation WHERE reconciliation_id=? AND shop_id=? FOR UPDATE",reconciliationId,shop());
        require(!rows.isEmpty(),"核对记录不存在",404);Map<String,Object> row=rows.get(0);
        List<Map<String,Object>> old=jdbc.queryForList("SELECT * FROM commerce_money_resolution WHERE shop_id=? AND request_key=?",shop(),key);
        if(!old.isEmpty()){require(payload.equals(old.get(0).get("payload_hash"))&&actor==number(old.get(0).get("actor_id")),"同一处理请求不能更换内容或责任人",409);return reconciliation(row);}
        require(number(row.get("healthy"))==0,"已对平记录无需处理",409);
        require(jdbc.queryForObject("SELECT COUNT(*) FROM commerce_money_resolution WHERE reconciliation_id=?",Long.class,reconciliationId)==0,"差异已有处理记录",409);
        String orderId=String.valueOf(row.get("order_id"));order(orderId,true);Map<String,Object> verified=facts(orderId);
        require(Boolean.TRUE.equals(verified.get("healthy")),"当前差异仍未消除，请核对原账并重新导入修正观测",409);
        try {jdbc.update("INSERT INTO commerce_money_resolution VALUES (?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",newId("RES"),reconciliationId,shop(),key,payload,reference,note,JSON.toJSONString(verified),actor);}
        catch(DuplicateKeyException duplicate){
            List<Map<String,Object>> previous=jdbc.queryForList("SELECT * FROM commerce_money_resolution WHERE shop_id=? AND request_key=?",shop(),key);
            require(!previous.isEmpty()&&payload.equals(previous.get(0).get("payload_hash"))&&actor==number(previous.get(0).get("actor_id")),"同一处理请求不能更换内容或责任人",409);
        }
        return reconciliation(row);
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> reconciliations(long actor) {
        authority(actor);List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> row:jdbc.queryForList("SELECT * FROM commerce_money_reconciliation WHERE shop_id=? ORDER BY created_at DESC,reconciliation_id DESC LIMIT 100",shop()))result.add(reconciliation(row));return result;
    }

    private Map<String,Object> facts(String orderId) {
        Map<String,Object> order=order(orderId,false);int status=(int)number(order.get("status"));
        BigDecimal businessPaid=status>=1&&status<=3?money(order.get("total_amount")):BigDecimal.ZERO.setScale(2),businessRefund=money(order.get("refunded_amount"));
        BigDecimal caseRefund=jdbc.queryForObject("SELECT COALESCE(SUM(refunded_amount),0) FROM commerce_after_sales_case WHERE order_id=? AND shop_id=? AND status='REFUNDED'",BigDecimal.class,orderId,shop());
        Map<String,Object> channel=jdbc.queryForMap("SELECT COALESCE(SUM(CASE WHEN movement='CHARGE' THEN amount ELSE 0 END),0) AS charged,COALESCE(SUM(CASE WHEN movement='REFUND' THEN amount ELSE 0 END),0) AS refunded FROM commerce_channel_entry WHERE shop_id=? AND order_id=?",shop(),orderId);
        List<Map<String,Object>> issues=new ArrayList<>(),operations=new ArrayList<>();BigDecimal providerCharge=BigDecimal.ZERO,providerRefund=BigDecimal.ZERO,compensationRefund=BigDecimal.ZERO;int observedCount=0,successfulPayments=0,successfulRefunds=0;
        for(Map<String,Object> op:jdbc.queryForList("SELECT * FROM commerce_payment_operation WHERE shop_id=? AND order_id=? ORDER BY operation_id",shop(),orderId)){
            String kind=String.valueOf(op.get("kind")),provider=String.valueOf(op.get("provider_status")),local=String.valueOf(op.get("local_status"));BigDecimal amount=money(op.get("amount"));
            if("SUCCEEDED".equals(provider)){if("PAYMENT".equals(kind)){providerCharge=providerCharge.add(amount);successfulPayments++;}else {providerRefund=providerRefund.add(amount);if("REFUND".equals(kind))successfulRefunds++;if("COMPENSATION".equals(kind))compensationRefund=compensationRefund.add(amount);}}
            // Totals alone can hide a wrong voucher association or equal and opposite errors.
            List<Map<String,Object>> entries=jdbc.queryForList("SELECT * FROM commerce_channel_entry WHERE operation_id=? AND shop_id=?",op.get("operation_id"),shop());
            if("SUCCEEDED".equals(provider)){
                boolean linked=entries.size()==1&&shop().equals(entries.get(0).get("shop_id"))&&orderId.equals(entries.get(0).get("order_id"))
                        &&("PAYMENT".equals(kind)?"CHARGE":"REFUND").equals(entries.get(0).get("movement"))
                        &&Objects.equals(op.get("currency"),entries.get(0).get("currency"))&&amount.compareTo(money(entries.get(0).get("amount")))==0;
                if(!linked)issues.add(map("type","OPERATION_CHANNEL_MISMATCH","operationId",op.get("operation_id"),"expectedAmount",amount,"actualAmount",entries.size()==1?entries.get(0).get("amount"):null));
                if("REFUND".equals(kind)){
                    List<Map<String,Object>> cases=jdbc.queryForList("SELECT status,refunded_amount FROM commerce_after_sales_case WHERE after_sales_id=? AND order_id=? AND shop_id=?",op.get("after_sales_id"),orderId,shop());
                    if(cases.size()!=1||!"REFUNDED".equals(cases.get(0).get("status"))||amount.compareTo(money(cases.get(0).get("refunded_amount")))!=0)
                        issues.add(map("type","REFUND_CASE_MISMATCH","operationId",op.get("operation_id"),"afterSalesId",op.get("after_sales_id"),"expectedAmount",amount));
                }
            }else if(!entries.isEmpty())issues.add(map("type","UNEXPECTED_CHANNEL_ENTRY","operationId",op.get("operation_id"),"providerStatus",provider));
            if(!"CNY".equals(op.get("currency")))issues.add(map("type","UNSUPPORTED_CURRENCY","operationId",op.get("operation_id"),"currency",op.get("currency")));
            if(Arrays.asList("PREPARED","PENDING","UNKNOWN","COMPENSATION_PENDING").contains(local))issues.add(map("type","OPERATION_PENDING","operationId",op.get("operation_id"),"status",local));
            List<Map<String,Object>> observations=jdbc.queryForList("SELECT * FROM commerce_statement_observation WHERE shop_id=? AND operation_id=? ORDER BY sequence_no DESC LIMIT 1",shop(),op.get("operation_id"));
            Map<String,Object> item=map("operationId",op.get("operation_id"),"kind",kind,"afterSalesId",op.get("after_sales_id"),"amount",amount,"providerStatus",provider,"localStatus",local);
            if(!observations.isEmpty()){
                observedCount++;Map<String,Object> observed=observations.get(0);item.put("observation",observation(observed));
                String state=String.valueOf(observed.get("observed_status"));BigDecimal observedAmount=money(observed.get("observed_amount"));
                if(!provider.equals(state)||("SUCCEEDED".equals(provider)&&amount.compareTo(observedAmount)!=0))issues.add(map("type","STATEMENT_MISMATCH","operationId",op.get("operation_id"),"expectedStatus",provider,"observedStatus",state,"expectedAmount",amount,"observedAmount",observedAmount,"difference",observedAmount.subtract(amount),"observationId",observed.get("observation_id")));
            }else issues.add(map("type","STATEMENT_MISSING","operationId",op.get("operation_id")));
            operations.add(item);
        }
        BigDecimal charged=money(channel.get("charged")),refunded=money(channel.get("refunded"));
        if(status>=1&&status<=3){
            if(successfulPayments==0)issues.add(map("type","PAYMENT_EVIDENCE_MISSING","orderId",orderId));
            difference(issues,"ORDER_CHANNEL_CHARGE",businessPaid,charged);
        }
        if(businessRefund.signum()>0&&successfulRefunds==0)issues.add(map("type","REFUND_EVIDENCE_MISSING","orderId",orderId));
        difference(issues,"ORDER_CHANNEL_REFUND",businessRefund.add(compensationRefund),refunded);
        difference(issues,"ORDER_CHANNEL_NET",businessPaid.subtract(businessRefund),charged.subtract(refunded));
        difference(issues,"CHANNEL_PAYMENT_OPERATIONS",providerCharge,charged);
        difference(issues,"CHANNEL_REFUND_OPERATIONS",providerRefund,refunded);
        difference(issues,"ORDER_AFTER_SALES_REFUND",businessRefund,caseRefund);
        long unknownCost=jdbc.queryForObject("SELECT COUNT(*) FROM commerce_cost_entry WHERE shop_id=? AND order_id=? AND cost_status='UNKNOWN'",Long.class,shop(),orderId);
        Map<String,Object> cost=jdbc.queryForMap("SELECT COALESCE(SUM(CASE WHEN event_type='DISPATCH' THEN -amount ELSE 0 END),0) AS outbound,COALESCE(SUM(CASE WHEN event_type='RETURN' THEN amount ELSE 0 END),0) AS returned FROM commerce_cost_entry WHERE shop_id=? AND order_id=? AND cost_status='KNOWN'",shop(),orderId);
        long missingDispatch=jdbc.queryForObject("SELECT COUNT(*) FROM commerce_shipment s WHERE s.order_id=? AND NOT EXISTS(SELECT 1 FROM commerce_cost_entry e WHERE e.source_id=s.shipment_id AND e.event_type='DISPATCH')",Long.class,orderId);
        return map("orderId",orderId,"shopId",shop(),"paymentProvider","LOCAL_SANDBOX","currency","CNY","businessPaid",businessPaid,"businessRefunded",businessRefund,"afterSalesRefunded",caseRefund,"channelCharged",charged,"channelRefunded",refunded,"expectedNet",businessPaid.subtract(businessRefund),"channelNet",charged.subtract(refunded),"operations",operations,"observedOperations",observedCount,"observationCoverage",observedCount==operations.size()?"COMPLETE":"PARTIAL","issues",issues,"healthy",issues.isEmpty(),"cost",map("basis","DOCUMENT_SNAPSHOT","outbound",cost.get("outbound"),"returned",cost.get("returned"),"unknownEntries",unknownCost,"missingHistoricalShipments",missingDispatch,"complete",unknownCost==0&&missingDispatch==0));
    }

    private Map<String,Object> reconciliation(Map<String,Object> row) {
        List<Map<String,Object>> resolution=jdbc.queryForList("SELECT * FROM commerce_money_resolution WHERE reconciliation_id=? AND shop_id=?",row.get("reconciliation_id"),shop());
        Map<String,Object> result=map("reconciliationId",row.get("reconciliation_id"),"orderId",row.get("order_id"),"status",resolution.isEmpty()?(number(row.get("healthy"))==1?"MATCHED":"OPEN"):"RESOLVED","createdAt",time(row.get("created_at")),"actorId",row.get("actor_id"),"snapshot",JSON.parseObject(String.valueOf(row.get("result_json"))));
        if(!resolution.isEmpty()){Map<String,Object> r=resolution.get(0);result.put("resolution",map("resolutionId",r.get("resolution_id"),"evidenceReference",r.get("evidence_reference"),"note",r.get("note"),"actorId",r.get("actor_id"),"createdAt",time(r.get("created_at")),"verifiedSnapshot",JSON.parseObject(String.valueOf(r.get("verified_json")))));}return result;
    }

    private Map<String,Object> operation(String id,boolean lock) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM commerce_payment_operation WHERE operation_id=? AND shop_id=?"+(lock?" FOR UPDATE":""),id,shop());require(!rows.isEmpty(),"支付操作不存在",404);return rows.get(0);
    }
    private Map<String,Object> order(String id,boolean lock) {
        id=id(id,32,"订单编号");List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM commerce_order WHERE order_id=? AND shop_id=?"+(lock?" FOR UPDATE":""),id,shop());require(!rows.isEmpty(),"订单不存在",404);return rows.get(0);
    }
    private static Map<String,Object> observation(Map<String,Object> row) {return map("observationId",row.get("observation_id"),"operationId",row.get("operation_id"),"orderId",row.get("order_id"),"status",row.get("observed_status"),"amount",row.get("observed_amount"),"sourceReference",row.get("source_reference"),"revision",row.get("sequence_no"),"actorId",row.get("actor_id"),"createdAt",time(row.get("created_at")),"provider","LOCAL_SANDBOX_OBSERVATION");}
    private void insert(String key,String shop,String type,String sourceId,String receipt,String order,Long origin,long product,long warehouse,long quantity,BigDecimal unit,String basis,long actor) {
        BigDecimal amount=unit==null?null:unit.multiply(BigDecimal.valueOf(quantity)).setScale(4,RoundingMode.UNNECESSARY);
        require(amount==null||amount.abs().compareTo(new BigDecimal("99999999999999.9999"))<=0,"成本快照金额超过范围",409);
        jdbc.update("INSERT INTO commerce_cost_entry(event_key,shop_id,event_type,source_id,receipt_id,order_id,origin_entry_id,product_id,warehouse_id,quantity,unit_cost,amount,cost_status,cost_basis,actor_id,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",key,shop,type,sourceId,receipt,order,origin,product,warehouse,quantity,unit,amount,unit==null?"UNKNOWN":"KNOWN",basis,actor>0?actor:null);
    }
    private void authority(long actor) {merchants.requireCapability(shop(),actor,CommerceCapability.REFUND_REVIEW);}
    private static String shop(){return CommerceShopContext.id();}
    private static void difference(List<Map<String,Object>> issues,String type,BigDecimal expected,BigDecimal actual){if(expected.compareTo(actual)!=0)issues.add(map("type",type,"expectedAmount",expected,"actualAmount",actual,"difference",actual.subtract(expected)));}
    private static BigDecimal knownCost(Object value) {if(value==null)return null;try{BigDecimal cost=new BigDecimal(String.valueOf(value)).setScale(4,RoundingMode.UNNECESSARY);return cost.signum()>0&&cost.compareTo(new BigDecimal("99999999999999.9999"))<=0?cost:null;}catch(RuntimeException invalid){return null;}}
    private static BigDecimal money(Object value) {try{BigDecimal amount=new BigDecimal(String.valueOf(value)).setScale(2,RoundingMode.UNNECESSARY);require(amount.signum()>=0&&amount.compareTo(new BigDecimal("999999999999.99"))<=0,"金额需为非负数且不超过两位小数",400);return amount;}catch(NumberFormatException|ArithmeticException error){throw new ServiceException("金额需为非负数且不超过两位小数",400);}}
    private static String id(Object value,int length,String field){String text=text(value,length,field);require(text.matches("[A-Za-z0-9_-]+"),field+"格式错误",400);return text;}
    private static String text(Object value,int length,String field){require(value instanceof String&&!((String)value).trim().isEmpty()&&((String)value).trim().length()<=length,field+"不能为空或过长",400);return ((String)value).trim();}
    private static String newId(String prefix){return prefix+UUID.randomUUID().toString().replace("-","");}
    private static String hash(Object value){try{byte[] digest=MessageDigest.getInstance("SHA-256").digest(JSON.toJSONString(value).getBytes(StandardCharsets.UTF_8));StringBuilder result=new StringBuilder();for(byte part:digest)result.append(String.format("%02x",part));return result.toString();}catch(Exception error){throw new IllegalStateException(error);}}
    private static long number(Object value){return value==null?0:((Number)value).longValue();}
    private static String time(Object value){return value==null?null:String.valueOf(value);}
    private static Map<String,Object> map(Object... pairs){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
    private static void require(boolean condition,String message,int code){if(!condition)throw new ServiceException(message,code);}
}

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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Expense evidence, review and local-sandbox settlement are separate facts.
 * Request mutex -> source order -> expense locks. No endpoint sends money to a bank. */
@Service
@Profile({"local","commerce"})
public class CommerceSettlementService {
    private final DataSource source;
    private final JdbcTemplate jdbc;
    private final CommerceMerchantService merchants;
    public CommerceSettlementService(DataSource source) {
        this.source=source;this.jdbc=new JdbcTemplate(source);this.merchants=new CommerceMerchantService(source);
    }
    public void initializeSchema() {
        ResourceDatabasePopulator script=new ResourceDatabasePopulator(new ClassPathResource("db/commerce-order-settlement.sql"));
        script.setSqlScriptEncoding("UTF-8");script.execute(source);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> expense(String id,Map<String,Object> body,long actor) {
        requireCapability(actor,CommerceCapability.REFUND_REVIEW);
        String key=key(body.get("requestKey")),category=String.valueOf(body.get("category"));
        require(Arrays.asList("LOGISTICS","PACKAGING","PLATFORM","OTHER").contains(category),"费用类别无效",400);
        BigDecimal value=money(body.get("amount"));String evidence=text(body.get("evidenceReference"),200,"费用凭证"),payee=text(body.get("payee"),100,"收款方");
        String fingerprint=hash(Arrays.asList(id,category,value.toPlainString(),evidence,payee));
        claim("EXPENSE",key,fingerprint);order(id,true);
        List<Map<String,Object>> previous=jdbc.queryForList("SELECT * FROM commerce_order_expense WHERE shop_id=? AND request_key=?",shop(),key);
        if(!previous.isEmpty()){require(fingerprint.equals(previous.get(0).get("payload_hash")),"费用请求编号已用于其他内容",409);return view(id);}
        jdbc.update("INSERT INTO commerce_order_expense(expense_id,shop_id,order_id,request_key,payload_hash,category,amount,payee,evidence_reference,status,actor_id,created_at) VALUES (?,?,?,?,?,?,?,?,?,'DRAFT',?,CURRENT_TIMESTAMP)",newId("EX"),shop(),id,key,fingerprint,category,value,payee,evidence,actor);
        return view(id);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> review(String expenseId,Map<String,Object> body,long actor) {
        requireCapability(actor,CommerceCapability.REFUND_REVIEW);
        List<Map<String,Object>> pointers=jdbc.queryForList("SELECT order_id FROM commerce_order_expense WHERE expense_id=? AND shop_id=?",expenseId,shop());
        require(!pointers.isEmpty(),"费用单不存在",404);String id=String.valueOf(pointers.get(0).get("order_id"));order(id,true);
        Map<String,Object> expense=jdbc.queryForMap("SELECT * FROM commerce_order_expense WHERE expense_id=? AND shop_id=? FOR UPDATE",expenseId,shop());
        String key=key(body.get("requestKey")),decision=String.valueOf(body.get("decision"));
        require(Arrays.asList("APPROVE","REJECT").contains(decision),"费用审核决定无效",400);
        String target="APPROVE".equals(decision)?"APPROVED":"REJECTED";
        if(expense.get("review_key")!=null){require(key.equals(expense.get("review_key"))&&target.equals(expense.get("status")),"费用已审核，不能更改审核结果",409);return view(id);}
        require("DRAFT".equals(expense.get("status")),"当前费用不能审核",409);
        require(jdbc.update("UPDATE commerce_order_expense SET status=?,review_key=?,reviewer_id=?,reviewed_at=CURRENT_TIMESTAMP WHERE expense_id=? AND status='DRAFT'",target,key,actor,expenseId)==1,"费用状态已变化",409);
        return view(id);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> pay(String id,Map<String,Object> body,long actor) {
        requireCapability(actor,CommerceCapability.REFUND_EXECUTE);
        String key=key(body.get("requestKey")),evidence=text(body.get("evidenceReference"),200,"结算凭证");
        Object raw=body.get("allocations");require(raw instanceof List && !((List<?>)raw).isEmpty()&&((List<?>)raw).size()<=30,"结算分配无效",400);
        SortedMap<String,BigDecimal> allocations=new TreeMap<>();
        for(Object item:(List<?>)raw){require(item instanceof Map,"结算分配格式无效",400);Map<?,?> row=(Map<?,?>)item;String expense=text(row.get("expenseId"),40,"费用单编号");require(!allocations.containsKey(expense),"结算分配不能重复费用单",400);allocations.put(expense,money(row.get("amount")));}
        String fingerprint=hash(Arrays.asList(id,evidence,allocations));
        claim("PAYMENT",key,fingerprint);order(id,true);
        List<Map<String,Object>> previous=jdbc.queryForList("SELECT * FROM commerce_expense_payment WHERE shop_id=? AND request_key=?",shop(),key);
        if(!previous.isEmpty()){require(fingerprint.equals(previous.get(0).get("payload_hash")),"结算请求编号已用于其他内容",409);return view(id);}
        BigDecimal total=BigDecimal.ZERO;
        for(Map.Entry<String,BigDecimal> allocation:allocations.entrySet()){
            List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM commerce_order_expense WHERE expense_id=? AND order_id=? AND shop_id=? FOR UPDATE",allocation.getKey(),id,shop());
            require(!rows.isEmpty(),"费用单不属于当前订单或店铺",404);Map<String,Object> expense=rows.get(0);
            require("APPROVED".equals(expense.get("status")),"只有审核通过的费用可以结算",409);
            require(decimal(expense.get("amount")).subtract(settled(allocation.getKey())).compareTo(allocation.getValue())>=0,"结算金额超过费用未结余额",409);
            total=total.add(allocation.getValue());
        }
        require(total.compareTo(new BigDecimal("999999999999.99"))<=0,"结算总额超出金额范围",400);
        String payment=newId("EP");
        jdbc.update("INSERT INTO commerce_expense_payment(payment_id,shop_id,order_id,request_key,payload_hash,amount,evidence_reference,provider,status,actor_id,created_at) VALUES (?,?,?,?,?,?,?,'LOCAL_SANDBOX','SUCCESS',?,CURRENT_TIMESTAMP)",payment,shop(),id,key,fingerprint,total,evidence,actor);
        for(Map.Entry<String,BigDecimal> allocation:allocations.entrySet())jdbc.update("INSERT INTO commerce_expense_allocation VALUES (?,?,?)",payment,allocation.getKey(),allocation.getValue());
        return view(id);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> preview(String id,long actor){financeRead(actor);order(id,false);Map<String,Object> result=view(id);result.put("snapshots",snapshots(id));return result;}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> snapshot(String id,Map<String,Object> body,long actor){
        requireCapability(actor,CommerceCapability.REFUND_REVIEW);String key=key(body.get("requestKey"));claim("SNAPSHOT",key,hash(id));order(id,true);
        List<Map<String,Object>> previous=jdbc.queryForList("SELECT * FROM commerce_order_settlement_snapshot WHERE shop_id=? AND request_key=?",shop(),key);
        if(!previous.isEmpty()){require(id.equals(previous.get(0).get("order_id")),"快照请求编号已用于其他订单",409);return JSON.parseObject(String.valueOf(previous.get(0).get("result_json")));}
        Map<String,Object> result=view(id);String fingerprint=hash(result),snapshot=newId("ST");
        result.put("snapshotId",snapshot);result.put("factsHash",fingerprint);
        jdbc.update("INSERT INTO commerce_order_settlement_snapshot VALUES (?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",snapshot,shop(),id,key,fingerprint,JSON.toJSONString(result),actor);
        return result;
    }
    /** A merchant read tool has no access to customer names, phone numbers or addresses. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> stockExplanation(String id,long actor){
        requireCapability(actor,CommerceCapability.READ);order(id,false);
        List<Map<String,Object>> products=jdbc.queryForList("SELECT product_id AS productId,product_code AS productCode,product_name AS productName,spec,quantity FROM commerce_order_item WHERE order_id=? ORDER BY product_id",id);
        List<Map<String,Object>> history=jdbc.queryForList("SELECT l.event_key AS eventKey,l.operation,l.receipt_id AS receiptId,l.product_id AS productId,l.warehouse_id AS warehouseId,l.delta_quantity AS deltaQuantity,l.before_quantity AS beforeQuantity,l.after_quantity AS afterQuantity FROM commerce_warehouse_ledger l WHERE EXISTS(SELECT 1 FROM commerce_shipment s JOIN commerce_order o ON o.order_id=s.order_id WHERE s.receipt_id=l.receipt_id AND o.order_id=? AND o.shop_id=?) OR EXISTS(SELECT 1 FROM commerce_after_sales_case c WHERE c.return_receipt_id=l.receipt_id AND c.order_id=? AND c.shop_id=?) ORDER BY l.ledger_id",id,shop(),id,shop());
        List<Map<String,Object>> holds=jdbc.queryForList("SELECT product_id AS productId,quantity,status FROM commerce_stock_hold WHERE order_id=? ORDER BY product_id",id);
        return map("orderId",id,"products",products,"warehouseEvents",history,"holds",holds,"writes",false);
    }
    private Map<String,Object> view(String id){
        Map<String,Object> order=order(id,false);
        BigDecimal paid=sum("SELECT COALESCE(SUM(amount),0) FROM commerce_channel_entry WHERE shop_id=? AND order_id=? AND movement='CHARGE'",shop(),id);
        BigDecimal refunded=sum("SELECT COALESCE(SUM(amount),0) FROM commerce_channel_entry WHERE shop_id=? AND order_id=? AND movement='REFUND'",shop(),id);
        BigDecimal netCost=sum("SELECT COALESCE(-SUM(amount),0) FROM commerce_cost_entry WHERE shop_id=? AND order_id=? AND event_type IN('DISPATCH','RETURN') AND cost_status='KNOWN'",shop(),id);
        long unknown=jdbc.queryForObject("SELECT COUNT(*) FROM commerce_cost_entry WHERE shop_id=? AND order_id=? AND event_type IN('DISPATCH','RETURN') AND cost_status='UNKNOWN'",Long.class,shop(),id);
        long missing=jdbc.queryForObject("SELECT COUNT(*) FROM commerce_shipment s WHERE s.order_id=? AND NOT EXISTS(SELECT 1 FROM commerce_cost_entry e WHERE e.source_id=s.shipment_id AND e.event_type='DISPATCH')",Long.class,id);
        long missingReturn=jdbc.queryForObject("SELECT COUNT(*) FROM commerce_after_sales_case c WHERE c.order_id=? AND c.return_receipt_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM commerce_cost_entry e WHERE e.source_id=c.after_sales_id AND e.event_type='RETURN')",Long.class,id);
        long conditionPending=jdbc.queryForObject("SELECT COUNT(*) FROM commerce_after_sales_case WHERE order_id=? AND return_receipt_id IS NOT NULL AND return_condition IN('QUALITY_HOLD','DAMAGED')",Long.class,id);
        long pendingMoney=jdbc.queryForObject("SELECT COUNT(*) FROM commerce_payment_operation WHERE order_id=? AND shop_id=? AND local_status IN('PREPARED','PENDING','UNKNOWN','COMPENSATION_PENDING')",Long.class,id,shop());
        long state=number(order.get("status"));
        BigDecimal businessPaid=(state>=1&&state<=3)||order.get("paid_time")!=null?decimal(order.get("total_amount")):BigDecimal.ZERO;
        // COMPENSATED payments were really charged, then returned outside the order's
        // after-sales balance. A zero channel net does not prove either gross movement.
        BigDecimal operationPaid=sum("SELECT COALESCE(SUM(amount),0) FROM commerce_payment_operation WHERE order_id=? AND shop_id=? AND kind='PAYMENT' AND local_status IN('SUCCEEDED','COMPENSATED')",id,shop());
        BigDecimal operationRefund=sum("SELECT COALESCE(SUM(amount),0) FROM commerce_payment_operation WHERE order_id=? AND shop_id=? AND kind='REFUND' AND local_status='SUCCEEDED'",id,shop());
        BigDecimal compensationRefund=sum("SELECT COALESCE(SUM(amount),0) FROM commerce_payment_operation WHERE order_id=? AND shop_id=? AND kind='COMPENSATION' AND local_status='SUCCEEDED'",id,shop());
        BigDecimal businessRefund=decimal(order.get("refunded_amount"));
        boolean moneyComplete=businessPaid.add(compensationRefund).compareTo(paid)==0&&businessRefund.add(compensationRefund).compareTo(refunded)==0&&operationPaid.compareTo(paid)==0&&operationRefund.compareTo(businessRefund)==0&&operationRefund.add(compensationRefund).compareTo(refunded)==0&&pendingMoney==0;
        List<Map<String,Object>> expenses=new ArrayList<>();BigDecimal approved=BigDecimal.ZERO,settled=BigDecimal.ZERO;
        for(Map<String,Object> row:jdbc.queryForList("SELECT * FROM commerce_order_expense WHERE shop_id=? AND order_id=? ORDER BY expense_id",shop(),id)){
            BigDecimal amount=decimal(row.get("amount")),allocated=settled(String.valueOf(row.get("expense_id")));
            if("APPROVED".equals(row.get("status"))){approved=approved.add(amount);settled=settled.add(allocated);}
            expenses.add(map("expenseId",row.get("expense_id"),"category",row.get("category"),"amount",amount,"payee",row.get("payee"),"evidenceReference",row.get("evidence_reference"),"status",row.get("status"),"settledAmount",allocated,"outstandingAmount",amount.subtract(allocated),"actorId",row.get("actor_id"),"reviewerId",row.get("reviewer_id")));
        }
        List<Map<String,Object>> payments=new ArrayList<>();
        for(Map<String,Object> row:jdbc.queryForList("SELECT * FROM commerce_expense_payment WHERE shop_id=? AND order_id=? ORDER BY payment_id",shop(),id))payments.add(map("paymentId",row.get("payment_id"),"amount",row.get("amount"),"provider",row.get("provider"),"status",row.get("status"),"evidenceReference",row.get("evidence_reference"),"allocations",jdbc.queryForList("SELECT expense_id AS expenseId,amount FROM commerce_expense_allocation WHERE payment_id=? ORDER BY expense_id",row.get("payment_id"))));
        BigDecimal receipts=paid.subtract(refunded),contribution=receipts.subtract(netCost).subtract(approved);
        long openCases=jdbc.queryForObject("SELECT COUNT(*) FROM commerce_after_sales_case WHERE order_id=? AND status NOT IN('REFUNDED','REJECTED')",Long.class,id);
        long draftExpenses=expenses.stream().filter(row->"DRAFT".equals(row.get("status"))).count();
        boolean complete=unknown==0&&missing==0&&missingReturn==0&&conditionPending==0;
        boolean closed=(number(order.get("status"))==3||number(order.get("status"))==4)&&openCases==0&&draftExpenses==0&&complete&&moneyComplete;
        return map("orderId",id,"currency","CNY","provider","LOCAL_SANDBOX","externalChannel",false,"grossPaid",paid,"refundedAmount",refunded,"netReceipts",receipts,"netStockCost",netCost,"costComplete",complete,"moneyComplete",moneyComplete,"coverage",map("unknownCostEntries",unknown,"missingDispatchCost",missing,"missingReturnCost",missingReturn,"conditionCostPending",conditionPending,"draftExpenses",draftExpenses,"pendingMoneyOperations",pendingMoney),"approvedExpenseAmount",approved,"settledExpenseAmount",settled,"outstandingExpenseAmount",approved.subtract(settled),"operatingResult",closed?contribution:null,"provisionalContribution",complete&&moneyComplete?contribution:null,"resultStatus",closed?"CLOSED":"PROVISIONAL","expenses",expenses,"payments",payments);
    }
    private List<Map<String,Object>> snapshots(String id){List<Map<String,Object>> result=new ArrayList<>();for(Map<String,Object> row:jdbc.queryForList("SELECT snapshot_id,facts_hash,result_json,created_at FROM commerce_order_settlement_snapshot WHERE shop_id=? AND order_id=? ORDER BY created_at,snapshot_id",shop(),id))result.add(map("snapshotId",row.get("snapshot_id"),"factsHash",row.get("facts_hash"),"createdAt",row.get("created_at"),"result",JSON.parseObject(String.valueOf(row.get("result_json")))));return result;}
    private Map<String,Object> order(String id,boolean lock){require(id!=null&&id.matches("[A-Za-z0-9_-]{1,32}"),"订单编号无效",400);List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM commerce_order WHERE order_id=? AND shop_id=?"+(lock?" FOR UPDATE":""),id,shop());require(!rows.isEmpty(),"订单不存在",404);return rows.get(0);}
    private BigDecimal settled(String expense){return sum("SELECT COALESCE(SUM(a.amount),0) FROM commerce_expense_allocation a JOIN commerce_expense_payment p ON p.payment_id=a.payment_id WHERE a.expense_id=? AND p.status='SUCCESS' AND p.shop_id=?",expense,shop());}
    private BigDecimal sum(String sql,Object... args){return jdbc.queryForObject(sql,BigDecimal.class,args);}
    private void claim(String kind,String key,String fingerprint){
        jdbc.update("INSERT INTO commerce_settlement_request(shop_id,kind,request_key,payload_hash) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE request_key=VALUES(request_key)",shop(),kind,key,fingerprint);
        String saved=jdbc.queryForObject("SELECT payload_hash FROM commerce_settlement_request WHERE shop_id=? AND kind=? AND request_key=? FOR UPDATE",String.class,shop(),kind,key);
        require(fingerprint.equals(saved),"请求编号已用于其他订单或内容",409);
    }
    private void requireCapability(long actor,CommerceCapability cap){merchants.requireCapability(shop(),actor,cap);}
    private void financeRead(long actor){Map<String,Object> member=merchants.requireShop(shop(),actor,false);String role=String.valueOf(member.get("member_role"));require(CommerceAccessPolicy.allows(role,CommerceCapability.REFUND_REVIEW)||CommerceAccessPolicy.allows(role,CommerceCapability.REFUND_EXECUTE),"当前成员没有财务查询权限",403);}
    private static String shop(){return CommerceShopContext.id();}
    private static long number(Object value){return value==null?0:((Number)value).longValue();}
    private static BigDecimal decimal(Object value){return value==null?BigDecimal.ZERO:new BigDecimal(String.valueOf(value));}
    private static BigDecimal money(Object value){try{BigDecimal result=new BigDecimal(String.valueOf(value)).setScale(2,java.math.RoundingMode.UNNECESSARY);require(result.signum()>0&&result.compareTo(new BigDecimal("999999999999.99"))<=0,"费用金额须为正数且精确到分",400);return result;}catch(NumberFormatException|ArithmeticException error){throw new ServiceException("费用金额须为正数且精确到分",400);}}
    private static String key(Object value){String result=text(value,80,"请求编号");require(result.matches("[A-Za-z0-9_-]+"),"请求编号无效",400);return result;}
    private static String text(Object value,int max,String label){require(value instanceof String,label+"格式无效",400);String result=((String)value).trim();require(!result.isEmpty()&&result.length()<=max,label+"长度无效",400);return result;}
    private static String newId(String prefix){return prefix+UUID.randomUUID().toString().replace("-","");}
    private static String hash(Object value){try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(JSON.toJSONString(value).getBytes(StandardCharsets.UTF_8));StringBuilder result=new StringBuilder();for(byte part:bytes)result.append(String.format("%02x",part));return result.toString();}catch(Exception error){throw new IllegalStateException(error);}}
    private static void require(boolean condition,String message,int code){if(!condition)throw new ServiceException(message,code);}
    private static Map<String,Object> map(Object... pairs){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
}

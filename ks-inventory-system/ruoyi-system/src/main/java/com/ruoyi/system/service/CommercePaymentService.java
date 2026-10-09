package com.ruoyi.system.service;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.core.tenant.CommerceShopContext;
import com.ruoyi.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Durable local-channel boundary. Request, provider result and business application commit separately.
 * Provider callbacks never manufacture stock, reopen a closed order, or trust client amounts.
 * A real adapter must replace submit/query, retaining this inbox and business state machine. */
@Service
@Profile({"local","commerce"})
public class CommercePaymentService {
    private final JdbcTemplate jdbc;
    private final DataSource source;
    private final TransactionTemplate tx;
    @Value("${commerce.payment-webhook-secret:}") private String webhookSecret="";
    @FunctionalInterface public interface RefundHandler { void complete(String caseId,String refundId,long actor); }

    public CommercePaymentService(DataSource source) {
        this.source=source;this.jdbc=new JdbcTemplate(source);
        tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public void initializeSchema() {
        ResourceDatabasePopulator script=new ResourceDatabasePopulator(new ClassPathResource("db/commerce-payment.sql"));
        script.setSqlScriptEncoding("UTF-8");script.execute(source);
    }
    public void configureWebhookSecret(String secret) {require(secret!=null&&secret.length()>=32,"支付通知密钥至少需要32个字符",400);webhookSecret=secret;}

    public Map<String,Object> pay(String orderId,String owner,String requestKey,String scenario,
                                   Supplier<Map<String,Object>> prepare,Consumer<String> expire) {
        scenario(scenario,false);
        Map<String,Object> operation=tx.execute(status->{
            Map<String,Object> order=prepare.get();
            List<Map<String,Object>> previous=jdbc.queryForList("SELECT * FROM commerce_payment_operation WHERE shop_id=? AND kind='PAYMENT' AND business_key=? AND request_key=?",shop(),orderId,requestKey);
            if(!previous.isEmpty()){require(scenario.equals(previous.get(0).get("scenario")),"同一支付请求不能改变沙箱场景",409);return previous.get(0);}
            List<String> legacy=jdbc.queryForList("SELECT outcome FROM commerce_payment_attempt WHERE order_id=? AND request_key=?",String.class,orderId,requestKey);
            if(!legacy.isEmpty()){require(("success".equals(scenario)?"SUCCEEDED":"failure".equals(scenario)?"FAILED":"INVALID").equals(legacy.get(0)),"同一支付请求不能改变沙箱场景",409);return map("local_status",legacy.get(0));}
            if(number(order.get("status"))==4)return map("local_status","EXPIRED");
            if(number(order.get("status"))>0){require("success".equals(scenario),"已付款订单不能重复扣款",409);return map("local_status","SUCCEEDED");}
            require(jdbc.queryForObject("SELECT COUNT(*) FROM commerce_payment_operation WHERE order_id=? AND kind='PAYMENT' AND local_status IN('PREPARED','PENDING','UNKNOWN')",Long.class,orderId)==0,"支付结果待确认，请先查询原支付请求",409);
            String id=insert("PAYMENT",orderId,null,orderId,requestKey,scenario,decimal(order.get("total_amount")),null,null);
            jdbc.update("INSERT INTO commerce_payment_attempt(order_id,request_key,outcome,create_time) VALUES (?,?,'PENDING',CURRENT_TIMESTAMP)",orderId,requestKey);
            return operation(id,false);
        });
        if(operation.get("operation_id")==null)return operation;
        return execute(String.valueOf(operation.get("operation_id")),expire,null);
    }

    public Map<String,Object> refund(String id,String requestKey,String scenario,long actor,
                                      Supplier<Map<String,Object>> prepare,RefundHandler completion) {
        scenario(scenario,true);
        Map<String,Object> operation=tx.execute(status->{
            Map<String,Object> row=prepare.get();
            List<Map<String,Object>> previous=jdbc.queryForList("SELECT * FROM commerce_payment_operation WHERE shop_id=? AND kind='REFUND' AND business_key=? AND request_key=?",shop(),id,requestKey);
            if(!previous.isEmpty()){require(scenario.equals(previous.get(0).get("scenario")),"同一退款请求不能改变沙箱场景",409);return previous.get(0);}
            List<String> legacy=jdbc.queryForList("SELECT outcome FROM commerce_refund_attempt WHERE after_sales_id=? AND request_key=?",String.class,id,requestKey);
            if(!legacy.isEmpty()){require(("success".equals(scenario)?"SUCCEEDED":"failure".equals(scenario)?"FAILED":"INVALID").equals(legacy.get(0)),"同一退款请求不能改变沙箱场景",409);return map("local_status",legacy.get(0));}
            if("REFUNDED".equals(row.get("status"))){require("success".equals(scenario),"售后已完成退款",409);return map("local_status","SUCCEEDED");}
            require(jdbc.queryForObject("SELECT COUNT(*) FROM commerce_payment_operation WHERE after_sales_id=? AND kind='REFUND' AND local_status IN('PREPARED','PENDING','UNKNOWN')",Long.class,id)==0,"退款结果待确认，请先查询原退款请求",409);
            String operationId=insert("REFUND",String.valueOf(row.get("order_id")),id,id,requestKey,scenario,decimal(row.get("refund_amount")),actor,null);
            jdbc.update("INSERT INTO commerce_refund_attempt(after_sales_id,request_key,outcome,amount,created_at) VALUES (?,?,'PENDING',?,CURRENT_TIMESTAMP)",id,requestKey,row.get("refund_amount"));
            return operation(operationId,false);
        });
        if(operation.get("operation_id")==null)return operation;
        return execute(String.valueOf(operation.get("operation_id")),null,completion);
    }

    private Map<String,Object> execute(String id,Consumer<String> expire,RefundHandler refund) {
        Map<String,Object> row=tx.execute(status->{
            Map<String,Object> attempt=operation(id,true);
            if(!"PREPARED".equals(attempt.get("local_status")))return attempt;
            String scenario=String.valueOf(attempt.get("scenario"));
            String provider="failure".equals(scenario)?"FAILED":("delayed_success".equals(scenario)||"refund_pending".equals(scenario))?"PENDING":"SUCCEEDED";
            providerResult(attempt,provider);
            jdbc.update("UPDATE commerce_payment_operation SET local_status=?,updated_at=CURRENT_TIMESTAMP WHERE operation_id=?","timeout_after_success".equals(scenario)?"UNKNOWN":"PENDING",id);
            return operation(id,false);
        });
        if("UNKNOWN".equals(row.get("local_status")))return shape(row);
        if("PENDING".equals(row.get("local_status")))return applyEvent(event(row,"submit"),expire,refund);
        return shape(row);
    }

    /** Query is the recovery path for timeout-after-charge, interrupted callbacks and provider pending. */
    public Map<String,Object> query(String id,Consumer<String> expire,RefundHandler refund) {
        tx.execute(status->{operation(id,true);jdbc.update("UPDATE commerce_payment_operation SET query_count=query_count+1,next_query_at=TIMESTAMPADD(SECOND,LEAST(60,10+query_count*5),CURRENT_TIMESTAMP) WHERE operation_id=?",id);return null;});
        Map<String,Object> row=operation(id,false);
        if("PREPARED".equals(row.get("local_status")))return execute(id,expire,refund);
        return applyEvent(event(row,"query"),expire,refund);
    }

    /** Bounded per-shop recovery. A crashed instance leaves the next durable query eligible again. */
    public Map<String,Object> recoverPending(int limit,Consumer<String> expire,RefundHandler refund) {
        require(limit>=1&&limit<=100,"支付恢复批次无效",400);
        List<String> ids=jdbc.queryForList("SELECT operation_id FROM commerce_payment_operation WHERE shop_id=? AND local_status IN('PREPARED','PENDING','UNKNOWN','COMPENSATION_PENDING') AND next_query_at<=CURRENT_TIMESTAMP ORDER BY next_query_at,operation_id LIMIT ?",String.class,shop(),limit);
        List<Map<String,Object>> failures=new ArrayList<>();int processed=0;
        for(String id:ids)try{query(id,expire,refund);processed++;}catch(RuntimeException error){failures.add(map("operationId",id,"error",error.getClass().getSimpleName()));}
        return map("processed",processed,"failures",failures);
    }

    /** Only a privileged sandbox operator may advance the channel; terminal success cannot revert. */
    public Map<String,Object> advance(String id,String result,Consumer<String> expire,RefundHandler refund) {
        require(Arrays.asList("PENDING","SUCCEEDED","FAILED").contains(result),"渠道结果无效",400);
        Map<String,Object> row=tx.execute(status->{Map<String,Object> attempt=operation(id,true);
            require(!"NOT_SUBMITTED".equals(attempt.get("provider_status")),"渠道请求尚未提交，请先查询",409);
            require("PENDING".equals(attempt.get("provider_status"))||result.equals(attempt.get("provider_status")),"渠道终态不能改写",409);
            if(!result.equals(attempt.get("provider_status")))providerResult(attempt,result);
            return operation(id,false);
        });
        return applyEvent(event(row,"advance"),expire,refund);
    }

    /** Exact raw UTF-8 body is signed; timestamp limits captured-notification replay. Event ID is durable. */
    public Map<String,Object> receive(String raw,String timestamp,String signature,Consumer<String> expire,RefundHandler refund) {
        require(webhookSecret.length()>=32,"支付通知密钥未配置",503);
        require(raw!=null&&raw.length()<=8000&&timestamp!=null&&timestamp.matches("[0-9]{10,13}"),"渠道通知格式错误",400);
        long sent=Long.parseLong(timestamp);require(Math.abs(System.currentTimeMillis()/1000-sent)<=300,"渠道通知时间已过期",401);
        require(signature!=null&&MessageDigest.isEqual(sign(timestamp,raw).getBytes(StandardCharsets.US_ASCII),signature.getBytes(StandardCharsets.US_ASCII)),"渠道通知签名无效",401);
        Map<String,Object> payload;
        try{payload=JSON.parseObject(raw);}catch(RuntimeException invalid){throw new ServiceException("渠道通知JSON无效",400);}
        return applyEvent(payload,expire,refund);
    }
    public String sign(String timestamp,String raw) {
        require(webhookSecret.length()>=32,"支付通知密钥未配置",503);
        try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return hex(mac.doFinal((timestamp+"."+raw).getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.GeneralSecurityException error){throw new IllegalStateException(error);}
    }

    public Map<String,Object> applyEvent(Map<String,Object> payload,Consumer<String> expire,RefundHandler refund) {
        String eventId=text(payload.get("eventId"),"[A-Za-z0-9_-]{1,80}","渠道事件编号");
        String id=text(payload.get("operationId"),"PO[A-F0-9]{32}","渠道请求编号");
        String result=String.valueOf(payload.get("status"));require(Arrays.asList("PENDING","SUCCEEDED","FAILED").contains(result),"渠道事件状态无效",400);
        String raw=JSON.toJSONString(new TreeMap<>(payload)),hash=digest(raw);
        // Persist the inbox before business processing, so a failed stock/case application is replayable.
        tx.execute(status->{operation(id,false);
            jdbc.update("INSERT INTO commerce_provider_event(event_id,shop_id,operation_id,payload_hash,payload,processing_status,created_at) VALUES (?,?,?,?,?,'RECEIVED',CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE event_id=VALUES(event_id)",eventId,shop(),id,hash,raw);
            Map<String,Object> old=jdbc.queryForMap("SELECT * FROM commerce_provider_event WHERE event_id=?",eventId);
            require(shop().equals(old.get("shop_id"))&&hash.equals(old.get("payload_hash")),"同一渠道事件不能改变内容",409);return null;
        });
        Map<String,Object> applied=tx.execute(status->{
            Map<String,Object> basic=operation(id,false);
            Map<String,Object> order=jdbc.queryForMap("SELECT * FROM commerce_order WHERE order_id=? AND shop_id=? FOR UPDATE",basic.get("order_id"),shop());
            Map<String,Object> row=operation(id,true);
            long revision=longValue(payload.get("revision"));
            require(revision>0&&revision<=number(row.get("provider_revision")),"渠道事件版本无效",409);
            require(decimal(payload.get("amount")).compareTo(decimal(row.get("amount")))==0&&"CNY".equals(payload.get("currency"))&&row.get("provider_reference").equals(payload.get("providerReference")),"渠道金额、币种或流水号不匹配",409);
            if(revision<number(row.get("provider_revision"))||revision<=number(row.get("applied_revision"))) {
                mark(eventId,"IGNORED");return shape(row);
            }
            require(result.equals(row.get("provider_status")),"通知结果与沙箱渠道账本不符",409);
            String state=result;
            if("SUCCEEDED".equals(result)) {
                if("PAYMENT".equals(row.get("kind"))) {
                    if(number(order.get("status"))==0&&expired(order)){require(expire!=null,"订单超时处理器不可用",503);expire.accept(String.valueOf(order.get("order_id")));order=jdbc.queryForMap("SELECT * FROM commerce_order WHERE order_id=?",order.get("order_id"));}
                    if(number(order.get("status"))==0)jdbc.update("UPDATE commerce_order SET status=1,paid_time=CURRENT_TIMESTAMP,transaction_id=? WHERE order_id=? AND status=0",row.get("provider_reference"),order.get("order_id"));
                    else if(number(order.get("status"))==4||!Objects.equals(order.get("transaction_id"),row.get("provider_reference"))) {
                        // A late/duplicate charge is returned. Never reserve again or revive released goods.
                        compensation(row);state="COMPENSATION_PENDING";
                    }
                } else if("REFUND".equals(row.get("kind"))) {
                    require(refund!=null,"退款履约处理器不可用",503);refund.complete(String.valueOf(row.get("after_sales_id")),String.valueOf(row.get("provider_reference")),number(row.get("actor_id")));
                } else if("COMPENSATION".equals(row.get("kind"))) {
                    jdbc.update("UPDATE commerce_payment_operation SET local_status='COMPENSATED',updated_at=CURRENT_TIMESTAMP WHERE operation_id=?",row.get("parent_operation_id"));
                    jdbc.update("UPDATE commerce_payment_attempt SET outcome='COMPENSATED' WHERE order_id=? AND request_key=(SELECT request_key FROM commerce_payment_operation WHERE operation_id=?)",row.get("order_id"),row.get("parent_operation_id"));
                }
            }
            jdbc.update("UPDATE commerce_payment_operation SET local_status=?,applied_revision=?,updated_at=CURRENT_TIMESTAMP WHERE operation_id=?",state,revision,id);
            if(!"PENDING".equals(state)&&!"COMPENSATION_PENDING".equals(state))jdbc.update("UPDATE commerce_payment_operation SET next_query_at=NULL WHERE operation_id=?",id);
            if("PAYMENT".equals(row.get("kind")))jdbc.update("UPDATE commerce_payment_attempt SET outcome=? WHERE order_id=? AND request_key=?","COMPENSATION_PENDING".equals(state)?"UNKNOWN":state,row.get("order_id"),row.get("request_key"));
            if("REFUND".equals(row.get("kind")))jdbc.update("UPDATE commerce_refund_attempt SET outcome=? WHERE after_sales_id=? AND request_key=?",state,row.get("after_sales_id"),row.get("request_key"));
            mark(eventId,"APPLIED");return shape(operation(id,false));
        });
        if("COMPENSATION_PENDING".equals(applied.get("outcome"))) {
            String compensation=jdbc.queryForObject("SELECT operation_id FROM commerce_payment_operation WHERE parent_operation_id=? AND shop_id=?",String.class,id,shop());
            execute(compensation,null,null);
            return detail(id);
        }
        return applied;
    }

    public Map<String,Object> replay(String eventId,Consumer<String> expire,RefundHandler refund) {
        List<String> events=jdbc.queryForList("SELECT payload FROM commerce_provider_event WHERE event_id=? AND shop_id=?",String.class,eventId,shop());
        require(events.size()==1,"渠道事件不存在",404);return applyEvent(JSON.parseObject(events.get(0)),expire,refund);
    }
    public Map<String,Object> reconciliation() {
        List<Map<String,Object>> orders=jdbc.queryForList("SELECT o.order_id AS orderId,o.status AS orderStatus,o.total_amount AS orderAmount,o.refunded_amount AS businessRefunded,COALESCE(SUM(CASE WHEN c.movement='CHARGE' THEN c.amount ELSE 0 END),0) AS channelCharged,COALESCE(SUM(CASE WHEN c.movement='REFUND' THEN c.amount ELSE 0 END),0) AS channelRefunded FROM commerce_order o LEFT JOIN commerce_channel_entry c ON c.order_id=o.order_id WHERE o.shop_id=? AND EXISTS(SELECT 1 FROM commerce_payment_operation p WHERE p.order_id=o.order_id AND p.kind='PAYMENT') GROUP BY o.order_id,o.status,o.total_amount,o.refunded_amount,o.create_time HAVING (CASE WHEN o.status IN(1,2,3) THEN o.total_amount ELSE 0 END-o.refunded_amount)<>COALESCE(SUM(CASE WHEN c.movement='CHARGE' THEN c.amount ELSE -c.amount END),0) ORDER BY o.create_time DESC LIMIT 200",shop());
        List<Map<String,Object>> differences=new ArrayList<>();
        for(Map<String,Object> row:orders){BigDecimal paid=number(row.get("orderStatus"))>=1&&number(row.get("orderStatus"))<=3?decimal(row.get("orderAmount")):BigDecimal.ZERO;
            BigDecimal expected=paid.subtract(decimal(row.get("businessRefunded"))),net=decimal(row.get("channelCharged")).subtract(decimal(row.get("channelRefunded")));
            if(expected.compareTo(net)!=0){row.put("expectedNet",expected);row.put("channelNet",net);row.put("difference",net.subtract(expected));differences.add(row);}
        }
        List<Map<String,Object>> pending=new ArrayList<>();for(Map<String,Object> row:jdbc.queryForList("SELECT * FROM commerce_payment_operation WHERE shop_id=? AND local_status IN('PREPARED','PENDING','UNKNOWN','COMPENSATION_PENDING') ORDER BY created_at LIMIT 100",shop()))pending.add(shape(row));
        List<Map<String,Object>> inbox=jdbc.queryForList("SELECT event_id AS eventId,operation_id AS operationId,processing_status AS status,created_at AS createdAt FROM commerce_provider_event WHERE shop_id=? AND processing_status='RECEIVED' ORDER BY created_at LIMIT 100",shop());
        Long legacy=jdbc.queryForObject("SELECT COUNT(*) FROM commerce_order o WHERE o.shop_id=? AND o.status IN(1,2,3) AND NOT EXISTS(SELECT 1 FROM commerce_payment_operation p WHERE p.order_id=o.order_id AND p.kind='PAYMENT')",Long.class,shop());
        return map("provider","LOCAL_SANDBOX","healthy",differences.isEmpty()&&pending.isEmpty()&&inbox.isEmpty(),"differences",differences,"pendingOperations",pending,"unappliedEvents",inbox,"coverage","ALL_TRACKED_ORDERS","differenceDisplayLimit",200,"untrackedLegacyOrders",legacy,"externalChannel",false);
    }
    public Map<String,Object> detail(String id) {return shape(operation(id,false));}
    private void compensation(Map<String,Object> payment) {
        List<String> old=jdbc.queryForList("SELECT operation_id FROM commerce_payment_operation WHERE parent_operation_id=?",String.class,payment.get("operation_id"));
        if(!old.isEmpty())return;
        String id=insert("COMPENSATION",String.valueOf(payment.get("order_id")),null,String.valueOf(payment.get("operation_id")),"late-charge-refund","success",decimal(payment.get("amount")),null,String.valueOf(payment.get("operation_id")));
    }
    private String insert(String kind,String order,String after,String business,String key,String scenario,BigDecimal amount,Long actor,String parent) {
        String id="PO"+UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT);
        jdbc.update("INSERT INTO commerce_payment_operation(operation_id,shop_id,order_id,after_sales_id,kind,business_key,request_key,scenario,amount,actor_id,local_status,provider_reference,parent_operation_id,next_query_at,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,'PREPARED',?,?,TIMESTAMPADD(SECOND,5,CURRENT_TIMESTAMP),CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id,shop(),order,after,kind,business,key,scenario,amount,actor,"LOCAL-"+id,parent);return id;
    }
    private void providerResult(Map<String,Object> row,String result) {
        jdbc.update("UPDATE commerce_payment_operation SET provider_status=?,provider_revision=provider_revision+1,updated_at=CURRENT_TIMESTAMP WHERE operation_id=?",result,row.get("operation_id"));
        if("SUCCEEDED".equals(result))jdbc.update("INSERT INTO commerce_channel_entry(entry_id,operation_id,shop_id,order_id,movement,amount,currency,created_at) VALUES (?,?,?,?,?,?,'CNY',CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE entry_id=VALUES(entry_id)","CE"+row.get("operation_id"),row.get("operation_id"),shop(),row.get("order_id"),"PAYMENT".equals(row.get("kind"))?"CHARGE":"REFUND",row.get("amount"));
    }
    private Map<String,Object> operation(String id,boolean lock) {
        require(id!=null&&id.matches("PO[A-F0-9]{32}"),"渠道请求不存在",404);
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM commerce_payment_operation WHERE operation_id=? AND shop_id=?"+(lock?" FOR UPDATE":""),id,shop());require(rows.size()==1,"渠道请求不存在",404);return rows.get(0);
    }
    private Map<String,Object> event(Map<String,Object> row,String source) {return map("eventId",source+"_"+row.get("operation_id")+"_"+row.get("provider_revision"),"operationId",row.get("operation_id"),"status",row.get("provider_status"),"revision",row.get("provider_revision"),"amount",row.get("amount"),"currency","CNY","providerReference",row.get("provider_reference"));}
    private Map<String,Object> shape(Map<String,Object> row) {return map("operationId",row.get("operation_id"),"orderId",row.get("order_id"),"afterSalesId",row.get("after_sales_id"),"kind",row.get("kind"),"outcome",row.get("local_status"),"local_status",row.get("local_status"),"providerStatus",row.get("provider_status"),"amount",row.get("amount"),"currency",row.get("currency"),"providerReference",row.get("provider_reference"),"scenario",row.get("scenario"),"updatedAt",row.get("updated_at"),"provider","LOCAL_SANDBOX");}
    private void mark(String id,String state){jdbc.update("UPDATE commerce_provider_event SET processing_status=?,applied_at=CURRENT_TIMESTAMP WHERE event_id=?",state,id);}
    private static String shop(){return CommerceShopContext.id();}
    private static void scenario(String scenario,boolean refund){require(Arrays.asList("success","failure","timeout_after_success","delayed_success").contains(scenario)||(refund&&"refund_pending".equals(scenario)),"沙箱场景无效",400);}
    private static boolean expired(Map<String,Object> order){Object raw=order.get("expires_at");return raw!=null&&!(raw instanceof java.sql.Timestamp?((java.sql.Timestamp)raw).toLocalDateTime():LocalDateTime.parse(String.valueOf(raw).replace(' ','T'))).isAfter(LocalDateTime.now());}
    private static long number(Object value){return value==null?0:((Number)value).longValue();}
    private static long longValue(Object value){require(value instanceof Number&&String.valueOf(value).matches("[1-9][0-9]{0,17}"),"渠道版本格式错误",400);return ((Number)value).longValue();}
    private static BigDecimal decimal(Object value){try{return new BigDecimal(String.valueOf(value));}catch(RuntimeException invalid){throw new ServiceException("渠道金额格式错误",400);}}
    private static String text(Object value,String pattern,String label){require(value instanceof String&&((String)value).matches(pattern),label+"无效",400);return (String)value;}
    private static String digest(String raw){try{return hex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));}catch(java.security.GeneralSecurityException error){throw new IllegalStateException(error);}}
    private static String hex(byte[] bytes){StringBuilder result=new StringBuilder();for(byte value:bytes)result.append(String.format("%02x",value&255));return result.toString();}
    private static void require(boolean condition,String message,int code){if(!condition)throw new ServiceException(message,code);}
    private static Map<String,Object> map(Object...pairs){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
}

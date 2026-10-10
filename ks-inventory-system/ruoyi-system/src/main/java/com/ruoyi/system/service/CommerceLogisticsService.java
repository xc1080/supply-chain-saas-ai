package com.ruoyi.system.service;

import com.ruoyi.common.core.tenant.CommerceShopContext;
import com.ruoyi.common.core.tenant.TenantContext;
import com.ruoyi.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.TimeoutException;

/** Access is checked against the order/case before a provider is called. No tracking cache or
 * provider observation can change order receipts, warehouse stock or after-sales acceptance. */
@Service
@Profile({"local","commerce"})
public class CommerceLogisticsService {
    private final DataSource source;
    private final JdbcTemplate jdbc;
    private final CommerceMerchantService merchants;
    private final CommerceLogisticsProvider provider;
    public CommerceLogisticsService(DataSource source){this(source,new CommerceMerchantService(source),new CommerceLocalLogisticsProvider(source));}
    @Autowired public CommerceLogisticsService(DataSource source,CommerceMerchantService merchants,CommerceLogisticsProvider provider){this.source=source;this.jdbc=new JdbcTemplate(source);this.merchants=merchants;this.provider=provider;}

    public void initializeSchema() {
        ResourceDatabasePopulator script=new ResourceDatabasePopulator(new ClassPathResource("db/commerce-logistics.sql"));
        script.setSqlScriptEncoding("UTF-8");script.execute(source);
        try(Connection connection=source.getConnection();ResultSet columns=connection.getMetaData().getColumns(connection.getCatalog(),null,"commerce_after_sales_case","return_evidence_version")) {
            if(!columns.next())jdbc.execute("ALTER TABLE commerce_after_sales_case ADD COLUMN return_evidence_version SMALLINT NOT NULL DEFAULT 0");
        }catch(java.sql.SQLException error){throw new IllegalStateException("Return evidence migration failed",error);}
    }

    @Transactional(readOnly=true)
    public Map<String,Object> shipmentTracking(String shipmentId,String ownerId) {
        Map<String,Object> row=shipment(shipmentId,ownerId,false);
        Map<String,Object> result=observe(subject("OUTBOUND",shipmentId,String.valueOf(row.get("carrier")),String.valueOf(row.get("tracking_no"))));
        result.put("shipmentId",shipmentId);result.put("orderId",row.get("order_id"));return result;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> recordShipmentEvent(String shipmentId,Map<String,Object> request,long actor) {
        merchants.requireCapability(shop(),actor,CommerceCapability.FULFILMENT);
        Map<String,Object> row=shipment(shipmentId,null,true);
        record(subject("OUTBOUND",shipmentId,String.valueOf(row.get("carrier")),String.valueOf(row.get("tracking_no"))),request,actor);
        return shipmentTracking(shipmentId,null);
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> registerReturnParcel(String id,Map<String,Object> request) {
        String owner=owner(request.get("ownerId")),requestKey=key(request.get("requestKey"));
        String carrier=text(request.get("carrierCode"),1,32,"承运商代码"),tracking=text(request.get("trackingNo"),1,80,"寄回运单号");
        require(carrier.matches("[A-Za-z0-9_-]{1,32}")&&tracking.matches("[A-Za-z0-9_-]{1,80}"),"承运商代码或寄回运单号格式错误",400);
        Map<String,Object> row=caseRow(id,owner,true);List<Map<String,Object>> existing=parcels(id);
        if(!existing.isEmpty()) {
            Map<String,Object> prior=existing.get(0);
            require(requestKey.equals(prior.get("request_key"))&&carrier.equals(prior.get("carrier_code"))&&tracking.equals(prior.get("tracking_no")),"寄回凭证已经登记，不能更改请求编号、承运商或运单号",409);
            return returnParcel(id,owner);
        }
        require("AWAITING_RETURN".equals(row.get("status"))&&"RETURN_REFUND".equals(row.get("kind"))&&number(row.get("return_required"))==1,"只有审核同意的退货售后可以登记寄回凭证",409);
        jdbc.update("INSERT INTO commerce_return_parcel(tenant_id,after_sales_id,shop_id,owner_id,carrier_code,tracking_no,request_key,registered_at) VALUES (?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",TenantContext.id(),id,shop(),owner,carrier,tracking,requestKey);
        jdbc.update("INSERT INTO commerce_after_sales_event(event_key,after_sales_id,event_type,actor_user_id,note,created_at) VALUES (?,?,'RETURN_REGISTERED',NULL,?,CURRENT_TIMESTAMP)","RETURN_PARCEL:"+id,id,carrier+" "+tracking);
        return returnParcel(id,owner);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> returnParcel(String id,String ownerId) {
        Map<String,Object> row=caseRow(id,ownerId,false),result=parcelSummary(row);
        result.put("afterSalesId",id);result.put("orderId",row.get("order_id"));
        if(Boolean.TRUE.equals(result.get("registered")))result.put("tracking",observe(subject("RETURN",id,(String)result.get("carrierCode"),(String)result.get("trackingNo"))));
        else result.put("tracking",null);
        return result;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> recordReturnEvent(String id,Map<String,Object> request,long actor) {
        merchants.requireCapability(shop(),actor,CommerceCapability.FULFILMENT);
        caseRow(id,null,true);List<Map<String,Object>> rows=parcels(id);require(!rows.isEmpty(),"寄回凭证尚未登记",409);
        Map<String,Object> parcel=rows.get(0);
        record(subject("RETURN",id,String.valueOf(parcel.get("carrier_code")),String.valueOf(parcel.get("tracking_no"))),request,actor);
        return returnParcel(id,null);
    }

    /** Called only after AfterSalesService has authorized and locked the case. */
    Map<String,Object> parcelSummary(Map<String,Object> row) {
        String id=String.valueOf(row.get("after_sales_id"));List<Map<String,Object>> rows=parcels(id);
        boolean registered=!rows.isEmpty();Map<String,Object> parcel=registered?rows.get(0):Collections.emptyMap();
        String state=number(row.get("return_required"))!=1?"NOT_REQUIRED":row.get("returned_at")!=null?"RECEIVED":registered?"REGISTERED":"NOT_REGISTERED";
        return map("registered",registered,"status",state,"carrierCode",parcel.get("carrier_code"),"trackingNo",parcel.get("tracking_no"),"registeredAt",time(parcel.get("registered_at")));
    }
    /** Called only with an already authorized case, using the same explicit scope as parcel reads. */
    String receiptEvidence(String id) {
        List<String> rows=jdbc.queryForList("SELECT evidence FROM commerce_return_receipt_evidence WHERE tenant_id=? AND shop_id=? AND after_sales_id=?",String.class,TenantContext.id(),shop(),id);
        return rows.isEmpty()?null:rows.get(0);
    }
    /** Evidence registration is not stock receipt. Only the caller's acceptance transaction restocks. */
    void requireReceiptEvidence(Map<String,Object> row,Map<String,Object> request,long actor) {
        String id=String.valueOf(row.get("after_sales_id"));boolean registered=!parcels(id).isEmpty();Object raw=request.get("receiptEvidence");
        require(number(row.get("return_evidence_version"))==0||registered||raw!=null,"新退货售后必须登记寄回运单，或提供商家人工实收凭证后验收",409);
        if(raw==null)return;
        String evidence=text(raw,1,200,"人工实收凭证");
        List<String> previous=jdbc.queryForList("SELECT evidence FROM commerce_return_receipt_evidence WHERE tenant_id=? AND after_sales_id=? AND shop_id=?",String.class,TenantContext.id(),id,shop());
        if(!previous.isEmpty()){require(evidence.equals(previous.get(0)),"同一退货验收不能更改人工实收凭证",409);return;}
        jdbc.update("INSERT INTO commerce_return_receipt_evidence(tenant_id,after_sales_id,shop_id,evidence,actor_user_id,recorded_at) VALUES (?,?,?,?,?,CURRENT_TIMESTAMP)",TenantContext.id(),id,shop(),evidence,actor);
    }

    private Map<String,Object> shipment(String id,String ownerId,boolean lock) {
        require(id!=null&&id.matches("SH[0-9]{14}[A-F0-9]{10}"),"发货包裹不存在",404);String ownerIdValue=ownerId==null?null:owner(ownerId);
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT s.*,o.shop_id,o.owner_id FROM commerce_shipment s JOIN commerce_order o ON o.order_id=s.order_id WHERE s.shipment_id=? AND o.shop_id=?",id,shop());
        require(rows.size()==1&&(ownerIdValue==null||ownerIdValue.equals(rows.get(0).get("owner_id"))),"发货包裹不存在",404);
        if(lock)jdbc.queryForList("SELECT order_id FROM commerce_order WHERE order_id=? AND shop_id=? FOR UPDATE",rows.get(0).get("order_id"),shop());
        return rows.get(0);
    }
    private Map<String,Object> caseRow(String id,String ownerId,boolean lock) {
        require(id!=null&&id.matches("AS[0-9]{14}[A-F0-9]{10}"),"售后不存在",404);String ownerValue=ownerId==null?null:owner(ownerId);
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT c.* FROM commerce_after_sales_case c JOIN commerce_order o ON o.order_id=c.order_id AND o.shop_id=c.shop_id AND o.owner_id=c.owner_id WHERE c.after_sales_id=? AND c.shop_id=?",id,shop());
        require(rows.size()==1&&(ownerValue==null||ownerValue.equals(rows.get(0).get("owner_id"))),"售后不存在",404);
        if(lock) {
            jdbc.queryForList("SELECT order_id FROM commerce_order WHERE order_id=? AND shop_id=? FOR UPDATE",rows.get(0).get("order_id"),shop());
            rows=jdbc.queryForList("SELECT * FROM commerce_after_sales_case WHERE after_sales_id=? AND shop_id=? FOR UPDATE",id,shop());
        }
        return rows.get(0);
    }
    private List<Map<String,Object>> parcels(String id){return jdbc.queryForList("SELECT * FROM commerce_return_parcel WHERE tenant_id=? AND shop_id=? AND after_sales_id=?",TenantContext.id(),shop(),id);}
    private CommerceLogisticsProvider.Subject subject(String type,String id,String carrier,String tracking){return new CommerceLogisticsProvider.Subject(TenantContext.id(),shop(),type,id,carrier,tracking);}
    private Map<String,Object> observe(CommerceLogisticsProvider.Subject subject) {
        Map<String,Object> result=map("tenantId",subject.tenantId,"shopId",subject.shopId,"carrierCode",subject.carrierCode,"trackingNo",subject.trackingNo,"source",provider.source(),"provider",provider.name());
        try {
            CommerceLogisticsProvider.Observation observation=provider.query(subject);List<Map<String,Object>> events=observation.events;
            String status=events.isEmpty()?"NO_OBSERVATION":String.valueOf(events.get(events.size()-1).get("status"));
            result.put("observedAt",observation.observedAt);result.put("status",status);result.put("statusLabel",statusLabel(status));result.put("queryStatus","AVAILABLE");result.put("events",events);
        }catch(TimeoutException error) {
            result.put("observedAt",LocalDateTime.now().toString());result.put("status","PROVIDER_TIMEOUT");result.put("statusLabel","物流服务查询超时");result.put("queryStatus","UNAVAILABLE");result.put("events",Collections.emptyList());
        }catch(RuntimeException error) {
            // Adapter errors reveal no credentials or response body and apply no business transition.
            result.put("observedAt",LocalDateTime.now().toString());result.put("status","PROVIDER_ERROR");result.put("statusLabel","物流服务暂时不可用");result.put("queryStatus","UNAVAILABLE");result.put("events",Collections.emptyList());
        }
        return result;
    }
    private void record(CommerceLogisticsProvider.Subject subject,Map<String,Object> request,long actor) {
        require(provider instanceof CommerceLocalLogisticsProvider,"当前物流Provider不支持模拟节点登记",409);
        String requestKey=key(request.get("requestKey")),status=text(request.get("status"),1,24,"模拟承运节点状态");
        require(Arrays.asList("ACCEPTED","IN_TRANSIT","OUT_FOR_DELIVERY","DELIVERED","EXCEPTION").contains(status),"模拟承运节点状态无效",400);
        String description=text(request.get("description"),1,200,"节点说明"),location=request.get("location")==null?null:text(request.get("location"),0,100,"节点地点");
        LocalDateTime occurred;
        try{occurred=LocalDateTime.parse(text(request.get("occurredAt"),1,40,"节点发生时间"));}catch(DateTimeParseException error){throw new ServiceException("节点发生时间必须为ISO本地日期时间",400);}
        require(occurred.getNano()==0,"节点发生时间必须精确到秒",400);
        ((CommerceLocalLogisticsProvider)provider).record(subject,requestKey,status,occurred,location,description,actor);
    }
    static String statusLabel(String status) {
        switch(status){case "ACCEPTED":return "模拟承运商已揽收";case "IN_TRANSIT":return "模拟运输中";case "OUT_FOR_DELIVERY":return "模拟派送中";case "DELIVERED":return "模拟已送达";case "EXCEPTION":return "模拟运输异常";default:return "暂无模拟承运节点";}
    }
    private static String owner(Object value){require(value instanceof String&&((String)value).matches("[a-f0-9]{64}"),"访客标识错误",400);return (String)value;}
    private static String key(Object value){require(value instanceof String&&((String)value).matches("[A-Za-z0-9_-]{1,80}"),"请求编号格式错误",400);return (String)value;}
    private static String text(Object value,int min,int max,String label){require(value instanceof String,label+"格式错误",400);String result=((String)value).trim();require(result.length()>=min&&result.length()<=max&&!result.matches("(?s).*[\\p{Cntrl}].*"),label+"长度或内容无效",400);return result;}
    private static long number(Object value){return value==null?0:((Number)value).longValue();}
    private static String shop(){return CommerceShopContext.id();}
    private static String time(Object value){return value==null?null:String.valueOf(value).replace(' ','T').replace(".0","");}
    private static void require(boolean condition,String message,int code){if(!condition)throw new ServiceException(message,code);}
    private static Map<String,Object> map(Object...values){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]);return result;}
}

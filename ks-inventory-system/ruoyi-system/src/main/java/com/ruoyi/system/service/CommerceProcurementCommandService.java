package com.ruoyi.system.service;

import com.ruoyi.common.core.domain.entity.DetailReceipt;
import com.ruoyi.common.core.domain.entity.ReceiptFrom;
import com.ruoyi.common.exception.ServiceException;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

/** Creates one source-linked ERP draft per human command. Approval and stock posting stay in the receipt guard. */
@Service
@Profile({"local","commerce"})
public class CommerceProcurementCommandService {
    private final JdbcTemplate jdbc;
    private final CommerceProcurementService procurement;
    private final CommerceReceiptInventoryGuard guard;
    public CommerceProcurementCommandService(DataSource source,CommerceProcurementService procurement,CommerceReceiptInventoryGuard guard) {
        this.jdbc=new JdbcTemplate(source);this.procurement=procurement;this.guard=guard;
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> createReceipt(String order,Map<String,Object> body,long actor,String username,boolean returned) {
        require(order!=null&&order.matches("[A-Za-z0-9_-]{1,32}"),"采购订单编号格式错误",400);
        require(actor>0&&body!=null,"采购命令参数错误",400);
        String key=String.valueOf(body.get("requestKey"));
        require(key.matches("[A-Za-z0-9:_-]{8,80}"),"采购请求键格式错误",400);
        Object raw=body.get("items");
        require(raw instanceof List&&!((List<?>)raw).isEmpty()&&((List<?>)raw).size()<=100,"请选择1至100条采购明细",400);
        String sourceField=returned?"sourceReceiptLineId":"purchaseLineId";
        SortedMap<String,Map<String,Object>> selected=new TreeMap<>();
        for(Object item:(List<?>)raw) {
            require(item instanceof Map,"采购明细格式错误",400);
            Map<?,?> input=(Map<?,?>)item;String source=String.valueOf(input.get(sourceField));
            require(source.matches("[A-Za-z0-9_-]{1,64}"),"来源采购行格式错误",400);
            long quantity=quantity(input.get("quantity"));
            Map<String,Object> normalized=new LinkedHashMap<>();normalized.put("source",source);normalized.put("quantity",quantity);
            if(input.get("warehouseId")!=null)normalized.put("warehouseId",quantity(input.get("warehouseId")));
            require(selected.put(source,normalized)==null,"同一来源行不能重复选择",400);
        }
        String kind=returned?"RETURN":"RECEIVE",fingerprint=hash(selected.toString());
        // The command mutex precedes receipt -> source order -> product locks.
        // No order editor takes this mutex or a receipt lock in reverse order.
        jdbc.update("INSERT INTO commerce_purchase_command(order_id,actor_id,command_kind,request_key,request_hash,created_at) VALUES (?,?,?,?,?,CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE request_key=VALUES(request_key)",order,actor,kind,key,fingerprint);
        Map<String,Object> command=jdbc.queryForMap("SELECT * FROM commerce_purchase_command WHERE order_id=? AND actor_id=? AND command_kind=? AND request_key=? FOR UPDATE",order,actor,kind,key);
        require(fingerprint.equals(command.get("request_hash")),"同一请求键不能改变采购来源或数量",409);
        if(command.get("receipt_id")!=null) {
            String receipt=String.valueOf(command.get("receipt_id"));
            List<Map<String,Object>> saved=jdbc.queryForList("SELECT receipt_status FROM head_receipt WHERE systematic_receipt=?",receipt);
            require(saved.size()==1,"原采购草稿已删除，请使用新的请求键",409);
            return result(receipt,Long.parseLong(String.valueOf(saved.get(0).get("receipt_status"))));
        }
        Map<String,Object> progress=procurement.progress(order);
        List<Map<String,Object>> available;
        if(returned)available=procurement.receiptSources(order);
        else available=(List<Map<String,Object>>)progress.get("lines");
        Map<String,Map<String,Object>> indexed=new HashMap<>();
        for(Map<String,Object> line:available)indexed.put(String.valueOf(line.get(sourceField)),line);
        List<Map<String,Object>> heads=jdbc.queryForList("SELECT * FROM head_order_form WHERE systematic_order_form=? AND order_form_type='1' AND order_form_status='2'",order);
        require(heads.size()==1,"仅已审核的采购订单可生成收货或退供草稿",409);
        Map<String,Object> head=heads.get(0);
        String receiptId=(returned?"PT":"PR")+UUID.randomUUID().toString().replace("-","").substring(0,30);
        ReceiptFrom receipt=new ReceiptFrom();receipt.setSystematicReceipt(receiptId);receipt.setOriginalReceipt(order);
        receipt.setReceiptCategory(1L);receipt.setReceiptType(returned?2L:1L);receipt.setReceiptStatus(1L);
        receipt.setInvoiceDate(LocalDate.now().toString());receipt.setSupplierIds(String.valueOf(head.get("supplier_ids")));
        receipt.setUserIds(String.valueOf(actor));receipt.setCreateBy(username);receipt.setUpdateBy(username);
        receipt.setReceiptNotes(returned?"来源采购订单退供草稿":"来源采购订单分批收货草稿");
        List<DetailReceipt> details=new ArrayList<>();BigDecimal total=BigDecimal.ZERO;
        Long commonWarehouse=null;
        for(Map.Entry<String,Map<String,Object>> item:selected.entrySet()) {
            Map<String,Object> line=indexed.get(item.getKey());
            require(line!=null,"来源行不属于该采购订单或没有有效入库证据",409);
            long amount=(Long)item.getValue().get("quantity"),warehouse=quantity(line.get("warehouseId"));
            if(item.getValue().containsKey("warehouseId"))
                require(warehouse==((Long)item.getValue().get("warehouseId")),"收货仓库必须与来源采购行一致",409);
            long remaining=number(line.get(returned?"availableReturnQuantity":"remainingToReceive"));
            require(amount<=remaining,"数量超过来源行剩余可收或可退数量",409);
            DetailReceipt detail=new DetailReceipt();detail.setSystematicReceipt(receiptId);
            detail.setProductId(String.valueOf(line.get("productId")));detail.setSupplierId(receipt.getSupplierIds());
            detail.setWarehousingId(String.valueOf(warehouse));detail.setRetrievalId(String.valueOf(warehouse));
            detail.setPlanQuantity(String.valueOf(amount));
            BigDecimal price=decimal(line.get("unitPrice")),discount=line.get("discount")==null?BigDecimal.ONE:decimal(line.get("discount"));
            BigDecimal money=price.multiply(discount).multiply(BigDecimal.valueOf(amount)).setScale(2,RoundingMode.HALF_UP);
            detail.setUnivalence(price.toPlainString());detail.setDiscount(discount.toPlainString());
            detail.setMoney(money.toPlainString());detail.setCost(money.toPlainString());
            detail.setSourcePurchaseLineId(String.valueOf(returned?line.get("sourcePurchaseLineId"):line.get("purchaseLineId")));
            if(returned)detail.setSourceReceiptLineId(item.getKey());
            if(line.get("productSpecifications")!=null)detail.setProductSpecifications(String.valueOf(line.get("productSpecifications")));
            if(line.get("measureUnit")!=null)detail.setMeasureUnit(String.valueOf(line.get("measureUnit")));
            details.add(detail);total=total.add(money);
            if(commonWarehouse==null)commonWarehouse=warehouse;
        }
        receipt.setWarehousingIds(String.valueOf(commonWarehouse));receipt.setRetrievalIds(String.valueOf(commonWarehouse));
        receipt.setTotalAmount(total.toPlainString());receipt.setDetails(details);
        // Rechecks the source under its lock; a concurrent draft may have consumed the balance.
        guard.save(receipt);
        jdbc.update("UPDATE commerce_purchase_command SET receipt_id=? WHERE order_id=? AND actor_id=? AND command_kind=? AND request_key=?",receiptId,order,actor,kind,key);
        return result(receiptId,1L);
    }
    private static Map<String,Object> result(String receipt,long status) {
        Map<String,Object> result=new LinkedHashMap<>();result.put("receiptId",receipt);result.put("receiptStatus",status);return result;
    }
    private static long quantity(Object value) {
        try {long number=new BigDecimal(String.valueOf(value)).longValueExact();require(number>0,"数量和仓库编号必须是正整数",400);return number;}
        catch(NumberFormatException|ArithmeticException error){throw new ServiceException("数量和仓库编号必须是正整数",400);}
    }
    private static long number(Object value){return new BigDecimal(String.valueOf(value)).longValueExact();}
    private static BigDecimal decimal(Object value){try{return new BigDecimal(String.valueOf(value));}catch(Exception error){throw new ServiceException("来源采购价格证据缺失",409);}}
    private static String hash(String value){try{StringBuilder result=new StringBuilder();for(byte item:MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)))result.append(String.format("%02x",item));return result.toString();}catch(Exception error){throw new IllegalStateException(error);}}
    private static void require(boolean ok,String message,int code){if(!ok)throw new ServiceException(message,code);}
}

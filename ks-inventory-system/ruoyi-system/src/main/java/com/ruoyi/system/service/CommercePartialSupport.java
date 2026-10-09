package com.ruoyi.system.service;

import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.util.*;

/** Shared projections and one-way, replay-safe migration. Existing money/stock balances are untouched. */
final class CommercePartialSupport {
    private CommercePartialSupport() { }
    static void migrate(JdbcTemplate jdbc) {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM commerce_partial_migration WHERE migration_id='PARTIAL_V1'",Long.class)>0){migrateReturnCondition(jdbc);return;}
        String columns="after_sales_id,order_id,shop_id,owner_id,request_key,reason,status,original_order_status,return_required,refund_amount,refunded_amount,review_key,review_note,return_key,return_receipt_id,refund_id,created_at,reviewed_at,returned_at,refunded_at";
        jdbc.update("INSERT INTO commerce_after_sales_case("+columns+",kind) SELECT "+columns+",CASE WHEN return_required=1 THEN 'RETURN_REFUND' ELSE 'UNSHIPPED_REFUND' END FROM commerce_after_sales old WHERE NOT EXISTS(SELECT 1 FROM commerce_after_sales_case migrated WHERE migrated.after_sales_id=old.after_sales_id)");
        jdbc.update("INSERT INTO commerce_after_sales_item(after_sales_id,product_id,product_code,product_name,spec,quantity,unit_price,amount) SELECT a.after_sales_id,i.product_id,i.product_code,i.product_name,i.spec,i.quantity,i.unit_price,i.amount FROM commerce_after_sales a JOIN commerce_order_item i ON i.order_id=a.order_id WHERE NOT EXISTS(SELECT 1 FROM commerce_after_sales_item migrated WHERE migrated.after_sales_id=a.after_sales_id AND migrated.product_id=i.product_id)");
        jdbc.update("INSERT INTO commerce_fulfillment_line(order_id,product_id,shipped,returned,released,refunded) SELECT i.order_id,i.product_id,CASE WHEN o.status IN(2,3) OR h.status IN('DISPATCHED','RETURNED') THEN i.quantity ELSE 0 END,CASE WHEN h.status='RETURNED' OR a.returned_at IS NOT NULL THEN i.quantity ELSE 0 END,CASE WHEN o.status=4 OR h.status='RELEASED' OR (o.status=1 AND o.refunded_amount=o.total_amount) THEN i.quantity ELSE 0 END,CASE WHEN o.refunded_amount=o.total_amount AND o.refunded_amount>0 THEN i.quantity ELSE 0 END FROM commerce_order_item i JOIN commerce_order o ON o.order_id=i.order_id LEFT JOIN commerce_stock_hold h ON h.order_id=i.order_id AND h.product_id=i.product_id LEFT JOIN commerce_after_sales a ON a.order_id=i.order_id WHERE NOT EXISTS(SELECT 1 FROM commerce_fulfillment_line f WHERE f.order_id=i.order_id AND f.product_id=i.product_id)");
        jdbc.update("INSERT INTO commerce_shipment(shipment_id,order_id,request_key,request_hash,receipt_id,carrier,tracking_no,amount,created_at) SELECT CONCAT('SH',SUBSTRING(o.order_id,3)),o.order_id,'LEGACY_FULL',REPEAT('0',64),o.receipt_id,COALESCE(o.carrier,'演示物流'),COALESCE(o.tracking_no,'LEGACY'),o.total_amount,COALESCE(o.shipped_time,o.create_time) FROM commerce_order o WHERE o.receipt_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM commerce_shipment s WHERE s.receipt_id=o.receipt_id)");
        jdbc.update("INSERT INTO commerce_shipment_item(shipment_id,product_id,quantity) SELECT s.shipment_id,i.product_id,i.quantity FROM commerce_shipment s JOIN commerce_order_item i ON i.order_id=s.order_id WHERE s.request_key='LEGACY_FULL' AND NOT EXISTS(SELECT 1 FROM commerce_shipment_item si WHERE si.shipment_id=s.shipment_id AND si.product_id=i.product_id)");
        jdbc.update("INSERT INTO commerce_return_allocation(after_sales_id,source_receipt_id,product_id,warehouse_id,quantity) SELECT a.after_sales_id,o.receipt_id,l.product_id,l.warehouse_id,SUM(l.delta_quantity) FROM commerce_after_sales a JOIN commerce_order o ON o.order_id=a.order_id JOIN commerce_warehouse_ledger l ON l.receipt_id=a.return_receipt_id AND l.operation='RETURN_ACCEPT' WHERE o.receipt_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM commerce_return_allocation r WHERE r.after_sales_id=a.after_sales_id AND r.source_receipt_id=o.receipt_id AND r.product_id=l.product_id AND r.warehouse_id=l.warehouse_id) GROUP BY a.after_sales_id,o.receipt_id,l.product_id,l.warehouse_id");
        jdbc.update("INSERT INTO commerce_partial_migration(migration_id,migrated_at) VALUES ('PARTIAL_V1',CURRENT_TIMESTAMP)");
        migrateReturnCondition(jdbc);
    }
    /** Previously only SELLABLE returns were accepted. Preserve that fact without touching balances. */
    private static void migrateReturnCondition(JdbcTemplate jdbc) {
        jdbc.update("UPDATE commerce_after_sales_case SET return_condition='SELLABLE' WHERE return_condition IS NULL AND (return_key IS NOT NULL OR return_receipt_id IS NOT NULL OR returned_at IS NOT NULL)");
    }
    static List<Map<String,Object>> lines(JdbcTemplate jdbc,String orderId) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT i.*,COALESCE(f.shipped,0) AS shipped,COALESCE(f.returned,0) AS returned,COALESCE(f.released,0) AS released,COALESCE(f.refunded,0) AS refunded FROM commerce_order_item i LEFT JOIN commerce_fulfillment_line f ON f.order_id=i.order_id AND f.product_id=i.product_id WHERE i.order_id=? ORDER BY i.product_id",orderId);
        for(Map<String,Object> line:rows) {
            long product=n(line.get("product_id"));
            long pendingUnshipped=pending(jdbc,orderId,product,"UNSHIPPED_REFUND"),pendingReturn=pending(jdbc,orderId,product,"RETURN_REFUND");
            line.put("unshipped_available",Math.max(0,n(line.get("quantity"))-n(line.get("shipped"))-n(line.get("released"))-pendingUnshipped));
            line.put("return_available",Math.max(0,n(line.get("shipped"))-n(line.get("returned"))-pendingReturn));
        }
        return rows;
    }
    static long pending(JdbcTemplate jdbc,String order,long product,String kind) {
        String state="RETURN_REFUND".equals(kind)?" AND a.status<>'RETURN_RECEIVED'":"";
        return jdbc.queryForObject("SELECT COALESCE(SUM(i.quantity),0) FROM commerce_after_sales_item i JOIN commerce_after_sales_case a ON a.after_sales_id=i.after_sales_id WHERE a.order_id=? AND i.product_id=? AND a.kind=? AND a.status NOT IN('REFUNDED','REJECTED')"+state,Long.class,order,product,kind);
    }
    static void decorate(JdbcTemplate jdbc,Map<String,Object> result) {
        String order=String.valueOf(result.get("orderId"));List<Map<String,Object>> facts=lines(jdbc,order);
        Map<Long,Map<String,Object>> index=new HashMap<>();for(Map<String,Object> fact:facts)index.put(n(fact.get("product_id")),fact);
        BigDecimal shipped=BigDecimal.ZERO,returned=BigDecimal.ZERO,cancelled=BigDecimal.ZERO;long orderedCount=0,shippedCount=0,returnedCount=0,unshippedCount=0;
        for(Object value:(List<?>)result.get("items")) {
            Map<String,Object> item=(Map<String,Object>)value,fact=index.get(n(item.get("productId")));long qty=n(fact.get("quantity")),sent=n(fact.get("shipped")),back=n(fact.get("returned")),released=n(fact.get("released"));
            item.put("orderedQuantity",qty);item.put("shippedQuantity",sent);item.put("returnedQuantity",back);item.put("cancelledQuantity",released);item.put("refundedQuantity",n(fact.get("refunded")));item.put("unshippedQuantity",qty-sent-released);
            item.put("unshippedRefundAvailableQuantity",fact.get("unshipped_available"));item.put("returnAvailableQuantity",fact.get("return_available"));item.put("afterSalesAvailableQuantity",n(fact.get("unshipped_available"))+n(fact.get("return_available")));
            item.put("shippableQuantity",fact.get("unshipped_available"));BigDecimal price=new BigDecimal(String.valueOf(fact.get("unit_price")));
            shipped=shipped.add(price.multiply(BigDecimal.valueOf(sent)));returned=returned.add(price.multiply(BigDecimal.valueOf(back)));cancelled=cancelled.add(price.multiply(BigDecimal.valueOf(released)));
            orderedCount+=qty;shippedCount+=sent;returnedCount+=back;unshippedCount+=qty-sent-released;
        }
        String fulfillment=shippedCount>0?(unshippedCount>0?"PARTIALLY_SHIPPED":returnedCount==shippedCount?"RETURNED":"SHIPPED"):(unshippedCount==0&&orderedCount>0?"SETTLED":"UNSHIPPED");
        result.put("fulfillmentStatus",fulfillment);result.put("shippedAmount",shipped);result.put("returnedAmount",returned);result.put("cancelledAmount",cancelled);result.put("reservationActive",unshippedCount>0&&n(result.get("orderStatus"))!=4);
        if("PARTIALLY_SHIPPED".equals(fulfillment))result.put("statusName","部分已发货·剩余待处理");
        List<Map<String,Object>> shipments=new ArrayList<>();
        for(Map<String,Object> row:jdbc.queryForList("SELECT * FROM commerce_shipment WHERE order_id=? ORDER BY created_at,shipment_id",order)) {
            List<Map<String,Object>> parts=new ArrayList<>();for(Map<String,Object> item:jdbc.queryForList("SELECT product_id,quantity FROM commerce_shipment_item WHERE shipment_id=? ORDER BY product_id",row.get("shipment_id")))parts.add(map("productId",item.get("product_id"),"quantity",item.get("quantity")));
            shipments.add(map("shipmentId",row.get("shipment_id"),"receiptId",row.get("receipt_id"),"carrier",row.get("carrier"),"trackingNo",row.get("tracking_no"),"amount",row.get("amount"),"createdAt",time(row.get("created_at")),"items",parts));
        }
        result.put("shipments",shipments);List<Map<String,Object>> cases=new ArrayList<>();
        for(Map<String,Object> row:jdbc.queryForList("SELECT * FROM commerce_after_sales_case WHERE order_id=? ORDER BY created_at,after_sales_id",order)) {
            List<Map<String,Object>> parts=new ArrayList<>();for(Map<String,Object> item:jdbc.queryForList("SELECT * FROM commerce_after_sales_item WHERE after_sales_id=? ORDER BY product_id",row.get("after_sales_id")))parts.add(map("productId",item.get("product_id"),"productName",item.get("product_name"),"quantity",item.get("quantity"),"unitPrice",item.get("unit_price"),"amount",item.get("amount")));
            cases.add(map("afterSalesId",row.get("after_sales_id"),"kind",row.get("kind"),"status",row.get("status"),"reason",row.get("reason"),"returnCondition",row.get("return_condition"),"refundAmount",row.get("refund_amount"),"refundedAmount",row.get("refunded_amount"),"items",parts));
        }
        result.put("afterSalesCases",cases);result.put("afterSales",cases);
        List<String> actions=new ArrayList<>();long state=n(result.get("orderStatus"));
        if(state==0){actions.add("PAY");actions.add("CANCEL");}
        if(state==1&&facts.stream().anyMatch(line->n(line.get("unshipped_available"))>0))actions.add("SHIP");
        if(state==2&&shippedCount>returnedCount)actions.add("RECEIVE");
        if(state>=1&&state<=3&&facts.stream().anyMatch(line->n(line.get("unshipped_available"))+n(line.get("return_available"))>0))actions.add("AFTER_SALES");
        result.put("availableActions",actions);
    }
    static long n(Object value){return value==null?0:((Number)value).longValue();}
    static String time(Object value){return value==null?null:String.valueOf(value).replace(".0","");}
    static Map<String,Object> map(Object...pairs){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
}

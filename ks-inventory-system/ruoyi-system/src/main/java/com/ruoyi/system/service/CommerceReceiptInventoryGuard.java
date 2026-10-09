package com.ruoyi.system.service;

import com.ruoyi.common.core.domain.entity.DetailReceipt;
import com.ruoyi.common.core.domain.entity.ReceiptFrom;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.mapper.DetailReceiptMapper;
import com.ruoyi.system.mapper.HeadReceiptMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import com.ruoyi.common.utils.SecurityUtils;

import javax.sql.DataSource;
import java.util.*;

/**
 * Local fusion adapter for ERP receipt posting, including reversals. Inventory is changed by the
 * difference between the old/new approved document effects; never by summing all historical receipts.
 * Lock order: receipt IDs, product IDs, warehouse/inventory IDs. Original non-local behavior is untouched.
 */
@Service
@Profile({"local","commerce"})
public class CommerceReceiptInventoryGuard {
    private final JdbcTemplate jdbc;
    private final CommerceInventoryService inventory;
    private final HeadReceiptMapper heads;
    private final DetailReceiptMapper details;
    private final CommerceWarehouseAllocationService allocations;
    private CommerceCostService costs;
    @Autowired public void configureCosts(CommerceCostService costs) { this.costs=costs; }
    private long costActor() { try { return SecurityUtils.getUserId(); } catch(ServiceException missing) { return 0L; } }

    public CommerceReceiptInventoryGuard(DataSource source, CommerceInventoryService inventory,
                                         HeadReceiptMapper heads, DetailReceiptMapper details) {
        this.jdbc = new JdbcTemplate(source);
        this.inventory = inventory;
        this.heads = heads;
        this.details = details;
        this.allocations = new CommerceWarehouseAllocationService(source);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int save(ReceiptFrom receipt) {
        require(receipt != null, "单据不能为空", 400);
        String id = receiptId(receipt.getSystematicReceipt());
        require(Long.valueOf(1).equals(receipt.getReceiptStatus()) || Long.valueOf(2).equals(receipt.getReceiptStatus()), "单据状态错误", 400);
        require(receipt.getReceiptType() != null && receipt.getReceiptType() >= 1 && receipt.getReceiptType() <= 8, "单据类型错误", 400);
        require(receipt.getDetails() != null && !receipt.getDetails().isEmpty() && receipt.getDetails().size() <= 100, "单据需包含1至100条明细", 400);
        canonicalize(receipt);
        List<String> ids = Collections.singletonList(id);
        lockReceipts(ids);
        Map<Key, Long> before = persistedEffects(ids);
        Map<Key, Long> after = inputEffects(receipt);
        SortedSet<Long> products = productIds(before, after);
        for (DetailReceipt detail : receipt.getDetails()) products.add(positive(detail.getProductId(), "货品编号"));
        SortedSet<Long> warehouseIds = warehouseIds(before, after);
        for (DetailReceipt detail : receipt.getDetails()) {
            long inbound = optionalNumber(detail.getWarehousingId()), outbound = optionalNumber(detail.getRetrievalId());
            if (inbound > 0) warehouseIds.add(inbound);
            if (outbound > 0) warehouseIds.add(outbound);
        }
        Map<Long, List<Map<String,Object>>> warehouses = lockStock(products, warehouseIds);
        validateCounting(receipt, before, warehouses);
        Map<Key, Long> changes = subtract(after, before);
        validateChanges(changes, warehouses);
        List<DetailReceipt> identifiers = Collections.singletonList(identifier(id));
        boolean existed = jdbc.queryForObject("SELECT COUNT(*) FROM head_receipt WHERE systematic_receipt=?", Long.class, id) > 0;
        details.delDetailReceipt(identifiers);
        details.addDetailReceipt(receipt.getDetails());
        if (existed) heads.updateHeadReceipt(receipt); else heads.addHeadReceipt(receipt);
        apply(changes, warehouses, id, id, "SAVE");
        synchronizeTracked(products, "ERP_SAVE:" + id);
        if(costs!=null)costs.recordProcurementReceipt(id,costActor());
        return 1;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int delete(List<DetailReceipt> request) {
        require(request != null && !request.isEmpty() && request.size() <= 100, "请选择1至100张单据", 400);
        SortedSet<String> receipts = new TreeSet<>();
        for (DetailReceipt detail : request) {
            require(detail != null, "单据参数错误", 400);
            receipts.add(receiptId(detail.getSystematicReceipt()));
        }
        List<String> ids = new ArrayList<>(receipts);
        lockReceipts(ids);
        Map<Key, Long> before = persistedEffects(ids);
        SortedSet<Long> products = productIds(before, Collections.emptyMap());
        Map<Long, List<Map<String,Object>>> warehouses = lockStock(products, warehouseIds(before, Collections.emptyMap()));
        Map<Key, Long> changes = subtract(Collections.emptyMap(), before);
        validateChanges(changes, warehouses);
        List<DetailReceipt> identifiers = new ArrayList<>();
        for (String id : ids) identifiers.add(identifier(id));
        // Retain the warehouse reversal journal even when the legacy UI deletes receipt rows.
        apply(changes, warehouses, ids.get(0), String.join(",", ids), "DELETE");
        details.delDetailReceipt(identifiers);
        heads.delHeadReceipt(identifiers);
        synchronizeTracked(products, "ERP_DELETE:" + ids.get(0));
        if(costs!=null)for(String id:ids)costs.reverseProcurementReceipt(id,costActor());
        return 1;
    }

    public void rejectHistoricalImport() {
        throw new ServiceException("本地融合模式请通过单据保存和审核记账，历史单据导入尚未接入库存审核", 409);
    }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> warehouseLedger(long productId, int limit) {
        require(productId > 0 && limit >= 1 && limit <= 200, "库存流水查询参数错误", 400);
        return jdbc.queryForList("SELECT * FROM commerce_warehouse_ledger WHERE product_id=? ORDER BY ledger_id DESC LIMIT ?", productId, limit);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int deleteProducts(Long[] requested) {
        SortedSet<Long> ids = masterIds(requested, "货品");
        Map<Long,Long> totals = new TreeMap<>();
        for (Long id : ids) {
            List<Long> rows = jdbc.queryForList("SELECT inventory_qty FROM product WHERE product_id=? FOR UPDATE", Long.class, id);
            if (!rows.isEmpty()) totals.put(id, rows.get(0) == null ? 0 : rows.get(0));
        }
        for (Map.Entry<Long,Long> product : totals.entrySet()) {
            long id = product.getKey();
            require(product.getValue() == 0, "货品仍有库存，请处理库存后停用，不能硬删除", 409);
            boolean used = referenced("SELECT inventory_id FROM inventory_product WHERE product_id=? LIMIT 1 FOR UPDATE", id)
                    || referenced("SELECT product_id FROM commerce_stock WHERE product_id=? LIMIT 1 FOR UPDATE", id)
                    || referenced("SELECT product_id FROM commerce_stock_hold WHERE product_id=? LIMIT 1 FOR UPDATE", id)
                    || referenced("SELECT ledger_id FROM commerce_stock_ledger WHERE product_id=? LIMIT 1 FOR UPDATE", id)
                    || referenced("SELECT ledger_id FROM commerce_warehouse_ledger WHERE product_id=? LIMIT 1 FOR UPDATE", id)
                    || referenced("SELECT systematic_id FROM detail_receipt WHERE product_id=? LIMIT 1 FOR UPDATE", id)
                    || referenced("SELECT product_id FROM commerce_order_item WHERE product_id=? LIMIT 1 FOR UPDATE", id)
                    || referenced("SELECT activity_id FROM commerce_activity WHERE product_id=? LIMIT 1 FOR UPDATE", id)
                    || referenced("SELECT product_id FROM commerce_product_shop WHERE product_id=? LIMIT 1 FOR UPDATE", id);
            require(!used, "货品已有库存、订单或历史流水引用，请停用，不能硬删除", 409);
        }
        int deleted = 0;
        for (Long id : totals.keySet()) deleted += jdbc.update("DELETE FROM product WHERE product_id=?", id);
        return deleted;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int deleteWarehouses(Long[] requested) {
        SortedSet<Long> ids = masterIds(requested, "仓库");
        SortedSet<Long> products = new TreeSet<>();
        // Discovery is not a lock: the warehouse lock below serializes a new product's inbound posting.
        for (Long id : ids)
            for (Long product : jdbc.queryForList("SELECT product_id FROM inventory_product WHERE warehouse_id=?", Long.class, id))
                if (product != null && product > 0) products.add(product);
        for (Long product : products) jdbc.queryForList("SELECT product_id FROM product WHERE product_id=? FOR UPDATE", product);
        SortedSet<Long> existing = new TreeSet<>();
        for (Long id : ids)
            if (!jdbc.queryForList("SELECT warehouse_id FROM warehouse WHERE warehouse_id=? FOR UPDATE", id).isEmpty()) existing.add(id);
        for (Long id : existing) {
            // Locking reads also see a posting that committed while this transaction waited for a master lock.
            boolean used = referenced("SELECT inventory_id FROM inventory_product WHERE warehouse_id=? LIMIT 1 FOR UPDATE", id)
                    || referenced("SELECT ledger_id FROM commerce_warehouse_ledger WHERE warehouse_id=? LIMIT 1 FOR UPDATE", id)
                    || referenced("SELECT systematic_id FROM detail_receipt WHERE warehousing_id=? OR retrieval_id=? LIMIT 1 FOR UPDATE", id, id);
            require(!used, "仓库已有库存或历史单据流水引用，请停用，不能硬删除", 409);
        }
        int deleted = 0;
        for (Long id : existing) deleted += jdbc.update("DELETE FROM warehouse WHERE warehouse_id=?", id);
        return deleted;
    }

    private void canonicalize(ReceiptFrom receipt) {
        for (DetailReceipt detail : receipt.getDetails()) {
            require(detail != null, "单据明细无效", 400);
            detail.setSystematicReceipt(receipt.getSystematicReceipt());
            positive(detail.getProductId(), "货品编号");
            long type = receipt.getReceiptType();
            long amount = signed(detail.getPlanQuantity(), "数量");
            require(type == 8 || amount > 0, "出入库数量必须大于零", 400);
            if (type == 1 || type == 4 || type == 5 || type == 7 || type == 8) {
                String warehouse = firstPositive(detail.getWarehousingId(), detail.getWarehouseId(), receipt.getWarehousingIds());
                positive(warehouse, "入库仓库"); detail.setWarehousingId(warehouse);
            }
            if (type == 2 || type == 3 || type == 6 || type == 7) {
                String warehouse = firstPositive(detail.getRetrievalId(), receipt.getRetrievalIds(), detail.getWarehouseId());
                positive(warehouse, "出库仓库"); detail.setRetrievalId(warehouse);
            }
            if (type == 7) require(!detail.getWarehousingId().equals(detail.getRetrievalId()), "调入和调出仓库不能相同", 400);
        }
    }

    private void lockReceipts(List<String> receipts) {
        for (String id : receipts) {
            jdbc.update("INSERT INTO commerce_receipt_lock(receipt_id) VALUES (?) ON DUPLICATE KEY UPDATE receipt_id=VALUES(receipt_id)", id);
            List<Map<String,Object>> existing = jdbc.queryForList("SELECT systematic_id FROM head_receipt WHERE systematic_receipt=? FOR UPDATE", id);
            require(existing.size() <= 1, "历史单号存在重复记录，请先核对", 409);
            require(jdbc.queryForObject("SELECT COUNT(*) FROM commerce_warehouse_ledger WHERE receipt_id=? AND operation='RETURN_ACCEPT'",Long.class,id)==0,
                    "商城退货验收单据不能在ERP中直接修改或删除",409);
            require(jdbc.queryForObject("SELECT COUNT(*) FROM commerce_order WHERE receipt_id=?", Long.class, id) == 0,
                    "商城发货单据由订单履约管理，不能在ERP中直接修改或删除", 409);
            require(jdbc.queryForObject("SELECT COUNT(*) FROM commerce_shipment WHERE receipt_id=?",Long.class,id)==0,"商城分批发货单据不能在ERP中直接修改或删除",409);
            require(jdbc.queryForObject("SELECT COUNT(*) FROM commerce_incoming_receipt WHERE receipt_id=?",Long.class,id)==0,"已关联采购在途收货的入库单据不能直接修改或删除",409);
        }
    }

    private Map<Key,Long> persistedEffects(List<String> receipts) {
        Map<Key,Long> result = new TreeMap<>();
        for (String id : receipts) {
            for (Map<String,Object> row : jdbc.queryForList("SELECT dr.product_id,dr.warehousing_id,dr.retrieval_id,dr.plan_quantity,hr.receipt_type,hr.warehousing_ids,hr.retrieval_ids FROM detail_receipt dr JOIN head_receipt hr ON hr.systematic_receipt=dr.systematic_receipt WHERE dr.systematic_receipt=? AND hr.receipt_status='2'", id)) {
                long product = number(row.get("product_id")), type = Long.parseLong(String.valueOf(row.get("receipt_type"))), quantity = number(row.get("plan_quantity"));
                require(product > 0 && type >= 1 && type <= 8, "历史单据明细不完整，请先核对", 409);
                long inbound = firstPositiveNumber(row.get("warehousing_id"), row.get("warehousing_ids"));
                long outbound = firstPositiveNumber(row.get("retrieval_id"), row.get("retrieval_ids"));
                effect(result, product, type, quantity, inbound, outbound);
            }
        }
        return result;
    }

    private Map<Key,Long> inputEffects(ReceiptFrom receipt) {
        Map<Key,Long> result = new TreeMap<>();
        if (!Long.valueOf(2).equals(receipt.getReceiptStatus())) return result;
        for (DetailReceipt detail : receipt.getDetails())
            effect(result, positive(detail.getProductId(), "货品编号"), receipt.getReceiptType(), signed(detail.getPlanQuantity(), "数量"),
                    optionalNumber(detail.getWarehousingId()), optionalNumber(detail.getRetrievalId()));
        return result;
    }

    private void effect(Map<Key,Long> result, long product, long type, long quantity, long inbound, long outbound) {
        if (type == 1 || type == 4 || type == 5 || type == 7 || type == 8) {
            require(inbound > 0, "入库仓库信息不完整", 409);
            add(result, new Key(product,inbound), quantity);
        }
        if (type == 2 || type == 3 || type == 6 || type == 7) {
            require(outbound > 0, "出库仓库信息不完整", 409);
            add(result, new Key(product,outbound), -quantity);
        }
    }

    private Map<Long,List<Map<String,Object>>> lockStock(SortedSet<Long> products, SortedSet<Long> requiredWarehouses) {
        Map<Long,List<Map<String,Object>>> warehouses = new TreeMap<>();
        for (Long product : products)
            require(!jdbc.queryForList("SELECT product_id FROM product WHERE product_id=? FOR UPDATE", product).isEmpty(), "货品不存在", 404);
        SortedSet<Long> masters = new TreeSet<>(requiredWarehouses);
        for (Long product : products) {
            for (Long warehouse : jdbc.queryForList("SELECT warehouse_id FROM inventory_product WHERE product_id=?", Long.class, product))
                if (warehouse != null && warehouse > 0) masters.add(warehouse);
        }
        // All master locks precede ensureStock, which locks warehouse inventory rows.
        for (Long warehouse : masters)
            require(!jdbc.queryForList("SELECT warehouse_id FROM warehouse WHERE warehouse_id=? FOR UPDATE", warehouse).isEmpty(), "仓库不存在", 404);
        for (Long product : products) {
            if (tracked(product)) inventory.ensureStock(product);
            if (tracked(product)) allocations.ensureProduct(product);
            warehouses.put(product, jdbc.queryForList("SELECT inventory_id,warehouse_id,plan_quantity FROM inventory_product WHERE product_id=? ORDER BY warehouse_id,inventory_id FOR UPDATE", product));
        }
        return warehouses;
    }

    private void validateChanges(Map<Key,Long> changes, Map<Long,List<Map<String,Object>>> warehouses) {
        Map<Long,Long> productDeltas = new TreeMap<>();
        for (Map.Entry<Key,Long> entry : changes.entrySet()) {
            if (entry.getValue() == 0) continue;
            productDeltas.put(entry.getKey().product, Math.addExact(productDeltas.getOrDefault(entry.getKey().product,0L),entry.getValue()));
        }
        Map<Long,Map<Long,Long>> relocations = new TreeMap<>();
        Map<Long,SortedSet<Long>> incoming = new TreeMap<>();
        for (Map.Entry<Key,Long> entry : changes.entrySet()) {
            Key key = entry.getKey(); long delta = entry.getValue();
            if (delta == 0) continue;
            long after = Math.addExact(warehouseQuantity(warehouses.get(key.product),key.warehouse),delta);
            long unavailable=jdbc.queryForObject("SELECT COALESCE(SUM(quality_hold+damaged),0) FROM commerce_warehouse_condition WHERE product_id=? AND warehouse_id=?",Long.class,key.product,key.warehouse);
            require(after >= unavailable, "指定仓库可用库存不足，不能侵占待检或损坏库存", 409);
            // A change that only moves a product's goods between warehouses carries its commitments along instead;
            // any net outflow or reversal keeps the original per-warehouse rule and its original failure order.
            if (productDeltas.get(key.product) != 0L)
                require(after-unavailable >= allocations.pending(key.product,key.warehouse), CommerceWarehouseAllocationService.ENCROACH_MESSAGE, 409);
            else {
                relocations.computeIfAbsent(key.product,product -> new TreeMap<>()).put(key.warehouse, after-unavailable);
                if (delta > 0) incoming.computeIfAbsent(key.product,product -> new TreeSet<>()).add(key.warehouse);
            }
        }
        for (Map.Entry<Long,Long> entry : productDeltas.entrySet()) {
            long product = entry.getKey(), delta = entry.getValue();
            long before = jdbc.queryForObject("SELECT inventory_qty FROM product WHERE product_id=? FOR UPDATE", Long.class, product);
            long physical = 0;
            for (Map<String,Object> row : warehouses.get(product)) physical = Math.addExact(physical,number(row.get("plan_quantity")));
            require(before == physical, "货品汇总和仓库库存不一致，请先核对", 409);
            require(Math.addExact(before,delta) >= 0, "货品库存不足", 409);
            if (tracked(product)) {
                long available = jdbc.queryForObject("SELECT on_hand-reserved-activity_reserved FROM commerce_stock WHERE product_id=? FOR UPDATE", Long.class, product);
                require(delta >= 0 || available >= -delta, "出库或冲销将侵占商城订单预留及活动配额，单据未审核", 409);
            }
        }
        for (Map.Entry<Long,Map<Long,Long>> entry : relocations.entrySet())
            allocations.followMove(entry.getKey(),entry.getValue(),incoming.getOrDefault(entry.getKey(),new TreeSet<>()));
    }

    private void validateCounting(ReceiptFrom receipt, Map<Key,Long> before, Map<Long,List<Map<String,Object>>> warehouses) {
        if (receipt.getReceiptType() != 8 || receipt.getReceiptStatus() != 2 || !before.isEmpty()) return;
        Set<Key> seen = new HashSet<>();
        for (DetailReceipt detail : receipt.getDetails()) {
            Key key = new Key(positive(detail.getProductId(),"货品编号"),positive(detail.getWarehousingId(),"盘点仓库"));
            require(seen.add(key), "同一盘点单不能重复同一仓库货品", 400);
            if (detail.getCurrentInventory() != null && detail.getActualInventory() != null) {
                long current = signed(detail.getCurrentInventory(),"盘点基数"), actual = signed(detail.getActualInventory(),"实盘数量");
                require(actual >= 0 && current == warehouseQuantity(warehouses.get(key.product),key.warehouse), "盘点基数已变化，请刷新库存后重新盘点", 409);
                require(actual-current == signed(detail.getPlanQuantity(),"盘点差额"), "实盘数量与盘点差额不一致", 400);
            }
        }
    }

    private void apply(Map<Key,Long> changes, Map<Long,List<Map<String,Object>>> warehouses, String receipt, String relatedReceipts, String operation) {
        String posting = UUID.randomUUID().toString().replace("-","");
        Map<Long,Long> productDeltas = new TreeMap<>();
        for (Map.Entry<Key,Long> entry : changes.entrySet()) {
            Key key=entry.getKey(); long delta=entry.getValue(); if(delta==0) continue;
            List<Map<String,Object>> rows=warehouses.get(key.product);
            long before=warehouseQuantity(rows,key.warehouse), remaining=Math.abs(delta);
            for (Map<String,Object> row:rows) {
                if (number(row.get("warehouse_id"))!=key.warehouse) continue;
                long quantity=number(row.get("plan_quantity"));
                long amount=delta>0?remaining:Math.min(Math.max(0,quantity),remaining);
                if(amount==0) continue;
                long signed=delta>0?amount:-amount;
                require(jdbc.update("UPDATE inventory_product SET plan_quantity=plan_quantity+?,update_by='commerce-erp',update_time=CURRENT_TIMESTAMP WHERE inventory_id=? AND COALESCE(plan_quantity,0)+?>=0",signed,row.get("inventory_id"),signed)==1,"仓库库存已变化",409);
                remaining-=amount; if(remaining==0) break;
            }
            if(remaining>0) {
                require(delta>0,"仓库库存不足",409);
                jdbc.update("INSERT INTO inventory_product(product_id,warehouse_id,supplier_id,plan_quantity,univalence,discount,money,create_by,create_time) VALUES (?,?,0,?,0,100,0,'commerce-erp',CURRENT_TIMESTAMP)",key.product,key.warehouse,remaining);
            }
            jdbc.update("INSERT INTO commerce_warehouse_ledger(event_key,receipt_id,related_receipts,operation,product_id,warehouse_id,delta_quantity,before_quantity,after_quantity,created_at) VALUES (?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",posting+":"+key.product+":"+key.warehouse,receipt,relatedReceipts,operation,key.product,key.warehouse,delta,before,before+delta);
            productDeltas.put(key.product,Math.addExact(productDeltas.getOrDefault(key.product,0L),delta));
        }
        for(Map.Entry<Long,Long> entry:productDeltas.entrySet()) {
            long delta=entry.getValue();
            require(jdbc.update("UPDATE product SET inventory_qty=inventory_qty+?,update_by='commerce-erp',update_time=CURRENT_TIMESTAMP WHERE product_id=? AND inventory_qty+?>=0",delta,entry.getKey(),delta)==1,"货品库存已变化",409);
        }
    }

    private void synchronizeTracked(SortedSet<Long> products,String reason) {
        for(Long product:products) if(tracked(product)) inventory.ensureStock(product,reason);
    }
    private boolean tracked(long product) {return jdbc.queryForObject("SELECT COUNT(*) FROM commerce_stock WHERE product_id=?",Long.class,product)>0;}
    private boolean referenced(String sql, Object... values) { return !jdbc.queryForList(sql, values).isEmpty(); }
    private static SortedSet<Long> masterIds(Long[] requested, String type) {
        require(requested != null && requested.length >= 1 && requested.length <= 100, "请选择1至100个" + type, 400);
        SortedSet<Long> ids = new TreeSet<>();
        for (Long id : requested) { require(id != null && id > 0, type + "编号无效", 400); ids.add(id); }
        return ids;
    }
    private static SortedSet<Long> warehouseIds(Map<Key,Long> before, Map<Key,Long> after) {
        SortedSet<Long> ids = new TreeSet<>();
        for (Key key : before.keySet()) ids.add(key.warehouse);
        for (Key key : after.keySet()) ids.add(key.warehouse);
        return ids;
    }
    private static SortedSet<Long> productIds(Map<Key,Long> before,Map<Key,Long> after) {SortedSet<Long> ids=new TreeSet<>();for(Key key:before.keySet())ids.add(key.product);for(Key key:after.keySet())ids.add(key.product);return ids;}
    private static Map<Key,Long> subtract(Map<Key,Long> after,Map<Key,Long> before) {Map<Key,Long> result=new TreeMap<>(after);for(Map.Entry<Key,Long> entry:before.entrySet())add(result,entry.getKey(),-entry.getValue());return result;}
    private static void add(Map<Key,Long> map,Key key,long quantity) {map.put(key,Math.addExact(map.getOrDefault(key,0L),quantity));}
    private static long warehouseQuantity(List<Map<String,Object>> rows,long warehouse) {long total=0;for(Map<String,Object> row:rows)if(number(row.get("warehouse_id"))==warehouse)total=Math.addExact(total,number(row.get("plan_quantity")));return total;}
    private static DetailReceipt identifier(String id) {DetailReceipt detail=new DetailReceipt();detail.setSystematicReceipt(id);return detail;}
    private static long firstPositiveNumber(Object... values) {for(Object value:values)if(value!=null&&Long.parseLong(String.valueOf(value))>0)return Long.parseLong(String.valueOf(value));return 0;}
    private static String firstPositive(String... values) {for(String value:values)if(value!=null&&value.matches("[1-9][0-9]{0,9}"))return value;return null;}
    private static String receiptId(String id) {require(id!=null&&id.matches("[A-Za-z0-9_-]{1,32}"),"单据编号无效",400);return id;}
    private static long positive(String value,String field) {require(value!=null&&value.matches("[1-9][0-9]{0,9}"),field+"必须是正整数",400);return Long.parseLong(value);}
    private static long signed(String value,String field) {require(value!=null&&value.matches("-?[0-9]{1,9}"),field+"必须是整数且不超过九位",400);return Long.parseLong(value);}
    private static long optionalNumber(String value) {return value==null||value.isEmpty()?0:Long.parseLong(value);}
    private static long number(Object value) {return value==null?0:((Number)value).longValue();}
    private static void require(boolean condition,String message,int code) {if(!condition)throw new ServiceException(message,code);}

    private static final class Key implements Comparable<Key> {
        private final long product,warehouse;
        private Key(long product,long warehouse) {this.product=product;this.warehouse=warehouse;}
        @Override public int compareTo(Key other) {int byProduct=Long.compare(product,other.product);return byProduct!=0?byProduct:Long.compare(warehouse,other.warehouse);}
        @Override public boolean equals(Object other) {return other instanceof Key&&product==((Key)other).product&&warehouse==((Key)other).warehouse;}
        @Override public int hashCode() {return Objects.hash(product,warehouse);}
    }
}

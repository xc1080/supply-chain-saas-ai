package com.ruoyi.system.service;

import com.ruoyi.common.core.domain.entity.*;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.mapper.DetailOrderFormMapper;
import com.ruoyi.system.mapper.HeadOrderFormMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Source lines supplement the legacy PO/receipt tables, without introducing a second inventory writer.
 * Lock order: receipt mutex (caller), sorted purchase order mutexes, source receipt lines, product/stock
 * (caller). Draft receipts reserve arrival/return quantities; a supplier return never restores PO quota.
 */
@Service
@Profile({"local", "commerce"})
public class CommerceProcurementService {
    private final DataSource source;
    private final JdbcTemplate jdbc;
    private final HeadOrderFormMapper heads;
    private final DetailOrderFormMapper details;

    public CommerceProcurementService(DataSource source, HeadOrderFormMapper heads, DetailOrderFormMapper details) {
        this.source = source;
        this.jdbc = new JdbcTemplate(source);
        this.heads = heads;
        this.details = details;
    }

    public void initializeSchema() {
        new ResourceDatabasePopulator(new ClassPathResource("db/commerce-procurement.sql")).execute(source);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int saveOrder(OrderFrom order) {
        require(order != null, "采购订单不能为空", 400);
        String id = documentId(order.getSystematicOrderForm());
        require(Long.valueOf(1).equals(order.getOrderFormType()), "采购入口只允许采购订单", 400);
        status(order.getOrderFormStatus());
        require(order.getDetails() != null && !order.getDetails().isEmpty() && order.getDetails().size() <= 100,
                "采购订单需包含1至100条明细", 400);
        positive(order.getSupplierIds(), "供应商");
        lockOrders(Collections.singleton(id));
        Map<String,Object> oldHead = orderHead(id);
        if (oldHead != null) require(number(oldHead.get("order_form_type")) == 1, "该编号已被其他类型订单使用", 409);
        List<Map<String,Object>> old = oldHead == null ? Collections.emptyList() : ensureOrderLines(id, oldHead);
        Map<String,Map<String,Object>> oldById = index(old, "purchase_line_id");
        List<Line> proposed = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (int position = 0; position < order.getDetails().size(); position++) {
            DetailOrderForm detail = order.getDetails().get(position);
            require(detail != null, "采购订单明细不能为空", 400);
            Line line = orderLine(order, detail);
            Map<String,Object> prior = findPrior(old, detail.getPurchaseLineId(), detail.getSystematicId(), position);
            if (blank(detail.getPurchaseLineId())) line.id = prior == null ? uuid() : string(prior.get("purchase_line_id"));
            else {
                line.id = stableId(detail.getPurchaseLineId());
                require(oldById.containsKey(line.id) || jdbc.queryForObject("SELECT COUNT(*) FROM commerce_purchase_order_line WHERE purchase_line_id=?", Long.class, line.id) == 0,
                        "采购来源行不属于当前订单", 409);
            }
            require(identities.add(line.id), "采购明细来源行重复", 400);
            detail.setPurchaseLineId(line.id);
            proposed.add(line);
            total = total.add(amount(line));
            detail.setSystematicOrderForm(id);
            detail.setProductId(Long.toString(line.product));
            detail.setSupplierId(Long.toString(line.supplier));
            detail.setWarehousingId(Long.toString(line.warehouse));
            detail.setPlanQuantity(Long.toString(line.quantity));
            detail.setUnivalence(line.price.toPlainString());
            detail.setDiscount(line.discount.toPlainString());
            detail.setMoney(amount(line).toPlainString());
            detail.setCost(amount(line).toPlainString());
        }
        boolean same = sameLines(old, proposed);
        if (oldHead != null && number(oldHead.get("order_form_status")) == 2)
            require(same, "已审核采购订单的明细已冻结，请先反审核再修改", 409);
        if (hasOrderReceipts(id)) {
            require(same, "采购订单已有来源入库单，不能修改来源行", 409);
            require(Long.valueOf(2).equals(order.getOrderFormStatus()), "采购订单已有来源入库单，不能反审核", 409);
        }
        require(total.compareTo(new BigDecimal("999999999999.99")) <= 0, "采购总金额超出范围", 400);
        order.setOrderFormAmount(total.setScale(2, RoundingMode.HALF_UP).toPlainString());
        if (oldHead == null) heads.addHeadOrderForm(order);
        else {
            DetailOrderForm identifier = new DetailOrderForm(); identifier.setSystematicOrderForm(id);
            details.delDetailOrderForm(Collections.singletonList(identifier));
            heads.updateHeadOrderForm(order);
        }
        details.addDetailOrderForm(order.getDetails());
        List<Map<String,Object>> persisted = legacyOrderLines(id);
        require(persisted.size() == proposed.size(), "采购明细保存数量不一致", 409);
        for (Map<String,Object> row : old)
            if (!identities.contains(string(row.get("purchase_line_id"))))
                jdbc.update("DELETE FROM commerce_purchase_order_line WHERE purchase_line_id=?", row.get("purchase_line_id"));
        for (int position = 0; position < proposed.size(); position++) {
            Line line = proposed.get(position);
            long detailId = number(persisted.get(position).get("systematic_id"));
            upsertOrderLine(id, detailId, position, line);
            order.getDetails().get(position).setSystematicId(detailId);
        }
        return 1;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int deleteOrders(List<DetailOrderForm> request) {
        require(request != null && !request.isEmpty() && request.size() <= 100, "请选择1至100张采购订单", 400);
        SortedSet<String> ids = new TreeSet<>();
        for (DetailOrderForm row : request) {
            require(row != null, "采购订单参数无效", 400);
            ids.add(documentId(row.getSystematicOrderForm()));
        }
        lockOrders(ids);
        for (String id : ids) {
            Map<String,Object> head = orderHead(id);
            if (head != null) require(number(head.get("order_form_type")) == 1, "采购入口不能删除其他类型订单", 409);
            require(!hasOrderReceipts(id), "采购订单已有来源入库单，不能删除", 409);
        }
        details.delDetailOrderForm(request);
        int count = heads.delHeadOrderForm(request);
        for (String id : ids) jdbc.update("DELETE FROM commerce_purchase_order_line WHERE order_id=?", id);
        return count;
    }

    /** Shared legacy sales tables must not offer a back door around the PO source freeze. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void validateNonPurchaseOrders(List<String> requested) {
        SortedSet<String> ids = new TreeSet<>();
        for (String id : requested) ids.add(documentId(id));
        lockOrders(ids);
        for (String id : ids) {
            Map<String,Object> head = orderHead(id);
            require(head == null || number(head.get("order_form_type")) != 1, "不能通过销售入口修改或删除采购订单", 409);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void enrichOrder(HeadOrderForm order) {
        if (order == null || !Long.valueOf(1).equals(order.getOrderFormType())) return;
        Map<String,Object> progress = progress(order.getSystematicOrderForm());
        @SuppressWarnings("unchecked") List<Map<String,Object>> rows = (List<Map<String,Object>>) progress.get("lines");
        Map<Long,Map<String,Object>> byDetail = new HashMap<>();
        for (Map<String,Object> row : rows) byDetail.put(number(row.get("detailId")), row);
        if (order.getDetails() == null) return;
        for (DetailOrderForm detail : order.getDetails()) {
            Map<String,Object> row = byDetail.get(detail.getSystematicId());
            if (row == null) continue;
            detail.setPurchaseLineId(string(row.get("purchaseLineId")));
            detail.setReceivedQuantity(number(row.get("receivedQuantity")));
            detail.setDraftReceiptQuantity(number(row.get("draftReceiptQuantity")));
            detail.setReturnedQuantity(number(row.get("returnedQuantity")));
            detail.setRemainingQuantity(number(row.get("remainingQuantity")));
        }
    }

    @Transactional(readOnly = true)
    public void enrichReceipt(HeadReceipt receipt) {
        if (receipt == null || receipt.getDetails() == null) return;
        List<Map<String,Object>> rows = receiptLines(receipt.getSystematicReceipt());
        Map<Long,Map<String,Object>> byDetail = new HashMap<>();
        for (Map<String,Object> row : rows) byDetail.put(number(row.get("detail_id")), row);
        for (DetailReceipt detail : receipt.getDetails()) {
            Map<String,Object> row = byDetail.get(detail.getSystematicId());
            if (row == null) continue;
            detail.setPurchaseReceiptLineId(string(row.get("receipt_line_id")));
            detail.setSourcePurchaseLineId(nullableString(row.get("source_purchase_line_id")));
            detail.setSourceReceiptLineId(nullableString(row.get("source_receipt_line_id")));
        }
    }

    /** Lazy PO identity initialization records no receipt history and changes no stock. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String,Object> progress(String orderId) {
        String id = documentId(orderId);
        lockOrders(Collections.singleton(id));
        Map<String,Object> head = orderHead(id);
        require(head != null && number(head.get("order_form_type")) == 1, "采购订单不存在", 404);
        List<Map<String,Object>> rows = ensureOrderLines(id, head);
        List<Map<String,Object>> output = new ArrayList<>();
        long ordered = 0, received = 0, draft = 0, returned = 0, draftReturned = 0, remaining = 0;
        for (Map<String,Object> row : rows) {
            String lineId = string(row.get("purchase_line_id"));
            long quantity = number(row.get("quantity"));
            long receiptQuantity = sum("source_purchase_line_id", lineId, 1, 2, null);
            long draftQuantity = sum("source_purchase_line_id", lineId, 1, 1, null);
            long returnQuantity = sum("source_purchase_line_id", lineId, 2, 2, null);
            long draftReturnQuantity = sum("source_purchase_line_id", lineId, 2, 1, null);
            long available = quantity - receiptQuantity - draftQuantity;
            require(available >= 0, "历史采购来源数量不一致，请先核对", 409);
            Map<String,Object> item = new LinkedHashMap<>();
            item.put("purchaseLineId", lineId); item.put("detailId", number(row.get("detail_id")));
            item.put("productId", number(row.get("product_id"))); item.put("supplierId", number(row.get("supplier_id")));
            item.put("warehouseId", number(row.get("warehouse_id"))); item.put("unitPrice", row.get("unit_price"));
            item.put("discount", row.get("discount")); item.put("quantity", quantity); item.put("orderedQuantity", quantity);
            item.put("receivedQuantity", receiptQuantity); item.put("draftReceiptQuantity", draftQuantity);
            item.put("returnedQuantity", returnQuantity); item.put("remainingQuantity", available); item.put("remainingToReceive", available);
            item.put("draftReceived", draftQuantity); item.put("draftReturned", draftReturnQuantity); item.put("netReceived", receiptQuantity-returnQuantity);
            addProductDescription(item, number(row.get("product_id")));
            output.add(item);
            ordered = Math.addExact(ordered, quantity); received = Math.addExact(received, receiptQuantity);
            draft = Math.addExact(draft, draftQuantity); returned = Math.addExact(returned, returnQuantity); remaining = Math.addExact(remaining, available);
            draftReturned = Math.addExact(draftReturned, draftReturnQuantity);
        }
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("orderId", id); result.put("status", number(head.get("order_form_status")));
        result.put("orderedQuantity", ordered); result.put("receivedQuantity", received); result.put("draftReceiptQuantity", draft);
        result.put("returnedQuantity", returned); result.put("remainingQuantity", remaining); result.put("remainingToReceive", remaining);
        result.put("draftReceived", draft); result.put("draftReturned", draftReturned); result.put("netReceived", received-returned);
        result.put("lines", output);
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> receiptSources(String orderId) {
        String id = documentId(orderId);
        Map<String,Object> head = orderHead(id);
        require(head != null && number(head.get("order_form_type")) == 1, "采购订单不存在", 404);
        List<Map<String,Object>> result = new ArrayList<>();
        for (Map<String,Object> row : jdbc.queryForList("SELECT r.* FROM commerce_purchase_receipt_line r JOIN commerce_purchase_order_line p ON p.purchase_line_id=r.source_purchase_line_id WHERE p.order_id=? AND r.receipt_type=1 AND r.receipt_status=2 ORDER BY r.receipt_id,r.position_no", id)) {
            String sourceId = string(row.get("receipt_line_id"));
            long quantity = number(row.get("quantity")), returned = sum("source_receipt_line_id", sourceId, 2, 2, null), draft = sum("source_receipt_line_id", sourceId, 2, 1, null);
            Map<String,Object> item = new LinkedHashMap<>();
            item.put("receiptLineId", sourceId); item.put("purchaseReceiptLineId", sourceId); item.put("sourceReceiptLineId", sourceId);
            item.put("receiptId", row.get("receipt_id")); item.put("sourceReceiptId", row.get("receipt_id"));
            item.put("sourcePurchaseLineId", row.get("source_purchase_line_id")); item.put("productId", number(row.get("product_id")));
            item.put("supplierId", number(row.get("supplier_id"))); item.put("warehouseId", number(row.get("warehouse_id")));
            item.put("quantity", quantity); item.put("unitPrice", row.get("unit_price")); item.put("discount", row.get("discount"));
            item.put("returnedQuantity", returned); item.put("draftReturnQuantity", draft); item.put("remainingReturnQuantity", quantity-returned-draft);
            item.put("availableReturnQuantity", quantity-returned-draft);
            addProductDescription(item, number(row.get("product_id")));
            result.add(item);
        }
        return result;
    }

    /** Invoked while the receipt mutex is held and before inventory locks or writes. */
    public void validateReceipt(ReceiptFrom receipt) {
        String id = documentId(receipt.getSystematicReceipt());
        List<Map<String,Object>> old = receiptLines(id);
        List<Map<String,Object>> oldHeads = jdbc.queryForList("SELECT * FROM head_receipt WHERE systematic_receipt=?", id);
        Map<String,Object> oldHead = oldHeads.isEmpty() ? null : oldHeads.get(0);
        List<Map<String,Object>> legacyHistory = oldHead == null ? Collections.emptyList()
                : jdbc.queryForList("SELECT * FROM detail_receipt WHERE systematic_receipt=? ORDER BY systematic_id", id);
        boolean purchase = Long.valueOf(1).equals(receipt.getReceiptType()) || Long.valueOf(2).equals(receipt.getReceiptType());
        boolean wasPurchase = oldHead != null && (number(oldHead.get("receipt_type")) == 1 || number(oldHead.get("receipt_type")) == 2);
        if (!purchase && !wasPurchase && old.isEmpty()) return;
        require(purchase && (!wasPurchase || number(oldHead.get("receipt_type")) == receipt.getReceiptType()),
                "采购单据类型不能变更以解除来源约束", 409);
        require(oldHead == null || wasPurchase, "已有其他类型单据不能改成采购单据", 409);
        require(Long.valueOf(1).equals(receipt.getReceiptCategory()), "采购入库及退库必须使用采购单据类别", 400);
        status(receipt.getReceiptStatus());
        List<Line> proposed = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        SortedSet<String> orders = new TreeSet<>(), origins = new TreeSet<>();
        for (Map<String,Object> row : old) discoverSources(row.get("source_purchase_line_id"), row.get("source_receipt_line_id"), orders, origins);
        for (int position = 0; position < receipt.getDetails().size(); position++) {
            DetailReceipt detail = receipt.getDetails().get(position);
            Line line = receiptLine(receipt, detail);
            Map<String,Object> prior = findPrior(old, detail.getPurchaseReceiptLineId(), detail.getSystematicId(), position);
            if (blank(detail.getPurchaseReceiptLineId())) line.id = prior == null ? uuid() : string(prior.get("receipt_line_id"));
            else {
                line.id = stableId(detail.getPurchaseReceiptLineId());
                require(prior != null || jdbc.queryForObject("SELECT COUNT(*) FROM commerce_purchase_receipt_line WHERE receipt_line_id=?", Long.class, line.id) == 0,
                        "采购收货来源行不属于当前单据", 409);
            }
            require(identities.add(line.id), "采购单据来源行重复", 400);
            line.purchaseSource = nullableString(detail.getSourcePurchaseLineId());
            line.receiptSource = nullableString(detail.getSourceReceiptLineId());
            if (prior != null) {
                require(Objects.equals(nullableString(prior.get("source_purchase_line_id")), line.purchaseSource)
                                && Objects.equals(nullableString(prior.get("source_receipt_line_id")), line.receiptSource),
                        "已有采购来源关系不能解绑或换绑", 409);
            }
            require(receipt.getReceiptType() != 1 || line.receiptSource == null, "采购入库不能引用退供来源", 400);
            require(receipt.getReceiptType() != 2 || line.receiptSource != null || oldHead != null,
                    "新增采购退库必须选择已审核原入库行", 400);
            if (receipt.getReceiptType() == 2 && line.receiptSource == null) {
                // An existing historical return is not permission to add arbitrary untraceable return lines.
                require(line.purchaseSource == null && legacyHistory.size() == receipt.getDetails().size(),
                        "历史无来源退库单不能增加或换绑明细", 409);
                Map<String,Object> historical = null;
                for (Map<String,Object> row : legacyHistory)
                    if (detail.getSystematicId() != null && detail.getSystematicId() == number(row.get("systematic_id"))) historical = row;
                if (historical == null && position < legacyHistory.size()) historical = legacyHistory.get(position);
                require(historical != null && matchesHistoricalReturn(line, historical, oldHead),
                        "历史无来源退库行只允许原数量与原身份延续，请使用来源退库创建新业务", 409);
            }
            discoverSources(line.purchaseSource, line.receiptSource, orders, origins);
            detail.setPurchaseReceiptLineId(line.id);
            detail.setSystematicReceipt(id);
            detail.setSupplierId(Long.toString(line.supplier));
            detail.setUnivalence(line.price.toPlainString()); detail.setDiscount(line.discount.toPlainString());
            detail.setMoney(amount(line).toPlainString()); detail.setCost(amount(line).toPlainString());
            proposed.add(line);
        }
        for (Map<String,Object> row : old)
            if (row.get("source_purchase_line_id") != null || row.get("source_receipt_line_id") != null)
                require(identities.contains(string(row.get("receipt_line_id"))),
                        "已有采购来源行不能换编号或删字段解绑，请通过整单删除或反审核处理", 409);
        lockOrders(orders);
        for (String origin : origins) jdbc.queryForList("SELECT receipt_line_id FROM commerce_purchase_receipt_line WHERE receipt_line_id=? FOR UPDATE", origin);
        // The source row lock serializes standalone returns as well as linked returns.
        jdbc.queryForList("SELECT receipt_line_id FROM commerce_purchase_receipt_line WHERE receipt_id=? ORDER BY receipt_line_id FOR UPDATE", id);
        require(old.size() == receiptLines(id).size(), "采购单据来源发生变化，请重新加载", 409);
        Map<String,Long> incoming = new HashMap<>(), returns = new HashMap<>();
        String purchaseOrder = null;
        for (Line line : proposed) {
            if (line.receiptSource != null) {
                Map<String,Object> origin = sourceReceipt(line.receiptSource);
                require(number(origin.get("receipt_type")) == 1 && number(origin.get("receipt_status")) == 2,
                        "退供来源必须是已审核采购入库行", 409);
                require(!id.equals(string(origin.get("receipt_id"))), "退供不能引用自身", 400);
                matchSource(line, origin);
                String derivedPurchase = nullableString(origin.get("source_purchase_line_id"));
                require(line.purchaseSource == null || Objects.equals(line.purchaseSource, derivedPurchase), "退供采购订单来源与原入库不一致", 409);
                line.purchaseSource = derivedPurchase;
                returns.merge(line.receiptSource, line.quantity, Math::addExact);
            }
            if (line.purchaseSource != null) {
                Map<String,Object> sourceLine = sourceOrderLine(line.purchaseSource);
                String sourceOrderId = string(sourceLine.get("order_id"));
                Map<String,Object> head = orderHead(sourceOrderId);
                require(head != null && number(head.get("order_form_type")) == 1 && number(head.get("order_form_status")) == 2,
                        "来源采购订单必须已审核", 409);
                matchSource(line, sourceLine);
                require(purchaseOrder == null || purchaseOrder.equals(sourceOrderId), "同一采购单据不能混用不同采购订单", 400);
                purchaseOrder = sourceOrderId;
                if (receipt.getReceiptType() == 1) incoming.merge(line.purchaseSource, line.quantity, Math::addExact);
            }
        }
        for (Map.Entry<String,Long> entry : incoming.entrySet()) {
            long already = sum("source_purchase_line_id", entry.getKey(), 1, 0, id);
            require(Math.addExact(already, entry.getValue()) <= number(sourceOrderLine(entry.getKey()).get("quantity")),
                    "草稿及已审核入库数量超过采购订单数量", 409);
        }
        for (Map.Entry<String,Long> entry : returns.entrySet()) {
            long already = sum("source_receipt_line_id", entry.getKey(), 2, 0, id);
            require(Math.addExact(already, entry.getValue()) <= number(sourceReceipt(entry.getKey()).get("quantity")),
                    "草稿及已审核退供数量超过原入库数量", 409);
        }
        boolean oldLinked = old.stream().anyMatch(row -> row.get("source_purchase_line_id") != null || row.get("source_receipt_line_id") != null);
        if (oldHead != null && number(oldHead.get("receipt_status")) == 2 && oldLinked)
            require(sameLines(old, proposed), "已审核来源采购单据已冻结，请先反审核再修改", 409);
        for (Map<String,Object> row : old) {
            if (hasReceiptChildren(string(row.get("receipt_line_id")))) {
                require(receipt.getReceiptStatus() == 2 && sameLines(old, proposed), "原入库行已有退供单据，不能修改或反审核", 409);
            }
        }
        if (purchaseOrder != null) {
            require(blank(receipt.getPlanReceipt()) || purchaseOrder.equals(receipt.getPlanReceipt()), "单据采购订单号与来源行不一致", 409);
            receipt.setPlanReceipt(purchaseOrder);
        }
        BigDecimal total = BigDecimal.ZERO;
        for (int position = 0; position < proposed.size(); position++) {
            Line line = proposed.get(position);
            receipt.getDetails().get(position).setSourcePurchaseLineId(line.purchaseSource);
            receipt.getDetails().get(position).setSourceReceiptLineId(line.receiptSource);
            total = total.add(amount(line));
        }
        require(total.compareTo(new BigDecimal("999999999999.99")) <= 0, "采购总金额超出范围", 400);
        receipt.setTotalAmount(total.setScale(2, RoundingMode.HALF_UP).toPlainString());
    }

    public void afterReceiptSaved(ReceiptFrom receipt) {
        if (!Long.valueOf(1).equals(receipt.getReceiptType()) && !Long.valueOf(2).equals(receipt.getReceiptType())) return;
        String id = documentId(receipt.getSystematicReceipt());
        List<Map<String,Object>> persisted = jdbc.queryForList("SELECT systematic_id FROM detail_receipt WHERE systematic_receipt=? ORDER BY systematic_id", id);
        require(persisted.size() == receipt.getDetails().size(), "采购收货明细保存数量不一致", 409);
        List<Map<String,Object>> old = receiptLines(id);
        Set<String> identities = new HashSet<>();
        for (DetailReceipt detail : receipt.getDetails()) identities.add(stableId(detail.getPurchaseReceiptLineId()));
        for (Map<String,Object> row : old)
            if (!identities.contains(string(row.get("receipt_line_id")))) jdbc.update("DELETE FROM commerce_purchase_receipt_line WHERE receipt_line_id=?", row.get("receipt_line_id"));
        for (int position = 0; position < receipt.getDetails().size(); position++) {
            DetailReceipt detail = receipt.getDetails().get(position);
            Line line = receiptLine(receipt, detail);
            long detailId = number(persisted.get(position).get("systematic_id"));
            int updated = jdbc.update("UPDATE commerce_purchase_receipt_line SET detail_id=?,position_no=?,receipt_status=?,quantity=?,unit_price=?,discount=?,product_id=?,supplier_id=?,warehouse_id=? WHERE receipt_line_id=? AND receipt_id=?",
                    detailId,position,receipt.getReceiptStatus(),line.quantity,line.price,line.discount,line.product,line.supplier,line.warehouse,detail.getPurchaseReceiptLineId(),id);
            if (updated == 0) {
                try {
                    jdbc.update("INSERT INTO commerce_purchase_receipt_line(receipt_line_id,receipt_id,detail_id,position_no,receipt_type,receipt_status,source_purchase_line_id,source_receipt_line_id,product_id,supplier_id,warehouse_id,quantity,unit_price,discount) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                            detail.getPurchaseReceiptLineId(),id,detailId,position,receipt.getReceiptType(),receipt.getReceiptStatus(),detail.getSourcePurchaseLineId(),detail.getSourceReceiptLineId(),line.product,line.supplier,line.warehouse,line.quantity,line.price,line.discount);
                } catch (DuplicateKeyException conflict) { throw new ServiceException("采购收货来源行编号已被其他单据使用", 409); }
            }
            detail.setSystematicId(detailId);
        }
    }

    public void validateDeleteReceipts(List<String> ids) {
        SortedSet<String> orders = new TreeSet<>(), origins = new TreeSet<>();
        for (String id : ids)
            for (Map<String,Object> row : receiptLines(id)) discoverSources(row.get("source_purchase_line_id"), row.get("source_receipt_line_id"), orders, origins);
        lockOrders(orders);
        for (String origin : origins) jdbc.queryForList("SELECT receipt_line_id FROM commerce_purchase_receipt_line WHERE receipt_line_id=? FOR UPDATE", origin);
        for (String id : new TreeSet<>(ids))
            for (Map<String,Object> row : jdbc.queryForList("SELECT * FROM commerce_purchase_receipt_line WHERE receipt_id=? ORDER BY receipt_line_id FOR UPDATE", id))
                require(!hasReceiptChildren(string(row.get("receipt_line_id"))), "原入库行已有退供单据，不能删除", 409);
    }

    public void afterReceiptsDeleted(List<String> ids) {
        for (String id : ids) jdbc.update("DELETE FROM commerce_purchase_receipt_line WHERE receipt_id=?", id);
    }

    private Line orderLine(OrderFrom order, DetailOrderForm detail) {
        Line line = new Line();
        line.product = positive(detail.getProductId(), "货品编号");
        line.supplier = positive(first(detail.getSupplierId(), order.getSupplierIds()), "供应商");
        if (!blank(order.getSupplierIds())) require(line.supplier == positive(order.getSupplierIds(), "供应商"), "订单供应商与明细不一致", 400);
        line.warehouse = positive(first(detail.getWarehousingId(), detail.getWarehouseId(), order.getWarehousingIds()), "入库仓库");
        line.quantity = positive(detail.getPlanQuantity(), "采购数量");
        line.price = decimal(detail.getUnivalence(), BigDecimal.ZERO, "采购单价", 2);
        line.discount = discount(detail.getDiscount());
        validateLine(line);
        return line;
    }

    private Line receiptLine(ReceiptFrom receipt, DetailReceipt detail) {
        require(detail != null, "采购单据明细不能为空", 400);
        Line line = new Line();
        line.product = positive(detail.getProductId(), "货品编号");
        line.supplier = optionalSupplier(first(detail.getSupplierId(), receipt.getSupplierIds()));
        if (optionalSupplier(receipt.getSupplierIds()) > 0) require(line.supplier == optionalSupplier(receipt.getSupplierIds()), "单据供应商与明细不一致", 400);
        line.warehouse = positive(receipt.getReceiptType() == 1 ? first(detail.getWarehousingId(), receipt.getWarehousingIds()) : first(detail.getRetrievalId(), receipt.getRetrievalIds()), "采购仓库");
        line.quantity = positive(detail.getPlanQuantity(), "采购数量");
        line.price = decimal(detail.getUnivalence(), BigDecimal.ZERO, "采购单价", 2);
        line.discount = discount(detail.getDiscount());
        validateLine(line);
        return line;
    }

    private void validateLine(Line line) {
        require(line.quantity <= 1_000_000_000L, "单行采购数量超出范围", 400);
        require(line.price.signum() >= 0 && line.price.compareTo(new BigDecimal("999999999999.99")) <= 0, "采购单价超出范围", 400);
        require(line.discount.signum() >= 0 && line.discount.compareTo(BigDecimal.ONE) <= 0, "折扣必须在0至1之间", 400);
        require(amount(line).compareTo(new BigDecimal("999999999999.99")) <= 0, "采购行金额超出范围", 400);
        master("product", "product_id", line.product, "货品");
        if (line.supplier > 0) master("supplier", "supplier_id", line.supplier, "供应商");
        master("warehouse", "warehouse_id", line.warehouse, "仓库");
    }

    private void master(String table, String column, long id, String label) {
        List<Map<String,Object>> found = jdbc.queryForList("SELECT * FROM " + table + " WHERE " + column + "=?", id);
        require(found.size() == 1, label + "不存在", 404);
        Object active = found.get(0).get("status");
        require(active == null || "0".equals(string(active)), label + "已停用", 409);
    }

    private void lockOrders(Collection<String> ids) {
        for (String id : new TreeSet<>(ids)) {
            jdbc.update("INSERT INTO commerce_purchase_order_lock(order_id) VALUES (?) ON DUPLICATE KEY UPDATE order_id=VALUES(order_id)", id);
            List<Map<String,Object>> found = jdbc.queryForList("SELECT systematic_id FROM head_order_form WHERE systematic_order_form=? FOR UPDATE", id);
            require(found.size() <= 1, "历史采购单号存在重复记录，请先核对", 409);
        }
    }

    private Map<String,Object> orderHead(String id) {
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT * FROM head_order_form WHERE systematic_order_form=?", id);
        require(rows.size() <= 1, "历史采购单号存在重复记录，请先核对", 409);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<Map<String,Object>> legacyOrderLines(String id) {
        return jdbc.queryForList("SELECT * FROM detail_order_form WHERE systematic_order_form=? ORDER BY systematic_id", id);
    }

    private List<Map<String,Object>> ensureOrderLines(String id, Map<String,Object> head) {
        List<Map<String,Object>> old = jdbc.queryForList("SELECT * FROM commerce_purchase_order_line WHERE order_id=? ORDER BY position_no", id);
        List<Map<String,Object>> legacy = legacyOrderLines(id);
        if (!old.isEmpty()) {
            Set<Long> identifiers = new HashSet<>();
            for (Map<String,Object> row : old) identifiers.add(number(row.get("detail_id")));
            require(old.size() == legacy.size() && legacy.stream().allMatch(row -> identifiers.contains(number(row.get("systematic_id")))), "采购来源行与原单不一致，请先核对", 409);
            Map<Long,Map<String,Object>> byDetail = new HashMap<>();
            for (Map<String,Object> row : legacy) byDetail.put(number(row.get("systematic_id")), row);
            for (Map<String,Object> row : old) require(matchesLegacy(row, byDetail.get(number(row.get("detail_id"))), head, true), "采购来源快照与原单不一致，请先核对", 409);
            return old;
        }
        for (int position = 0; position < legacy.size(); position++) {
            Map<String,Object> row = legacy.get(position);
            Line line = new Line(); line.id = uuid();
            line.product = number(row.get("product_id")); line.supplier = firstNumber(row.get("supplier_id"), head.get("supplier_ids"));
            line.warehouse = firstNumber(row.get("warehousing_id"), head.get("warehousing_ids")); line.quantity = number(row.get("plan_quantity"));
            line.price = decimal(string(row.get("univalence")), BigDecimal.ZERO, "历史采购单价", 2);
            line.discount = discount(string(row.get("discount")));
            require(line.product > 0 && line.supplier > 0 && line.warehouse > 0 && line.quantity > 0, "历史采购来源明细不完整，请先核对", 409);
            upsertOrderLine(id, number(row.get("systematic_id")), position, line);
        }
        return jdbc.queryForList("SELECT * FROM commerce_purchase_order_line WHERE order_id=? ORDER BY position_no", id);
    }

    private void upsertOrderLine(String order, long detail, int position, Line line) {
        int updated = jdbc.update("UPDATE commerce_purchase_order_line SET detail_id=?,position_no=?,product_id=?,supplier_id=?,warehouse_id=?,quantity=?,unit_price=?,discount=? WHERE purchase_line_id=? AND order_id=?",
                detail,position,line.product,line.supplier,line.warehouse,line.quantity,line.price,line.discount,line.id,order);
        if (updated == 0) {
            try {
                jdbc.update("INSERT INTO commerce_purchase_order_line(purchase_line_id,order_id,detail_id,position_no,product_id,supplier_id,warehouse_id,quantity,unit_price,discount) VALUES (?,?,?,?,?,?,?,?,?,?)", line.id,order,detail,position,line.product,line.supplier,line.warehouse,line.quantity,line.price,line.discount);
            } catch (DuplicateKeyException conflict) { throw new ServiceException("采购来源行编号已被其他订单使用", 409); }
        }
    }

    private List<Map<String,Object>> receiptLines(String id) {
        return jdbc.queryForList("SELECT * FROM commerce_purchase_receipt_line WHERE receipt_id=? ORDER BY position_no", id);
    }

    private Map<String,Object> sourceOrderLine(String line) {
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT * FROM commerce_purchase_order_line WHERE purchase_line_id=?", stableId(line));
        require(rows.size() == 1, "采购来源订单行不存在", 404);
        Map<String,Object> row = rows.get(0);
        Map<String,Object> head = orderHead(string(row.get("order_id")));
        List<Map<String,Object>> legacy = jdbc.queryForList("SELECT * FROM detail_order_form WHERE systematic_id=? AND systematic_order_form=?", row.get("detail_id"), row.get("order_id"));
        require(head != null && legacy.size() == 1 && matchesLegacy(row, legacy.get(0), head, true), "采购来源行与原采购单不一致", 409);
        return rows.get(0);
    }

    private Map<String,Object> sourceReceipt(String line) {
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT * FROM commerce_purchase_receipt_line WHERE receipt_line_id=?", stableId(line));
        require(rows.size() == 1, "原采购入库来源行不存在", 404);
        Map<String,Object> row = rows.get(0);
        List<Map<String,Object>> head = jdbc.queryForList("SELECT * FROM head_receipt WHERE systematic_receipt=?", row.get("receipt_id"));
        List<Map<String,Object>> legacy = jdbc.queryForList("SELECT * FROM detail_receipt WHERE systematic_id=? AND systematic_receipt=?", row.get("detail_id"), row.get("receipt_id"));
        require(head.size() == 1 && legacy.size() == 1 && matchesLegacy(row, legacy.get(0), head.get(0), false)
                        && number(head.get(0).get("receipt_type")) == number(row.get("receipt_type"))
                        && number(head.get(0).get("receipt_status")) == number(row.get("receipt_status")),
                "原入库来源行与原单不一致", 409);
        return rows.get(0);
    }

    private boolean matchesLegacy(Map<String,Object> snapshot, Map<String,Object> legacy, Map<String,Object> head, boolean order) {
        long warehouse = order || number(snapshot.get("receipt_type")) == 1
                ? firstNumber(legacy.get("warehousing_id"), head.get("warehousing_ids"))
                : firstNumber(legacy.get("retrieval_id"), head.get("retrieval_ids"));
        return number(snapshot.get("product_id")) == number(legacy.get("product_id"))
                && number(snapshot.get("supplier_id")) == firstNumber(legacy.get("supplier_id"), head.get("supplier_ids"))
                && number(snapshot.get("warehouse_id")) == warehouse
                && number(snapshot.get("quantity")) == number(legacy.get("plan_quantity"))
                && new BigDecimal(string(snapshot.get("unit_price"))).compareTo(decimal(string(legacy.get("univalence")), BigDecimal.ZERO, "采购单价", 2)) == 0
                && new BigDecimal(string(snapshot.get("discount"))).compareTo(discount(string(legacy.get("discount")))) == 0;
    }

    private boolean matchesHistoricalReturn(Line line, Map<String,Object> legacy, Map<String,Object> head) {
        return line.product == number(legacy.get("product_id"))
                && line.supplier == firstNumber(legacy.get("supplier_id"), head.get("supplier_ids"))
                && line.warehouse == firstNumber(legacy.get("retrieval_id"), head.get("retrieval_ids"))
                && line.quantity == number(legacy.get("plan_quantity"))
                && line.price.compareTo(decimal(string(legacy.get("univalence")), BigDecimal.ZERO, "历史采购单价", 2)) == 0
                && line.discount.compareTo(discount(string(legacy.get("discount")))) == 0;
    }

    private void discoverSources(Object purchase, Object origin, Set<String> orders, Set<String> origins) {
        String purchaseId = nullableString(purchase), originId = nullableString(origin);
        if (originId != null) {
            origins.add(stableId(originId));
            Map<String,Object> row = sourceReceipt(originId);
            String derived = nullableString(row.get("source_purchase_line_id"));
            if (derived != null) orders.add(string(sourceOrderLine(derived).get("order_id")));
        }
        if (purchaseId != null) orders.add(string(sourceOrderLine(purchaseId).get("order_id")));
    }

    private long sum(String column, String id, int type, int state, String excluding) {
        String sql = "SELECT COALESCE(SUM(quantity),0) FROM commerce_purchase_receipt_line WHERE " + column + "=? AND receipt_type=?";
        List<Object> args = new ArrayList<>(Arrays.asList(id, type));
        if (state > 0) { sql += " AND receipt_status=?"; args.add(state); }
        else sql += " AND receipt_status IN (1,2)";
        if (excluding != null) { sql += " AND receipt_id<>?"; args.add(excluding); }
        return jdbc.queryForObject(sql, Long.class, args.toArray());
    }

    private boolean hasOrderReceipts(String id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM commerce_purchase_receipt_line r JOIN commerce_purchase_order_line p ON p.purchase_line_id=r.source_purchase_line_id WHERE p.order_id=?", Long.class, id) > 0;
    }

    private boolean hasReceiptChildren(String id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM commerce_purchase_receipt_line WHERE source_receipt_line_id=?", Long.class, id) > 0;
    }

    private Map<String,Map<String,Object>> index(List<Map<String,Object>> rows, String column) {
        Map<String,Map<String,Object>> result = new HashMap<>();
        for (Map<String,Object> row : rows) result.put(string(row.get(column)), row);
        return result;
    }

    private Map<String,Object> findPrior(List<Map<String,Object>> old, String identity, Long legacyId, int position) {
        for (Map<String,Object> row : old) {
            String key = row.containsKey("purchase_line_id") ? "purchase_line_id" : "receipt_line_id";
            if (!blank(identity) && identity.equals(string(row.get(key)))) return row;
            if (blank(identity) && legacyId != null && legacyId == number(row.get("detail_id"))) return row;
        }
        return blank(identity) && position < old.size() ? old.get(position) : null;
    }

    private boolean sameLines(List<Map<String,Object>> old, List<Line> proposed) {
        if (old.size() != proposed.size()) return false;
        for (Line line : proposed) {
            Map<String,Object> prior = findPrior(old, line.id, null, 0);
            if (prior == null || line.product != number(prior.get("product_id")) || line.supplier != number(prior.get("supplier_id"))
                    || line.warehouse != number(prior.get("warehouse_id")) || line.quantity != number(prior.get("quantity"))
                    || line.price.compareTo(new BigDecimal(string(prior.get("unit_price")))) != 0
                    || line.discount.compareTo(new BigDecimal(string(prior.get("discount")))) != 0) return false;
        }
        return true;
    }

    private void matchSource(Line line, Map<String,Object> origin) {
        require(line.product == number(origin.get("product_id")), "采购来源货品不一致", 409);
        require(line.supplier == number(origin.get("supplier_id")), "采购来源供应商不一致", 409);
        require(line.warehouse == number(origin.get("warehouse_id")), "采购来源仓库不一致，请选择原入库仓库", 409);
        require(line.price.compareTo(new BigDecimal(string(origin.get("unit_price")))) == 0
                        && line.discount.compareTo(new BigDecimal(string(origin.get("discount")))) == 0,
                "采购来源单价及折扣不一致", 409);
    }

    private void addProductDescription(Map<String,Object> result, long product) {
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT product_code,product_name FROM product WHERE product_id=?", product);
        if (!rows.isEmpty()) { result.put("productCode", rows.get(0).get("product_code")); result.put("productName", rows.get(0).get("product_name")); }
    }

    private static class Line {
        String id, purchaseSource, receiptSource;
        long product, supplier, warehouse, quantity;
        BigDecimal price, discount;
    }

    private static BigDecimal amount(Line line) { return line.price.multiply(BigDecimal.valueOf(line.quantity)).multiply(line.discount).setScale(2, RoundingMode.HALF_UP); }
    private static long optionalSupplier(String value) { return blank(value) || "0".equals(value) ? 0 : positive(value, "供应商"); }
    private static BigDecimal discount(String value) {
        BigDecimal result = decimal(value, BigDecimal.ONE, "折扣", 2);
        // Historical ERP clients used 100 to mean full price; persisted source values use the 0..1 ratio.
        return result.compareTo(new BigDecimal("100")) == 0 ? BigDecimal.ONE.setScale(2) : result;
    }
    private static void status(Long value) { require(Long.valueOf(1).equals(value) || Long.valueOf(2).equals(value), "采购单据状态错误", 400); }
    private static String first(String... values) { for (String value : values) if (!blank(value) && !"0".equals(value)) return value; return null; }
    private static long firstNumber(Object... values) { for (Object value : values) if (number(value) > 0) return number(value); return 0; }
    private static boolean blank(String value) { return value == null || value.trim().isEmpty() || "null".equals(value); }
    private static String nullableString(Object value) { String result = string(value); return blank(result) ? null : result; }
    private static String string(Object value) { return value == null ? null : String.valueOf(value); }
    private static long number(Object value) { return value == null || blank(string(value)) ? 0 : Long.parseLong(string(value)); }
    private static long positive(String value, String label) {
        try { long result = Long.parseLong(value); require(result > 0, label + "必须为正整数", 400); return result; }
        catch (NumberFormatException | NullPointerException invalid) { throw new ServiceException(label + "必须为正整数", 400); }
    }
    private static String documentId(String id) { require(id != null && id.matches("[A-Za-z0-9_-]{1,32}"), "采购单号格式错误", 400); return id; }
    private static String stableId(String id) { require(id != null && id.matches("[a-fA-F0-9]{32}"), "采购来源行编号格式错误", 400); return id; }
    private static String uuid() { return UUID.randomUUID().toString().replace("-", ""); }
    private static BigDecimal decimal(String value, BigDecimal fallback, String label, int scale) {
        try {
            BigDecimal result = blank(value) ? fallback : new BigDecimal(value);
            require(result.stripTrailingZeros().scale() <= scale, label + "最多保留" + scale + "位小数", 400);
            return result.setScale(scale, RoundingMode.UNNECESSARY);
        } catch (NumberFormatException | ArithmeticException invalid) { throw new ServiceException(label + "格式错误", 400); }
    }
    private static void require(boolean accepted, String message, int code) { if (!accepted) throw new ServiceException(message, code); }
}

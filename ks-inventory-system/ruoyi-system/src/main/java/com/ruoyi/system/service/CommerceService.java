package com.ruoyi.system.service;

import com.ruoyi.common.exception.ServiceException;
import com.alibaba.fastjson2.JSON;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.beans.factory.annotation.Value;
import com.ruoyi.common.core.tenant.TenantContext;
import com.ruoyi.common.core.tenant.CommerceShopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Connection;
import java.sql.ResultSet;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Local demo commerce. MySQL is authoritative for orders, holds and stock. */
@Service
@Profile({"local","commerce"})
public class CommerceService {
    @Value("${commerce.payment-timeout-seconds:900}") private int paymentTimeoutSeconds = 900;
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final CommerceInventoryService stock;
    private final CommerceMerchantService merchants;
    private final CommercePaymentService payments;
    private final CommerceDeliveryService delivery;
    private final CommerceWarehouseAllocationService warehouses;
    private static final Map<String, String> MEDIA = new LinkedHashMap<>();
    static {
        MEDIA.put("DEMO-LAMP-ZB", "lamp-zb");
        MEDIA.put("DEMO-LAMP-WIFI", "lamp-wifi");
        MEDIA.put("DEMO-LAMP-PRO", "lamp-pro");
        MEDIA.put("DEMO-SENSOR-DOOR", "sensor-door");
        MEDIA.put("DEMO-SENSOR-MOTION", "sensor-motion");
        MEDIA.put("DEMO-GATEWAY-ZB", "gateway-zb");
        MEDIA.put("DEMO-LOCK-WIFI", "lock-wifi");
        MEDIA.put("DEMO-SWITCH-ZB", "switch-zb");
        MEDIA.put("LAB-TAPO-H100", "gateway-zb");
        MEDIA.put("LAB-TAPO-H200", "gateway-zb");
        MEDIA.put("LAB-AQARA-M3", "gateway-zb");
        MEDIA.put("LAB-TAPO-T100", "sensor-motion");
        MEDIA.put("LAB-TAPO-T110", "sensor-door");
        MEDIA.put("LAB-TAPO-T310", "sensor-door");
        MEDIA.put("LAB-TAPO-T300", "sensor-door");
        MEDIA.put("LAB-AQARA-P2", "sensor-door");
        MEDIA.put("LAB-TAPO-S210", "switch-zb");
        MEDIA.put("LAB-TAPO-P110", "switch-zb");
        MEDIA.put("LAB-TAPO-L530E", "lamp-wifi");
        MEDIA.put("LAB-TAPO-L510E", "lamp-wifi");
    }

    private CommerceCostService costs;
    @Autowired public void configureCosts(CommerceCostService costs) { this.costs=costs; }

    public CommerceService(DataSource dataSource) {
        this(dataSource, new CommerceInventoryService(dataSource), new CommerceMerchantService(dataSource));
    }
    public CommerceService(DataSource dataSource, CommerceInventoryService stock, CommerceMerchantService merchants) {
        this(dataSource,stock,merchants,new CommercePaymentService(dataSource));
    }
    public CommerceService(DataSource dataSource, CommerceInventoryService stock, CommerceMerchantService merchants, CommercePaymentService payments) {
        this(dataSource,stock,merchants,payments,new CommerceDeliveryService(dataSource));
    }
    @Autowired
    public CommerceService(DataSource dataSource, CommerceInventoryService stock, CommerceMerchantService merchants, CommercePaymentService payments,CommerceDeliveryService delivery) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
        this.stock = stock; this.merchants = merchants;this.payments=payments;this.delivery=delivery;
        this.warehouses=new CommerceWarehouseAllocationService(dataSource);
    }

    public void initializeSchema() {
        ResourceDatabasePopulator script = new ResourceDatabasePopulator(new ClassPathResource("db/commerce-demo.sql"));
        script.setSqlScriptEncoding("UTF-8");
        script.execute(dataSource);
        try (Connection connection = dataSource.getConnection();
             ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), null, "commerce_order", "expires_at")) {
            if (!columns.next()) {
                jdbc.execute("ALTER TABLE commerce_order ADD COLUMN expires_at DATETIME NULL, ADD COLUMN close_reason VARCHAR(24) NULL, ADD INDEX commerce_expiry(status,expires_at)");
            }
        } catch (java.sql.SQLException ex) { throw new IllegalStateException("Commerce schema migration failed", ex); }
        try (Connection connection = dataSource.getConnection();
             ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), null, "commerce_order", "activity_id")) {
            if (!columns.next()) jdbc.execute("ALTER TABLE commerce_order ADD COLUMN activity_id VARCHAR(32) NULL");
        } catch (java.sql.SQLException ex) { throw new IllegalStateException("Activity migration failed", ex); }
        addShopColumn("commerce_order"); addShopColumn("commerce_activity");
        addOrderColumn("shipping_address","VARCHAR(2000) NULL");
        addOrderColumn("after_sales_id","VARCHAR(32) NULL");
        addOrderColumn("after_sales_status","VARCHAR(24) NULL");
        addOrderColumn("refunded_amount","DECIMAL(14,2) NOT NULL DEFAULT 0");
        addParticipationIndex();
        jdbc.update("UPDATE commerce_order SET expires_at=TIMESTAMPADD(SECOND,?,create_time) WHERE status=0 AND expires_at IS NULL", paymentTimeoutSeconds);
        merchants.initializeSchema(); stock.initializeSchema();
        ResourceDatabasePopulator afterSales=new ResourceDatabasePopulator(new ClassPathResource("db/commerce-after-sales.sql"));
        afterSales.setSqlScriptEncoding("UTF-8"); afterSales.execute(dataSource);
        try(Connection connection=dataSource.getConnection();ResultSet columns=connection.getMetaData().getColumns(connection.getCatalog(),null,"commerce_after_sales_case","return_condition")) {
            if(!columns.next())jdbc.execute("ALTER TABLE commerce_after_sales_case ADD COLUMN return_condition VARCHAR(20) NULL");
        }catch(java.sql.SQLException exception){throw new IllegalStateException("Return condition migration failed",exception);}
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.execute(status->{CommercePartialSupport.migrate(jdbc);return null;});
        for (Long id : jdbc.queryForList("SELECT product_id FROM commerce_product_shop ORDER BY product_id",Long.class))
            transaction.execute(status -> stock.ensureStock(id));
    }
    private void addShopColumn(String table) {
        try (Connection c=dataSource.getConnection(); ResultSet columns=c.getMetaData().getColumns(c.getCatalog(),null,table,"shop_id")) {
            if (!columns.next()) jdbc.execute("ALTER TABLE " + table + " ADD COLUMN shop_id VARCHAR(32) NOT NULL DEFAULT 'default', ADD INDEX commerce_shop_"+ (table.endsWith("order")?"orders(shop_id,status,create_time)":"activities(shop_id,starts_at)"));
        } catch (java.sql.SQLException ex) { throw new IllegalStateException("Shop migration failed",ex); }
    }
    private void addParticipationIndex() {
        try (Connection c=dataSource.getConnection(); ResultSet indexes=c.getMetaData().getIndexInfo(c.getCatalog(),null,"commerce_order",false,false)) {
            while(indexes.next()) if("commerce_owner_activity".equalsIgnoreCase(indexes.getString("INDEX_NAME")))return;
            jdbc.execute("CREATE INDEX commerce_owner_activity ON commerce_order(shop_id,owner_id,activity_id,create_time,order_id,status)");
        } catch (java.sql.SQLException ex) { throw new IllegalStateException("Participation index migration failed",ex); }
    }
    private void addOrderColumn(String column,String definition) {
        try(Connection c=dataSource.getConnection();ResultSet columns=c.getMetaData().getColumns(c.getCatalog(),null,"commerce_order",column)) {
            if(!columns.next())jdbc.execute("ALTER TABLE commerce_order ADD COLUMN "+column+" "+definition);
        } catch(java.sql.SQLException ex) {throw new IllegalStateException("Order migration failed",ex);}
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> create(Map<String, Object> request) { return createInternal(request, null); }

    private Map<String, Object> createInternal(Map<String, Object> request, Map<String, Object> activity) {
        String owner = owner(request.get("ownerId"));
        String key = key(request.get("requestKey"), "requestKey");
        String address=shippingSnapshot(request.get("shippingAddress"),false);
        SortedMap<Long, Long> quantities = normalizeItems(request.get("items"));
        merchants.requireProducts(quantities.keySet());
        String hash = shopHash(activity == null ? requestHash(quantities) : activityHash(quantities, String.valueOf(activity.get("activity_id"))));
        // A unique request row serializes retries even when their product sets differ.
        jdbc.update("INSERT INTO commerce_request(owner_id,request_key) VALUES (?,?) ON DUPLICATE KEY UPDATE request_key=VALUES(request_key)", owner, key);
        List<Map<String, Object>> existing = jdbc.queryForList("SELECT * FROM commerce_order WHERE owner_id=? AND request_key=?", owner, key);
        if (!existing.isEmpty()) {
            require(CommerceShopContext.id().equals(existing.get(0).get("shop_id")), "请求编号已用于其他店铺",409);
            require(hash.equals(existing.get(0).get("request_hash")), "同一请求编号不能提交不同商品", 409);
            require(Objects.equals(address,existing.get(0).get("shipping_address")),"同一请求编号不能更换收货地址",409);
            return shape(existing.get(0));
        }
        require(address!=null,"请提供收货地址",400);
        String orderId = "SC" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + random(10);
        delivery.reserveOrder(orderId,quantities.values().stream().mapToLong(Long::longValue).sum(),activity==null?null:String.valueOf(activity.get("activity_id")));
        List<Map<String, Object>> products = lockProducts(quantities.keySet());
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> product : products) {
            long id = number(product.get("product_id"));
            require("0".equals(String.valueOf(product.get("status"))), "商品已停用", 409);
            Map<String,Object> balance=stock.ensureStock(id);
            long available = number(balance.get("availableStock")) + (activity == null ? 0 : number(balance.get("activityStock")));
            require(available >= quantities.get(id), product.get("product_name") + "可售库存不足", 409);
            BigDecimal price = decimal(activity == null ? product.get("univalence") : activity.get("price"));
            require(price.signum() > 0, "商品售价未配置", 409);
            total = total.add(price.multiply(BigDecimal.valueOf(quantities.get(id))));
        }
        jdbc.update("INSERT INTO commerce_order(order_id,owner_id,request_key,request_hash,status,total_amount,create_time,expires_at,shop_id,shipping_address) VALUES (?,?,?,?,0,?,CURRENT_TIMESTAMP,?,?,?)", orderId, owner, key, hash, total, LocalDateTime.now().plusSeconds(paymentTimeoutSeconds),CommerceShopContext.id(),address);
        for (Map<String, Object> product : products) {
            long id = number(product.get("product_id"));
            BigDecimal price = decimal(activity == null ? product.get("univalence") : activity.get("price"));
            jdbc.update("INSERT INTO commerce_order_item(order_id,product_id,product_code,product_name,spec,quantity,unit_price,amount) VALUES (?,?,?,?,?,?,?,?)",
                    orderId, id, product.get("product_code"), product.get("product_name"), product.get("product_specifications"), quantities.get(id), price, price.multiply(BigDecimal.valueOf(quantities.get(id))));
        }
        if (activity != null) jdbc.update("UPDATE commerce_order SET activity_id=? WHERE order_id=?", activity.get("activity_id"), orderId);
        stock.reserve(orderId, quantities, activity == null ? null : String.valueOf(activity.get("activity_id")));
        return detail(orderId, owner);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> list(String ownerId, Integer status, int pageNum, int pageSize) {
        require(pageNum >= 1 && pageNum <= 100000 && pageSize >= 1 && pageSize <= 100, "分页参数错误", 400);
        List<Object> args = new ArrayList<>();
        String where = " WHERE shop_id=?"; args.add(CommerceShopContext.id());
        if (ownerId != null) { where += " AND owner_id=?"; args.add(owner(ownerId)); }
        if (status != null) { require(status >= 0 && status <= 4, "订单状态错误", 400); where += " AND status=?"; args.add(status); }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM commerce_order" + where, Long.class, args.toArray());
        args.add(pageSize); args.add((pageNum - 1) * pageSize);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM commerce_order" + where + " ORDER BY create_time DESC,order_id DESC LIMIT ? OFFSET ?", args.toArray());
        List<Map<String,Object>> result=new ArrayList<>();
        Map<String,List<Map<String,Object>>> grouped=new HashMap<>();
        if(!rows.isEmpty()) {
            List<Object> ids=new ArrayList<>(); for(Map<String,Object> row:rows)ids.add(row.get("order_id"));
            String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
            for(Map<String,Object> item:jdbc.queryForList("SELECT * FROM commerce_order_item WHERE order_id IN ("+placeholders+") ORDER BY order_id,product_id",ids.toArray()))
                grouped.computeIfAbsent(String.valueOf(item.get("order_id")),key->new ArrayList<>()).add(item);
        }
        for(Map<String,Object> row:rows)result.add(shape(row,grouped.getOrDefault(String.valueOf(row.get("order_id")),Collections.emptyList())));
        return map("rows",result,"total",total);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(String orderId, String ownerId) {
        return shape(find(orderId, ownerId, false));
    }

    public Map<String, Object> pay(String orderId, Map<String, Object> request) {
        String owner = owner(request.get("ownerId"));
        String key = key(request.get("paymentRequestId"), "paymentRequestId");
        String scenario = String.valueOf(request.get("scenario"));
        Map<String,Object> operation=payments.pay(orderId,owner,key,scenario,()->{
            Map<String,Object> order=find(orderId,owner,true);
            if(number(order.get("status"))==0&&expired(order)){closeExpired(orderId,order);return find(orderId,owner,false);}
            if(number(order.get("status"))==4)require("PAYMENT_TIMEOUT".equals(order.get("close_reason")),"已取消订单不能再次支付",409);
            if(number(order.get("status"))!=4)requireFulfillable(order);
            return order;
        },this::expireOrder);
        Map<String,Object> result=detail(orderId,owner);result.put("paymentOutcome",operation.get("local_status"));
        if(operation.get("operationId")!=null)result.put("paymentOperation",operation);return result;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> cancel(String orderId, String ownerId) {
        String owner = owner(ownerId);
        Map<String, Object> order = find(orderId, owner, true);
        int status = (int) number(order.get("status"));
        if (status == 4) return shape(order);
        require(status == 0, "仅待支付订单可以取消；已支付订单需要退款流程", 409);
        boolean active=releaseActivity(order);
        delivery.releaseOrder(orderId,"CUSTOMER_CANCEL",active);
        lockProducts(itemQuantities(orderId).keySet());
        stock.release(orderId,"CUSTOMER_CANCEL",active);
        jdbc.update("UPDATE commerce_order SET status=4,close_reason='CUSTOMER_CANCEL',cancelled_time=CURRENT_TIMESTAMP WHERE order_id=? AND status=0", orderId);
        return detail(orderId, owner);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> ship(String orderId, Map<String, Object> request, long userId) {
        merchants.requireCapability(CommerceShopContext.id(),userId,CommerceCapability.FULFILMENT);
        Map<String, Object> order = find(orderId, null, true);
        List<Map<String,Object>> facts=CommercePartialSupport.lines(jdbc,orderId);
        SortedMap<Long,Long> quantities=new TreeMap<>();
        if(request.get("items")!=null)quantities=normalizeItems(request.get("items"));
        else for(Map<String,Object> fact:facts){long remaining=number(fact.get("quantity"))-number(fact.get("shipped"))-number(fact.get("released"));if(remaining>0)quantities.put(number(fact.get("product_id")),remaining);}
        String requestKey=request.get("requestKey")==null?"LEGACY_FULL":key(request.get("requestKey"),"requestKey");
        Long warehouseId=request.get("warehouseId")==null?null:positiveInteger(request.get("warehouseId"),"warehouseId");
        List<Map<String,Object>> previous=jdbc.queryForList("SELECT * FROM commerce_shipment WHERE order_id=? AND request_key=?",orderId,requestKey);
        if(!previous.isEmpty()) {
            SortedMap<Long,Long> original=new TreeMap<>();
            for(Map<String,Object> row:jdbc.queryForList("SELECT product_id,quantity FROM commerce_shipment_item WHERE shipment_id=?",previous.get(0).get("shipment_id")))original.put(number(row.get("product_id")),number(row.get("quantity")));
            String previousHash=String.valueOf(previous.get(0).get("request_hash"));
            if("LEGACY_FULL".equals(previous.get(0).get("request_key"))&&previousHash.equals(String.join("",Collections.nCopies(64,"0"))))previousHash=requestHash(original);
            require(shipmentHash(request.get("items")==null?original:quantities,warehouseId).equals(previousHash),"同一发货请求不能改变商品、数量或仓库",409);
            return shape(order);
        }
        requireFulfillable(order);
        int status = (int) number(order.get("status"));
        if ((status == 2 || status == 3)&&request.get("items")==null)return shape(order);
        require(status == 1, "只有沙箱支付成功的订单可以发货", 409);
        require(!quantities.isEmpty(),"没有剩余可发货商品",409);
        Map<Long,Map<String,Object>> factIndex=new TreeMap<>();for(Map<String,Object> fact:facts)factIndex.put(number(fact.get("product_id")),fact);
        BigDecimal shipmentAmount=BigDecimal.ZERO;
        for(Map.Entry<Long,Long> part:quantities.entrySet()) {
            Map<String,Object> fact=factIndex.get(part.getKey());require(fact!=null&&number(fact.get("unshipped_available"))>=part.getValue(),"发货数量超过剩余数量或已被售后申请锁定",409);
            shipmentAmount=shipmentAmount.add(decimal(fact.get("unit_price")).multiply(BigDecimal.valueOf(part.getValue())));
        }
        String carrier = request.get("carrier") == null ? "演示物流" : String.valueOf(request.get("carrier")).trim();
        require(!carrier.isEmpty() && carrier.length() <= 64, "物流名称需为1至64个字符", 400);
        String tracking = request.get("trackingNo") == null ? "DEMO-" + random(18) : String.valueOf(request.get("trackingNo")).trim();
        require(!tracking.isEmpty() && tracking.length() <= 80, "物流单号需为1至80个字符", 400);
        String shipmentId="SH"+LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))+random(10);
        String receipt = "CX" + ("LEGACY_FULL".equals(requestKey)?orderId.substring(2):shipmentId.substring(2));
        delivery.consumeOrder(orderId,requestKey,quantities.values().stream().mapToLong(Long::longValue).sum());
        // Share the ERP document lock before taking product locks. Otherwise an ERP save that
        // checked the order before dispatch committed could overwrite the new outbound receipt.
        jdbc.update("INSERT INTO commerce_receipt_lock(receipt_id) VALUES (?) ON DUPLICATE KEY UPDATE receipt_id=VALUES(receipt_id)",receipt);
        require(jdbc.queryForList("SELECT systematic_id FROM head_receipt WHERE systematic_receipt=? FOR UPDATE",receipt).isEmpty(),"发货单号已存在，请先核对历史单据",409);
        List<Map<String, Object>> products = lockProducts(quantities.keySet());
        Map<Long,Map<Long,Long>> warehousePlan=stock.dispatch(orderId,quantities,"DISPATCH:"+shipmentId,warehouseId);
        Long firstWarehouse = null;
        for (Map<String, Object> product : products) {
            long id = number(product.get("product_id"));
            long remaining = quantities.get(id);
            List<Map<String, Object>> inventory = jdbc.queryForList("SELECT * FROM inventory_product WHERE product_id=? ORDER BY warehouse_id,inventory_id FOR UPDATE", id);
            long book = 0;
            Map<Long,Long> warehouseBefore=new TreeMap<>(),warehouseTaken=new TreeMap<>();
            Map<Long,Long> warehouseRemaining=new TreeMap<>(warehousePlan.get(id));
            for (Map<String,Object> row:inventory) {
                long warehouse=number(row.get("warehouse_id")),quantity=number(row.get("plan_quantity")); book+=quantity;
                warehouseBefore.put(warehouse,warehouseBefore.getOrDefault(warehouse,0L)+quantity);
            }
            require(book >= remaining && number(product.get("inventory_qty")) >= remaining, "账面库存不足，发货未执行", 409);
            Map<String, Object> item = jdbc.queryForMap("SELECT * FROM commerce_order_item WHERE order_id=? AND product_id=?", orderId, id);
            for (Map<String, Object> row : inventory) {
                long before = number(row.get("plan_quantity"));
                long warehouse = number(row.get("warehouse_id"));
                long amount = Math.min(Math.max(before,0),warehouseRemaining.getOrDefault(warehouse,0L));
                if (amount == 0) continue;
                if (firstWarehouse == null) firstWarehouse = warehouse;
                warehouseTaken.put(warehouse,warehouseTaken.getOrDefault(warehouse,0L)+amount);
                warehouseRemaining.put(warehouse,warehouseRemaining.get(warehouse)-amount);
                int changed = jdbc.update("UPDATE inventory_product SET plan_quantity=plan_quantity-?,update_by='commerce-demo',update_time=CURRENT_TIMESTAMP WHERE inventory_id=? AND plan_quantity>=?", amount, row.get("inventory_id"), amount);
                require(changed == 1, "库存已变化，发货未执行", 409);
                BigDecimal price = decimal(item.get("unit_price"));
                jdbc.update("INSERT INTO detail_receipt(systematic_receipt,product_id,product_specifications,measure_unit,warehousing_id,retrieval_id,supplier_id,customer_id,current_inventory,actual_inventory,plan_quantity,univalence,discount,money,cost,remarks) VALUES (?,?,?,?,0,?,0,0,?,?,?,?,100,?,?,?)",
                        receipt, id, product.get("product_specifications"), product.get("measure_unit"), warehouse, before, before - amount, amount, price, price.multiply(BigDecimal.valueOf(amount)), decimal(product.get("cost_price")), "商城沙箱订单发货");
                remaining -= amount;
                if (remaining == 0) break;
            }
            require(remaining == 0, "库存不足，发货未执行", 409);
            for(Map.Entry<Long,Long> taken:warehouseTaken.entrySet()) {
                long before=warehouseBefore.get(taken.getKey()),amount=taken.getValue();
                jdbc.update("INSERT INTO commerce_warehouse_ledger(event_key,receipt_id,related_receipts,operation,product_id,warehouse_id,delta_quantity,before_quantity,after_quantity,created_at) VALUES (?,?,?,'DISPATCH',?,?,?,?,?,CURRENT_TIMESTAMP)","DISPATCH:"+shipmentId+":"+id+":"+taken.getKey(),receipt,receipt,id,taken.getKey(),-amount,before,before-amount);
            }
            int changed = jdbc.update("UPDATE product SET inventory_qty=inventory_qty-?,update_by='commerce-demo',update_time=CURRENT_TIMESTAMP WHERE product_id=? AND inventory_qty>=?", quantities.get(id), id, quantities.get(id));
            require(changed == 1, "货品库存已变化，发货未执行", 409);
        }
        // Type 3 is sales outbound; type 2 in the original schema is purchase return.
        jdbc.update("INSERT INTO head_receipt(systematic_receipt,original_receipt,receipt_category,receipt_type,receipt_status,invoice_date,warehousing_ids,retrieval_ids,user_ids,supplier_ids,customer_ids,deposit,total_amount,receipt_notes,create_by,create_time) VALUES (?,?,'2','3','2',CURRENT_DATE,0,?,?,0,0,0,?,?,'commerce-demo',CURRENT_TIMESTAMP)",
                receipt, orderId, firstWarehouse, userId, shipmentAmount, "商城本地支付沙箱；分批发货登记（未提交真实物流）");
        jdbc.update("INSERT INTO commerce_shipment(shipment_id,order_id,request_key,request_hash,receipt_id,carrier,tracking_no,amount,created_at) VALUES (?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",shipmentId,orderId,requestKey,shipmentHash(quantities,warehouseId),receipt,carrier,tracking,shipmentAmount);
        for(Map.Entry<Long,Long> part:quantities.entrySet())jdbc.update("INSERT INTO commerce_shipment_item(shipment_id,product_id,quantity) VALUES (?,?,?)",shipmentId,part.getKey(),part.getValue());
        if(costs!=null)costs.recordShipment(shipmentId,userId);
        long outstanding=jdbc.queryForObject("SELECT COALESCE(SUM(i.quantity-f.shipped-f.released),0) FROM commerce_order_item i JOIN commerce_fulfillment_line f ON f.order_id=i.order_id AND f.product_id=i.product_id WHERE i.order_id=?",Long.class,orderId);
        jdbc.update("UPDATE commerce_order SET status=?,shipped_time=CURRENT_TIMESTAMP,receipt_id=?,carrier=?,tracking_no=? WHERE order_id=? AND status=1",outstanding==0?2:1, receipt, carrier, tracking, orderId);
        return detail(orderId, null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> receive(String orderId, String ownerId) {
        String owner = owner(ownerId);
        Map<String, Object> order = find(orderId, owner, true);
        requireFulfillable(order);
        int status = (int) number(order.get("status"));
        if (status == 3) return shape(order);
        require(status == 2, "订单尚未发货，不能确认收货", 409);
        jdbc.update("UPDATE commerce_order SET status=3,received_time=CURRENT_TIMESTAMP WHERE order_id=? AND status=2", orderId);
        return detail(orderId, owner);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> inventory() {
        List<Map<String, Object>> result = new ArrayList<>();
        // Display committed snapshots only. Reads never bootstrap, repair physical stock, release
        // campaigns, or acquire the product/warehouse/stock locks used by checkout and ERP posting.
        for (Map<String,Object> row:jdbc.queryForList("SELECT p.product_id,p.product_code,p.product_name,p.product_specifications,p.univalence,p.notes,p.status,pt.product_type_name,s.listed,c.product_id AS snapshot_id,c.on_hand,c.reserved,c.activity_reserved,c.unavailable,c.version FROM product p JOIN commerce_product_shop s ON s.product_id=p.product_id LEFT JOIN commerce_stock c ON c.product_id=p.product_id LEFT JOIN product_type pt ON pt.product_type_id=p.product_type WHERE s.shop_id=? ORDER BY p.product_id",CommerceShopContext.id())) {
            long onHand=number(row.get("on_hand")),reserved=number(row.get("reserved")),activity=number(row.get("activity_reserved"));
            result.add(map("productId",number(row.get("product_id")),"shopId",CommerceShopContext.id(),"listed",row.get("listed"),
                    "productCode",row.get("product_code"),"productName",row.get("product_name"),"cover",cover(String.valueOf(row.get("product_code"))),
                    "spec",row.get("product_specifications"),"price",row.get("univalence"),"description",row.get("notes"),"categoryName",row.get("product_type_name"),"productStatus",row.get("status"),
                    "bookStock",onHand,"onHand",onHand,"reservedStock",reserved,"activityStock",activity,
                    "unavailableStock",number(row.get("unavailable")),"availableStock",onHand-reserved-activity-number(row.get("unavailable")),"version",number(row.get("version")),"snapshotReady",row.get("snapshot_id")!=null));
        }
        return result;
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> stockLedger(long product, int limit) {
        require(jdbc.queryForObject("SELECT COUNT(*) FROM commerce_product_shop WHERE product_id=? AND shop_id=?",Long.class,product,CommerceShopContext.id())==1L,"商品不属于当前店铺",404);
        return stock.ledger(product,limit);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> reconcile() {
        Map<String,Object> report=stock.reconcile();
        Set<Long> owned=new HashSet<>(jdbc.queryForList("SELECT product_id FROM commerce_product_shop WHERE shop_id=?",Long.class,CommerceShopContext.id()));
        List<Map<String,Object>> issues=new ArrayList<>();
        for(Object value:(List<?>)report.get("issues")) { Map<String,Object> issue=(Map<String,Object>)value; if(owned.contains(number(issue.get("productId"))))issues.add(issue); }
        return map("healthy",issues.isEmpty(),"checkedProducts",owned.size(),"issues",issues);
    }

    private List<Map<String, Object>> lockProducts(Collection<Long> ids) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Long id : new TreeSet<>(ids)) {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM product WHERE product_id=? FOR UPDATE", id);
            require(!rows.isEmpty(), "商品不存在", 404);
            result.add(rows.get(0));
        }
        return result;
    }

    private Map<String, Object> find(String id, String ownerId, boolean lock) {
        require(id != null && id.matches("SC[0-9]{14}[A-F0-9]{10}"), "订单不存在", 404);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM commerce_order WHERE order_id=? AND shop_id=?" + (lock ? " FOR UPDATE" : ""), id,CommerceShopContext.id());
        require(!rows.isEmpty(), "订单不存在", 404);
        if (ownerId != null) require(owner(ownerId).equals(rows.get(0).get("owner_id")), "订单不存在", 404);
        return rows.get(0);
    }

    private SortedMap<Long, Long> itemQuantities(String id) {
        SortedMap<Long, Long> result = new TreeMap<>();
        for (Map<String, Object> item : jdbc.queryForList("SELECT product_id,quantity FROM commerce_order_item WHERE order_id=? ORDER BY product_id", id)) result.put(number(item.get("product_id")), number(item.get("quantity")));
        return result;
    }

    private long bookStock(long productId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(plan_quantity),0) FROM inventory_product WHERE product_id=?", Long.class, productId);
    }

    private long reservedStock(long productId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(i.quantity),0) FROM commerce_order_item i JOIN commerce_order o ON o.order_id=i.order_id WHERE i.product_id=? AND o.status IN (0,1)", Long.class, productId);
    }

    private Map<String,Object> shape(Map<String,Object> row) {
        return shape(row,jdbc.queryForList("SELECT * FROM commerce_order_item WHERE order_id=? ORDER BY product_id",row.get("order_id")));
    }
    private Map<String,Object> shape(Map<String,Object> row,List<Map<String,Object>> lines) {
        String id = String.valueOf(row.get("order_id"));
        int status = (int) number(row.get("status"));
        String[] names = {"待支付（本地沙箱）", "沙箱已支付·待发货", "已发货（演示登记）", "已收货", "已取消"};
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String,Object> item:lines) {
            items.add(map("productId", item.get("product_id"), "productCode", item.get("product_code"), "productName", item.get("product_name"), "spec", item.get("spec"), "quantity", item.get("quantity"), "unitPrice", item.get("unit_price"), "amount", item.get("amount"), "cover", cover(String.valueOf(item.get("product_code")))));
        }
        Map<String,Object> result=map("orderId", id, "tenantId", TenantContext.id(), "shopId",row.get("shop_id"), "activityId", row.get("activity_id"), "expiresAt", time(row.get("expires_at")), "closeReason", row.get("close_reason"), "status", status, "orderStatus", status, "statusName", names[status], "totalAmount", row.get("total_amount"), "createTime", time(row.get("create_time")), "paidTime", time(row.get("paid_time")), "shippedTime", time(row.get("shipped_time")), "receivedTime", time(row.get("received_time")), "cancelledTime", time(row.get("cancelled_time")), "transactionId", row.get("transaction_id"), "receiptId", row.get("receipt_id"), "carrier", row.get("carrier"), "trackingNo", row.get("tracking_no"), "shippingAddress",row.get("shipping_address")==null?null:JSON.parseObject(String.valueOf(row.get("shipping_address"))), "afterSalesId",row.get("after_sales_id"),"afterSalesStatus",row.get("after_sales_status"),"refundedAmount",row.get("refunded_amount"), "demo", true, "paymentProvider", "LOCAL_SANDBOX", "reservationActive", (status == 0 || status == 1) && !"REFUNDED".equals(row.get("after_sales_status")), "items", items);
        result.put("dispatchPromise",delivery.orderPromise(id));
        result.put("warehouseAllocations",warehouses.allocations(id));
        List<String> paymentIds=jdbc.queryForList("SELECT operation_id FROM commerce_payment_operation WHERE order_id=? AND kind='PAYMENT' ORDER BY CASE WHEN provider_reference=? THEN 0 WHEN local_status IN('PREPARED','PENDING','UNKNOWN','COMPENSATION_PENDING') THEN 1 ELSE 2 END,created_at DESC,operation_id DESC LIMIT 1",String.class,id,row.get("transaction_id"));
        if(!paymentIds.isEmpty()){Map<String,Object> payment=payments.detail(paymentIds.get(0));result.put("paymentOperation",payment);result.put("paymentOutcome",payment.get("outcome"));}
        CommercePartialSupport.decorate(jdbc,result);return result;
    }

    @Transactional(readOnly = true)
    public List<String> expiredOrderIds() {
        return jdbc.queryForList("SELECT order_id FROM commerce_order WHERE shop_id=? AND status=0 AND expires_at<=CURRENT_TIMESTAMP ORDER BY expires_at,order_id LIMIT 100", String.class,CommerceShopContext.id());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean expireOrder(String id) {
        Map<String, Object> order = find(id, null, true);
        if ((int) number(order.get("status")) != 0 || !expired(order)) return false;
        closeExpired(id, order); return true;
    }
    private boolean expired(Map<String, Object> order) {
        Object value = order.get("expires_at");
        if (value == null) return false;
        LocalDateTime deadline = value instanceof java.sql.Timestamp ? ((java.sql.Timestamp) value).toLocalDateTime() : LocalDateTime.parse(String.valueOf(value).replace(' ', 'T'));
        return !deadline.isAfter(LocalDateTime.now());
    }
    private void closeExpired(String id, Map<String, Object> order) {
        boolean active=releaseActivity(order);
        delivery.releaseOrder(id,"PAYMENT_TIMEOUT",active);
        lockProducts(itemQuantities(id).keySet());
        stock.release(id,"PAYMENT_TIMEOUT",active);
        jdbc.update("UPDATE commerce_order SET status=4,close_reason='PAYMENT_TIMEOUT',cancelled_time=CURRENT_TIMESTAMP WHERE order_id=? AND status=0", id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> createActivity(Map<String, Object> request) {
        String id = key(request.get("activityId"), "activityId");
        require(id.length() <= 32, "活动编号过长", 400);
        long product = positiveInteger(request.get("productId"), "productId");
        long capacity = positiveInteger(request.get("capacity"), "capacity");
        long limit = positiveInteger(request.get("perOwnerLimit"), "perOwnerLimit");
        require(capacity <= 1000 && limit <= 10, "演示活动配额超出范围", 400);
        BigDecimal price = decimal(request.get("price"));
        require(price.signum() > 0, "活动价格必须大于零", 400);
        String title = String.valueOf(request.getOrDefault("title", "限时秒杀"));
        require(title.length() <= 80, "活动标题过长", 400);
        LocalDateTime starts, ends;
        try { starts = LocalDateTime.parse(String.valueOf(request.get("startsAt"))); ends = LocalDateTime.parse(String.valueOf(request.get("endsAt"))); }
        catch (RuntimeException ex) { throw new ServiceException("活动时间格式错误", 400); }
        require(ends.isAfter(starts) && ends.isAfter(LocalDateTime.now()), "活动截止时间无效", 400);
        merchants.requireProducts(Collections.singleton(product));
        delivery.reserveActivity(id,capacity,starts);
        lockProducts(Collections.singletonList(product));
        require(number(stock.ensureStock(product).get("availableStock")) >= capacity, "可分配库存不足", 409);
        require(jdbc.queryForObject("SELECT COUNT(*) FROM commerce_activity WHERE activity_id=?", Long.class, id) == 0, "活动编号已存在", 409);
        jdbc.update("INSERT INTO commerce_activity(activity_id,product_id,title,price,capacity,remaining,per_owner_limit,starts_at,ends_at,shop_id) VALUES (?,?,?,?,?,?,?,?,?,?)", id, product, title, price, capacity, capacity, limit, starts, ends,CommerceShopContext.id());
        stock.allocateActivity(id,product,capacity);
        return activity(id, false);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> activities() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT a.*,p.product_code,p.product_name FROM commerce_activity a JOIN product p ON p.product_id=a.product_id WHERE a.shop_id=? ORDER BY a.starts_at DESC,a.activity_id DESC LIMIT 100",CommerceShopContext.id())) {
            long product = number(row.get("product_id"));
            Map<String,Object> p=row;
            result.add(map("activityId", row.get("activity_id"), "shopId",row.get("shop_id"), "productId", product, "productCode", p.get("product_code"), "productName", p.get("product_name"), "title", row.get("title"), "price", row.get("price"), "remaining", row.get("remaining"), "capacity", row.get("capacity"), "perOwnerLimit", row.get("per_owner_limit"), "startsAt", time(row.get("starts_at")), "endsAt", time(row.get("ends_at")), "cover", cover(String.valueOf(p.get("product_code")))));
        } return result;
    }

    /** Private summary for one server-authenticated visitor; no order history or item fan-out. */
    @Transactional(readOnly = true)
    public List<Map<String,Object>> activityParticipation(String ownerId) {
        String visitor=owner(ownerId);
        List<Map<String,Object>> result=new ArrayList<>();
        String sql="SELECT a.activity_id,(SELECT COUNT(*) FROM commerce_order counted WHERE counted.shop_id=a.shop_id AND counted.owner_id=? AND counted.activity_id=a.activity_id AND counted.status IN (0,1,2,3) AND COALESCE(counted.after_sales_status,'')<>'REFUNDED') AS participation_count,o.order_id,o.status FROM (SELECT visible.activity_id,visible.shop_id,visible.starts_at FROM commerce_activity visible JOIN product p ON p.product_id=visible.product_id WHERE visible.shop_id=? ORDER BY visible.starts_at DESC,visible.activity_id DESC LIMIT 100) a LEFT JOIN commerce_order o ON o.order_id=(SELECT mine.order_id FROM commerce_order mine WHERE mine.shop_id=a.shop_id AND mine.owner_id=? AND mine.activity_id=a.activity_id AND mine.status IN (0,1,2,3) AND COALESCE(mine.after_sales_status,'')<>'REFUNDED' ORDER BY mine.create_time DESC,mine.order_id DESC LIMIT 1) ORDER BY a.starts_at DESC,a.activity_id DESC";
        for(Map<String,Object> row:jdbc.queryForList(sql,visitor,CommerceShopContext.id(),visitor))
            result.add(map("activityId",row.get("activity_id"),"participationCount",number(row.get("participation_count")),"myOrderId",row.get("order_id"),"myOrderStatus",row.get("status")));
        return result;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> seckill(String activityId, Map<String, Object> request) {
        String owner = owner(request.get("ownerId")); String requestKey = key(request.get("requestKey"), "requestKey");
        Map<String, Object> activity = activity(activityId, true);
        SortedMap<Long, Long> quantities = new TreeMap<>(); quantities.put(number(activity.get("product_id")), 1L);
        List<Map<String, Object>> old = jdbc.queryForList("SELECT * FROM commerce_order WHERE owner_id=? AND request_key=?", owner, requestKey);
        if (!old.isEmpty()) { require(CommerceShopContext.id().equals(old.get(0).get("shop_id")) && shopHash(activityHash(quantities, activityId)).equals(old.get(0).get("request_hash")), "请求编号已用于其他订单", 409); require(Objects.equals(shippingSnapshot(request.get("shippingAddress"),false),old.get(0).get("shipping_address")),"同一请求编号不能更换收货地址",409); return shape(old.get(0)); }
        LocalDateTime now = LocalDateTime.now();
        require(!now.isBefore(dateTime(activity.get("starts_at"))) && now.isBefore(dateTime(activity.get("ends_at"))), "活动尚未开始或已结束", 409);
        require(number(activity.get("remaining")) > 0, "活动库存已抢完", 409);
        Long bought = jdbc.queryForObject("SELECT COUNT(*) FROM commerce_order WHERE owner_id=? AND activity_id=? AND shop_id=? AND status IN (0,1,2,3) AND COALESCE(after_sales_status,'')<>'REFUNDED'", Long.class, owner, activityId,CommerceShopContext.id());
        require(bought < number(activity.get("per_owner_limit")), "已达到本活动限购数量", 409);
        Map<String, Object> body = new LinkedHashMap<>(request);
        body.put("items", Collections.singletonList(map("productId", number(activity.get("product_id")), "quantity", 1)));
        Map<String, Object> order = createInternal(body, activity);
        require(jdbc.update("UPDATE commerce_activity SET remaining=remaining-1 WHERE activity_id=? AND remaining>0", activityId) == 1, "活动库存已抢完", 409);
        return order;
    }
    private Map<String, Object> activity(String id, boolean lock) {
        require(id != null && id.matches("[A-Za-z0-9_-]{1,32}"), "活动不存在", 404);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM commerce_activity WHERE activity_id=? AND shop_id=?" + (lock ? " FOR UPDATE" : ""), id,CommerceShopContext.id());
        require(!rows.isEmpty(), "活动不存在", 404); return rows.get(0);
    }
    private long activityFreeStock(long product) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(remaining),0) FROM commerce_activity WHERE product_id=? AND ends_at>CURRENT_TIMESTAMP", Long.class, product);
    }
    private boolean releaseActivity(Map<String,Object> order) {
        if (order.get("activity_id")==null) return false;
        String id=String.valueOf(order.get("activity_id"));
        Map<String,Object> activity=activity(id,true);
        boolean active=dateTime(activity.get("ends_at")).isAfter(LocalDateTime.now());
        jdbc.update("UPDATE commerce_activity SET remaining=remaining+1 WHERE activity_id=? AND remaining<capacity",id);
        return active;
    }
    private void releaseEndedActivities(Collection<Long> products) {
        if(products.isEmpty())return;
        String placeholders=String.join(",",Collections.nCopies(products.size(),"?"));
        List<Object> args=new ArrayList<>(products); args.add(CommerceShopContext.id());
        List<Map<String,Object>> ended=jdbc.queryForList("SELECT a.* FROM commerce_activity a WHERE a.product_id IN ("+placeholders+") AND a.shop_id=? AND a.ends_at<=CURRENT_TIMESTAMP AND NOT EXISTS (SELECT 1 FROM commerce_stock_ledger l WHERE l.event_key=CONCAT('ACTIVITY_EXPIRE:',a.activity_id,':',a.product_id)) ORDER BY a.product_id,a.activity_id FOR UPDATE",args.toArray());
        if(ended.isEmpty())return;
        List<String> activityIds=new ArrayList<>();for(Map<String,Object> row:ended)activityIds.add(String.valueOf(row.get("activity_id")));
        delivery.releaseActivities(activityIds);
        lockProducts(products);
        for(Map<String,Object> row:ended)stock.releaseExpiredActivity(String.valueOf(row.get("activity_id")),number(row.get("product_id")),number(row.get("remaining")));
    }
    public List<String> shopIds() { return jdbc.queryForList("SELECT shop_id FROM commerce_shop WHERE status='ENABLED' ORDER BY shop_id",String.class); }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void expireActivities() {
        List<Long> products=jdbc.queryForList("SELECT DISTINCT a.product_id FROM commerce_activity a WHERE a.shop_id=? AND a.ends_at<=CURRENT_TIMESTAMP AND NOT EXISTS (SELECT 1 FROM commerce_stock_ledger l WHERE l.event_key=CONCAT('ACTIVITY_EXPIRE:',a.activity_id,':',a.product_id)) ORDER BY a.product_id",Long.class,CommerceShopContext.id());
        releaseEndedActivities(products);
    }
    private static String shopHash(String hash) {
        if("default".equals(CommerceShopContext.id()))return hash;
        try { byte[] bytes=MessageDigest.getInstance("SHA-256").digest((CommerceShopContext.id()+":"+hash).getBytes(StandardCharsets.UTF_8)); StringBuilder out=new StringBuilder(); for(byte b:bytes)out.append(String.format("%02x",b&255)); return out.toString(); }
        catch(Exception ex){throw new IllegalStateException(ex);}
    }
    private static LocalDateTime dateTime(Object value) {
        return value instanceof java.sql.Timestamp ? ((java.sql.Timestamp) value).toLocalDateTime() : LocalDateTime.parse(String.valueOf(value).replace(' ', 'T'));
    }
    private static String activityHash(SortedMap<Long, Long> quantities, String activity) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest((requestHash(quantities) + ":" + activity).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format("%02x", b & 255)); return out.toString();
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    public static SortedMap<Long, Long> normalizeItems(Object raw) {
        require(raw instanceof List, "商品列表格式错误", 400);
        List<?> list = (List<?>) raw;
        require(!list.isEmpty() && list.size() <= 30, "每单需有1至30个商品", 400);
        SortedMap<Long, Long> result = new TreeMap<>();
        for (Object value : list) {
            require(value instanceof Map, "商品格式错误", 400);
            Map<?, ?> item = (Map<?, ?>) value;
            long id = positiveInteger(item.get("productId"), "productId");
            long quantity = positiveInteger(item.get("quantity"), "quantity");
            require(quantity <= 99, "单商品数量不能超过99", 400);
            long merged = result.getOrDefault(id, 0L) + quantity;
            require(merged <= 99, "单商品合计数量不能超过99", 400);
            result.put(id, merged);
        }
        return result;
    }

    public static String requestHash(SortedMap<Long, Long> quantities) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(quantities.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte value : digest) result.append(String.format("%02x", value & 255));
            return result.toString();
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    private static String shipmentHash(SortedMap<Long,Long> quantities,Long warehouseId) {
        return warehouseId==null?requestHash(quantities):activityHash(quantities,"WAREHOUSE:"+warehouseId);
    }

    private static long positiveInteger(Object value, String field) {
        require(value != null && !(value instanceof Boolean) && String.valueOf(value).matches("[1-9][0-9]{0,17}"), field + "必须是正整数", 400);
        try { return Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException ex) { throw new ServiceException(field + "超出范围", 400); }
    }

    private static String owner(Object value) {
        require(value instanceof String && ((String) value).matches("[a-f0-9]{64}"), "访客标识错误", 400);
        return (String) value;
    }
    /** Canonical immutable snapshot shared by direct and durable queued checkout. */
    private static void requireFulfillable(Map<String,Object> order) {
        require(decimal(order.get("refunded_amount")).compareTo(decimal(order.get("total_amount")))<0,"订单已全额退款，不能继续履约",409);
    }
    public static String shippingSnapshot(Object raw,boolean required) {
        if(raw==null) {require(!required,"请提供收货地址",400);return null;}
        require(raw instanceof Map,"收货地址格式错误",400);
        Map<?,?> value=(Map<?,?>)raw;
        String name=addressText(value.get("addressee"),1,64,"收货人"),phone=addressText(value.get("phone"),6,24,"联系电话"),address=addressText(value.get("address"),5,300,"详细地址");
        require(phone.matches("\\+?[0-9][0-9 ()-]{4,22}[0-9]"),"联系电话格式错误",400);
        return JSON.toJSONString(map("addressee",name,"phone",phone,"address",address));
    }
    private static String addressText(Object raw,int min,int max,String label) {
        require(raw instanceof String,label+"格式错误",400);String value=((String)raw).trim();
        require(value.length()>=min && value.length()<=max && value.chars().noneMatch(Character::isISOControl),label+"长度或内容无效",400);return value;
    }

    private static String key(Object value, String field) {
        require(value instanceof String && ((String) value).matches("[A-Za-z0-9_-]{1,80}"), field + "格式错误", 400);
        return (String) value;
    }

    private static String random(int size) { return UUID.randomUUID().toString().replace("-", "").substring(0, size).toUpperCase(Locale.ROOT); }
    private static long number(Object value) { return value == null ? 0 : ((Number) value).longValue(); }
    private static BigDecimal decimal(Object value) { return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value)).setScale(2); }
    private static String time(Object value) { return value == null ? null : String.valueOf(value).replace(".0", ""); }
    private static String cover(String code) { String image=MEDIA.get(code); return image==null?"/demo-media/fallback.svg":"/demo-media/products/"+image+".svg"; }
    private static void require(boolean condition, String message, int code) { if (!condition) throw new ServiceException(message, code); }
    private static Map<String, Object> map(Object... args) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i += 2) result.put((String) args[i], args[i + 1]);
        return result;
    }
}

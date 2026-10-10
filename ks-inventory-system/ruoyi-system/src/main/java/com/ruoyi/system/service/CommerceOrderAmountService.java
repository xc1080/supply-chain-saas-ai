package com.ruoyi.system.service;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.core.tenant.CommerceShopContext;
import com.ruoyi.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

/** Server-owned prices in integer cents. No stock writes; callers retain order -> product lock order. */
@Service
@Profile({"local","commerce"})
public class CommerceOrderAmountService {
    private static final long MAX_CENTS=99999999999999L; // DECIMAL(14,2)
    private final DataSource source;
    private final JdbcTemplate jdbc;
    private final CommerceMerchantService merchants;
    public CommerceOrderAmountService(DataSource source) {this(source,new CommerceMerchantService(source));}
    @Autowired public CommerceOrderAmountService(DataSource source,CommerceMerchantService merchants) {
        this.source=source;this.jdbc=new JdbcTemplate(source);this.merchants=merchants;
    }

    public void initializeSchema() {
        ResourceDatabasePopulator script=new ResourceDatabasePopulator(new ClassPathResource("db/commerce-order-amount.sql"));
        script.setSqlScriptEncoding("UTF-8");script.execute(source);
    }

    /** Only legacy test/upgrade databases may omit V14. Never silently drop a selected promotion. */
    public boolean schemaAvailable() {
        Connection connection=DataSourceUtils.getConnection(source);
        try(ResultSet tables=connection.getMetaData().getTables(connection.getCatalog(),null,"commerce_order_amount",new String[]{"TABLE"})) {
            return tables.next();
        } catch(java.sql.SQLException error) {throw new IllegalStateException("Order amount schema inspection failed",error);}
        finally {DataSourceUtils.releaseConnection(connection,source);}
    }
    private void requireSchema() {require(schemaAvailable(),"金额配置尚未完成迁移",503);}
    public static String promotionSelection(Object raw) {
        if(raw==null)return null;
        require(raw instanceof String&&((String)raw).matches("[A-Za-z0-9_-]{1,32}"),"优惠标识格式错误",400);
        return (String)raw;
    }

    @Transactional(readOnly=true)
    public Map<String,Object> policy() {
        requireSchema();requireShop();
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT shipping_cents FROM commerce_pricing_policy WHERE shop_id=?",shop());
        return map("shopId",shop(),"shippingFee",money(rows.isEmpty()?0:n(rows.get(0).get("shipping_cents"))));
    }
    @Transactional
    public Map<String,Object> savePolicy(Map<String,Object> body,long actor) {
        merchants.requireCapability(shop(),actor,CommerceCapability.CATALOG);requireSchema();
        require(body!=null,"金额配置不能为空",400);long fee=inputCents(body.get("shippingFee"),false,"运费");
        jdbc.update("INSERT INTO commerce_pricing_policy(shop_id,shipping_cents,updated_at) VALUES (?,?,CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE shipping_cents=VALUES(shipping_cents),updated_at=CURRENT_TIMESTAMP",shop(),fee);
        return policy();
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> promotions() {requireSchema();requireShop();return promotionRows(true);}
    @Transactional(readOnly=true)
    public List<Map<String,Object>> managePromotions(long actor) {
        merchants.requireCapability(shop(),actor,CommerceCapability.READ);requireSchema();return promotionRows(false);
    }
    private List<Map<String,Object>> promotionRows(boolean activeOnly) {
        String condition=activeOnly?" AND enabled=1 AND starts_at<=CURRENT_TIMESTAMP AND ends_at>CURRENT_TIMESTAMP":"";
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> row:jdbc.queryForList("SELECT * FROM commerce_promotion WHERE shop_id=?"+condition+" ORDER BY starts_at,promotion_id",shop()))result.add(promotionView(row));
        return result;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> savePromotion(String id,Map<String,Object> body,long actor) {
        merchants.requireCapability(shop(),actor,CommerceCapability.CATALOG);requireSchema();
        require(body!=null,"优惠参数不能为空",400);
        if(id!=null){promotionSelection(id);require(!jdbc.queryForList("SELECT promotion_id FROM commerce_promotion WHERE promotion_id=? AND shop_id=? FOR UPDATE",id,shop()).isEmpty(),"优惠不存在",404);}
        String title=text(body.get("title"),80,"优惠名称");long discount=inputCents(body.get("discountAmount"),true,"优惠金额");
        SortedSet<Long> products=productIds(body.get("productIds"));
        for(Long product:products)require(jdbc.queryForObject("SELECT COUNT(*) FROM commerce_product_shop WHERE product_id=? AND shop_id=?",Long.class,product,shop())==1L,"优惠商品不属于当前店铺",404);
        LocalDateTime starts=date(body.get("startsAt")),ends=date(body.get("endsAt"));require(starts.isBefore(ends),"优惠结束时间必须晚于开始时间",400);
        require(body.get("enabled") instanceof Boolean,"优惠启停参数错误",400);int enabled=Boolean.TRUE.equals(body.get("enabled"))?1:0;
        if(id==null) {
            id="PR"+UUID.randomUUID().toString().replace("-","").substring(0,30);
            jdbc.update("INSERT INTO commerce_promotion(promotion_id,shop_id,title,discount_cents,product_ids_json,starts_at,ends_at,enabled) VALUES (?,?,?,?,?,?,?,?)",id,shop(),title,discount,JSON.toJSONString(products),Timestamp.valueOf(starts),Timestamp.valueOf(ends),enabled);
        } else jdbc.update("UPDATE commerce_promotion SET title=?,discount_cents=?,product_ids_json=?,starts_at=?,ends_at=?,enabled=?,updated_at=CURRENT_TIMESTAMP WHERE promotion_id=? AND shop_id=?",title,discount,JSON.toJSONString(products),Timestamp.valueOf(starts),Timestamp.valueOf(ends),enabled,id,shop());
        return promotionView(jdbc.queryForMap("SELECT * FROM commerce_promotion WHERE promotion_id=? AND shop_id=?",id,shop()));
    }

    /** Quote does not reserve stock or delivery capacity. Checkout revalidates and snapshots its own quote. */
    @Transactional(readOnly=true)
    public Map<String,Object> quote(Map<String,Object> body) {
        require(body!=null,"报价参数不能为空",400);SortedMap<Long,Long> quantities=CommerceService.normalizeItems(body.get("items"));
        merchants.requireProducts(quantities.keySet());
        String placeholders=String.join(",",Collections.nCopies(quantities.size(),"?"));
        List<Map<String,Object>> products=jdbc.queryForList("SELECT product_id,univalence,status FROM product WHERE product_id IN ("+placeholders+") ORDER BY product_id",quantities.keySet().toArray());
        require(products.size()==quantities.size(),"商品不存在",404);
        for(Map<String,Object> product:products)require("0".equals(String.valueOf(product.get("status"))),"商品已停用",409);
        return calculate(products,quantities,null,promotionSelection(body.get("promotionId"))).view();
    }

    /** Inputs are locked server product rows; activityPrice is a server activity row value. */
    public AmountQuote calculate(List<Map<String,Object>> products,SortedMap<Long,Long> quantities,BigDecimal activityPrice,String selectedPromotion) {
        selectedPromotion=promotionSelection(selectedPromotion);boolean snapshots=schemaAvailable();
        require(snapshots||selectedPromotion==null,"金额配置尚未完成迁移，无法使用优惠",503);
        SortedMap<Long,Long> originals=new TreeMap<>();long original=0;
        for(Map<String,Object> product:products) {
            long productId=n(product.get("product_id"));long price=inputCents(activityPrice==null?product.get("univalence"):activityPrice,true,"商品售价");
            long line=checked(Math.multiplyExact(price,quantities.get(productId)));original=checked(Math.addExact(original,line));originals.put(productId,line);
        }
        require(originals.size()==quantities.size(),"报价商品不完整",409);
        long shipping=0,discount=0;String title=null;SortedMap<Long,Long> eligible=new TreeMap<>();
        if(snapshots) {
            List<Long> fees=jdbc.queryForList("SELECT shipping_cents FROM commerce_pricing_policy WHERE shop_id=?",Long.class,shop());
            shipping=fees.isEmpty()?0:checked(fees.get(0));
            if(selectedPromotion!=null) {
                List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM commerce_promotion WHERE promotion_id=? AND shop_id=?",selectedPromotion,shop());require(rows.size()==1,"优惠不存在",404);
                Map<String,Object> promotion=rows.get(0);LocalDateTime now=LocalDateTime.now();
                require(n(promotion.get("enabled"))==1&&!now.isBefore(date(promotion.get("starts_at")))&&now.isBefore(date(promotion.get("ends_at"))),"优惠未生效或已停用",409);
                SortedSet<Long> applies=productIds(JSON.parseArray(String.valueOf(promotion.get("product_ids_json")),Long.class));
                long eligibleTotal=0;for(Map.Entry<Long,Long> entry:originals.entrySet())if(applies.isEmpty()||applies.contains(entry.getKey())){eligible.put(entry.getKey(),entry.getValue());eligibleTotal=checked(eligibleTotal+entry.getValue());}
                require(eligibleTotal>0,"当前商品不适用该优惠",409);discount=Math.min(checked(n(promotion.get("discount_cents"))),eligibleTotal);title=String.valueOf(promotion.get("title"));
            }
        }
        long payable=checked(original-discount+shipping);
        require(payable>0,"当前支付流程暂不支持零元订单，请调整优惠",409);
        SortedMap<Long,Long> discounts=allocate(discount,eligible),shippingShares=allocate(shipping,originals);
        SortedMap<Long,AmountLine> lines=new TreeMap<>();
        for(Map.Entry<Long,Long> entry:originals.entrySet()) {
            long product=entry.getKey(),lineDiscount=discounts.getOrDefault(product,0L),lineShipping=shippingShares.getOrDefault(product,0L);
            long linePayable=checked(entry.getValue()-lineDiscount+lineShipping);
            // Payment/fulfillment currently close an order by its money balance. Every refundable
            // unit therefore needs at least one cent; free gifts require a separate quantity lifecycle.
            require(linePayable>=quantities.get(product),"优惠后每件商品含分摊运费的实付须至少为0.01元，请调整优惠",409);
            lines.put(product,new AmountLine(product,quantities.get(product),entry.getValue(),lineDiscount,lineShipping,linePayable));
        }
        return new AmountQuote(snapshots?1:0,original,discount,shipping,payable,selectedPromotion,title,lines);
    }

    /** Largest remainder allocation, with productId as the stable tie-breaker. */
    static SortedMap<Long,Long> allocate(long total,SortedMap<Long,Long> weights) {
        checked(total);SortedMap<Long,Long> result=new TreeMap<>();if(weights.isEmpty()){require(total==0,"金额分摊权重为空",409);return result;}
        BigInteger denominator=BigInteger.ZERO;for(Long weight:weights.values()){checked(weight);denominator=denominator.add(BigInteger.valueOf(weight));}
        require(denominator.signum()>0||total==0,"金额分摊权重必须大于零",409);
        Map<Long,BigInteger> remainders=new HashMap<>();long assigned=0;
        for(Map.Entry<Long,Long> entry:weights.entrySet()) {
            BigInteger[] division=denominator.signum()==0?new BigInteger[]{BigInteger.ZERO,BigInteger.ZERO}:BigInteger.valueOf(total).multiply(BigInteger.valueOf(entry.getValue())).divideAndRemainder(denominator);
            long value=division[0].longValueExact();result.put(entry.getKey(),value);remainders.put(entry.getKey(),division[1]);assigned+=value;
        }
        List<Long> order=new ArrayList<>(weights.keySet());order.sort((a,b)->{int compared=remainders.get(b).compareTo(remainders.get(a));return compared==0?Long.compare(a,b):compared;});
        long remaining=total-assigned;for(int i=0;i<remaining;i++){long id=order.get(i);result.put(id,result.get(id)+1);}
        return result;
    }
    public void snapshot(String orderId,AmountQuote quote) {
        if(quote.version==0)return;
        jdbc.update("INSERT INTO commerce_order_amount(order_id,shop_id,snapshot_version,original_cents,discount_cents,shipping_cents,payable_cents,promotion_id,promotion_title) VALUES (?,?,?,?,?,?,?,?,?)",orderId,shop(),quote.version,quote.original,quote.discount,quote.shipping,quote.payable,quote.promotionId,quote.promotionTitle);
        for(AmountLine line:quote.lines.values())jdbc.update("INSERT INTO commerce_order_line_amount(order_id,product_id,quantity,original_cents,discount_cents,shipping_cents,payable_cents) VALUES (?,?,?,?,?,?,?)",orderId,line.productId,line.quantity,line.original,line.discount,line.shipping,line.payable);
    }
    public void requireReplayPromotion(String orderId,String selected) {
        selected=promotionSelection(selected);boolean available=schemaAvailable();require(available||selected==null,"金额配置尚未完成迁移，无法使用优惠",503);
        if(!available)return;
        List<String> stored=jdbc.queryForList("SELECT promotion_id FROM commerce_order_amount WHERE order_id=? AND shop_id=?",String.class,orderId,shop());
        require(Objects.equals(selected,stored.isEmpty()?null:stored.get(0)),"同一请求编号不能更换优惠",409);
    }

    /** Called after the normal shop/owner check. Legacy orders expose original-price amounts. */
    public void decorate(Map<String,Object> order) {
        String orderId=String.valueOf(order.get("orderId"));List<Map<String,Object>> snapshots=schemaAvailable()?jdbc.queryForList("SELECT * FROM commerce_order_amount WHERE order_id=? AND shop_id=?",orderId,shop()):Collections.emptyList();
        SortedMap<Long,Map<String,Object>> lineSnapshots=new TreeMap<>();
        if(!snapshots.isEmpty())for(Map<String,Object> line:jdbc.queryForList("SELECT * FROM commerce_order_line_amount WHERE order_id=? ORDER BY product_id",orderId))lineSnapshots.put(n(line.get("product_id")),line);
        long original=0;for(Object value:(List<?>)order.get("items")) {
            Map<String,Object> item=(Map<String,Object>)value;Map<String,Object> line=lineSnapshots.get(n(item.get("productId")));long lineOriginal=inputCents(item.get("amount"),false,"订单商品金额");original=checked(original+lineOriginal);
            putAmounts(item,line==null?breakdown(lineOriginal,0,0,lineOriginal):breakdown(n(line.get("original_cents")),n(line.get("discount_cents")),n(line.get("shipping_cents")),n(line.get("payable_cents"))));
        }
        Map<String,Object> values=snapshots.isEmpty()?breakdown(original,0,0,inputCents(order.get("totalAmount"),false,"订单实付金额")):breakdown(n(snapshots.get(0).get("original_cents")),n(snapshots.get(0).get("discount_cents")),n(snapshots.get(0).get("shipping_cents")),n(snapshots.get(0).get("payable_cents")));
        long paid=n(order.get("orderStatus"))>=1&&n(order.get("orderStatus"))<=3?inputCents(order.get("totalAmount"),false,"订单实付金额"):0,refunded=inputCents(order.get("refundedAmount"),false,"已退金额");
        BigDecimal pending=jdbc.queryForObject("SELECT COALESCE(SUM(refund_amount),0) FROM commerce_after_sales_case WHERE order_id=? AND status NOT IN('REFUNDED','REJECTED','REFUND_FAILED')",BigDecimal.class,orderId);
        values.put("paidAmount",money(paid));values.put("refundedAmount",money(refunded));values.put("refundableAmount",money(Math.max(0,paid-refunded-inputCents(pending,false,"售后占用金额"))));
        putAmounts(order,values);order.put("amountSnapshotVersion",snapshots.isEmpty()?0:(int)n(snapshots.get(0).get("snapshot_version")));
        order.put("promotionId",snapshots.isEmpty()?null:snapshots.get(0).get("promotion_id"));order.put("promotionTitle",snapshots.isEmpty()?null:snapshots.get(0).get("promotion_title"));
    }

    /** Caller must hold the order transaction. Preview and reserve use identical deterministic units. */
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public SortedMap<Long,BigDecimal> refundAmounts(String orderId,SortedMap<Long,Long> quantities) {
        return refundPlan(orderId,quantities,null,false);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public SortedMap<Long,BigDecimal> reserveRefund(String orderId,String caseId,SortedMap<Long,Long> quantities) {
        require(TransactionSynchronizationManager.isActualTransactionActive(),"退款金额占用必须处于订单事务",409);
        List<Map<String,Object>> cases=jdbc.queryForList("SELECT refund_amount FROM commerce_after_sales_case WHERE after_sales_id=? AND order_id=? AND shop_id=?",caseId,orderId,shop());require(cases.size()==1,"售后不存在",404);
        SortedMap<Long,Long> saved=new TreeMap<>();SortedMap<Long,BigDecimal> savedAmounts=new TreeMap<>();
        for(Map<String,Object> item:jdbc.queryForList("SELECT product_id,quantity,amount FROM commerce_after_sales_item WHERE after_sales_id=? ORDER BY product_id",caseId)){saved.put(n(item.get("product_id")),n(item.get("quantity")));savedAmounts.put(n(item.get("product_id")),money(inputCents(item.get("amount"),false,"售后行金额")));}
        require(saved.equals(quantities),"售后金额占用必须与已保存商品数量一致",409);
        SortedMap<Long,BigDecimal> result=refundPlan(orderId,quantities,caseId,true);long total=0;for(BigDecimal amount:result.values())total=checked(total+inputCents(amount,false,"售后金额"));
        require(savedAmounts.equals(result)&&total==inputCents(cases.get(0).get("refund_amount"),false,"售后总金额"),"售后金额与订单单位分摊不一致",409);return result;
    }
    private SortedMap<Long,BigDecimal> refundPlan(String orderId,SortedMap<Long,Long> quantities,String caseId,boolean reserve) {
        require(quantities!=null&&!quantities.isEmpty(),"退款商品不能为空",400);
        List<Map<String,Object>> orders=jdbc.queryForList("SELECT order_id FROM commerce_order WHERE order_id=? AND shop_id=? FOR UPDATE",orderId,shop());require(orders.size()==1,"订单不存在",404);
        List<Map<String,Object>> snapshot=schemaAvailable()?jdbc.queryForList("SELECT order_id FROM commerce_order_amount WHERE order_id=? AND shop_id=?",orderId,shop()):Collections.emptyList();
        SortedMap<Long,BigDecimal> result=new TreeMap<>();
        if(snapshot.isEmpty()) {
            for(Map.Entry<Long,Long> entry:quantities.entrySet()) {
                List<Map<String,Object>> lines=jdbc.queryForList("SELECT quantity,unit_price FROM commerce_order_item WHERE order_id=? AND product_id=?",orderId,entry.getKey());require(lines.size()==1&&entry.getValue()>0&&entry.getValue()<=n(lines.get(0).get("quantity")),"退款商品数量错误",409);
                result.put(entry.getKey(),money(checked(inputCents(lines.get(0).get("unit_price"),false,"原订单单价")*entry.getValue())));
            }
            return result;
        }
        if(reserve) {
            require(jdbc.queryForObject("SELECT COUNT(*) FROM commerce_after_sales_case WHERE after_sales_id=? AND order_id=? AND shop_id=?",Long.class,caseId,orderId,shop())==1L,"售后不存在",404);
            List<Map<String,Object>> previous=jdbc.queryForList("SELECT product_id,COUNT(*) AS quantity,SUM(amount_cents) AS cents FROM commerce_refund_unit_allocation WHERE after_sales_id=? GROUP BY product_id ORDER BY product_id",caseId);
            if(!previous.isEmpty()) {
                SortedMap<Long,Long> prior=new TreeMap<>();for(Map<String,Object> line:previous){long id=n(line.get("product_id"));prior.put(id,n(line.get("quantity")));result.put(id,money(n(line.get("cents"))));}
                require(prior.equals(quantities),"同一售后不能改变金额占用数量",409);return result;
            }
        }
        for(Map.Entry<Long,Long> entry:quantities.entrySet()) {
            List<Map<String,Object>> lines=jdbc.queryForList("SELECT quantity,payable_cents FROM commerce_order_line_amount WHERE order_id=? AND product_id=?",orderId,entry.getKey());require(lines.size()==1,"退款商品不存在",404);
            long count=n(lines.get(0).get("quantity")),payable=n(lines.get(0).get("payable_cents")),wanted=entry.getValue();require(wanted>0&&wanted<=count,"退款数量错误",409);
            Set<Long> occupied=new HashSet<>(jdbc.queryForList("SELECT u.unit_index FROM commerce_refund_unit_allocation u JOIN commerce_after_sales_case a ON a.after_sales_id=u.after_sales_id WHERE u.order_id=? AND u.product_id=? AND a.status NOT IN('REJECTED','REFUND_FAILED')",Long.class,orderId,entry.getKey()));
            long sum=0,selected=0;for(long unit=0;unit<count&&selected<wanted;unit++)if(!occupied.contains(unit)) {
                long amount=unitAmount(payable,count,unit);sum=checked(sum+amount);selected++;
                if(reserve)jdbc.update("INSERT INTO commerce_refund_unit_allocation(after_sales_id,order_id,product_id,unit_index,amount_cents) VALUES (?,?,?,?,?)",caseId,orderId,entry.getKey(),unit,amount);
            }
            require(selected==wanted,"退款数量已被其他售后占用",409);result.put(entry.getKey(),money(sum));
        }
        return result;
    }
    static long unitAmount(long payable,long quantity,long unitIndex) {
        require(quantity>0&&unitIndex>=0&&unitIndex<quantity,"退款单位序号错误",409);checked(payable);
        return payable/quantity+(unitIndex<payable%quantity?1:0);
    }

    public static final class AmountQuote {
        public final int version;public final long original,discount,shipping,payable;public final String promotionId,promotionTitle;public final SortedMap<Long,AmountLine> lines;
        private AmountQuote(int version,long original,long discount,long shipping,long payable,String id,String title,SortedMap<Long,AmountLine> lines) {
            this.version=version;this.original=original;this.discount=discount;this.shipping=shipping;this.payable=payable;this.promotionId=id;this.promotionTitle=title;this.lines=Collections.unmodifiableSortedMap(lines);
        }
        public Map<String,Object> view() {
            Map<String,Object> result=map("shopId",shop(),"promotionId",promotionId,"promotionTitle",promotionTitle,"amountSnapshotVersion",version);putAmounts(result,breakdown(original,discount,shipping,payable));
            List<Map<String,Object>> items=new ArrayList<>();for(AmountLine line:lines.values()){Map<String,Object> item=map("productId",line.productId,"quantity",line.quantity);putAmounts(item,breakdown(line.original,line.discount,line.shipping,line.payable));items.add(item);}result.put("items",items);return result;
        }
    }
    public static final class AmountLine {
        public final long productId,quantity,original,discount,shipping,payable;
        private AmountLine(long id,long quantity,long original,long discount,long shipping,long payable){this.productId=id;this.quantity=quantity;this.original=original;this.discount=discount;this.shipping=shipping;this.payable=payable;}
    }
    public static BigDecimal money(long cents) {return BigDecimal.valueOf(cents,2);}
    private static long checked(long value){require(value>=0&&value<=MAX_CENTS,"金额超出可用范围",400);return value;}
    private static long inputCents(Object value,boolean positive,String label) {
        try {require(value!=null&&(value instanceof Number||value instanceof String),label+"格式错误",400);long result=new BigDecimal(String.valueOf(value)).setScale(2,RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();checked(result);require(!positive||result>0,label+"必须大于零",400);return result;}
        catch(NumberFormatException|ArithmeticException error){throw new ServiceException(label+"必须为精确到分的金额",400);}
    }
    private void requireShop(){require(jdbc.queryForObject("SELECT COUNT(*) FROM commerce_shop WHERE shop_id=? AND status='ENABLED'",Long.class,shop())==1L,"店铺不存在或已停用",404);}
    private static SortedSet<Long> productIds(Object raw) {
        if(raw==null)return new TreeSet<>();require(raw instanceof List,"优惠商品列表格式错误",400);List<?> items=(List<?>)raw;require(items.size()<=100,"优惠商品不能超过100个",400);
        SortedSet<Long> result=new TreeSet<>();for(Object value:items){require(value!=null&&String.valueOf(value).matches("[1-9][0-9]{0,17}"),"优惠商品标识错误",400);result.add(Long.parseLong(String.valueOf(value)));}return result;
    }
    private static Map<String,Object> promotionView(Map<String,Object> row) {return map("promotionId",row.get("promotion_id"),"shopId",row.get("shop_id"),"title",row.get("title"),"discountAmount",money(n(row.get("discount_cents"))),"productIds",JSON.parseArray(String.valueOf(row.get("product_ids_json")),Long.class),"startsAt",date(row.get("starts_at")).toString(),"endsAt",date(row.get("ends_at")).toString(),"enabled",n(row.get("enabled"))==1);}
    private static LocalDateTime date(Object value) {try {if(value instanceof LocalDateTime)return (LocalDateTime)value;if(value instanceof Timestamp)return ((Timestamp)value).toLocalDateTime();require(value instanceof String,"优惠日期格式错误",400);return LocalDateTime.parse(((String)value).replace(' ','T'));}catch(java.time.format.DateTimeParseException error){throw new ServiceException("优惠日期格式错误",400);}}
    private static String text(Object raw,int maximum,String label){require(raw instanceof String,label+"格式错误",400);String value=((String)raw).trim();require(!value.isEmpty()&&value.length()<=maximum,label+"长度错误",400);return value;}
    private static Map<String,Object> breakdown(long original,long discount,long shipping,long payable){return map("originalAmount",money(original),"discountAmount",money(discount),"shippingAmount",money(shipping),"payableAmount",money(payable));}
    private static void putAmounts(Map<String,Object> target,Map<String,Object> values){target.putAll(values);target.put("amountBreakdown",new LinkedHashMap<>(values));}
    private static String shop(){return CommerceShopContext.id();}
    private static long n(Object value){return value==null?0:((Number)value).longValue();}
    private static Map<String,Object> map(Object...pairs){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
    private static void require(boolean condition,String message,int code){if(!condition)throw new ServiceException(message,code);}
}

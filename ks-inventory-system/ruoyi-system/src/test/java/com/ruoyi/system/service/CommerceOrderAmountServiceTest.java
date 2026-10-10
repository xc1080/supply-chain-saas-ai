package com.ruoyi.system.service;

import com.ruoyi.common.core.tenant.CommerceShopContext;
import com.ruoyi.common.exception.ServiceException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.util.StreamUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.junit.Assert.*;

/** Real SQL transactions cover authoritative checkout, exact allocations and concurrent reservations. */
public class CommerceOrderAmountServiceTest {
    private CommerceServiceTest fixture;
    private CommerceService commerce;
    private CommerceOrderAmountService amounts;
    private JdbcTemplate jdbc;
    private static final String OWNER=CommerceServiceTest.OWNER;
    @Before public void setup()throws Exception {
        fixture=new CommerceServiceTest();fixture.setup();jdbc=fixture.jdbc;commerce=fixture.service;
        amounts=new CommerceOrderAmountService(jdbc.getDataSource(),fixture.merchants);installSchema();
    }
    private void installSchema()throws Exception {
        String script=StreamUtils.copyToString(new ClassPathResource("db/commerce-order-amount.sql").getInputStream(),StandardCharsets.UTF_8).replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","");
        new ResourceDatabasePopulator(new ByteArrayResource(script.getBytes(StandardCharsets.UTF_8))).execute(jdbc.getDataSource());
    }
    @After public void clear(){CommerceShopContext.clear();}
    @Test public void jdbcLocalDateTimePromotionCanBeSerializedAndQuoted() throws Exception {
        // Connector/J returns DATETIME as LocalDateTime; H2 returns Timestamp.
        Map<String,Object> row=map("promotion_id","PR_DATE","shop_id","default","title","学习优惠",
                "discount_cents",100L,"product_ids_json","[]","enabled",1,
                "starts_at",LocalDateTime.of(2026,10,10,9,0),"ends_at",LocalDateTime.of(2026,10,11,9,0));
        java.lang.reflect.Method view=CommerceOrderAmountService.class.getDeclaredMethod("promotionView",Map.class);
        view.setAccessible(true);
        Map<String,Object> result=(Map<String,Object>)view.invoke(null,row);
        assertEquals("2026-10-10T09:00",result.get("startsAt"));
        assertEquals("2026-10-11T09:00",result.get("endsAt"));
        assertEquals(new BigDecimal("1.00"),result.get("discountAmount"));
    }
    private <T>T tx(Supplier<T> work){return fixture.transaction(work);}
    private static Map<String,Object> map(Object...pairs){return CommerceServiceTest.map(pairs);}
    private SortedMap<Long,Long> quantities(long product,long quantity){SortedMap<Long,Long> result=new TreeMap<>();result.put(product,quantity);return result;}
    private Map<String,Object> promotionBody(Object discount,List<Long> products){return map("title","学习优惠","discountAmount",discount,"productIds",products,"startsAt",LocalDateTime.now().minusMinutes(1).toString(),"endsAt",LocalDateTime.now().plusHours(1).toString(),"enabled",true);}
    private String promotion(Object discount,List<Long> products){return String.valueOf(tx(()->amounts.savePromotion(null,promotionBody(discount,products),1)).get("promotionId"));}
    private Map<String,Object> request(String key,int quantity,String promotion){Map<String,Object> body=fixture.request(key,quantity);if(promotion!=null)body.put("promotionId",promotion);return body;}
    private Map<String,Object> create(String key,int quantity,String promotion){return tx(()->commerce.create(request(key,quantity,promotion)));}
    private void fee(Object amount){tx(()->amounts.savePolicy(map("shippingFee",amount),1));}
    private Map<String,Object> line(Map<String,Object> order,int index){return (Map<String,Object>)((List<?>)order.get("items")).get(index);}
    private void rejects(int code,Runnable work){fixture.rejected(code,work);}
    private long count(String table){return fixture.count(table);}
    private void addProduct(long id,String price){
        jdbc.update("INSERT INTO product(product_id,product_code,product_name,product_specifications,measure_unit,status,univalence,cost_price,inventory_qty) VALUES (?,?,?,'Demo','件','0',?,0,10)",id,"PRODUCT-"+id,"商品"+id,new BigDecimal(price));
        jdbc.update("INSERT INTO inventory_product(inventory_id,product_id,warehouse_id,plan_quantity) VALUES (?,?,1,10)",id,id);jdbc.update("INSERT INTO commerce_product_shop VALUES (?,'default',1)",id);
    }
    private String paid(String key,int quantity,String promotion){Map<String,Object> order=create(key,quantity,promotion);String id=String.valueOf(order.get("orderId"));tx(()->commerce.pay(id,map("ownerId",OWNER,"paymentRequestId",key+"-pay","scenario","success")));return id;}
    private String rawCase(String order,String key,SortedMap<Long,Long> chosen,String status){
        SortedMap<Long,BigDecimal> money=amounts.refundAmounts(order,chosen);BigDecimal total=money.values().stream().reduce(BigDecimal.ZERO,BigDecimal::add);
        String id="AS20261010120000"+UUID.randomUUID().toString().replace("-","").substring(0,10).toUpperCase(Locale.ROOT);
        jdbc.update("INSERT INTO commerce_after_sales_case(after_sales_id,order_id,shop_id,owner_id,request_key,request_hash,kind,reason,status,original_order_status,return_required,refund_amount,created_at) VALUES (?,?,'default',?,?,?,'UNSHIPPED_REFUND','学习退款',?,1,0,?,CURRENT_TIMESTAMP)",id,order,OWNER,key,CommerceService.requestHash(chosen),status,total);
        for(Map.Entry<Long,Long> entry:chosen.entrySet()) {
            Map<String,Object> item=jdbc.queryForMap("SELECT * FROM commerce_order_item WHERE order_id=? AND product_id=?",order,entry.getKey());
            jdbc.update("INSERT INTO commerce_after_sales_item(after_sales_id,product_id,product_code,product_name,spec,quantity,unit_price,amount) VALUES (?,?,?,?,?,?,?,?)",id,entry.getKey(),item.get("product_code"),item.get("product_name"),item.get("spec"),entry.getValue(),item.get("unit_price"),money.get(entry.getKey()));
        }
        assertEquals(money,amounts.reserveRefund(order,id,chosen));return id;
    }

    @Test public void largestRemainderAndUnitCentsAreDeterministicAndNeverLoseMoney() {
        SortedMap<Long,Long> weights=new TreeMap<>();weights.put(3L,1L);weights.put(1L,1L);weights.put(2L,1L);
        assertEquals(quantities(1,1).get(1L),CommerceOrderAmountService.allocate(1,weights).get(1L));assertEquals(Long.valueOf(0),CommerceOrderAmountService.allocate(1,weights).get(2L));
        for(long total=0;total<=500;total++) {
            SortedMap<Long,Long> split=CommerceOrderAmountService.allocate(total,weights);assertEquals(total,split.values().stream().mapToLong(Long::longValue).sum());
            long assigned=0;for(int unit=0;unit<7;unit++){long value=CommerceOrderAmountService.unitAmount(total,7,unit);assertTrue(value>=0);assigned+=value;assertTrue(assigned<=total);}assertEquals(total,assigned);
        }
        weights.put(1L,99999999999999L);assertEquals(99999999999999L,CommerceOrderAmountService.allocate(99999999999999L,weights).values().stream().mapToLong(Long::longValue).sum());
    }

    @Test public void serverQuoteIgnoresClientMoneyAndNeverReservesAnything() {
        jdbc.update("UPDATE product SET univalence=0.01 WHERE product_id=1");addProduct(2,"0.01");addProduct(3,"0.01");fee("0.01");String selected=promotion("0.01",Collections.emptyList());
        Map<String,Object> body=map("items",Arrays.asList(map("productId",3,"quantity",1,"unitPrice",999),map("productId",1,"quantity",1,"unitPrice",0),map("productId",2,"quantity",1)),"promotionId",selected,"shippingFee",999,"discountAmount",999,"totalAmount",0);
        Map<String,Object> quote=amounts.quote(body);assertEquals(new BigDecimal("0.03"),quote.get("originalAmount"));assertEquals(new BigDecimal("0.01"),quote.get("discountAmount"));assertEquals(new BigDecimal("0.01"),quote.get("shippingAmount"));assertEquals(new BigDecimal("0.03"),quote.get("payableAmount"));
        assertEquals(1L,line(quote,0).get("productId"));assertEquals(new BigDecimal("0.01"),line(quote,0).get("discountAmount"));assertEquals(new BigDecimal("0.01"),line(quote,0).get("shippingAmount"));assertEquals(new BigDecimal("0.01"),line(quote,1).get("payableAmount"));assertEquals(0,count("commerce_order"));assertEquals(0,count("commerce_stock_hold"));assertEquals(0,count("commerce_delivery_hold"));
        body.put("ownerId",OWNER);body.put("requestKey","quote-checkout");body.put("shippingAddress",CommerceServiceTest.address());Map<String,Object> order=tx(()->commerce.create(body));
        assertEquals(quote.get("amountBreakdown"),subset((Map<String,Object>)order.get("amountBreakdown")));assertEquals(quote.get("items"),stripOrderLines((List<Map<String,Object>>)order.get("items")));
    }
    private Map<String,Object> subset(Map<String,Object> values){Map<String,Object> result=new LinkedHashMap<>();for(String key:Arrays.asList("originalAmount","discountAmount","shippingAmount","payableAmount"))result.put(key,values.get(key));return result;}
    private List<Map<String,Object>> stripOrderLines(List<Map<String,Object>> lines){List<Map<String,Object>> result=new ArrayList<>();for(Map<String,Object> line:lines){Map<String,Object> value=subset(line);value.put("productId",line.get("productId"));value.put("quantity",line.get("quantity"));value.put("amountBreakdown",line.get("amountBreakdown"));result.add(value);}return result;}

    @Test public void checkoutSnapshotsServerPolicyAndProviderChargesTheActualPayableAmount() {
        fee("6.01");String selected=promotion("10.03",Collections.singletonList(1L));Map<String,Object> body=request("authoritative",2,selected);body.put("totalAmount",0);body.put("shippingFee",0);body.put("discountAmount",999999);
        Map<String,Object> order=tx(()->commerce.create(body));assertEquals(new BigDecimal("258.00"),order.get("originalAmount"));assertEquals(new BigDecimal("253.98"),order.get("totalAmount"));assertEquals(new BigDecimal("253.98"),line(order,0).get("payableAmount"));
        String id=String.valueOf(order.get("orderId"));jdbc.update("UPDATE product SET univalence=999 WHERE product_id=1");fee("99.99");tx(()->amounts.savePromotion(selected,promotionBody("20.00",Collections.emptyList()),1));
        assertEquals(new BigDecimal("253.98"),commerce.detail(id,OWNER).get("totalAmount"));assertEquals(new BigDecimal("10.03"),commerce.detail(id,OWNER).get("discountAmount"));
        tx(()->commerce.pay(id,map("ownerId",OWNER,"paymentRequestId","pay","scenario","success")));assertEquals(new BigDecimal("253.98"),jdbc.queryForObject("SELECT amount FROM commerce_payment_operation WHERE order_id=? AND kind='PAYMENT'",BigDecimal.class,id));
        assertEquals(new BigDecimal("253.98"),((Map<?,?>)commerce.detail(id,OWNER).get("amountBreakdown")).get("paidAmount"));assertEquals(1,count("commerce_order_amount"));assertEquals(1,count("commerce_order_line_amount"));
    }

    @Test public void discountOnlyUsesEligibleLinesAndIsCappedAtTheirOriginalAmount() {
        addProduct(2,"1.00");fee("1.30");String selected=promotion("999.99",Collections.singletonList(2L));Map<String,Object> quote=amounts.quote(map("items",Arrays.asList(map("productId",1,"quantity",1),map("productId",2,"quantity",1)),"promotionId",selected));
        assertEquals(new BigDecimal("1.00"),quote.get("discountAmount"));assertEquals(new BigDecimal("130.30"),quote.get("payableAmount"));assertEquals(new BigDecimal("0.00"),line(quote,0).get("discountAmount"));assertEquals(new BigDecimal("0.01"),line(quote,1).get("payableAmount"));
        rejects(409,()->amounts.quote(map("items",Collections.singletonList(map("productId",1,"quantity",1)),"promotionId",selected)));
    }

    @Test public void idempotentCheckoutComparesPromotionSelectionAndKeepsExpiredPromotionSnapshot() {
        String first=promotion("0.01",Collections.emptyList()),second=promotion("0.02",Collections.emptyList());Map<String,Object> order=create("replay",1,first);String id=String.valueOf(order.get("orderId"));
        jdbc.update("UPDATE commerce_promotion SET enabled=0,ends_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE promotion_id=?",first);
        assertEquals(id,create("replay",1,first).get("orderId"));assertEquals(order.get("payableAmount"),create("replay",1,first).get("payableAmount"));rejects(409,()->create("replay",1,null));rejects(409,()->create("replay",1,second));rejects(409,()->create("new-invalid",1,first));assertEquals(1,count("commerce_order"));
    }

    @Test public void promotionPolicyPermissionsScopeValidityAndExactPrecisionAreEnforced() {
        rejects(403,()->tx(()->amounts.savePolicy(map("shippingFee","0.01"),2)));rejects(403,()->tx(()->amounts.savePromotion(null,promotionBody("0.01",Collections.emptyList()),2)));
        rejects(400,()->fee("0.001"));rejects(400,()->fee("-1.00"));rejects(400,()->promotion("0.001",Collections.emptyList()));rejects(400,()->promotion("0.00",Collections.emptyList()));
        addProduct(2,"1.00");jdbc.update("INSERT INTO commerce_shop(shop_id,shop_name) VALUES ('foreign','Other')");jdbc.update("INSERT INTO commerce_shop_member VALUES ('foreign',1,'OWNER')");jdbc.update("UPDATE commerce_product_shop SET shop_id='foreign' WHERE product_id=2");
        rejects(404,()->promotion("0.01",Collections.singletonList(2L)));String selected=promotion("0.01",Collections.emptyList());CommerceShopContext.set("foreign");
        assertTrue(amounts.promotions().isEmpty());rejects(404,()->tx(()->amounts.savePromotion(selected,promotionBody("0.02",Collections.emptyList()),1)));rejects(404,()->amounts.quote(map("items",Collections.singletonList(map("productId",2,"quantity",1)),"promotionId",selected)));CommerceShopContext.clear();
        Map<String,Object> future=promotionBody("0.01",Collections.emptyList());future.put("startsAt",LocalDateTime.now().plusDays(1).toString());future.put("endsAt",LocalDateTime.now().plusDays(2).toString());String futureId=String.valueOf(tx(()->amounts.savePromotion(null,future,1)).get("promotionId"));rejects(409,()->create("future",1,futureId));
    }

    @Test public void legacyOrdersHaveOriginalRefundBehaviorAndMissingMigrationRejectsSelectedPromotion()throws Exception {
        jdbc.execute("DROP TABLE commerce_refund_unit_allocation");jdbc.execute("DROP TABLE commerce_order_line_amount");jdbc.execute("DROP TABLE commerce_order_amount");
        Map<String,Object> old=create("legacy",3,null);assertEquals(0,old.get("amountSnapshotVersion"));rejects(503,()->create("missing-migration",1,"some-promotion"));String id=String.valueOf(old.get("orderId"));
        installSchema();fee("8.00");String selected=promotion("100.00",Collections.emptyList());assertEquals(new BigDecimal("387.00"),commerce.detail(id,OWNER).get("payableAmount"));assertEquals(Integer.valueOf(0),commerce.detail(id,OWNER).get("amountSnapshotVersion"));
        assertEquals(new BigDecimal("129.00"),tx(()->amounts.refundAmounts(id,quantities(1,1))).get(1L));rejects(409,()->create("legacy",3,selected));assertEquals(id,create("legacy",3,null).get("orderId"));assertEquals(0,count("commerce_order_amount"));
    }

    @Test public void unitReservationIsIdempotentAndRejectedOrFailedCasesMakeTheirPenniesReusable() {
        jdbc.update("UPDATE product SET univalence=0.34 WHERE product_id=1");String selected=promotion("0.02",Collections.emptyList());String order=paid("pennies",3,selected);
        String first=tx(()->rawCase(order,"one",quantities(1,1),"REQUESTED"));assertEquals(new BigDecimal("0.34"),jdbc.queryForObject("SELECT refund_amount FROM commerce_after_sales_case WHERE after_sales_id=?",BigDecimal.class,first));
        assertEquals(new BigDecimal("0.34"),tx(()->amounts.reserveRefund(order,first,quantities(1,1))).get(1L));assertEquals(1,count("commerce_refund_unit_allocation"));rejects(409,()->tx(()->amounts.reserveRefund(order,first,quantities(1,2))));
        String second=tx(()->rawCase(order,"two",quantities(1,1),"REQUESTED"));assertEquals(new BigDecimal("0.33"),jdbc.queryForObject("SELECT refund_amount FROM commerce_after_sales_case WHERE after_sales_id=?",BigDecimal.class,second));
        jdbc.update("UPDATE commerce_after_sales_case SET status='REJECTED' WHERE after_sales_id=?",first);String reused=tx(()->rawCase(order,"reused",quantities(1,1),"REQUESTED"));assertEquals(new BigDecimal("0.34"),jdbc.queryForObject("SELECT refund_amount FROM commerce_after_sales_case WHERE after_sales_id=?",BigDecimal.class,reused));
        jdbc.update("UPDATE commerce_after_sales_case SET status='REFUND_FAILED' WHERE after_sales_id=?",second);String retry=tx(()->rawCase(order,"failed-reused",quantities(1,1),"REQUESTED"));assertEquals(new BigDecimal("0.33"),jdbc.queryForObject("SELECT refund_amount FROM commerce_after_sales_case WHERE after_sales_id=?",BigDecimal.class,retry));
        String last=tx(()->rawCase(order,"last",quantities(1,1),"REFUNDED"));assertEquals(new BigDecimal("0.33"),jdbc.queryForObject("SELECT refund_amount FROM commerce_after_sales_case WHERE after_sales_id=?",BigDecimal.class,last));rejects(409,()->tx(()->amounts.refundAmounts(order,quantities(1,1))));
        assertEquals(Long.valueOf(100),jdbc.queryForObject("SELECT SUM(u.amount_cents) FROM commerce_refund_unit_allocation u JOIN commerce_after_sales_case a ON a.after_sales_id=u.after_sales_id WHERE a.status NOT IN('REJECTED','REFUND_FAILED')",Long.class));
    }

    @Test public void concurrentCheckoutRetryCreatesOneImmutableSnapshot()throws Exception {
        String selected=promotion("0.01",Collections.emptyList());ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {Callable<Map<String,Object>> task=()->{start.await();return create("concurrent",2,selected);};Future<Map<String,Object>> one=pool.submit(task),two=pool.submit(task);start.countDown();assertEquals(one.get(10,TimeUnit.SECONDS).get("orderId"),two.get(10,TimeUnit.SECONDS).get("orderId"));assertEquals(1,count("commerce_order_amount"));assertEquals(1,count("commerce_order_line_amount"));assertEquals(Long.valueOf(2),jdbc.queryForObject("SELECT SUM(quantity) FROM commerce_stock_hold",Long.class));}
        finally {pool.shutdownNow();}
    }

    @Test public void concurrentRefundReservationsCannotOccupyTheSameUnit()throws Exception {
        String order=paid("refund-race",3,null);ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {List<Future<Boolean>> results=new ArrayList<>();for(int i=0;i<2;i++){String key="race-"+i;results.add(pool.submit(()->{start.await();try {tx(()->rawCase(order,key,quantities(1,2),"REQUESTED"));return true;}catch(ServiceException failure){assertEquals(Integer.valueOf(409),failure.getCode());return false;}}));}start.countDown();int success=0;for(Future<Boolean> result:results)if(result.get(10,TimeUnit.SECONDS))success++;assertEquals(1,success);assertEquals(1,count("commerce_after_sales_case"));assertEquals(2,count("commerce_refund_unit_allocation"));assertEquals(new BigDecimal("129.00"),tx(()->amounts.refundAmounts(order,quantities(1,1))).get(1L));}
        finally {pool.shutdownNow();}
    }

    @Test public void refundHelperNeverCrossesShopBoundary() {
        String order=paid("scope",2,null);CommerceShopContext.set("foreign");rejects(404,()->tx(()->amounts.refundAmounts(order,quantities(1,1))));assertEquals(0,count("commerce_refund_unit_allocation"));
    }

    @Test public void seckillAlsoSnapshotsServerMoneyAndComparesPromotionOnReplay() {
        fee("0.01");String selected=promotion("0.02",Collections.emptyList()),other=promotion("0.03",Collections.emptyList());
        tx(()->commerce.createActivity(map("activityId","money-rush","productId",1,"capacity",2,"perOwnerLimit",1,"price","99.00","startsAt",LocalDateTime.now().minusMinutes(1).toString(),"endsAt",LocalDateTime.now().plusMinutes(10).toString())));
        Map<String,Object> body=map("ownerId",OWNER,"requestKey","rush","shippingAddress",CommerceServiceTest.address(),"promotionId",selected,"unitPrice",0,"shippingFee",0);
        Map<String,Object> order=tx(()->commerce.seckill("money-rush",body));assertEquals(new BigDecimal("98.99"),order.get("totalAmount"));assertEquals(new BigDecimal("99.00"),order.get("originalAmount"));
        jdbc.update("UPDATE commerce_promotion SET enabled=0 WHERE promotion_id=?",selected);assertEquals(order.get("orderId"),tx(()->commerce.seckill("money-rush",body)).get("orderId"));
        body.put("promotionId",other);rejects(409,()->tx(()->commerce.seckill("money-rush",body)));assertEquals(Long.valueOf(1),jdbc.queryForObject("SELECT remaining FROM commerce_activity WHERE activity_id='money-rush'",Long.class));assertEquals(1,count("commerce_order_amount"));
    }

    @Test public void quoteRevalidatesCatalogAndChangedPolicyAtCheckout() {
        Map<String,Object> quote=amounts.quote(map("items",Collections.singletonList(map("productId",1,"quantity",1))));assertEquals(new BigDecimal("129.00"),quote.get("payableAmount"));fee("0.01");
        assertEquals(new BigDecimal("129.01"),create("requote",1,null).get("payableAmount"));jdbc.update("UPDATE product SET status='1' WHERE product_id=1");rejects(409,()->amounts.quote(map("items",Collections.singletonList(map("productId",1,"quantity",1)))));
        jdbc.update("UPDATE product SET status='0' WHERE product_id=1");jdbc.update("UPDATE commerce_product_shop SET listed=0 WHERE product_id=1");rejects(404,()->amounts.quote(map("items",Collections.singletonList(map("productId",1,"quantity",1)))));
    }

    @Test public void helperUsesSnapshotQuantityWithoutAddingACartQuantityLimit() {
        String order=paid("large-legacy-line",1,null);
        jdbc.update("UPDATE commerce_order_item SET quantity=150,amount=19350 WHERE order_id=?",order);jdbc.update("UPDATE commerce_order_line_amount SET quantity=150,original_cents=1935000,payable_cents=1935000 WHERE order_id=?",order);
        jdbc.update("UPDATE commerce_order_amount SET original_cents=1935000,payable_cents=1935000 WHERE order_id=?",order);jdbc.update("UPDATE commerce_order SET total_amount=19350 WHERE order_id=?",order);
        String id=tx(()->rawCase(order,"large",quantities(1,150),"REQUESTED"));assertEquals(new BigDecimal("19350.00"),tx(()->amounts.reserveRefund(order,id,quantities(1,150))).get(1L));assertEquals(150,count("commerce_refund_unit_allocation"));
    }

    @Test public void queuedPromotionRejectionLeavesStockAndDeliveryReservationsUnchanged()throws Exception {
        String queueSchema=StreamUtils.copyToString(new ClassPathResource("db/commerce-queue.sql").getInputStream(),StandardCharsets.UTF_8).replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","");
        new ResourceDatabasePopulator(new ByteArrayResource(queueSchema.getBytes(StandardCharsets.UTF_8))).execute(jdbc.getDataSource());
        tx(()->commerce.createActivity(map("activityId","queue-money","productId",1,"capacity",2,"perOwnerLimit",1,"price","99.00","startsAt",LocalDateTime.now().minusMinutes(1).toString(),"endsAt",LocalDateTime.now().plusMinutes(10).toString())));
        Map<String,Object> before=commerce.inventory().get(0);long stockEvents=count("commerce_stock_ledger"),deliveryEvents=count("commerce_delivery_event");CommerceQueueService queue=new CommerceQueueService(jdbc.getDataSource(),commerce);
        Map<String,Object> body=map("ownerId",OWNER,"requestKey","no-promo-queue","shippingAddress",CommerceServiceTest.address(),"promotionId",promotion("0.01",Collections.emptyList()));
        rejects(400,()->queue.submit("queue-money",body));assertEquals(0,count("commerce_checkout_job"));assertEquals(0,count("commerce_checkout_outbox"));assertEquals(0,count("commerce_order"));assertEquals(stockEvents,count("commerce_stock_ledger"));assertEquals(deliveryEvents,count("commerce_delivery_event"));
        assertEquals(before.get("availableStock"),commerce.inventory().get(0).get("availableStock"));assertEquals(before.get("reservedStock"),commerce.inventory().get(0).get("reservedStock"));assertEquals(Integer.valueOf(0),jdbc.queryForObject("SELECT pending_count FROM commerce_checkout_gate WHERE gate_id=1",Integer.class));
    }

    @Test public void zeroPayableOrZeroCentUnitsAreRejectedByQuoteAndCheckoutWithoutReservations() {
        jdbc.update("UPDATE product SET univalence=0.34 WHERE product_id=1");String free=promotion("9.99",Collections.emptyList()),zeroUnit=promotion("1.00",Collections.emptyList());
        Map<String,Object> quoteBody=map("items",Collections.singletonList(map("productId",1,"quantity",3)),"promotionId",free);
        rejects(409,()->amounts.quote(quoteBody));rejects(409,()->create("free-order",3,free));quoteBody.put("promotionId",zeroUnit);rejects(409,()->amounts.quote(quoteBody));rejects(409,()->create("zero-unit",3,zeroUnit));
        assertEquals(0,count("commerce_order"));assertEquals(0,count("commerce_order_amount"));assertEquals(0,count("commerce_stock_hold"));assertEquals(0,count("commerce_stock_ledger"));assertEquals(0,count("commerce_delivery_hold"));assertEquals(0,count("commerce_delivery_event"));assertEquals(0,count("commerce_delivery_bucket"));
        String minimum=promotion("0.99",Collections.emptyList());assertEquals(new BigDecimal("0.03"),create("one-cent-each",3,minimum).get("payableAmount"));
    }

    @Test public void zeroPriceSeckillRollsBackActivityAndStockAndDeliveryTransfers() {
        tx(()->commerce.createActivity(map("activityId","free-rush","productId",1,"capacity",2,"perOwnerLimit",1,"price","99.00","startsAt",LocalDateTime.now().minusMinutes(1).toString(),"endsAt",LocalDateTime.now().plusMinutes(10).toString())));
        String free=promotion("999.99",Collections.emptyList());Map<String,Object> before=commerce.inventory().get(0);long stockEvents=count("commerce_stock_ledger"),deliveryEvents=count("commerce_delivery_event");
        rejects(409,()->tx(()->commerce.seckill("free-rush",map("ownerId",OWNER,"requestKey","free-rush-order","shippingAddress",CommerceServiceTest.address(),"promotionId",free))));
        assertEquals(0,count("commerce_order"));assertEquals(0,count("commerce_order_amount"));assertEquals(Long.valueOf(2),jdbc.queryForObject("SELECT remaining FROM commerce_activity WHERE activity_id='free-rush'",Long.class));assertEquals(Long.valueOf(0),jdbc.queryForObject("SELECT COUNT(*) FROM commerce_delivery_hold WHERE subject_type='ORDER'",Long.class));
        assertEquals(stockEvents,count("commerce_stock_ledger"));assertEquals(deliveryEvents,count("commerce_delivery_event"));assertEquals(before.get("availableStock"),commerce.inventory().get(0).get("availableStock"));assertEquals(before.get("activityStock"),commerce.inventory().get(0).get("activityStock"));
    }
}

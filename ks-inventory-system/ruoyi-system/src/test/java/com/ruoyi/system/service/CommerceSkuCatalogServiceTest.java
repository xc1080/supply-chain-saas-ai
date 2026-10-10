package com.ruoyi.system.service;

import com.ruoyi.common.core.domain.entity.Product;
import com.ruoyi.common.core.tenant.CommerceShopContext;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.mapper.ProductMapper;
import com.ruoyi.system.service.impl.ProductServiceImpl;
import com.ruoyi.system.service.ProductService;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.lang.reflect.Field;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import javax.validation.Validation;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Shared transaction fixture exercises real SQL, catalog identity, order and stock behavior. */
public class CommerceSkuCatalogServiceTest {
    private CommerceServiceTest fixture;
    private CommerceSkuCatalogService catalog;
    private JdbcTemplate jdbc;
    private CommerceService commerce;

    @Before public void setup() throws Exception {
        fixture=new CommerceServiceTest();fixture.setup();jdbc=fixture.jdbc;commerce=fixture.service;
        catalog=new CommerceSkuCatalogService(jdbc.getDataSource(),fixture.merchants);catalog.initializeSchema();
        commerce.configureSkuCatalog(catalog);fixture.warehouse.configureSkuCatalog(catalog);
        jdbc.update("INSERT INTO product(product_id,product_code,product_name,product_specifications,measure_unit,status,univalence,cost_price,inventory_qty) VALUES (2,'TEST-BLACK','卧室柔光智能灯','WiFi/12W','件','0',199,100,5)");
        jdbc.update("INSERT INTO inventory_product VALUES (2,2,1,5,NULL,NULL)");
        jdbc.update("INSERT INTO commerce_product_shop VALUES (2,'default',0)");
    }
    @After public void clear() {CommerceShopContext.clear();}
    private <T> T tx(Supplier<T> work) {return fixture.transaction(work);}
    private static void field(Object target,String name,Object value) {
        try{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}
        catch(Exception error){throw new IllegalStateException(error);}
    }
    private static Map<String,Object> map(Object... items) {return CommerceServiceTest.map(items);}
    private static Map<String,Object> property(String id,String name,String... values) {
        List<Map<String,Object>> choices=new ArrayList<>();for(String value:values)choices.add(map("propertyValueId",value,"propertyValue",value));
        return map("propertyId",id,"propertyName",name,"propertyValues",choices);
    }
    private static List<Map<String,Object>> properties() {return Arrays.asList(property("color","颜色","white","black"),property("protocol","协议","zigbee","wifi"));}
    private static Map<String,Object> sku(long product,String color,String protocol) {return map("productId",product,"attributes",map("color",color,"protocol",protocol));}
    private Map<String,Object> create(Object properties,List<?> skus) {return tx(()->catalog.create(map("name","卧室灯具","properties",properties,"skus",skus),1));}
    private String group() {return String.valueOf(create(properties(),Collections.singletonList(sku(1,"white","zigbee"))).get("spuId"));}
    private void rejected(int code,Runnable work) {fixture.rejected(code,work);}
    @SuppressWarnings("unchecked") private Map<String,Object> line(Map<String,Object> order) {return ((List<Map<String,Object>>)order.get("items")).get(0);}
    private Map<String,Object> checkout(String key,long product,Object selection) {
        Map<String,Object> item=map("productId",product,"quantity",1);if(selection!=null)item.put("propertyValueIds",selection);
        return tx(()->commerce.create(map("ownerId",CommerceServiceTest.OWNER,"requestKey",key,"shippingAddress",CommerceServiceTest.address(),"items",Collections.singletonList(item))));
    }

    @Test public void bindingExposesStructuredValuesWithoutChangingLegacySpecPriceOrInventory() {
        Map<String,Object> group=create(properties(),Arrays.asList(sku(1,"white","zigbee"),sku(2,"black","wifi")));
        assertEquals(2,((List<?>)group.get("skus")).size());
        Map<String,Object> first=commerce.inventory().get(0),second=commerce.inventory().get(1);
        assertEquals("Zigbee/9W",first.get("spec"));assertEquals(new BigDecimal("129.00"),first.get("price"));
        assertEquals(new BigDecimal("199.00"),second.get("price"));
        assertEquals("white-zigbee",((Map<?,?>)first.get("skuCatalog")).get("propertyValueIds"));
        assertEquals("black-wifi",((Map<?,?>)second.get("skuCatalog")).get("propertyValueIds"));
        assertEquals(0L,fixture.count("commerce_stock"));assertEquals(10L,fixture.book());
    }

    @Test public void rejectsDuplicateDimensionsNamesValuesInvalidIdentifiersAndUnknownChoices() {
        rejected(400,()->create(Arrays.asList(property("color","颜色","white"),property("color","重复","black")),Collections.singletonList(sku(1,"white","zigbee"))));
        rejected(400,()->create(Arrays.asList(property("color","颜色","white"),property("protocol","颜色","zigbee")),Collections.singletonList(sku(1,"white","zigbee"))));
        rejected(400,()->create(Collections.singletonList(property("color","颜色","white","white")),Collections.singletonList(map("productId",1,"attributes",map("color","white")))));
        rejected(400,()->create(Collections.singletonList(property("bad-id","颜色","white")),Collections.singletonList(map("productId",1,"attributes",map("bad-id","white")))));
        rejected(400,()->create(properties(),Collections.singletonList(sku(1,"red","wifi"))));
        assertEquals(0L,fixture.count("commerce_spu"));
    }

    @Test public void requiresExactDimensionsAndNoDuplicateCombinationOrProduct() {
        rejected(400,()->create(properties(),Collections.singletonList(map("productId",1,"attributes",map("color","white")))));
        rejected(400,()->create(properties(),Collections.singletonList(map("productId",1,"attributes",map("color","white","protocol","wifi","extra","x")))));
        rejected(400,()->create(properties(),Arrays.asList(sku(1,"white","wifi"),sku(1,"black","wifi"))));
        rejected(409,()->create(properties(),Arrays.asList(sku(1,"white","wifi"),sku(2,"white","wifi"))));
        assertEquals(0L,fixture.count("commerce_spu"));
    }

    @Test public void declaredDimensionOrderIsSelectionIdentityEvenIfValuesRepeatAcrossDimensions() {
        List<Map<String,Object>> dimensions=Arrays.asList(property("finish","表面","white","black"),property("color","颜色","white","black"));
        create(dimensions,Arrays.asList(map("productId",1,"attributes",map("finish","white","color","black")),map("productId",2,"attributes",map("finish","black","color","white"))));
        assertEquals("white-black",((Map<?,?>)commerce.inventory().get(0).get("skuCatalog")).get("propertyValueIds"));
        rejected(409,()->checkout("wrong-order",1,"black-white"));
        checkout("right-order",1,"white-black");
    }

    @Test public void appendIsIdempotentButChangingIdentityOrDuplicateCombinationIsRejected() {
        String id=group();Map<String,Object> request=map("skus",Collections.singletonList(sku(2,"black","wifi")));
        tx(()->catalog.append(id,request,1));tx(()->catalog.append(id,request,1));assertEquals(2L,fixture.count("commerce_sku"));
        rejected(409,()->tx(()->catalog.append(id,map("skus",Collections.singletonList(sku(2,"white","wifi"))),1)));
        jdbc.update("INSERT INTO product(product_id,product_code,product_name,status,inventory_qty) VALUES (3,'THIRD','Third','0',0)");
        jdbc.update("INSERT INTO commerce_product_shop VALUES (3,'default',0)");
        rejected(409,()->tx(()->catalog.append(id,map("skus",Collections.singletonList(sku(3,"black","wifi"))),1)));
        rejected(409,()->create(properties(),Collections.singletonList(sku(1,"white","zigbee"))));
        assertEquals(1L,fixture.count("commerce_spu"));assertEquals(2L,fixture.count("commerce_sku"));
    }

    @Test public void shopAndCapabilityAreCheckedInsideServiceIncludingUnlistedBinding() {
        rejected(403,()->tx(()->catalog.create(map("name","灯具","properties",properties(),"skus",Collections.singletonList(sku(1,"white","wifi"))),2)));
        assertEquals(0,catalog.list(2).size());rejected(403,()->catalog.list(999));
        jdbc.update("INSERT INTO commerce_shop VALUES ('other','Other','ENABLED',CURRENT_TIMESTAMP)");jdbc.update("INSERT INTO commerce_shop_member VALUES ('other',1,'OWNER')");
        String id=group();CommerceShopContext.set("other");
        assertEquals(0,catalog.list(1).size());
        rejected(404,()->tx(()->catalog.append(id,map("skus",Collections.singletonList(sku(2,"black","wifi"))),1)));
        rejected(404,()->create(properties(),Collections.singletonList(sku(2,"black","wifi"))));
        CommerceShopContext.clear();tx(()->catalog.append(id,map("skus",Collections.singletonList(sku(2,"black","wifi"))),1));
        assertEquals(2L,fixture.count("commerce_sku"));
    }

    @Test public void skuInventoryAndPriceRemainIndependentAndWrongSelectionReplayCannotBypassCheck() {
        create(properties(),Arrays.asList(sku(1,"white","zigbee"),sku(2,"black","wifi")));
        tx(()->{fixture.merchants.listing(1,true);fixture.merchants.listing(2,true);return null;});
        Map<String,Object> first=checkout("first",1,"white-zigbee");assertEquals(new BigDecimal("129.00"),first.get("totalAmount"));
        assertEquals(9L,commerce.inventory().get(0).get("availableStock"));assertEquals(5L,commerce.inventory().get(1).get("availableStock"));
        Map<String,Object> second=checkout("second",2,"black-wifi");assertEquals(new BigDecimal("199.00"),second.get("totalAmount"));
        assertEquals(4L,commerce.inventory().get(1).get("availableStock"));
        assertEquals(first.get("orderId"),checkout("first",1,"white-zigbee").get("orderId"));
        rejected(409,()->checkout("first",1,"black-wifi"));assertEquals(2L,fixture.count("commerce_order"));
        assertEquals("white-zigbee",((Map<?,?>)line(first).get("skuSnapshot")).get("propertyValueIds"));
    }

    @Test public void historicalOrderUsesPersistedSnapshotAndUnboundOrdersAreNeverReconstructed() {
        Map<String,Object> old=checkout("before-binding",1,null);assertNull(line(old).get("skuSnapshot"));
        String id=group();Map<String,Object> after=checkout("after-binding",1,"white-zigbee");
        Map<?,?> snapshot=(Map<?,?>)line(after).get("skuSnapshot");assertEquals("卧室灯具",snapshot.get("spuName"));
        jdbc.update("UPDATE commerce_spu SET name='Changed current catalog' WHERE spu_id=?",id);
        jdbc.update("UPDATE product SET univalence=999,product_name='Changed current name' WHERE product_id=1");
        Map<String,Object> reread=commerce.detail(String.valueOf(after.get("orderId")),CommerceServiceTest.OWNER);
        assertEquals("卧室灯具",((Map<?,?>)line(reread).get("skuSnapshot")).get("spuName"));assertEquals(new BigDecimal("129.00"),line(reread).get("unitPrice"));
        assertNull(line(commerce.detail(String.valueOf(old.get("orderId")),CommerceServiceTest.OWNER)).get("skuSnapshot"));
    }

    @Test public void unboundSelectionAcceptsOnlyDefaultOrEmptyAndNoSelectionPreservesAgentPath() {
        rejected(409,()->checkout("invalid",1,"white"));rejected(400,()->checkout("array-invalid",1,Collections.singletonList("default")));
        checkout("default",1,"default");checkout("empty",1,"");checkout("agent",1,null);
        assertEquals(0L,fixture.count("commerce_order_sku_snapshot"));
    }

    @Test public void boundCodeAndLegacySpecAreFixedButPriceAndDisplayNameCanChange() {
        group();Product changed=new Product();changed.setProductId("1");changed.setProductCode("NEW-CODE");
        rejected(409,()->tx(()->{catalog.guardProduct(changed,false);return null;}));
        changed.setProductCode("DEMO-LAMP-ZB");changed.setProductSpecifications("WiFi/12W");
        rejected(409,()->tx(()->{catalog.guardProduct(changed,false);return null;}));
        changed.setProductSpecifications("Zigbee/9W");changed.setProductName("Display rename");changed.setUnivalence("149");
        tx(()->{catalog.guardProduct(changed,false);jdbc.update("UPDATE product SET product_name=?,univalence=? WHERE product_id=1",changed.getProductName(),changed.getUnivalence());return null;});
        assertEquals(new BigDecimal("149.00"),commerce.inventory().get(0).get("price"));
    }

    @Test public void importUsesCodeForSameNamedVariantsAndFailsOnDuplicateLegacyCode() {
        ProductMapper mapper=mock(ProductMapper.class);ProductServiceImpl implementation=new ProductServiceImpl();
        field(implementation,"productMapper",mapper);field(implementation,"skuCatalog",catalog);
        field(implementation,"validator",Validation.buildDefaultValidatorFactory().getValidator());
        Product existing=new Product();existing.setProductId("2");existing.setProductCode("TEST-BLACK");
        when(mapper.selectProductById(2L)).thenReturn(existing);
        when(mapper.updateProduct(any(Product.class))).thenAnswer(call->{Product row=call.getArgument(0);return jdbc.update("UPDATE product SET univalence=? WHERE product_id=?",row.getUnivalence(),row.getProductId());});
        Product importing=new Product();importing.setProductCode("TEST-BLACK");importing.setProductName("卧室柔光智能灯");importing.setProductType("1");importing.setMeasureUnit("件");importing.setUnivalence("205");
        tx(()->implementation.importProduct(Collections.singletonList(importing),true,"test"));
        assertEquals("2",importing.getProductId());assertEquals(new BigDecimal("129.00"),jdbc.queryForObject("SELECT univalence FROM product WHERE product_id=1",BigDecimal.class));
        assertEquals(new BigDecimal("205.00"),jdbc.queryForObject("SELECT univalence FROM product WHERE product_id=2",BigDecimal.class));verify(mapper,never()).selectProductByProductName(anyString());
        jdbc.update("UPDATE product SET product_code='TEST-BLACK' WHERE product_id=1");
        rejected(409,()->catalog.importProductId("TEST-BLACK"));
    }

    @Test public void catalogCodeMutexPreventsConcurrentDuplicateLegacyInserts() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            Callable<Boolean> a=()->insertCode(3,start),b=()->insertCode(4,start);
            Future<Boolean> left=pool.submit(a),right=pool.submit(b);start.countDown();
            assertTrue(left.get(10,TimeUnit.SECONDS)^right.get(10,TimeUnit.SECONDS));
            assertEquals(Long.valueOf(1),jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE product_code='SAME-CODE'",Long.class));
        } finally {pool.shutdownNow();}
    }

    private ProductService proxiedImporter(ProductMapper mapper) {
        ProductServiceImpl target=new ProductServiceImpl();field(target,"productMapper",mapper);field(target,"skuCatalog",catalog);
        field(target,"validator",Validation.buildDefaultValidatorFactory().getValidator());
        ProxyFactory proxy=new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(jdbc.getDataSource()),new AnnotationTransactionAttributeSource()));
        return (ProductService)proxy.getProxy();
    }
    private Product importRow(String code,String price,String spec) {
        Product row=new Product();row.setProductCode(code);row.setProductName("卧室柔光智能灯");row.setProductType("1");row.setMeasureUnit("件");row.setUnivalence(price);row.setProductSpecifications(spec);return row;
    }
    @Test public void fullImportProxyPreservesConflictCodeAndRollsBackPriorSuccessfulRow() {
        group();ProductMapper mapper=mock(ProductMapper.class);
        when(mapper.selectProductById(anyLong())).thenAnswer(call->{Long id=call.getArgument(0);Product row=new Product();row.setProductId(String.valueOf(id));return row;});
        when(mapper.updateProduct(any(Product.class))).thenAnswer(call->{
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            Product row=call.getArgument(0);return jdbc.update("UPDATE product SET univalence=? WHERE product_id=?",row.getUnivalence(),row.getProductId());
        });
        ProductService importer=proxiedImporter(mapper);
        try {
            importer.importProduct(Arrays.asList(importRow("TEST-BLACK","205",null),importRow("DEMO-LAMP-ZB","149","Changed spec")),true,"test");
            fail("Expected frozen SKU import rejection");
        } catch(ServiceException error) {
            assertEquals(Integer.valueOf(409),error.getCode());assertTrue(error.getMessage().contains("共 1 条货品"));
        }
        assertEquals(new BigDecimal("199.00"),jdbc.queryForObject("SELECT univalence FROM product WHERE product_id=2",BigDecimal.class));
        assertEquals(new BigDecimal("129.00"),jdbc.queryForObject("SELECT univalence FROM product WHERE product_id=1",BigDecimal.class));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());verify(mapper,times(1)).updateProduct(any(Product.class));
    }

    @Test public void fullImportProxyCountsInvalidRowOnceAndKeepsBadInput400() {
        ProductService importer=proxiedImporter(mock(ProductMapper.class));
        Product invalid=importRow("","99",null);
        try{importer.importProduct(Collections.singletonList(invalid),true,"test");fail("Expected invalid row");}
        catch(ServiceException error){assertEquals(Integer.valueOf(400),error.getCode());assertTrue(error.getMessage().contains("共 1 条货品"));assertFalse(error.getMessage().contains("共 2 条"));}
        rejected(400,()->importer.importProduct(Collections.emptyList(),true,"test"));
        rejected(400,()->importer.importProduct(Collections.singletonList(null),true,"test"));
    }

    @Test public void fullImportProxyDoesNotRelabelUnexpectedMapperFailureAsBusinessConflict() {
        ProductMapper mapper=mock(ProductMapper.class);when(mapper.selectProductById(2L)).thenThrow(new IllegalStateException("Unexpected mapper error"));
        ProductService importer=proxiedImporter(mapper);rejected(500,()->importer.importProduct(Collections.singletonList(importRow("TEST-BLACK","205",null)),true,"test"));
    }
    private boolean insertCode(long id,CountDownLatch start) throws Exception {
        start.await();try {tx(()->{Product product=new Product();product.setProductCode("SAME-CODE");catalog.guardProduct(product,true);jdbc.update("INSERT INTO product(product_id,product_code) VALUES (?,'SAME-CODE')",id);return null;});return true;}
        catch(ServiceException error){assertEquals(Integer.valueOf(409),error.getCode());return false;}
    }

    @Test public void concurrentBindingCannotReassignSkuAndLosingGroupRollsBack() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            Callable<Boolean> bind=()->{start.await();try{group();return true;}catch(ServiceException error){assertEquals(Integer.valueOf(409),error.getCode());return false;}};
            Future<Boolean> first=pool.submit(bind),second=pool.submit(bind);start.countDown();assertTrue(first.get(10,TimeUnit.SECONDS)^second.get(10,TimeUnit.SECONDS));
            assertEquals(1L,fixture.count("commerce_spu"));assertEquals(1L,fixture.count("commerce_sku"));
        }finally{pool.shutdownNow();}
    }

    // H2 FOR UPDATE does not implement MySQL's RR current-read semantics. Exercise the actual
    // legacy writer's READ_COMMITTED contract here; native MySQL RR is a separate live acceptance.
    @Test public void importerSeesCommittedCodeAfterEarlierReadAndMutexWait() throws Exception {
        ExecutorService pool=Executors.newSingleThreadExecutor();CountDownLatch snapshot=new CountDownLatch(1),written=new CountDownLatch(1);
        try {
            Future<Long> importer=pool.submit(()->{
                TransactionTemplate repeatable=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));repeatable.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                return repeatable.execute(status->{
                    jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE product_code='RR-CODE'",Long.class);snapshot.countDown();
                    try{assertTrue(written.await(5,TimeUnit.SECONDS));}catch(InterruptedException error){throw new RuntimeException(error);}
                    return catalog.importProductId("RR-CODE");
                });
            });
            assertTrue(snapshot.await(5,TimeUnit.SECONDS));tx(()->{Product product=new Product();product.setProductCode("RR-CODE");catalog.guardProduct(product,true);jdbc.update("INSERT INTO product(product_id,product_code) VALUES (3,'RR-CODE')");return null;});
            written.countDown();assertEquals(Long.valueOf(3),importer.get(5,TimeUnit.SECONDS));
        }finally{written.countDown();pool.shutdownNow();}
    }

    @Test public void editorSeesCommittedBindingAfterEarlierReadAndProductLock() throws Exception {
        ExecutorService pool=Executors.newSingleThreadExecutor();CountDownLatch snapshot=new CountDownLatch(1),bound=new CountDownLatch(1);
        try {
            Future<?> editor=pool.submit(()->{
                TransactionTemplate repeatable=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));repeatable.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                return repeatable.execute(status->{
                    jdbc.queryForObject("SELECT COUNT(*) FROM commerce_sku WHERE product_id=1",Long.class);snapshot.countDown();
                    try{assertTrue(bound.await(5,TimeUnit.SECONDS));}catch(InterruptedException error){throw new RuntimeException(error);}
                    Product product=new Product();product.setProductId("1");product.setProductSpecifications("Changed");
                    rejected(409,()->catalog.guardProduct(product,false));return null;
                });
            });
            assertTrue(snapshot.await(5,TimeUnit.SECONDS));group();bound.countDown();editor.get(5,TimeUnit.SECONDS);
        }finally{bound.countDown();pool.shutdownNow();}
    }

    @Test public void inventoryReadsDoNotAcquireBoundProductLocks() throws Exception {
        group();ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1);
        try {
            Future<?> hold=pool.submit(()->tx(()->{jdbc.queryForObject("SELECT product_id FROM product WHERE product_id=1 FOR UPDATE",Long.class);locked.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException error){throw new RuntimeException(error);}return null;}));
            assertTrue(locked.await(5,TimeUnit.SECONDS));Future<List<Map<String,Object>>> read=pool.submit(commerce::inventory);
            assertNotNull(read.get(3,TimeUnit.SECONDS).get(0).get("skuCatalog"));release.countDown();hold.get(5,TimeUnit.SECONDS);
        }finally{release.countDown();pool.shutdownNow();}
    }
}

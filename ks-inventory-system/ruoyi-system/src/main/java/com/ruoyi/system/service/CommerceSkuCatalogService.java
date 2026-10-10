package com.ruoyi.system.service;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.core.domain.entity.Product;
import com.ruoyi.common.core.tenant.CommerceShopContext;
import com.ruoyi.common.exception.ServiceException;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** SPU is a permanent catalog grouping; product_id remains the stock and trade identity. */
@Service
@Profile({"local","commerce"})
public class CommerceSkuCatalogService {
    private final DataSource source;
    private final JdbcTemplate jdbc;
    private final CommerceMerchantService merchants;

    public CommerceSkuCatalogService(DataSource source,CommerceMerchantService merchants) {
        this.source=source;this.jdbc=new JdbcTemplate(source);this.merchants=merchants;
    }

    public void initializeSchema() {
        ResourceDatabasePopulator script=new ResourceDatabasePopulator(new ClassPathResource("db/commerce-sku-catalog.sql"));
        script.setSqlScriptEncoding("UTF-8");script.execute(source);
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> create(Map<String,Object> body,long actor) {
        capability(actor,CommerceCapability.CATALOG);
        require(body!=null,"商品分组参数不能为空",400);
        String name=text(body.get("name"),100,"商品分组名称");
        List<Map<String,Object>> properties=properties(body.get("properties"));
        SortedMap<Long,Map<String,String>> skus=skuInput(body.get("skus"),properties);
        String id=UUID.randomUUID().toString().replace("-","");
        jdbc.update("INSERT INTO commerce_spu(spu_id,shop_id,name,properties_json) VALUES (?,?,?,?)",id,shop(),name,JSON.toJSONString(properties));
        // The new SPU row is already owned by this transaction. Appends lock the same row.
        bind(id,skus);
        return view(spu(id,false));
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> append(String id,Map<String,Object> body,long actor) {
        capability(actor,CommerceCapability.CATALOG);
        Map<String,Object> group=spu(id,true);
        require(body!=null,"SKU参数不能为空",400);
        SortedMap<Long,Map<String,String>> skus=skuInput(body.get("skus"),readProperties(group));
        bind(id,skus);
        return view(group);
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> list(long actor) {
        capability(actor,CommerceCapability.READ);
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> group:jdbc.queryForList("SELECT * FROM commerce_spu WHERE shop_id=? ORDER BY created_at,spu_id",shop()))result.add(view(group));
        return result;
    }

    private void bind(String id,SortedMap<Long,Map<String,String>> skus) {
        // Catalog writers lock SPU then products in increasing order. Checkout reads immutable SPU
        // data without a row lock, so it never reverses this order.
        for(Long product:skus.keySet()) {
            require(!jdbc.queryForList("SELECT product_id FROM product WHERE product_id=? FOR UPDATE",product).isEmpty(),"商品不存在",404);
            require(jdbc.queryForObject("SELECT COUNT(*) FROM commerce_product_shop WHERE product_id=? AND shop_id=?",Long.class,product,shop())==1L,"商品不属于当前店铺",404);
        }
        for(Map.Entry<Long,Map<String,String>> entry:skus.entrySet()) {
            long product=entry.getKey();String attributes=JSON.toJSONString(entry.getValue());String signature=hash(attributes);
            List<Map<String,Object>> existing=jdbc.queryForList("SELECT * FROM commerce_sku WHERE product_id=?",product);
            if(!existing.isEmpty()) {
                Map<String,Object> old=existing.get(0);
                require(id.equals(old.get("spu_id"))&&signature.equals(old.get("signature"))&&entry.getValue().equals(JSON.parseObject(String.valueOf(old.get("attributes_json")))),"SKU属性身份已固定，不能重新绑定或修改",409);
                continue;
            }
            require(jdbc.queryForObject("SELECT COUNT(*) FROM commerce_sku WHERE spu_id=? AND signature=?",Long.class,id,signature)==0L,"该属性组合已绑定其他SKU",409);
            jdbc.update("INSERT INTO commerce_sku(product_id,spu_id,signature,attributes_json) VALUES (?,?,?,?)",product,id,signature,attributes);
        }
    }

    /** One query per inventory page, no stock/catalog writes or product row locks. */
    public void enrich(List<Map<String,Object>> rows) {
        if(rows.isEmpty())return;
        List<Long> ids=new ArrayList<>();
        for(Map<String,Object> row:rows) {row.put("skuCatalog",null);ids.add(((Number)row.get("productId")).longValue());}
        Map<Long,Map<String,Object>> found=current(ids);
        for(Map<String,Object> row:rows)row.put("skuCatalog",found.get(((Number)row.get("productId")).longValue()));
    }

    private Map<Long,Map<String,Object>> current(Collection<Long> ids) {
        Map<Long,Map<String,Object>> result=new HashMap<>();if(ids.isEmpty())return result;
        List<Object> args=new ArrayList<>(ids);args.add(shop());
        String in=String.join(",",Collections.nCopies(ids.size(),"?"));
        for(Map<String,Object> row:jdbc.queryForList("SELECT k.*,g.name,g.properties_json FROM commerce_sku k JOIN commerce_spu g ON g.spu_id=k.spu_id JOIN commerce_product_shop owned ON owned.product_id=k.product_id AND owned.shop_id=g.shop_id WHERE k.product_id IN ("+in+") AND g.shop_id=?",args.toArray())) {
            result.put(((Number)row.get("product_id")).longValue(),catalog(row));
        }
        return result;
    }

    /** Called after checkout has acquired the existing product locks. No lock on immutable SPU. */
    public void snapshot(String order,Collection<Long> products) {
        for(Map.Entry<Long,Map<String,Object>> item:current(products).entrySet())
            jdbc.update("INSERT INTO commerce_order_sku_snapshot(order_id,product_id,snapshot_json) VALUES (?,?,?)",order,item.getKey(),JSON.toJSONString(item.getValue()));
    }

    /** The browser selection is advisory; the immutable SKU binding is authoritative. */
    public void validateSelection(Object rawItems) {
        if(!(rawItems instanceof List))return; // Existing checkout validation handles the list itself.
        List<Long> selected=new ArrayList<>();
        for(Object item:(List<?>)rawItems)if(item instanceof Map&&((Map<?,?>)item).containsKey("propertyValueIds"))selected.add(positiveId(((Map<?,?>)item).get("productId")));
        if(selected.isEmpty())return;
        Map<Long,Map<String,Object>> catalogs=current(selected);
        for(Object item:(List<?>)rawItems) {
            if(!(item instanceof Map)||!((Map<?,?>)item).containsKey("propertyValueIds"))continue;
            Map<?,?> row=(Map<?,?>)item;Object value=row.get("propertyValueIds");
            require(value instanceof String&&((String)value).length()<=197,"所选SKU规格格式错误",400);String given=(String)value;
            Map<String,Object> catalog=catalogs.get(positiveId(row.get("productId")));
            if(catalog==null)require(given.isEmpty()||"default".equals(given),"商品没有该规格组合",409);
            else require(catalog.get("propertyValueIds").equals(given),"所选规格与SKU不匹配，请重新选择",409);
        }
    }

    /** Historical orders use only persisted snapshots, never today's catalog metadata. */
    public Map<Long,Map<String,Object>> orderSnapshots(String order) {
        Map<Long,Map<String,Object>> result=new HashMap<>();
        for(Map<String,Object> row:jdbc.queryForList("SELECT s.product_id,s.snapshot_json FROM commerce_order_sku_snapshot s JOIN commerce_order o ON o.order_id=s.order_id WHERE s.order_id=? AND o.shop_id=?",order,shop()))
            result.put(((Number)row.get("product_id")).longValue(),JSON.parseObject(String.valueOf(row.get("snapshot_json"))));
        return result;
    }

    /** Low-volume ERP catalog writes serialize by code, preserving legacy DB collation semantics.
     * Must run in the same transaction as the mapper mutation. No new uniqueness constraint on
     * historical product rows is introduced by migration.
     */
    public void guardProduct(Product product,boolean inserting) {
        require(product!=null,"货品参数不能为空",400);
        lockCodes();
        Map<String,Object> old=null;Long productId=null;
        if(!inserting) {
            productId=positiveId(product.getProductId());
        }
        String supplied=product.getProductCode();
        if(supplied!=null&&!supplied.trim().isEmpty()) {
            String code=text(supplied,64,"货品编号");product.setProductCode(code);
            // Current read is required even when the legacy import transaction began under RR.
            List<Long> ids=jdbc.queryForList("SELECT product_id FROM product WHERE product_code=? ORDER BY product_id FOR UPDATE",Long.class,code);
            for(Long id:ids)require(id.equals(productId),"货品编号已被其他SKU使用",409);
        } else if(inserting)throw new ServiceException("货品编号不能为空",400);
        if(!inserting) {
            List<Map<String,Object>> rows=jdbc.queryForList("SELECT product_id,product_code,product_specifications FROM product WHERE product_id=? FOR UPDATE",productId);
            require(!rows.isEmpty(),"货品不存在",404);old=rows.get(0);
        }
        if(old!=null&&!jdbc.queryForList("SELECT product_id FROM commerce_sku WHERE product_id=? FOR UPDATE",Long.class,productId).isEmpty()) {
            if(supplied!=null&&!supplied.trim().isEmpty())require(Objects.equals(product.getProductCode(),old.get("product_code")),"已绑定SKU的货品编号不能修改",409);
            String spec=product.getProductSpecifications();
            if(spec!=null&&!spec.isEmpty())require(Objects.equals(spec,old.get("product_specifications")),"已绑定SKU的规格不能修改，请新增SKU",409);
        }
    }

    /** Import identity is SKU code, not the shared SPU/product display name. */
    @Transactional
    public Long importProductId(String value) {
        String code=text(value,64,"货品编号");
        lockCodes();
        List<Long> ids=jdbc.queryForList("SELECT product_id FROM product WHERE product_code=? ORDER BY product_id FOR UPDATE",Long.class,code);
        require(ids.size()<=1,"旧数据存在重复货品编号，请先处理，不能自动覆盖",409);
        return ids.isEmpty()?null:ids.get(0);
    }
    private void lockCodes() {
        jdbc.update("INSERT INTO commerce_catalog_mutex(mutex_key) VALUES ('product_code') ON DUPLICATE KEY UPDATE mutex_key=VALUES(mutex_key)");
        jdbc.queryForObject("SELECT mutex_key FROM commerce_catalog_mutex WHERE mutex_key='product_code' FOR UPDATE",String.class);
    }

    private Map<String,Object> spu(String id,boolean lock) {
        require(id!=null&&id.matches("[a-f0-9]{32}"),"商品分组不存在",404);
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM commerce_spu WHERE spu_id=? AND shop_id=?"+(lock?" FOR UPDATE":""),id,shop());
        require(!rows.isEmpty(),"商品分组不存在",404);return rows.get(0);
    }

    private Map<String,Object> view(Map<String,Object> group) {
        List<Map<String,Object>> skus=new ArrayList<>();
        for(Map<String,Object> row:jdbc.queryForList("SELECT product_id,signature,attributes_json FROM commerce_sku WHERE spu_id=? ORDER BY product_id",group.get("spu_id"))) {
            Map<String,String> attributes=readAttributes(row);
            skus.add(map("productId",row.get("product_id"),"attributes",attributes,"propertyValueIds",valueIds(readProperties(group),attributes),"skuSignature",row.get("signature")));
        }
        return map("spuId",group.get("spu_id"),"name",group.get("name"),"properties",readProperties(group),"skus",skus);
    }

    private Map<String,Object> catalog(Map<String,Object> row) {
        Map<String,String> attributes=readAttributes(row);
        return map("spuId",row.get("spu_id"),"spuName",row.get("name"),"properties",readProperties(row),"attributes",attributes,"propertyValueIds",valueIds(readProperties(row),attributes),"skuSignature",row.get("signature"));
    }

    private static String valueIds(List<Map<String,Object>> properties,Map<String,String> attributes) {
        List<String> values=new ArrayList<>();for(Map<String,Object> property:properties)values.add(attributes.get(String.valueOf(property.get("propertyId"))));return String.join("-",values);
    }

    @SuppressWarnings("unchecked") private static List<Map<String,Object>> readProperties(Map<String,Object> row) { return (List<Map<String,Object>>)(List<?>)JSON.parseArray(String.valueOf(row.get("properties_json"))); }
    private static Map<String,String> readAttributes(Map<String,Object> row) {
        Map<String,String> result=new TreeMap<>();JSON.parseObject(String.valueOf(row.get("attributes_json"))).forEach((k,v)->result.put(k,String.valueOf(v)));return result;
    }

    private static List<Map<String,Object>> properties(Object value) {
        require(value instanceof List,"商品规格维度格式错误",400);
        List<?> rows=(List<?>)value;require(rows.size()>=1&&rows.size()<=6,"规格维度需为1至6个",400);
        Map<String,Map<String,Object>> result=new LinkedHashMap<>();Set<String> names=new HashSet<>();
        for(Object item:rows) {
            require(item instanceof Map,"规格维度格式错误",400);Map<?,?> row=(Map<?,?>)item;
            String id=identifier(row.get("propertyId")),name=text(row.get("propertyName"),40,"规格名称");
            require(!result.containsKey(id)&&names.add(name),"规格维度或名称重复",400);
            require(row.get("propertyValues") instanceof List,"规格值格式错误",400);List<?> values=(List<?>)row.get("propertyValues");
            require(values.size()>=1&&values.size()<=30,"每个维度需为1至30个值",400);
            Map<String,Map<String,Object>> normalized=new LinkedHashMap<>();Set<String> labels=new HashSet<>();
            for(Object raw:values) {
                require(raw instanceof Map,"规格值格式错误",400);Map<?,?> choice=(Map<?,?>)raw;
                String valueId=identifier(choice.get("propertyValueId")),label=text(choice.get("propertyValue"),40,"规格值");
                require(!normalized.containsKey(valueId)&&labels.add(label),"规格值或名称重复",400);
                normalized.put(valueId,map("propertyValueId",valueId,"propertyValue",label));
            }
            result.put(id,map("propertyId",id,"propertyName",name,"propertyValues",new ArrayList<>(normalized.values())));
        }
        return new ArrayList<>(result.values());
    }

    private static SortedMap<Long,Map<String,String>> skuInput(Object value,List<Map<String,Object>> properties) {
        require(value instanceof List,"SKU列表格式错误",400);List<?> rows=(List<?>)value;
        require(rows.size()>=1&&rows.size()<=100,"每次需绑定1至100个SKU",400);
        Map<String,Set<String>> choices=new TreeMap<>();
        for(Map<String,Object> property:properties) {
            Set<String> values=new HashSet<>();for(Object item:(List<?>)property.get("propertyValues"))values.add(String.valueOf(((Map<?,?>)item).get("propertyValueId")));
            choices.put(String.valueOf(property.get("propertyId")),values);
        }
        SortedMap<Long,Map<String,String>> result=new TreeMap<>();Set<String> combinations=new HashSet<>();
        for(Object item:rows) {
            require(item instanceof Map,"SKU格式错误",400);Map<?,?> row=(Map<?,?>)item;long id=positiveId(row.get("productId"));
            require(!result.containsKey(id),"同一SKU不能重复提交",400);
            require(row.get("attributes") instanceof Map,"SKU属性格式错误",400);Map<?,?> attributes=(Map<?,?>)row.get("attributes");
            require(attributes.keySet().equals(choices.keySet()),"SKU必须完整选择相同的规格维度",400);
            Map<String,String> normalized=new TreeMap<>();
            for(String dimension:choices.keySet()) {
                String choice=identifier(attributes.get(dimension));require(choices.get(dimension).contains(choice),"SKU规格值不属于该维度",400);normalized.put(dimension,choice);
            }
            require(combinations.add(hash(JSON.toJSONString(normalized))),"重复的SKU属性组合",409);result.put(id,normalized);
        }
        return result;
    }

    private void capability(long actor,CommerceCapability capability) {require(actor>0,"操作人无效",403);merchants.requireCapability(shop(),actor,capability);}
    private static String shop() {return CommerceShopContext.id();}
    private static String identifier(Object value) {require(value instanceof String&&((String)value).matches("[a-z0-9_]{1,32}"),"规格标识仅支持小写字母、数字和下划线，长度1至32",400);return (String)value;}
    private static long positiveId(Object value) {require(value!=null&&String.valueOf(value).matches("[1-9][0-9]{0,17}"),"SKU编号无效",400);return Long.parseLong(String.valueOf(value));}
    private static String text(Object value,int length,String label) {require(value instanceof String&&!((String)value).trim().isEmpty()&&((String)value).trim().length()<=length,label+"格式错误",400);return ((String)value).trim();}
    private static String hash(String value) {
        try {byte[] digest=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:digest)out.append(String.format("%02x",b));return out.toString();}
        catch(Exception error){throw new IllegalStateException("SKU signature failed",error);}
    }
    private static Map<String,Object> map(Object... values) {Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]);return result;}
    private static void require(boolean valid,String message,int code) {if(!valid)throw new ServiceException(message,code);}
}

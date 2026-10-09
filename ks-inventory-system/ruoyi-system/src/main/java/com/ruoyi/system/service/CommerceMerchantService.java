package com.ruoyi.system.service;

import com.ruoyi.common.core.tenant.CommerceShopContext;
import com.ruoyi.common.core.tenant.TenantContext;
import com.ruoyi.common.exception.ServiceException;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import javax.sql.DataSource;
import java.util.*;

/** A tenant owns stores; an operator may only act on stores with explicit membership. */
@Service
@Profile({"local","commerce"})
public class CommerceMerchantService {
    private final DataSource source;
    private final JdbcTemplate jdbc;
    private final CommerceInventoryService stock;
    private boolean bootstrapDemo;
    @Autowired public void configureBootstrap(Environment environment){bootstrapDemo=Arrays.asList(environment.getActiveProfiles()).contains("local");}
    public CommerceMerchantService(DataSource source) { this(source,new CommerceInventoryService(source)); }
    @Autowired
    public CommerceMerchantService(DataSource source,CommerceInventoryService stock) { this.source = source; this.jdbc = new JdbcTemplate(source); this.stock=stock; }

    public void initializeSchema() {
        ResourceDatabasePopulator script = new ResourceDatabasePopulator(new ClassPathResource("db/commerce-merchant.sql"));
        script.setSqlScriptEncoding("UTF-8"); script.execute(source);
        if(!bootstrapDemo)return;
        jdbc.update("INSERT INTO commerce_shop(shop_id,shop_name) VALUES ('default',?) ON DUPLICATE KEY UPDATE shop_id=VALUES(shop_id)", "studio".equals(TenantContext.id()) ? "Studio 智能家居" : "智选生活旗舰店");
        jdbc.update("INSERT INTO commerce_shop_member VALUES ('default',?,'OWNER') ON DUPLICATE KEY UPDATE user_id=VALUES(user_id)", "studio".equals(TenantContext.id()) ? 101L : 1L);
        // Only the originally published catalog is migrated, never the tenant's private ERP catalog.
        jdbc.update("INSERT INTO commerce_product_shop(product_id,shop_id,listed) SELECT product_id,'default',1 FROM product WHERE product_code IN ('DEMO-LAMP-ZB','DEMO-LAMP-WIFI','DEMO-LAMP-PRO','DEMO-SENSOR-DOOR','DEMO-SENSOR-MOTION','DEMO-GATEWAY-ZB','DEMO-LOCK-WIFI','DEMO-SWITCH-ZB') ON DUPLICATE KEY UPDATE product_id=VALUES(product_id)");
    }

    public List<Map<String,Object>> shops(long user) {
        return jdbc.queryForList("SELECT s.shop_id AS shopId,s.shop_name AS shopName,s.status,m.member_role AS memberRole FROM commerce_shop s JOIN commerce_shop_member m ON m.shop_id=s.shop_id WHERE m.user_id=? ORDER BY s.created_at,s.shop_id", user);
    }

    public Map<String,Object> requireShop(String id, long user, boolean write) {
        validId(id);
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT s.*,m.member_role FROM commerce_shop s JOIN commerce_shop_member m ON m.shop_id=s.shop_id WHERE s.shop_id=? AND m.user_id=? AND s.status='ENABLED'", id, user);
        require(!rows.isEmpty(), "无权访问该店铺", 403);
        if (write) require(CommerceAccessPolicy.allows(String.valueOf(rows.get(0).get("member_role")),CommerceCapability.CATALOG), "当前成员没有通用写权限，请校验具体业务权限", 403);
        return rows.get(0);
    }

    public Map<String,Object> requireCapability(String shopId,long user,CommerceCapability capability) {
        Map<String,Object> shop=requireShop(shopId,user,false);
        require(CommerceAccessPolicy.allows(String.valueOf(shop.get("member_role")),capability),"当前店铺成员无权执行此业务操作",403);
        return shop;
    }

    public Map<String,Object> requireCustomerService(String shopId,long user) {
        Map<String,Object> shop=requireShop(shopId,user,false);
        require("CUSTOMER_SERVICE".equals(shop.get("member_role")),"客户服务账号没有当前店铺授权",403);
        return shop;
    }

    @Transactional
    public Map<String,Object> create(String name, long user) {
        require(name != null && !name.trim().isEmpty() && name.trim().length() <= 80, "店铺名称需为1至80个字符", 400);
        String id = "shop_" + UUID.randomUUID().toString().replace("-", "").substring(0,20);
        jdbc.update("INSERT INTO commerce_shop(shop_id,shop_name) VALUES (?,?)", id, name.trim());
        jdbc.update("INSERT INTO commerce_shop_member VALUES (?,?,'OWNER')", id, user);
        Map<String,Object> result = new LinkedHashMap<>(); result.put("shopId",id); result.put("shopName",name.trim()); return result;
    }

    @Transactional
    public void setMember(String id, long actor, long user, String role) {
        requireCapability(id, actor, CommerceCapability.SHOP_MEMBERS);
        require(user != actor && Arrays.asList("OPERATOR","VIEWER","CATALOG","FULFILMENT","FINANCE_REVIEW","FINANCE_EXECUTE","SUPPLY_PLANNER","SUPPLY_REVIEWER","WAREHOUSE").contains(role), "成员或角色无效", 400);
        require(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE user_id=? AND del_flag='0' AND status='0'", Long.class, user) == 1L, "成员账号不存在或已停用", 404);
        jdbc.update("INSERT INTO commerce_shop_member VALUES (?,?,?) ON DUPLICATE KEY UPDATE member_role=VALUES(member_role)", id,user,role);
    }

    @Transactional
    public void listing(long product, boolean listed) {
        String shop = CommerceShopContext.id();
        // Existence is checked under the same product lock used by posting and hard deletion.
        require(!jdbc.queryForList("SELECT product_id FROM product WHERE product_id=? FOR UPDATE",product).isEmpty(),"商品不存在",404);
        // A SKU belongs to one shop. It cannot silently share another shop's stock pool.
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM commerce_product_shop WHERE product_id=?",product);
        if (rows.isEmpty()) jdbc.update("INSERT INTO commerce_product_shop VALUES (?,?,?)",product,shop,listed?1:0);
        else {
            require(shop.equals(rows.get(0).get("shop_id")), "商品已归属其他店铺",409);
            jdbc.update("UPDATE commerce_product_shop SET listed=? WHERE product_id=? AND shop_id=?",listed?1:0,product,shop);
        }
        // Publishing initializes the snapshot in a write transaction; browsing never does.
        if(listed)stock.ensureStock(product);
    }

    @Transactional
    public void listing(long product, boolean listed,long actor) {
        requireCapability(CommerceShopContext.id(),actor,CommerceCapability.CATALOG);
        listing(product,listed);
    }

    public void requireProducts(Collection<Long> products) {
        for (long product:products) require(jdbc.queryForObject("SELECT COUNT(*) FROM commerce_product_shop p JOIN commerce_shop s ON s.shop_id=p.shop_id WHERE p.product_id=? AND p.shop_id=? AND p.listed=1 AND s.status='ENABLED'",Long.class,product,CommerceShopContext.id()) == 1L,"商品未在当前店铺上架",404);
    }
    private static void validId(String id) { require(id!=null && id.matches("[A-Za-z0-9_-]{1,32}"), "店铺标识无效",400); }
    private static void require(boolean ok,String message,int code) { if(!ok)throw new ServiceException(message,code); }
}

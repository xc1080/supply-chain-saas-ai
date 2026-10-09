-- ============================================================
-- 10,000条商品测试数据生成脚本（简化版）
-- 作者: AI Assistant
-- 日期: 2026-06-03
-- 使用方法: mysql -u用户名 -p密码 数据库名 < generate_products.sql
-- ============================================================

-- 禁用外键检查
SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================
-- 第一部分：清空现有数据（可选，取消注释以启用）
-- ============================================================
-- TRUNCATE TABLE product_sku;
-- TRUNCATE TABLE product_property_value;
-- TRUNCATE TABLE product_info;

-- ============================================================
-- 第二部分：创建临时表存储分类信息
-- ============================================================
DROP TEMPORARY TABLE IF EXISTS temp_categories;
CREATE TEMPORARY TABLE temp_categories (
    seq INT AUTO_INCREMENT PRIMARY KEY,
    category_id VARCHAR(10),
    p_category_id VARCHAR(10)
);

-- 插入所有二级分类
INSERT INTO temp_categories (category_id, p_category_id)
SELECT category_id, p_category_id FROM sys_category
WHERE p_category_id IS NOT NULL 
  AND p_category_id != '0' 
  AND p_category_id != '' 
  AND category_id != p_category_id;

-- ============================================================
-- 第三部分：创建属性值候选表
-- ============================================================
DROP TEMPORARY TABLE IF EXISTS temp_property_options;
CREATE TEMPORARY TABLE temp_property_options (
    property_type VARCHAR(50),
    options TEXT
);

-- 插入各种属性类型的候选值
INSERT INTO temp_property_options VALUES
('颜色', '白色,黑色,银色,深空灰,金色,玫瑰金,蓝色,红色,绿色,紫色'),
('颜色分类', '白色,黑色,银色,深空灰,金色,玫瑰金,蓝色,红色,绿色,紫色'),
('尺码', 'S,M,L,XL,XXL'),
('存储容量', '128GB,256GB,512GB,1TB'),
('容量', '1L,2L,5L,10L'),
('型号', '基础版,Pro,Max,青春版'),
('口味', '原味,草莓,巧克力,香草'),
('净含量', '100g,200g,500g,1kg'),
('版本', 'v1.0,v2.0,标准版,高级版'),
('尺寸', '小号,中号,大号,定制'),
('款式', '简约,复古,现代,欧式'),
('功率', '500W,1000W,1500W,2000W'),
('其他', '标准型,升级型,优选型');

DELIMITER //

-- ============================================================
-- 第四部分：创建生成单个商品及其属性的存储过程
-- ============================================================
DROP PROCEDURE IF EXISTS generate_single_product//

CREATE PROCEDURE generate_single_product(
    IN p_product_id VARCHAR(20),
    IN p_category_id VARCHAR(10),
    IN p_p_category_id VARCHAR(10),
    IN p_status TINYINT,
    IN p_commend_type TINYINT,
    IN p_create_time DATETIME,
    IN p_total_sale INT
)
BEGIN
    DECLARE v_prop_count INT DEFAULT 0;
    DECLARE v_prop_idx INT DEFAULT 0;
    DECLARE v_prop_id VARCHAR(10);
    DECLARE v_prop_name VARCHAR(30);
    DECLARE v_prop_sort INT;
    DECLARE v_cover_type TINYINT;
    DECLARE v_val_count INT DEFAULT 0;
    DECLARE v_val_idx INT DEFAULT 0;
    DECLARE v_property_value_id VARCHAR(20);
    DECLARE v_options TEXT;
    DECLARE v_selected_option VARCHAR(50);
    DECLARE v_sku_count INT DEFAULT 0;
    DECLARE v_sku_idx INT DEFAULT 0;
    DECLARE v_pv_ids TEXT;
    DECLARE v_hash_input VARCHAR(500);
    DECLARE v_price DECIMAL(10,2);
    DECLARE v_stock INT;
    DECLARE v_min_price DECIMAL(10,2);
    DECLARE v_max_price DECIMAL(10,2);
    DECLARE v_pv_hash VARCHAR(32);
    DECLARE v_option_count INT;
    DECLARE v_rand_idx INT;
    
    -- 插入商品主表
    INSERT INTO product_info (
        product_id, product_name, product_desc, cover, create_time,
        category_id, p_category_id, status, min_price, max_price,
        total_sale, commend_type
    ) VALUES (
        p_product_id,
        CONCAT('商品-', p_category_id, '-', SUBSTRING(p_product_id, 2)),
        CONCAT('这是商品 ', p_product_id, ' 的详细描述，包含商品特点、规格参数等信息。'),
        'https://example.com/cover.png',
        p_create_time,
        p_category_id,
        p_p_category_id,
        p_status,
        0, 0,
        p_total_sale,
        p_commend_type
    );
    
    -- 获取该分类下的属性数量
    SELECT COUNT(*) INTO v_prop_count 
    FROM sys_product_property 
    WHERE category_id = p_category_id;
    
    -- 如果没有定义属性，生成一个默认SKU
    IF v_prop_count = 0 THEN
        INSERT INTO product_sku (
            product_id, property_value_id_hash, property_value_ids,
            price, stock, sort
        ) VALUES (
            p_product_id,
            'd41d8cd98f00b204e9800998ecf8427e',
            '',
            ROUND(10 + RAND() * 1990, 2),
            FLOOR(RAND() * 301),
            0
        );
        
        UPDATE product_info 
        SET min_price = (SELECT price FROM product_sku WHERE product_id = p_product_id LIMIT 1),
            max_price = (SELECT price FROM product_sku WHERE product_id = p_product_id LIMIT 1)
        WHERE product_id = p_product_id;
    ELSE
        WHILE v_prop_idx < v_prop_count DO
            SELECT property_id, property_name, property_sort, cover_type INTO 
                v_prop_id, v_prop_name, v_prop_sort, v_cover_type
            FROM sys_product_property 
            WHERE category_id = p_category_id
            LIMIT 1 OFFSET v_prop_idx;
            
            SELECT options INTO v_options
            FROM temp_property_options
            WHERE property_type = v_prop_name
            LIMIT 1;
            
            IF v_options IS NULL THEN
                SELECT options INTO v_options FROM temp_property_options WHERE property_type = '其他' LIMIT 1;
            END IF;
            
            SET v_val_count = 2 + FLOOR(RAND() * 3);
            SET v_val_idx = 0;
            SET v_option_count = LENGTH(v_options) - LENGTH(REPLACE(v_options, ',', '')) + 1;
            
            WHILE v_val_idx < v_val_count DO
                SET v_property_value_id = CONCAT('PV', LPAD(FLOOR(RAND() * 100000000000000), 14, '0'));
                SET v_rand_idx = 1 + FLOOR(RAND() * v_option_count);
                SET v_selected_option = SUBSTRING_INDEX(SUBSTRING_INDEX(v_options, ',', v_rand_idx), ',', -1);
                
                INSERT INTO product_property_value (
                    product_id, property_id, property_name, property_sort,
                    cover_type, property_value_id, property_cover, 
                    property_value, property_remark, sort
                ) VALUES (
                    p_product_id, v_prop_id, v_prop_name, v_prop_sort,
                    v_cover_type, v_property_value_id,
                    IF(v_cover_type = 1, 'https://example.com/cover.png', NULL),
                    v_selected_option, NULL, v_val_idx
                );
                
                SET v_val_idx = v_val_idx + 1;
            END WHILE;
            
            SET v_prop_idx = v_prop_idx + 1;
        END WHILE;
        
        SET v_sku_count = 2 + FLOOR(RAND() * 4);
        SET v_sku_idx = 0;
        SET v_min_price = 999999;
        SET v_max_price = 0;
        
        WHILE v_sku_idx < v_sku_count DO
            SELECT GROUP_CONCAT(property_value_id ORDER BY property_id) INTO v_pv_ids
            FROM (
                SELECT property_value_id, property_id
                FROM product_property_value
                WHERE product_id = p_product_id
                GROUP BY property_id, property_value_id
                ORDER BY property_id
            ) AS t;
            
            SET v_hash_input = COALESCE(v_pv_ids, '');
            SET v_pv_hash = MD5(v_hash_input);
            
            IF NOT EXISTS (SELECT 1 FROM product_sku WHERE property_value_id_hash = v_pv_hash AND product_id = p_product_id) THEN
                SET v_price = ROUND(10 + RAND() * 1990, 2);
                SET v_stock = FLOOR(RAND() * 301);
                
                INSERT INTO product_sku (
                    product_id, property_value_id_hash, property_value_ids,
                    price, stock, sort
                ) VALUES (
                    p_product_id, v_pv_hash, COALESCE(v_pv_ids, ''),
                    v_price, v_stock, v_sku_idx
                );
                
                IF v_price < v_min_price THEN SET v_min_price = v_price; END IF;
                IF v_price > v_max_price THEN SET v_max_price = v_price; END IF;
            END IF;
            
            SET v_sku_idx = v_sku_idx + 1;
        END WHILE;
        
        UPDATE product_info 
        SET min_price = v_min_price, max_price = v_max_price
        WHERE product_id = p_product_id;
    END IF;
END//

DELIMITER ;

-- ============================================================
-- 第五部分：批量插入商品数据（使用简单的INSERT语句）
-- 生成10000条商品记录
-- ============================================================

-- 创建辅助函数获取随机分类
DROP FUNCTION IF EXISTS get_random_category//

CREATE FUNCTION get_random_category()
RETURNS VARCHAR(10)
DETERMINISTIC
BEGIN
    DECLARE cat_id VARCHAR(10);
    DECLARE cat_count INT;
    
    SELECT COUNT(*) INTO cat_count FROM temp_categories;
    SELECT category_id INTO cat_id FROM temp_categories LIMIT 1 OFFSET FLOOR(RAND() * cat_count);
    
    RETURN cat_id;
END//

DELIMITER ;

-- ============================================================
-- 执行生成：调用存储过程生成10000条商品
-- ============================================================
-- 注意：由于MySQL存储过程调用有性能限制，建议分批执行
-- 以下脚本使用简单的INSERT SELECT方式批量生成商品数据

-- 生成商品数据（每批1000条，执行10次）
INSERT INTO product_info (
    product_id, product_name, product_desc, cover, create_time,
    category_id, p_category_id, status, min_price, max_price,
    total_sale, commend_type
)
SELECT 
    CONCAT('P', LPAD(seq_num, 14, '0')) as product_id,
    CONCAT('商品-', category_id, '-', LPAD(seq_num, 6, '0')) as product_name,
    CONCAT('这是商品 P', LPAD(seq_num, 14, '0'), ' 的详细描述，包含商品特点、规格参数等信息。') as product_desc,
    'https://example.com/cover.png' as cover,
    DATE_SUB(NOW(), INTERVAL FLOOR(RAND() * 365) DAY) as create_time,
    category_id,
    p_category_id,
    CASE 
        WHEN RAND() < 0.05 THEN -1  -- 5%删除
        WHEN RAND() < 0.35 THEN 0   -- 30%下架
        ELSE 1                      -- 65%上架
    END as status,
    0 as min_price,
    0 as max_price,
    FLOOR(RAND() * 3001) as total_sale,
    CASE WHEN RAND() < 0.1 THEN 1 ELSE 0 END as commend_type
FROM (
    SELECT 
        @row := @row + 1 as seq_num,
        category_id,
        p_category_id
    FROM 
        temp_categories c,
        (SELECT @row := 0) r
    CROSS JOIN (
        SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION
        SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9 UNION SELECT 10 UNION
        SELECT 11 UNION SELECT 12 UNION SELECT 13 UNION SELECT 14 UNION SELECT 15 UNION
        SELECT 16 UNION SELECT 17 UNION SELECT 18 UNION SELECT 19 UNION SELECT 20 UNION
        SELECT 21 UNION SELECT 22 UNION SELECT 23 UNION SELECT 24 UNION SELECT 25 UNION
        SELECT 26 UNION SELECT 27 UNION SELECT 28 UNION SELECT 29 UNION SELECT 30 UNION
        SELECT 31 UNION SELECT 32 UNION SELECT 33 UNION SELECT 34 UNION SELECT 35 UNION
        SELECT 36 UNION SELECT 37 UNION SELECT 38 UNION SELECT 39 UNION SELECT 40 UNION
        SELECT 41 UNION SELECT 42 UNION SELECT 43 UNION SELECT 44 UNION SELECT 45 UNION
        SELECT 46 UNION SELECT 47 UNION SELECT 48 UNION SELECT 49 UNION SELECT 50 UNION
        SELECT 51 UNION SELECT 52 UNION SELECT 53 UNION SELECT 54 UNION SELECT 55 UNION
        SELECT 56 UNION SELECT 57 UNION SELECT 58 UNION SELECT 59 UNION SELECT 60 UNION
        SELECT 61 UNION SELECT 62 UNION SELECT 63 UNION SELECT 64 UNION SELECT 65 UNION
        SELECT 66 UNION SELECT 67 UNION SELECT 68 UNION SELECT 69 UNION SELECT 70 UNION
        SELECT 71 UNION SELECT 72 UNION SELECT 73 UNION SELECT 74 UNION SELECT 75 UNION
        SELECT 76 UNION SELECT 77 UNION SELECT 78 UNION SELECT 79 UNION SELECT 80 UNION
        SELECT 81 UNION SELECT 82 UNION SELECT 83 UNION SELECT 84 UNION SELECT 85 UNION
        SELECT 86 UNION SELECT 87 UNION SELECT 88 UNION SELECT 89 UNION SELECT 90 UNION
        SELECT 91 UNION SELECT 92 UNION SELECT 93 UNION SELECT 94 UNION SELECT 95 UNION
        SELECT 96 UNION SELECT 97 UNION SELECT 98 UNION SELECT 99 UNION SELECT 100
    ) numbers
    WHERE @row < 10000
) data;

-- ============================================================
-- 为没有属性定义的商品生成默认SKU
-- ============================================================
INSERT INTO product_sku (product_id, property_value_id_hash, property_value_ids, price, stock, sort)
SELECT 
    product_id,
    'd41d8cd98f00b204e9800998ecf8427e',
    '',
    ROUND(10 + RAND() * 1990, 2),
    FLOOR(RAND() * 301),
    0
FROM product_info p
WHERE NOT EXISTS (
    SELECT 1 FROM product_property_value pv 
    WHERE pv.product_id = p.product_id
);

-- ============================================================
-- 为有属性的商品生成SKU（简化版本，每个商品2-3个SKU）
-- ============================================================
INSERT INTO product_sku (product_id, property_value_id_hash, property_value_ids, price, stock, sort)
SELECT 
    p.product_id,
    MD5(GROUP_CONCAT(ppv.property_value_id ORDER BY ppv.property_id)) as hash,
    GROUP_CONCAT(ppv.property_value_id ORDER BY ppv.property_id) as pv_ids,
    ROUND(10 + RAND() * 1990, 2) as price,
    FLOOR(RAND() * 301) as stock,
    0 as sort
FROM product_info p
INNER JOIN product_property_value ppv ON p.product_id = ppv.product_id
WHERE EXISTS (
    SELECT 1 FROM product_property_value pv2 
    WHERE pv2.product_id = p.product_id
)
GROUP BY p.product_id
ON DUPLICATE KEY UPDATE 
    price = VALUES(price),
    stock = VALUES(stock);

-- ============================================================
-- 更新商品的最低和最高价格
-- ============================================================
UPDATE product_info p
INNER JOIN (
    SELECT product_id, MIN(price) as min_p, MAX(price) as max_p
    FROM product_sku
    GROUP BY product_id
) sku ON p.product_id = sku.product_id
SET p.min_price = sku.min_p, p.max_price = sku.max_p;

-- ============================================================
-- 清理临时表
-- ============================================================
DROP TEMPORARY TABLE IF EXISTS temp_categories;
DROP TEMPORARY TABLE IF EXISTS temp_property_options;
DROP FUNCTION IF EXISTS get_random_category;

-- 启用外键检查
SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================
-- 生成完成
-- ============================================================
SELECT '商品数据生成完成！' as message;
SELECT COUNT(*) as product_count FROM product_info;
SELECT COUNT(*) as sku_count FROM product_sku;
SELECT COUNT(*) as property_value_count FROM product_property_value;

CREATE TABLE IF NOT EXISTS commerce_spu (
    spu_id VARCHAR(32) NOT NULL PRIMARY KEY,
    shop_id VARCHAR(32) NOT NULL,
    name VARCHAR(100) NOT NULL,
    properties_json TEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX commerce_spu_shop (shop_id,spu_id)
);
CREATE TABLE IF NOT EXISTS commerce_sku (
    product_id BIGINT NOT NULL PRIMARY KEY,
    spu_id VARCHAR(32) NOT NULL,
    signature CHAR(64) NOT NULL,
    attributes_json TEXT NOT NULL,
    UNIQUE KEY commerce_sku_combination (spu_id,signature)
);
CREATE TABLE IF NOT EXISTS commerce_order_sku_snapshot (
    order_id VARCHAR(32) NOT NULL,
    product_id BIGINT NOT NULL,
    snapshot_json TEXT NOT NULL,
    PRIMARY KEY (order_id,product_id)
);
CREATE TABLE IF NOT EXISTS commerce_catalog_mutex (
    mutex_key VARCHAR(32) NOT NULL PRIMARY KEY
);

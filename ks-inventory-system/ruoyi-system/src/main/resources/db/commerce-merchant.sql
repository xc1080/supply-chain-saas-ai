CREATE TABLE IF NOT EXISTS commerce_shop (
    shop_id VARCHAR(32) NOT NULL PRIMARY KEY,
    shop_name VARCHAR(80) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS commerce_shop_member (
    shop_id VARCHAR(32) NOT NULL,
    user_id BIGINT NOT NULL,
    member_role VARCHAR(16) NOT NULL,
    PRIMARY KEY (shop_id,user_id),
    INDEX commerce_member_user (user_id,shop_id)
);
CREATE TABLE IF NOT EXISTS commerce_product_shop (
    product_id BIGINT NOT NULL PRIMARY KEY,
    shop_id VARCHAR(32) NOT NULL,
    listed TINYINT NOT NULL DEFAULT 1,
    INDEX commerce_shop_product (shop_id,listed,product_id)
);

-- V14: authoritative shop pricing and immutable money snapshots. Existing orders remain legacy.
CREATE TABLE IF NOT EXISTS commerce_pricing_policy (
  shop_id VARCHAR(32) NOT NULL PRIMARY KEY,
  shipping_cents BIGINT NOT NULL DEFAULT 0,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_promotion (
  promotion_id VARCHAR(32) NOT NULL PRIMARY KEY,
  shop_id VARCHAR(32) NOT NULL,
  title VARCHAR(80) NOT NULL,
  discount_cents BIGINT NOT NULL,
  product_ids_json TEXT NOT NULL,
  starts_at DATETIME NOT NULL,
  ends_at DATETIME NOT NULL,
  enabled SMALLINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX commerce_promotion_shop(shop_id,enabled,starts_at,ends_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_order_amount (
  order_id VARCHAR(32) NOT NULL PRIMARY KEY,
  shop_id VARCHAR(32) NOT NULL,
  snapshot_version SMALLINT NOT NULL,
  original_cents BIGINT NOT NULL,
  discount_cents BIGINT NOT NULL,
  shipping_cents BIGINT NOT NULL,
  payable_cents BIGINT NOT NULL,
  promotion_id VARCHAR(32) NULL,
  promotion_title VARCHAR(80) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX commerce_amount_shop(shop_id,order_id),
  CONSTRAINT commerce_amount_order FOREIGN KEY(order_id) REFERENCES commerce_order(order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_order_line_amount (
  order_id VARCHAR(32) NOT NULL,
  product_id BIGINT NOT NULL,
  quantity BIGINT NOT NULL,
  original_cents BIGINT NOT NULL,
  discount_cents BIGINT NOT NULL,
  shipping_cents BIGINT NOT NULL,
  payable_cents BIGINT NOT NULL,
  PRIMARY KEY(order_id,product_id),
  CONSTRAINT commerce_line_amount_order FOREIGN KEY(order_id) REFERENCES commerce_order_amount(order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Each unit has a deterministic amount. The order lock serializes reservations;
-- rejected/failed cases no longer occupy units but keep their historical allocation.
CREATE TABLE IF NOT EXISTS commerce_refund_unit_allocation (
  after_sales_id VARCHAR(32) NOT NULL,
  order_id VARCHAR(32) NOT NULL,
  product_id BIGINT NOT NULL,
  unit_index BIGINT NOT NULL,
  amount_cents BIGINT NOT NULL,
  PRIMARY KEY(after_sales_id,product_id,unit_index),
  INDEX commerce_refund_unit_order(order_id,product_id,unit_index),
  CONSTRAINT commerce_refund_unit_case FOREIGN KEY(after_sales_id) REFERENCES commerce_after_sales_case(after_sales_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

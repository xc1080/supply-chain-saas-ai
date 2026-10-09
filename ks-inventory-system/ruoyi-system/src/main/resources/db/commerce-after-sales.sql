CREATE TABLE IF NOT EXISTS commerce_after_sales (
  after_sales_id VARCHAR(32) NOT NULL PRIMARY KEY,
  order_id VARCHAR(32) NOT NULL,
  shop_id VARCHAR(32) NOT NULL,
  owner_id CHAR(64) NOT NULL,
  request_key VARCHAR(80) NOT NULL,
  reason VARCHAR(200) NOT NULL,
  status VARCHAR(24) NOT NULL,
  original_order_status SMALLINT NOT NULL,
  return_required SMALLINT NOT NULL,
  refund_amount DECIMAL(14,2) NOT NULL,
  refunded_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
  review_key VARCHAR(80) NULL,
  review_note VARCHAR(200) NULL,
  return_key VARCHAR(80) NULL,
  return_receipt_id VARCHAR(32) NULL,
  refund_id VARCHAR(64) NULL,
  created_at DATETIME NOT NULL,
  reviewed_at DATETIME NULL,
  returned_at DATETIME NULL,
  refunded_at DATETIME NULL,
  UNIQUE KEY commerce_after_sales_order(order_id),
  INDEX commerce_after_sales_owner(shop_id,owner_id,created_at),
  INDEX commerce_after_sales_status(shop_id,status,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- v1 is retained as an immutable migration source; its per-order unique key is not removed.
CREATE TABLE IF NOT EXISTS commerce_after_sales_case (
  after_sales_id VARCHAR(32) NOT NULL PRIMARY KEY,
  order_id VARCHAR(32) NOT NULL,
  shop_id VARCHAR(32) NOT NULL,
  owner_id CHAR(64) NOT NULL,
  request_key VARCHAR(80) NOT NULL,
  request_hash CHAR(64) NULL,
  kind VARCHAR(24) NOT NULL,
  reason VARCHAR(200) NOT NULL,
  status VARCHAR(24) NOT NULL,
  original_order_status SMALLINT NOT NULL,
  return_required SMALLINT NOT NULL,
  refund_amount DECIMAL(14,2) NOT NULL,
  refunded_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
  review_key VARCHAR(80) NULL,
  review_note VARCHAR(200) NULL,
  return_key VARCHAR(80) NULL,
  return_receipt_id VARCHAR(32) NULL,
  return_condition VARCHAR(20) NULL,
  refund_id VARCHAR(64) NULL,
  created_at DATETIME NOT NULL,
  reviewed_at DATETIME NULL,
  returned_at DATETIME NULL,
  refunded_at DATETIME NULL,
  UNIQUE KEY commerce_case_request(order_id,request_key),
  INDEX commerce_case_owner(shop_id,owner_id,created_at),
  INDEX commerce_case_status(shop_id,status,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_after_sales_item (
  after_sales_id VARCHAR(32) NOT NULL,
  product_id BIGINT NOT NULL,
  product_code VARCHAR(64) NOT NULL,
  product_name VARCHAR(64) NOT NULL,
  spec VARCHAR(64) NULL,
  quantity BIGINT NOT NULL,
  unit_price DECIMAL(14,2) NOT NULL,
  amount DECIMAL(14,2) NOT NULL,
  PRIMARY KEY(after_sales_id,product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_after_sales_event (
  event_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  event_key VARCHAR(160) NOT NULL,
  after_sales_id VARCHAR(32) NOT NULL,
  event_type VARCHAR(24) NOT NULL,
  actor_user_id BIGINT NULL,
  note VARCHAR(200) NULL,
  created_at DATETIME NOT NULL,
  UNIQUE KEY commerce_after_sales_event_key(event_key),
  INDEX commerce_after_sales_history(after_sales_id,event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_refund_attempt (
  after_sales_id VARCHAR(32) NOT NULL,
  request_key VARCHAR(80) NOT NULL,
  outcome VARCHAR(16) NOT NULL,
  amount DECIMAL(14,2) NOT NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY(after_sales_id,request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

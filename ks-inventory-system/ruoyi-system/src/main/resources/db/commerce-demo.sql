CREATE TABLE IF NOT EXISTS commerce_activity (
  activity_id VARCHAR(32) NOT NULL PRIMARY KEY,
  product_id BIGINT NOT NULL,
  title VARCHAR(80) NOT NULL,
  price DECIMAL(14,2) NOT NULL,
  capacity BIGINT NOT NULL,
  remaining BIGINT NOT NULL,
  per_owner_limit BIGINT NOT NULL,
  starts_at DATETIME NOT NULL,
  ends_at DATETIME NOT NULL,
  INDEX commerce_activity_product(product_id,ends_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_request (
  owner_id CHAR(64) NOT NULL,
  request_key VARCHAR(80) NOT NULL,
  PRIMARY KEY (owner_id, request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_order (
  order_id VARCHAR(32) NOT NULL PRIMARY KEY,
  owner_id CHAR(64) NOT NULL,
  request_key VARCHAR(80) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  status SMALLINT NOT NULL DEFAULT 0,
  total_amount DECIMAL(14,2) NOT NULL,
  create_time DATETIME NOT NULL,
  expires_at DATETIME NULL,
  close_reason VARCHAR(24) NULL,
  activity_id VARCHAR(32) NULL,
  paid_time DATETIME NULL,
  shipped_time DATETIME NULL,
  received_time DATETIME NULL,
  cancelled_time DATETIME NULL,
  transaction_id VARCHAR(64) NULL,
  receipt_id VARCHAR(32) NULL,
  carrier VARCHAR(64) NULL,
  tracking_no VARCHAR(80) NULL,
  shipping_address VARCHAR(2000) NULL,
  after_sales_id VARCHAR(32) NULL,
  after_sales_status VARCHAR(24) NULL,
  refunded_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
  UNIQUE KEY commerce_owner_request (owner_id, request_key),
  INDEX commerce_owner_status (owner_id, status),
  INDEX commerce_expiry (status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_order_item (
  order_id VARCHAR(32) NOT NULL,
  product_id BIGINT NOT NULL,
  product_code VARCHAR(64) NOT NULL,
  product_name VARCHAR(64) NOT NULL,
  spec VARCHAR(64) NULL,
  quantity BIGINT NOT NULL,
  unit_price DECIMAL(14,2) NOT NULL,
  amount DECIMAL(14,2) NOT NULL,
  PRIMARY KEY (order_id, product_id),
  INDEX commerce_item_product (product_id),
  CONSTRAINT commerce_item_order FOREIGN KEY (order_id) REFERENCES commerce_order(order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_payment_attempt (
  order_id VARCHAR(32) NOT NULL,
  request_key VARCHAR(80) NOT NULL,
  outcome VARCHAR(16) NOT NULL,
  create_time DATETIME NOT NULL,
  PRIMARY KEY (order_id, request_key),
  CONSTRAINT commerce_payment_order FOREIGN KEY (order_id) REFERENCES commerce_order(order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_stock (
  product_id BIGINT NOT NULL PRIMARY KEY,
  on_hand BIGINT NOT NULL,
  reserved BIGINT NOT NULL DEFAULT 0,
  activity_reserved BIGINT NOT NULL DEFAULT 0,
  unavailable BIGINT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  updated_at DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_stock_hold (
  order_id VARCHAR(32) NOT NULL,
  product_id BIGINT NOT NULL,
  quantity BIGINT NOT NULL,
  activity_id VARCHAR(32) NULL,
  status VARCHAR(16) NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (order_id,product_id),
  INDEX commerce_hold_product(product_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_stock_ledger (
  ledger_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  event_key VARCHAR(160) NOT NULL,
  product_id BIGINT NOT NULL,
  event_type VARCHAR(32) NOT NULL,
  order_id VARCHAR(32) NULL,
  activity_id VARCHAR(32) NULL,
  reason VARCHAR(80) NULL,
  event_quantity BIGINT NOT NULL DEFAULT 0,
  delta_on_hand BIGINT NOT NULL,
  delta_reserved BIGINT NOT NULL,
  delta_activity BIGINT NOT NULL,
  delta_unavailable BIGINT NOT NULL DEFAULT 0,
  before_on_hand BIGINT NOT NULL,
  after_on_hand BIGINT NOT NULL,
  before_reserved BIGINT NOT NULL,
  after_reserved BIGINT NOT NULL,
  before_activity BIGINT NOT NULL,
  after_activity BIGINT NOT NULL,
  before_unavailable BIGINT NOT NULL DEFAULT 0,
  after_unavailable BIGINT NOT NULL DEFAULT 0,
  stock_version BIGINT NOT NULL,
  created_at DATETIME NOT NULL,
  UNIQUE KEY commerce_stock_event(event_key),
  INDEX commerce_ledger_product(product_id,ledger_id),
  INDEX commerce_ledger_activity(activity_id,product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_fulfillment_line (
  order_id VARCHAR(32) NOT NULL,
  product_id BIGINT NOT NULL,
  shipped BIGINT NOT NULL DEFAULT 0,
  returned BIGINT NOT NULL DEFAULT 0,
  released BIGINT NOT NULL DEFAULT 0,
  refunded BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY(order_id,product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_shipment (
  shipment_id VARCHAR(32) NOT NULL PRIMARY KEY,
  order_id VARCHAR(32) NOT NULL,
  request_key VARCHAR(80) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  receipt_id VARCHAR(32) NOT NULL,
  carrier VARCHAR(64) NOT NULL,
  tracking_no VARCHAR(80) NOT NULL,
  amount DECIMAL(14,2) NOT NULL,
  created_at DATETIME NOT NULL,
  UNIQUE KEY commerce_shipment_request(order_id,request_key),
  UNIQUE KEY commerce_shipment_receipt(receipt_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_shipment_item (
  shipment_id VARCHAR(32) NOT NULL,
  product_id BIGINT NOT NULL,
  quantity BIGINT NOT NULL,
  PRIMARY KEY(shipment_id,product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_partial_migration (
  migration_id VARCHAR(64) NOT NULL PRIMARY KEY,
  migrated_at DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_return_allocation (
  after_sales_id VARCHAR(32) NOT NULL,
  source_receipt_id VARCHAR(32) NOT NULL,
  product_id BIGINT NOT NULL,
  warehouse_id BIGINT NOT NULL,
  quantity BIGINT NOT NULL,
  PRIMARY KEY(after_sales_id,source_receipt_id,product_id,warehouse_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_receipt_lock (
  receipt_id VARCHAR(32) NOT NULL PRIMARY KEY
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_warehouse_ledger (
  ledger_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  event_key VARCHAR(160) NOT NULL,
  receipt_id VARCHAR(32) NOT NULL,
  related_receipts VARCHAR(4000) NOT NULL,
  operation VARCHAR(16) NOT NULL,
  product_id BIGINT NOT NULL,
  warehouse_id BIGINT NOT NULL,
  delta_quantity BIGINT NOT NULL,
  before_quantity BIGINT NOT NULL,
  after_quantity BIGINT NOT NULL,
  created_at DATETIME NOT NULL,
  UNIQUE KEY commerce_warehouse_event(event_key),
  INDEX commerce_warehouse_product(product_id,warehouse_id,ledger_id),
  INDEX commerce_warehouse_receipt(receipt_id,ledger_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

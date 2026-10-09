CREATE TABLE IF NOT EXISTS commerce_warehouse_condition (
  product_id BIGINT NOT NULL,
  warehouse_id BIGINT NOT NULL,
  quality_hold BIGINT NOT NULL DEFAULT 0,
  damaged BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY(product_id,warehouse_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_condition_event (
  event_id VARCHAR(32) NOT NULL PRIMARY KEY,
  shop_id VARCHAR(32) NOT NULL,
  request_key VARCHAR(80) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  product_id BIGINT NOT NULL,
  warehouse_id BIGINT NOT NULL,
  from_state VARCHAR(20) NOT NULL,
  to_state VARCHAR(20) NOT NULL,
  quantity BIGINT NOT NULL,
  before_quality BIGINT NOT NULL,
  after_quality BIGINT NOT NULL,
  before_damaged BIGINT NOT NULL,
  after_damaged BIGINT NOT NULL,
  reason VARCHAR(160) NOT NULL,
  actor_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL,
  UNIQUE KEY commerce_condition_request(shop_id,request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_delivery_policy (
  shop_id VARCHAR(32) NOT NULL PRIMARY KEY,
  daily_item_capacity BIGINT NOT NULL,
  dispatch_days INT NOT NULL,
  actor_id BIGINT NOT NULL,
  updated_at DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_supply_policy (
  product_id BIGINT NOT NULL PRIMARY KEY,
  shop_id VARCHAR(32) NOT NULL,
  supplier_lead_days INT NULL,
  actor_id BIGINT NOT NULL,
  updated_at DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_incoming (
  incoming_id VARCHAR(32) NOT NULL PRIMARY KEY,
  shop_id VARCHAR(32) NOT NULL,
  request_key VARCHAR(80) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  product_id BIGINT NOT NULL,
  warehouse_id BIGINT NOT NULL,
  source_reference VARCHAR(80) NOT NULL,
  quantity BIGINT NOT NULL,
  received_quantity BIGINT NOT NULL DEFAULT 0,
  expected_at DATETIME NOT NULL,
  status VARCHAR(20) NOT NULL,
  actor_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL,
  UNIQUE KEY commerce_incoming_request(shop_id,request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_incoming_receipt (
  receipt_id VARCHAR(32) NOT NULL,
  product_id BIGINT NOT NULL,
  warehouse_id BIGINT NOT NULL,
  incoming_id VARCHAR(32) NOT NULL,
  quantity BIGINT NOT NULL,
  actor_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY(receipt_id,product_id,warehouse_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_replenishment_draft (
  draft_id VARCHAR(32) NOT NULL PRIMARY KEY,
  shop_id VARCHAR(32) NOT NULL,
  request_key VARCHAR(80) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  status VARCHAR(24) NOT NULL,
  snapshot_json TEXT NOT NULL,
  actor_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL,
  reviewed_by BIGINT NULL,
  reviewed_at DATETIME NULL,
  review_key VARCHAR(80) NULL,
  review_hash CHAR(64) NULL,
  review_note VARCHAR(160) NULL,
  UNIQUE KEY commerce_replenishment_request(shop_id,request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

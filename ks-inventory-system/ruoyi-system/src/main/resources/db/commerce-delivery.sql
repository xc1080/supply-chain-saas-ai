CREATE TABLE IF NOT EXISTS commerce_delivery_bucket (
  shop_id VARCHAR(32) NOT NULL,
  dispatch_date DATE NOT NULL,
  capacity BIGINT NOT NULL,
  reserved_quantity BIGINT NOT NULL DEFAULT 0,
  consumed_quantity BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY(shop_id,dispatch_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS commerce_delivery_hold (
  subject_type VARCHAR(16) NOT NULL,
  subject_id VARCHAR(32) NOT NULL,
  shop_id VARCHAR(32) NOT NULL,
  dispatch_date DATE NOT NULL,
  total_quantity BIGINT NOT NULL,
  remaining_quantity BIGINT NOT NULL,
  consumed_quantity BIGINT NOT NULL DEFAULT 0,
  released_quantity BIGINT NOT NULL DEFAULT 0,
  activity_id VARCHAR(32) NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY(subject_type,subject_id),
  INDEX commerce_delivery_shop_date(shop_id,dispatch_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS commerce_delivery_event (
  event_key VARCHAR(160) NOT NULL PRIMARY KEY,
  shop_id VARCHAR(32) NOT NULL,
  subject_type VARCHAR(16) NOT NULL,
  subject_id VARCHAR(32) NOT NULL,
  dispatch_date DATE NOT NULL,
  action VARCHAR(24) NOT NULL,
  quantity BIGINT NOT NULL,
  payload_hash CHAR(64) NOT NULL,
  created_at DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS commerce_delivery_migration (
  migration_id VARCHAR(32) NOT NULL PRIMARY KEY,
  migrated_at DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

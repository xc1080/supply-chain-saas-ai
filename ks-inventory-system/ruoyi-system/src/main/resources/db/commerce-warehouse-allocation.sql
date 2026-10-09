CREATE TABLE IF NOT EXISTS commerce_warehouse_allocation (
  order_id VARCHAR(32) NOT NULL,
  product_id BIGINT NOT NULL,
  warehouse_id BIGINT NOT NULL,
  quantity BIGINT NOT NULL,
  shipped BIGINT NOT NULL DEFAULT 0,
  released BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY(order_id,product_id,warehouse_id),
  INDEX commerce_warehouse_pending(product_id,warehouse_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_warehouse_allocation_event (
  event_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  event_key VARCHAR(160) NOT NULL,
  order_id VARCHAR(32) NOT NULL,
  product_id BIGINT NOT NULL,
  warehouse_id BIGINT NOT NULL,
  event_type VARCHAR(16) NOT NULL,
  quantity BIGINT NOT NULL,
  created_at DATETIME NOT NULL,
  UNIQUE KEY commerce_warehouse_allocation_event(event_key,product_id,warehouse_id),
  INDEX commerce_warehouse_order_events(order_id,event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

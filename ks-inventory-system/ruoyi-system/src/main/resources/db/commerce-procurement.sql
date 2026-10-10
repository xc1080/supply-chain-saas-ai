-- Stable source identities supplement legacy documents. No historical receipt linkage is inferred.
CREATE TABLE IF NOT EXISTS commerce_purchase_order_lock (
  order_id VARCHAR(32) NOT NULL PRIMARY KEY
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS commerce_purchase_order_line (
  purchase_line_id VARCHAR(32) NOT NULL PRIMARY KEY,
  order_id VARCHAR(32) NOT NULL,
  detail_id BIGINT NOT NULL,
  position_no INT NOT NULL,
  product_id BIGINT NOT NULL,
  supplier_id BIGINT NOT NULL,
  warehouse_id BIGINT NOT NULL,
  quantity BIGINT NOT NULL,
  unit_price DECIMAL(14,2) NOT NULL,
  discount DECIMAL(5,2) NOT NULL,
  UNIQUE KEY commerce_purchase_detail(detail_id),
  INDEX commerce_purchase_order(order_id,position_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS commerce_purchase_receipt_line (
  receipt_line_id VARCHAR(32) NOT NULL PRIMARY KEY,
  receipt_id VARCHAR(32) NOT NULL,
  detail_id BIGINT NOT NULL,
  position_no INT NOT NULL,
  receipt_type INT NOT NULL,
  receipt_status INT NOT NULL,
  source_purchase_line_id VARCHAR(32) NULL,
  source_receipt_line_id VARCHAR(32) NULL,
  product_id BIGINT NOT NULL,
  supplier_id BIGINT NOT NULL,
  warehouse_id BIGINT NOT NULL,
  quantity BIGINT NOT NULL,
  unit_price DECIMAL(14,2) NOT NULL,
  discount DECIMAL(5,2) NOT NULL,
  UNIQUE KEY commerce_purchase_receipt_detail(detail_id),
  INDEX commerce_purchase_receipt(receipt_id,position_no),
  INDEX commerce_purchase_received(source_purchase_line_id,receipt_type,receipt_status),
  INDEX commerce_purchase_returned(source_receipt_line_id,receipt_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

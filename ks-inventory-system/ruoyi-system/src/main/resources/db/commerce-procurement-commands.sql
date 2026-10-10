CREATE TABLE IF NOT EXISTS commerce_purchase_command (
  order_id VARCHAR(32) NOT NULL,
  actor_id BIGINT NOT NULL,
  command_kind VARCHAR(16) NOT NULL,
  request_key VARCHAR(80) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  receipt_id VARCHAR(32) NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY(order_id,actor_id,command_kind,request_key),
  UNIQUE KEY commerce_purchase_command_receipt(receipt_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

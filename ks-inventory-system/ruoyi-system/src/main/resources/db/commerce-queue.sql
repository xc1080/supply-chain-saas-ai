CREATE TABLE IF NOT EXISTS commerce_checkout_gate (
  gate_id SMALLINT NOT NULL PRIMARY KEY,
  pending_count INT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO commerce_checkout_gate(gate_id,pending_count) VALUES (1,0) ON DUPLICATE KEY UPDATE gate_id=VALUES(gate_id);

CREATE TABLE IF NOT EXISTS commerce_checkout_job (
  job_id CHAR(32) NOT NULL PRIMARY KEY,
  shop_id VARCHAR(32) NOT NULL DEFAULT 'default',
  activity_id VARCHAR(32) NOT NULL,
  owner_id CHAR(64) NOT NULL,
  request_key VARCHAR(80) NOT NULL,
  fingerprint CHAR(64) NOT NULL,
  shipping_address VARCHAR(2000) NULL,
  state VARCHAR(16) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(3) NOT NULL,
  lease_until DATETIME(3) NULL,
  lease_token CHAR(32) NULL,
  result_order_id VARCHAR(32) NULL,
  error_code INT NULL,
  error_message VARCHAR(200) NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY commerce_checkout_idempotency(owner_id,request_key),
  INDEX commerce_checkout_pending(state,next_attempt_at),
  INDEX commerce_checkout_lease(state,lease_until),
  INDEX commerce_checkout_shop(shop_id,state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_checkout_outbox (
  event_id CHAR(32) NOT NULL PRIMARY KEY,
  job_id CHAR(32) NOT NULL,
  event_type VARCHAR(40) NOT NULL,
  state VARCHAR(16) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  completed_at DATETIME(3) NULL,
  UNIQUE KEY commerce_checkout_outbox_job(job_id,event_type),
  INDEX commerce_checkout_outbox_pending(state,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

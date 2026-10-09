CREATE TABLE IF NOT EXISTS commerce_supply_line (
  draft_id VARCHAR(32) NOT NULL,
  product_id BIGINT NOT NULL,
  shop_id VARCHAR(32) NOT NULL,
  quantity BIGINT NOT NULL,
  state VARCHAR(24) NOT NULL,
  incoming_id VARCHAR(32) NULL,
  execution_key VARCHAR(80) NULL,
  execution_hash CHAR(64) NULL,
  executed_by BIGINT NULL,
  executed_at DATETIME NULL,
  PRIMARY KEY(draft_id,product_id),
  UNIQUE KEY commerce_supply_incoming(incoming_id),
  INDEX commerce_supply_commitment(shop_id,product_id,state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS commerce_policy_history (
  event_id VARCHAR(32) NOT NULL PRIMARY KEY,
  shop_id VARCHAR(32) NOT NULL,
  policy_kind VARCHAR(16) NOT NULL,
  product_id BIGINT NULL,
  before_json TEXT NULL,
  after_json TEXT NOT NULL,
  actor_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL,
  INDEX commerce_policy_shop(shop_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS commerce_supply_event (
  event_id VARCHAR(32) NOT NULL PRIMARY KEY,
  shop_id VARCHAR(32) NOT NULL,
  draft_id VARCHAR(32) NOT NULL,
  product_id BIGINT NULL,
  action VARCHAR(24) NOT NULL,
  reference_id VARCHAR(80) NULL,
  actor_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL,
  INDEX commerce_supply_event_draft(shop_id,draft_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS commerce_supply_command (
  draft_id VARCHAR(32) NOT NULL,
  command_kind VARCHAR(16) NOT NULL,
  request_key VARCHAR(80) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  payload_json TEXT NOT NULL,
  actor_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY(draft_id,command_kind)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Preserve original supplier quantities and received evidence. Only reviewed
-- cancellations remove outstanding commitments; no stock or money is posted.
CREATE TABLE IF NOT EXISTS commerce_incoming_resolution (
  incoming_id VARCHAR(32) NOT NULL PRIMARY KEY,
  shop_id VARCHAR(32) NOT NULL,
  cancelled_quantity BIGINT NOT NULL DEFAULT 0,
  actor_id BIGINT NOT NULL,
  updated_at DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_incoming_change (
  change_id VARCHAR(32) NOT NULL PRIMARY KEY,
  shop_id VARCHAR(32) NOT NULL,
  incoming_id VARCHAR(32) NOT NULL,
  action VARCHAR(24) NOT NULL,
  request_key VARCHAR(80) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  status VARCHAR(24) NOT NULL,
  before_hash CHAR(64) NOT NULL,
  before_json TEXT NOT NULL,
  after_json TEXT NOT NULL,
  reason VARCHAR(160) NOT NULL,
  source_reference VARCHAR(80) NOT NULL,
  actor_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL,
  reviewed_by BIGINT NULL,
  reviewed_at DATETIME NULL,
  review_key VARCHAR(80) NULL,
  review_hash CHAR(64) NULL,
  review_note VARCHAR(160) NULL,
  UNIQUE KEY commerce_incoming_change_request(shop_id,request_key),
  INDEX commerce_incoming_change_pending(shop_id,incoming_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

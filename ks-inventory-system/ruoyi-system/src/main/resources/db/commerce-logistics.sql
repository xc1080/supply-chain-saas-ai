-- Provider observations are separate from dispatch, receipt and inventory facts.
-- Tenant/shop/subject form the scope even when an adapter is backed by a shared database.
CREATE TABLE IF NOT EXISTS commerce_logistics_event (
  event_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  tenant_id VARCHAR(32) NOT NULL,
  shop_id VARCHAR(32) NOT NULL,
  subject_type VARCHAR(16) NOT NULL,
  subject_id VARCHAR(32) NOT NULL,
  request_key VARCHAR(80) NOT NULL,
  payload_hash CHAR(64) NOT NULL,
  provider VARCHAR(40) NOT NULL,
  status VARCHAR(24) NOT NULL,
  occurred_at DATETIME NOT NULL,
  location VARCHAR(100) NULL,
  description VARCHAR(200) NOT NULL,
  actor_user_id BIGINT NOT NULL,
  recorded_at DATETIME NOT NULL,
  UNIQUE KEY commerce_logistics_request(tenant_id,shop_id,subject_type,subject_id,request_key),
  INDEX commerce_logistics_history(tenant_id,shop_id,subject_type,subject_id,occurred_at,event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_return_parcel (
  tenant_id VARCHAR(32) NOT NULL,
  after_sales_id VARCHAR(32) NOT NULL,
  shop_id VARCHAR(32) NOT NULL,
  owner_id CHAR(64) NOT NULL,
  carrier_code VARCHAR(32) NOT NULL,
  tracking_no VARCHAR(80) NOT NULL,
  request_key VARCHAR(80) NOT NULL,
  registered_at DATETIME NOT NULL,
  PRIMARY KEY(tenant_id,after_sales_id),
  INDEX commerce_return_parcel_owner(tenant_id,shop_id,owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_return_receipt_evidence (
  tenant_id VARCHAR(32) NOT NULL,
  after_sales_id VARCHAR(32) NOT NULL,
  shop_id VARCHAR(32) NOT NULL,
  evidence VARCHAR(200) NOT NULL,
  actor_user_id BIGINT NOT NULL,
  recorded_at DATETIME NOT NULL,
  PRIMARY KEY(tenant_id,after_sales_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Local channel simulator is deliberately separate from the business order transaction.
CREATE TABLE IF NOT EXISTS commerce_payment_operation (
 operation_id VARCHAR(40) NOT NULL PRIMARY KEY,
 shop_id VARCHAR(32) NOT NULL,
 order_id VARCHAR(32) NOT NULL,
 after_sales_id VARCHAR(32) NULL,
 kind VARCHAR(20) NOT NULL,
 business_key VARCHAR(80) NOT NULL,
 request_key VARCHAR(80) NOT NULL,
 scenario VARCHAR(32) NOT NULL,
 amount DECIMAL(14,2) NOT NULL,
 currency VARCHAR(3) NOT NULL DEFAULT 'CNY',
 actor_id BIGINT NULL,
 local_status VARCHAR(24) NOT NULL,
 provider_status VARCHAR(16) NOT NULL DEFAULT 'NOT_SUBMITTED',
 provider_revision BIGINT NOT NULL DEFAULT 0,
 applied_revision BIGINT NOT NULL DEFAULT 0,
 provider_reference VARCHAR(64) NOT NULL,
 parent_operation_id VARCHAR(40) NULL,
 next_query_at DATETIME NULL,
 query_count BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME NOT NULL,
 updated_at DATETIME NOT NULL,
 UNIQUE KEY commerce_payment_request(shop_id,kind,business_key,request_key),
 UNIQUE KEY commerce_payment_compensation(parent_operation_id),
 INDEX commerce_payment_pending(shop_id,local_status,next_query_at),
 INDEX commerce_payment_order(order_id,kind)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_provider_event (
 event_id VARCHAR(80) NOT NULL PRIMARY KEY,
 shop_id VARCHAR(32) NOT NULL,
 operation_id VARCHAR(40) NOT NULL,
 payload_hash CHAR(64) NOT NULL,
 payload TEXT NOT NULL,
 processing_status VARCHAR(16) NOT NULL,
 created_at DATETIME NOT NULL,
 applied_at DATETIME NULL,
 INDEX commerce_provider_replay(shop_id,processing_status,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_channel_entry (
 entry_id VARCHAR(48) NOT NULL PRIMARY KEY,
 operation_id VARCHAR(40) NOT NULL,
 shop_id VARCHAR(32) NOT NULL,
 order_id VARCHAR(32) NOT NULL,
 movement VARCHAR(8) NOT NULL,
 amount DECIMAL(14,2) NOT NULL,
 currency VARCHAR(3) NOT NULL,
 created_at DATETIME NOT NULL,
 UNIQUE KEY commerce_channel_once(operation_id),
 INDEX commerce_channel_order(shop_id,order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

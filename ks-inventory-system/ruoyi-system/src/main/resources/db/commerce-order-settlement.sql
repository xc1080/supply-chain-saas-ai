-- Order expenses and local-sandbox allocations. Entries are never edited or deleted.
CREATE TABLE IF NOT EXISTS commerce_settlement_request (
 shop_id VARCHAR(32) NOT NULL,
 kind VARCHAR(20) NOT NULL,
 request_key VARCHAR(80) NOT NULL,
 payload_hash CHAR(64) NOT NULL,
 PRIMARY KEY(shop_id,kind,request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS commerce_order_expense (
 expense_id VARCHAR(40) NOT NULL PRIMARY KEY,
 shop_id VARCHAR(32) NOT NULL,
 order_id VARCHAR(32) NOT NULL,
 request_key VARCHAR(80) NOT NULL,
 payload_hash CHAR(64) NOT NULL,
 category VARCHAR(20) NOT NULL,
 amount DECIMAL(14,2) NOT NULL,
 payee VARCHAR(100) NOT NULL,
 evidence_reference VARCHAR(200) NOT NULL,
 status VARCHAR(20) NOT NULL,
 actor_id BIGINT NOT NULL,
 review_key VARCHAR(80) NULL,
 reviewer_id BIGINT NULL,
 created_at DATETIME NOT NULL,
 reviewed_at DATETIME NULL,
 UNIQUE KEY commerce_expense_request(shop_id,request_key),
 INDEX commerce_expense_order(shop_id,order_id,expense_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS commerce_expense_payment (
 payment_id VARCHAR(40) NOT NULL PRIMARY KEY,
 shop_id VARCHAR(32) NOT NULL,
 order_id VARCHAR(32) NOT NULL,
 request_key VARCHAR(80) NOT NULL,
 payload_hash CHAR(64) NOT NULL,
 amount DECIMAL(14,2) NOT NULL,
 evidence_reference VARCHAR(200) NOT NULL,
 provider VARCHAR(40) NOT NULL,
 status VARCHAR(20) NOT NULL,
 actor_id BIGINT NOT NULL,
 created_at DATETIME NOT NULL,
 UNIQUE KEY commerce_expense_payment_request(shop_id,request_key),
 INDEX commerce_expense_payment_order(shop_id,order_id,payment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS commerce_expense_allocation (
 payment_id VARCHAR(40) NOT NULL,
 expense_id VARCHAR(40) NOT NULL,
 amount DECIMAL(14,2) NOT NULL,
 PRIMARY KEY(payment_id,expense_id),
 INDEX commerce_expense_allocated(expense_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS commerce_order_settlement_snapshot (
 snapshot_id VARCHAR(40) NOT NULL PRIMARY KEY,
 shop_id VARCHAR(32) NOT NULL,
 order_id VARCHAR(32) NOT NULL,
 request_key VARCHAR(80) NOT NULL,
 facts_hash CHAR(64) NOT NULL,
 result_json TEXT NOT NULL,
 actor_id BIGINT NOT NULL,
 created_at DATETIME NOT NULL,
 UNIQUE KEY commerce_settlement_snapshot_request(shop_id,request_key),
 INDEX commerce_settlement_snapshot_order(shop_id,order_id,snapshot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

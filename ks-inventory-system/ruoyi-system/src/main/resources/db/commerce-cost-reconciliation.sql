-- Learning basis: document unit-cost snapshots, not FIFO, landed cost or a general ledger.
-- No backfill from today's product cost: absent historical evidence remains UNKNOWN.
CREATE TABLE IF NOT EXISTS commerce_cost_receipt (
 receipt_id VARCHAR(32) NOT NULL PRIMARY KEY,
 revision BIGINT NOT NULL,
 content_hash CHAR(64) NOT NULL,
 active SMALLINT NOT NULL,
 updated_at DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_cost_entry (
 entry_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 event_key VARCHAR(160) NOT NULL,
 shop_id VARCHAR(32) NOT NULL,
 event_type VARCHAR(24) NOT NULL,
 source_id VARCHAR(40) NOT NULL,
 receipt_id VARCHAR(32) NOT NULL,
 order_id VARCHAR(32) NULL,
 origin_entry_id BIGINT NULL,
 product_id BIGINT NOT NULL,
 warehouse_id BIGINT NOT NULL,
 quantity BIGINT NOT NULL,
 unit_cost DECIMAL(18,4) NULL,
 amount DECIMAL(18,4) NULL,
 cost_status VARCHAR(16) NOT NULL,
 cost_basis VARCHAR(40) NOT NULL,
 actor_id BIGINT NULL,
 created_at DATETIME NOT NULL,
 UNIQUE KEY commerce_cost_event(event_key),
 INDEX commerce_cost_history(shop_id,entry_id),
 INDEX commerce_cost_origin(origin_entry_id),
 INDEX commerce_cost_order(shop_id,order_id),
 INDEX commerce_cost_receipt_lines(receipt_id,event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Imported observations are a separate sandbox statement; business/channel books never change here.
CREATE TABLE IF NOT EXISTS commerce_statement_observation (
 observation_id VARCHAR(40) NOT NULL PRIMARY KEY,
 shop_id VARCHAR(32) NOT NULL,
 order_id VARCHAR(32) NOT NULL,
 operation_id VARCHAR(40) NOT NULL,
 request_key VARCHAR(80) NOT NULL,
 payload_hash CHAR(64) NOT NULL,
 observed_status VARCHAR(20) NOT NULL,
 observed_amount DECIMAL(14,2) NOT NULL,
 source_reference VARCHAR(200) NOT NULL,
 actor_id BIGINT NOT NULL,
 sequence_no BIGINT NOT NULL,
 created_at DATETIME NOT NULL,
 UNIQUE KEY commerce_statement_request(shop_id,request_key),
 UNIQUE KEY commerce_statement_revision(operation_id,sequence_no),
 INDEX commerce_statement_order(shop_id,order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_money_reconciliation (
 reconciliation_id VARCHAR(40) NOT NULL PRIMARY KEY,
 shop_id VARCHAR(32) NOT NULL,
 order_id VARCHAR(32) NOT NULL,
 request_key VARCHAR(80) NOT NULL,
 facts_hash CHAR(64) NOT NULL,
 result_json TEXT NOT NULL,
 healthy SMALLINT NOT NULL,
 actor_id BIGINT NOT NULL,
 created_at DATETIME NOT NULL,
 UNIQUE KEY commerce_money_reconcile_request(shop_id,request_key),
 INDEX commerce_money_reconcile_history(shop_id,created_at,reconciliation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_money_resolution (
 resolution_id VARCHAR(40) NOT NULL PRIMARY KEY,
 reconciliation_id VARCHAR(40) NOT NULL,
 shop_id VARCHAR(32) NOT NULL,
 request_key VARCHAR(80) NOT NULL,
 payload_hash CHAR(64) NOT NULL,
 evidence_reference VARCHAR(200) NOT NULL,
 note VARCHAR(200) NOT NULL,
 verified_json TEXT NOT NULL,
 actor_id BIGINT NOT NULL,
 created_at DATETIME NOT NULL,
 UNIQUE KEY commerce_money_resolution_once(reconciliation_id),
 UNIQUE KEY commerce_money_resolution_request(shop_id,request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

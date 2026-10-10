-- Exact stable receipt source -> immutable cost snapshot. Historical SKU/warehouse guesses are forbidden.
CREATE TABLE IF NOT EXISTS commerce_cost_source_line (
  receipt_line_id VARCHAR(32) NOT NULL,
  receipt_revision BIGINT NOT NULL,
  entry_id BIGINT NOT NULL,
  PRIMARY KEY(receipt_line_id,receipt_revision),
  UNIQUE KEY commerce_cost_source_entry(entry_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

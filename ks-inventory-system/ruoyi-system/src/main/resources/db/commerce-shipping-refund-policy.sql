-- V17: preserve existing order promises; new orders refund freight only before dispatch by default.
-- CommerceOrderAmountService checks each column before executing its statement (safe retry after partial MySQL DDL).
ALTER TABLE commerce_pricing_policy ADD COLUMN shipping_refund_policy VARCHAR(24) NOT NULL DEFAULT 'UNSHIPPED_ONLY';
ALTER TABLE commerce_order_amount ADD COLUMN shipping_refund_policy VARCHAR(24) NOT NULL DEFAULT 'PROPORTIONAL';

-- Full legacy document columns used by the real receipt and purchase-order MyBatis XML.
CREATE TABLE product (
  product_id BIGINT PRIMARY KEY,
  product_code VARCHAR(64), product_name VARCHAR(64), product_type BIGINT,
  product_specifications VARCHAR(32), measure_unit VARCHAR(16), producer VARCHAR(64),
  inventory_qty BIGINT, update_by VARCHAR(32), update_time DATETIME
);
CREATE TABLE product_type (
  product_type_id BIGINT PRIMARY KEY, parent_id BIGINT, ancestors VARCHAR(64), product_type_name VARCHAR(64)
);
CREATE TABLE warehouse (warehouse_id BIGINT PRIMARY KEY, warehouse_name VARCHAR(64));
CREATE TABLE supplier (supplier_id BIGINT PRIMARY KEY, supplier_code VARCHAR(64), supplier_name VARCHAR(64));
CREATE TABLE customer (customer_id BIGINT PRIMARY KEY, customer_code VARCHAR(64), customer_name VARCHAR(64));
CREATE TABLE sys_user (user_id BIGINT PRIMARY KEY, user_name VARCHAR(64));
CREATE TABLE inventory_product (
  inventory_id BIGINT AUTO_INCREMENT PRIMARY KEY, product_id BIGINT, warehouse_id BIGINT, supplier_id BIGINT,
  plan_quantity BIGINT, univalence DECIMAL(14,2), discount DECIMAL(5,2), money DECIMAL(14,2),
  create_by VARCHAR(32), create_time DATETIME, update_by VARCHAR(32), update_time DATETIME
);
CREATE TABLE head_order_form (
  systematic_id BIGINT AUTO_INCREMENT PRIMARY KEY, systematic_order_form VARCHAR(32), original_order_form VARCHAR(64),
  order_form_type CHAR(1), order_form_status CHAR(1), order_date DATE, delivery_date DATE,
  warehousing_ids BIGINT, retrieval_ids BIGINT, user_ids BIGINT, supplier_ids BIGINT, customer_ids BIGINT,
  plan_receipt VARCHAR(64), order_form_notes VARCHAR(128), deposit DECIMAL(14,2), order_form_amount DECIMAL(14,2),
  order_capitalize_amount VARCHAR(64), after_sales_installation BIGINT, finding_of_audit CHAR(1), review_comments VARCHAR(64),
  create_by VARCHAR(32), create_time DATETIME, update_by VARCHAR(32), update_time DATETIME
);
CREATE TABLE detail_order_form (
  systematic_id BIGINT AUTO_INCREMENT PRIMARY KEY, systematic_order_form VARCHAR(32), product_id BIGINT,
  product_specifications VARCHAR(32), measure_unit VARCHAR(16), warehousing_id BIGINT, retrieval_id BIGINT,
  supplier_id BIGINT, customer_id BIGINT, current_inventory BIGINT, actual_inventory BIGINT, plan_quantity BIGINT,
  univalence DECIMAL(14,2), discount DECIMAL(5,2), money DECIMAL(14,2), cost DECIMAL(14,2), remarks VARCHAR(64)
);
CREATE TABLE head_receipt (
  systematic_id BIGINT AUTO_INCREMENT PRIMARY KEY, systematic_receipt VARCHAR(32), original_receipt VARCHAR(64),
  receipt_category CHAR(1), receipt_type CHAR(1), receipt_status CHAR(1), invoice_date DATE,
  warehousing_ids BIGINT, retrieval_ids BIGINT, user_ids BIGINT, supplier_ids BIGINT, customer_ids BIGINT,
  plan_receipt VARCHAR(64), receipt_notes VARCHAR(128), deposit DECIMAL(14,2), total_amount DECIMAL(14,2),
  capitalize_total_amount VARCHAR(64), after_sales_installation BIGINT, finding_of_audit CHAR(1), review_comments VARCHAR(64),
  create_by VARCHAR(32), create_time DATETIME, update_by VARCHAR(32), update_time DATETIME
);
CREATE TABLE detail_receipt (
  systematic_id BIGINT AUTO_INCREMENT PRIMARY KEY, systematic_receipt VARCHAR(32), product_id BIGINT,
  warehousing_id BIGINT, retrieval_id BIGINT, supplier_id BIGINT, customer_id BIGINT,
  product_specifications VARCHAR(32), measure_unit VARCHAR(16), current_inventory BIGINT, actual_inventory BIGINT,
  plan_quantity BIGINT, univalence DECIMAL(14,2), discount DECIMAL(5,2), money DECIMAL(14,2), cost DECIMAL(14,2), remarks VARCHAR(64)
);
INSERT INTO product_type VALUES (1,0,'0','Lighting');
INSERT INTO product VALUES (1,'TEST-LAMP','Lamp',1,'Zigbee/9W','piece','Fixture',10,NULL,NULL);
INSERT INTO product VALUES (2,'TEST-GATEWAY','Gateway',1,'Zigbee','piece','Fixture',5,NULL,NULL);
INSERT INTO warehouse VALUES (1,'Primary'),(2,'Secondary');
INSERT INTO supplier VALUES (1,'SUP-ONE','Supplier One'),(2,'SUP-TWO','Supplier Two');
INSERT INTO sys_user VALUES (1,'Operator');
INSERT INTO inventory_product(product_id,warehouse_id,supplier_id,plan_quantity) VALUES (1,1,1,10),(2,1,1,5);

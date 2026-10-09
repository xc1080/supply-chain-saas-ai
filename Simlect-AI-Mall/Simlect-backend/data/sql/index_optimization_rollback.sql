-- ============================================================
-- EShop 索引优化回滚脚本
-- 对应：index_optimization.sql
-- 作用：删除优化脚本新增的索引，恢复优化前的索引状态
-- 执行前：USE eshop;
-- ============================================================

USE eshop;

-- order_info：删除 P0 新增索引，恢复被删的冗余索引
ALTER TABLE order_info
    DROP INDEX idx_order_status_time;

ALTER TABLE order_info
    ADD INDEX idx_pay_order_id (pay_order_id);

-- product_info：删除 P1 新增索引（保留原 idx_product_commend / idx_product_category_status）
ALTER TABLE product_info
    DROP INDEX idx_commend_status_time;

ALTER TABLE product_info
    DROP INDEX idx_status_commend_sale;

ALTER TABLE product_info
    DROP INDEX idx_status_pcategory_sale;

ALTER TABLE product_info
    DROP INDEX idx_status_category_sale;

ALTER TABLE product_info
    DROP INDEX idx_status_min_price_sale;

-- mq_compensation_log
ALTER TABLE mq_compensation_log
    DROP INDEX idx_status_create_time;

ALTER TABLE mq_compensation_log
    DROP INDEX idx_scene_status_time;

-- comment_report
ALTER TABLE comment_report
    DROP INDEX idx_status_report_time;

-- image_moderation_record（仅删联合索引，保留原 idx_create_time）
ALTER TABLE image_moderation_record
    DROP INDEX idx_status_create_time;

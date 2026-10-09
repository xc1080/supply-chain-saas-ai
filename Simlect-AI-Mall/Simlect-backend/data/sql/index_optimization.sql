-- ============================================================
-- EShop 索引优化脚本
-- 说明：请在业务低峰执行；大表 ADD INDEX 可能锁表，生产环境建议 pt-osc/gh-ost
-- 执行前：USE eshop;
-- 执行后：运行文末 EXPLAIN 验证
-- ============================================================

USE eshop;

-- ============================================================
-- P0 订单超时关单 / 待付款扫描
-- 对应：order_status = 0 AND order_time <= ?
--       OrderInfoServiceImpl 支付超时关单、MQ 延迟关单
-- ============================================================
ALTER TABLE order_info
    ADD INDEX idx_order_status_time (order_status, order_time);

-- 冗余：pay_order_id 两个索引完全重复，删除其一（保留 idx_order_pay_order）
ALTER TABLE order_info
    DROP INDEX idx_pay_order_id;

-- idx_create_time 仅 order_time，无法覆盖「待付款+超时」；保留作其他按时间统计即可

-- ============================================================
-- P1 首页推荐 /product/loadCommendProduct
-- 对应：commend_type=1 AND status=1 ORDER BY create_time DESC LIMIT 11
-- 注意：应用层需 query.setStatus(1)，见 ProductController.loadCommendProduct
-- ============================================================
ALTER TABLE product_info
    ADD INDEX idx_commend_status_time (commend_type, status, create_time DESC);

-- 新索引覆盖旧 idx_product_commend 后可删除（可选，减写入开销）
-- ALTER TABLE product_info DROP INDEX idx_product_commend;

-- ============================================================
-- P1 商品列表 /product/loadProduct
-- 首页：status=1, commend_type=0, ORDER BY total_sale / min_price
-- 分类：status=1 AND (category_id=? OR p_category_id=?)
-- ============================================================
ALTER TABLE product_info
    ADD INDEX idx_status_commend_sale (status, commend_type, total_sale DESC);

ALTER TABLE product_info
    ADD INDEX idx_status_pcategory_sale (status, p_category_id, total_sale DESC);

ALTER TABLE product_info
    ADD INDEX idx_status_category_sale (status, category_id, total_sale DESC);

-- 按价格排序场景（sortField=price）
ALTER TABLE product_info
    ADD INDEX idx_status_min_price_sale (status, min_price, total_sale DESC);

-- ============================================================
-- P2 管理端：状态筛选 + 时间倒序（消除 Using filesort）
-- ============================================================

-- MQ 补偿审查 MqCompensationLogList
ALTER TABLE mq_compensation_log
    ADD INDEX idx_status_create_time (status, create_time DESC);

-- 常按场景+状态查时可启用（可选）
ALTER TABLE mq_compensation_log
    ADD INDEX idx_scene_status_time (biz_scene, status, create_time DESC);

-- 评论举报
ALTER TABLE comment_report
    ADD INDEX idx_status_report_time (status, report_time DESC);

-- 图片审核
ALTER TABLE image_moderation_record
    ADD INDEX idx_status_create_time (status, create_time DESC);

-- ============================================================
-- P3 冗余索引清理（可选，确认无单独使用单列索引的慢查后再删）
-- ============================================================

-- agent_message：idx_user_id 已被 idx_agent_user_time(user_id, send_time) 左前缀覆盖
-- ALTER TABLE agent_message DROP INDEX idx_user_id;

-- order_logistics_info：tracking_no 与 idx_tracking_no 重复
-- ALTER TABLE order_logistics_info DROP INDEX tracking_no;

-- mq_compensation_log：加联合索引后，单列 idx_status 可酌情删除
-- ALTER TABLE mq_compensation_log DROP INDEX idx_status;

-- ============================================================
-- 验证 EXPLAIN（执行后手动跑，期望 type=ref/range，filesort 减少或消失）
-- ============================================================
/*
EXPLAIN SELECT product_id FROM product_info
WHERE commend_type = 1 AND status = 1
ORDER BY create_time DESC LIMIT 11;

EXPLAIN SELECT order_id FROM order_info
WHERE order_status = 0 AND order_time <= NOW()
LIMIT 100;

EXPLAIN SELECT product_id FROM product_info
WHERE status = 1 AND p_category_id = '01001'
ORDER BY total_sale DESC LIMIT 15;

EXPLAIN SELECT log_id FROM mq_compensation_log
WHERE status = 0
ORDER BY create_time DESC LIMIT 15;
*/

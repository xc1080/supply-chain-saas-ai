-- 评论疑似违规：关联订单与待发布评论
ALTER TABLE `image_moderation_record`
  ADD COLUMN `order_id` varchar(32) DEFAULT NULL COMMENT '关联订单ID（评论场景）' AFTER `scene`,
  ADD KEY `idx_order_id` (`order_id`);

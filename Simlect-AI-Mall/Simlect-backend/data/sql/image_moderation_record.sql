-- 图像内容审核记录（疑似/违规人工复核）
CREATE TABLE IF NOT EXISTS `image_moderation_record` (
  `record_id` int NOT NULL AUTO_INCREMENT COMMENT '记录ID',
  `user_id` varchar(32) NOT NULL COMMENT '上传用户ID',
  `user_ip` varchar(64) DEFAULT NULL COMMENT '上传IP',
  `image_path` varchar(512) NOT NULL COMMENT '图片相对路径',
  `scene` varchar(32) NOT NULL COMMENT '场景：avatar/comment',
  `order_id` varchar(32) DEFAULT NULL COMMENT '关联订单ID（评论场景）',
  `conclusion_type` int DEFAULT NULL COMMENT '百度结论类型 1合规 2不合规 3疑似 4失败',
  `conclusion` varchar(128) DEFAULT NULL COMMENT '百度结论文案',
  `baidu_response` text COMMENT '百度原始响应摘要',
  `status` int NOT NULL DEFAULT 0 COMMENT '0待人工复核 1已通过 2确认违规 3误报驳回',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `handle_time` datetime DEFAULT NULL COMMENT '处理时间',
  `handle_remark` varchar(512) DEFAULT NULL COMMENT '处理备注',
  PRIMARY KEY (`record_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_order_id` (`order_id`),
  KEY `idx_status` (`status`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='图像内容审核记录';

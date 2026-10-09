-- 用户签到明细（按日落库，用于 Redis 日历重建）
CREATE TABLE IF NOT EXISTS `user_sign_record_detail` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` varchar(32) NOT NULL COMMENT '用户ID',
  `sign_date` char(8) NOT NULL COMMENT '签到日期 yyyyMMdd',
  `sign_type` tinyint NOT NULL DEFAULT 0 COMMENT '0普通签到 1补签',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_sign_date` (`user_id`, `sign_date`),
  KEY `idx_sign_date` (`sign_date`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户签到明细';

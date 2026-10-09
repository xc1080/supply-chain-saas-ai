-- 管理端敏感操作审计
CREATE TABLE IF NOT EXISTS `admin_audit_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `operator` varchar(64) NOT NULL COMMENT '操作人账号',
  `action` varchar(64) NOT NULL COMMENT '动作标识',
  `target_user_id` varchar(32) DEFAULT NULL COMMENT '目标用户ID',
  `detail` varchar(2000) DEFAULT NULL COMMENT '详情 JSON/文本',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_action_time` (`action`, `create_time`),
  KEY `idx_operator_time` (`operator`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理端操作审计';

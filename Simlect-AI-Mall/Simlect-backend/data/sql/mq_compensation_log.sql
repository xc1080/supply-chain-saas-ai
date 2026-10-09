-- MQ 补偿审查日志（运维：查看发送失败、更新处理状态、触发重放）
CREATE TABLE IF NOT EXISTS `mq_compensation_log` (
  `log_id` int NOT NULL AUTO_INCREMENT COMMENT '日志ID',
  `idempotency_key` varchar(128) NOT NULL COMMENT '幂等键',
  `exchange` varchar(64) NOT NULL COMMENT '交换机',
  `routing_key` varchar(64) NOT NULL COMMENT '路由键',
  `biz_scene` varchar(32) DEFAULT NULL COMMENT '业务场景：RAG/NOTIFY/BROWSE/SIGN/PAY/OTHER',
  `payload_json` mediumtext COMMENT '消息体 JSON',
  `reliability_level` varchar(16) NOT NULL DEFAULT 'HIGH' COMMENT '发送级别 HIGH/STANDARD',
  `error_message` varchar(512) DEFAULT NULL COMMENT '失败原因',
  `retry_count` int NOT NULL DEFAULT 0 COMMENT '重放次数',
  `status` int NOT NULL DEFAULT 0 COMMENT '0待处理 1处理中 2已重放成功 3重放失败 4已忽略',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `handle_time` datetime DEFAULT NULL COMMENT '运维处理时间',
  `handle_remark` varchar(512) DEFAULT NULL COMMENT '处理备注',
  PRIMARY KEY (`log_id`),
  UNIQUE KEY `uk_idempotency_key` (`idempotency_key`),
  KEY `idx_status` (`status`),
  KEY `idx_biz_scene` (`biz_scene`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MQ补偿审查日志';

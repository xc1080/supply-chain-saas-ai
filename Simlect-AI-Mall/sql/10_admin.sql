USE simlect_admin;
create table if not exists admin_audit_log
(
    id             bigint auto_increment comment '主键' primary key,
    operator       varchar(64)   not null comment '操作人账号',
    action         varchar(64)   not null comment '动作标识',
    target_user_id varchar(32)   null comment '目标用户ID',
    detail         varchar(2000) null comment '详情 JSON/文本',
    create_time    datetime      not null comment '创建时间'
) comment '管理端操作审计' charset = utf8mb4;

create table if not exists statistics_info
(
    statistics_date varchar(10)    not null comment '日期',
    data_type       tinyint(1)     not null comment '数据类型',
    data_value      decimal(10, 2) null comment '统计数据',
    primary key (statistics_date, data_type)
) comment '数据统计结果' collate = utf8mb4_general_ci row_format = DYNAMIC;

create table if not exists sensitive_word
(
    id           bigint auto_increment primary key,
    word         varchar(128)                           not null comment '敏感词',
    replace_word varchar(128) default '***'             null comment '替换词',
    status       tinyint      default 1                 null comment '状态：1启用 0停用',
    create_time  datetime     default CURRENT_TIMESTAMP null,
    update_time  datetime     default CURRENT_TIMESTAMP null on update CURRENT_TIMESTAMP,
    constraint uk_word unique (word)
) comment '敏感词表' charset = utf8mb4;

create table if not exists mq_compensation_log
(
    log_id            int auto_increment comment '日志ID' primary key,
    idempotency_key   varchar(128)               not null comment '幂等键',
    exchange          varchar(64)                not null comment '交换机',
    routing_key       varchar(64)                not null comment '路由键',
    biz_scene         varchar(32)                null comment '业务场景：RAG/NOTIFY/BROWSE/SIGN/PAY/OTHER',
    payload_json      mediumtext                 null comment '消息体 JSON',
    reliability_level varchar(16) default 'HIGH' not null comment '发送级别 HIGH/STANDARD',
    error_message     varchar(512)               null comment '失败原因',
    retry_count       int         default 0      not null comment '重放次数',
    status            int         default 0      not null comment '0待处理 1处理中 2已重放成功 3重放失败 4已忽略',
    create_time       datetime                   not null comment '创建时间',
    update_time       datetime                   null comment '更新时间',
    handle_time       datetime                   null comment '运维处理时间',
    handle_remark     varchar(512)               null comment '处理备注',
    constraint uk_idempotency_key unique (idempotency_key)
) comment 'MQ补偿审查日志' charset = utf8mb4;

create table if not exists image_moderation_record
(
    record_id       int auto_increment comment '记录ID' primary key,
    user_id         varchar(32)   not null comment '上传用户ID',
    user_ip         varchar(64)   null comment '上传IP',
    image_path      varchar(512)  not null comment '图片相对路径',
    scene           varchar(32)   not null comment '场景：avatar/comment',
    order_id        varchar(32)   null comment '关联订单ID（评论场景）',
    conclusion_type int           null comment '百度结论类型 1合规 2不合规 3疑似 4失败',
    conclusion      varchar(128)  null comment '百度结论文案',
    baidu_response  text          null comment '百度原始响应摘要',
    status          int default 0 not null comment '0待人工复核 1已通过 2确认违规 3误报驳回',
    create_time     datetime      not null comment '创建时间',
    handle_time     datetime      null comment '处理时间',
    handle_remark   varchar(512)  null comment '处理备注'
) comment '图像内容审核记录' charset = utf8mb4;

create table if not exists local_message_outbox
(
    id                bigint auto_increment comment '主键' primary key,
    idempotency_key   varchar(128)                    not null comment '发送幂等键',
    exchange_name     varchar(64)                     not null comment '交换机',
    routing_key       varchar(64)                     not null comment '路由键',
    payload_json      mediumtext                      not null comment '消息体 JSON',
    reliability_level varchar(16) default 'STANDARD'  not null comment 'HIGH/STANDARD',
    status            tinyint     default 0           not null comment '0待发送 1发送中 2已发送 3失败',
    retry_count       int         default 0           not null comment '重试次数',
    error_message     varchar(512)                    null comment '最近失败原因',
    create_time       datetime                        not null comment '创建时间',
    update_time       datetime                        null comment '更新时间',
    sent_time         datetime                        null comment '成功发送时间',
    constraint uk_outbox_idempotency unique (idempotency_key),
    key idx_outbox_status_ctime (status, create_time)
) comment '本地消息 Outbox' charset = utf8mb4;


USE simlect_agent;
create table if not exists agent_message
(
    message_id        int auto_increment comment '消息ID' primary key,
    assistant_message text                 null comment 'AI消息',
    user_message      varchar(500)         null comment '用户消息',
    send_time         datetime             null comment '发送时间',
    user_id           varchar(15)          null comment '用户ID',
    status            tinyint(1) default 1 null comment '0:用户取消 1:回答中 2:完成',
    biz_type          varchar(30)          null comment '业务类型',
    biz_data          varchar(2000)        null comment '业务数据'
) collate = utf8mb4_general_ci row_format = DYNAMIC;

create table if not exists agent_session_memory
(
    user_id      varchar(32)                        not null primary key,
    summary_json json                               null,
    state_json   json                               null,
    turn_count   int      default 0                 not null,
    updated_at   datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP
) charset = utf8mb4;

USE simlect_pay;
create table if not exists pay_trade_record
(
    trade_id         varchar(33)          not null primary key,
    order_id         varchar(32)          not null,
    user_id          varchar(15)          not null,
    pay_order_id     varchar(32)          not null comment '与 order_info.pay_order_id 一致',
    channel_order_id varchar(50)          null comment '与 order_info.channel_order_Id 一致',
    pay_channel      varchar(10)          null,
    pay_amount       decimal(10, 2)       not null,
    trade_status     tinyint(1) default 0 not null comment '0待支付 1成功 2关闭 3退款',
    pay_time         datetime             null,
    create_time      datetime             not null
) comment '支付流水' collate = utf8mb4_general_ci row_format = DYNAMIC;

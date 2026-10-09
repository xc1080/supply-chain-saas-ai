USE simlect_product;

create table if not exists product_info
(
    product_id    varchar(15)          not null comment '商品ID' primary key,
    product_name  varchar(200)         null comment '商品名称',
    product_desc  text                 null comment '商品描述',
    cover         varchar(500)         null comment '封面',
    create_time   datetime             null comment '创建时间',
    category_id   varchar(10)          null comment '分类ID',
    p_category_id varchar(10)          null comment '分类父ID',
    status        tinyint(1) default 0 null comment '-1:已删除 0:下架  1:上架',
    min_price     decimal(10, 2)       null comment '最低价格',
    max_price     decimal(10, 2)       null comment '最高价格',
    total_sale    int        default 0 null comment '销量',
    commend_type  tinyint(1) default 0 null comment '0:未推荐 1:已经推荐'
) comment '商品信息' collate = utf8mb4_general_ci row_format = DYNAMIC;

create table if not exists product_property_value
(
    product_id        varchar(15)  not null comment '商品ID',
    property_id       varchar(10)  null comment '属性ID',
    property_name     varchar(30)  null comment '属性名称',
    property_sort     int          null comment '属性排序',
    cover_type        tinyint(1)   null comment '0:无需传封面 1:需传封面',
    property_value_id varchar(15)  not null,
    property_cover    varchar(60)  null comment '属性封面',
    property_value    varchar(100) null comment '属性值',
    property_remark   varchar(100) null comment '备注',
    sort              int          null comment '属性值排序',
    primary key (product_id, property_value_id)
) collate = utf8mb4_general_ci row_format = DYNAMIC;

create table if not exists product_sku
(
    product_id             varchar(15)    not null comment '商品ID',
    property_value_id_hash varchar(32)    not null comment '属性值id组hash',
    property_value_ids     varchar(500)   null comment '属性值id组',
    price                  decimal(10, 2) null comment '价格',
    sort                   int            null comment '排序',
    primary key (product_id, property_value_id_hash)
) comment 'SKU（库存已迁至 simlect_stock.sku_stock）' collate = utf8mb4_general_ci row_format = DYNAMIC;

create table if not exists sys_category
(
    category_id   varchar(5)             not null primary key,
    category_name varchar(100)           null,
    p_category_id varchar(5) default '0' null,
    sort          int        default 0   null
) collate = utf8mb4_general_ci row_format = DYNAMIC;

create table if not exists sys_product_property
(
    property_id   varchar(10)            not null comment '属性ID' primary key,
    property_name varchar(30)            null comment '属性名称',
    p_category_id varchar(5)             null comment '一级分类',
    category_id   varchar(5) default '0' null comment '二级分类',
    property_sort int                    null comment '排序',
    cover_type    tinyint(1)             null comment '0:无需传封面 1:需传封面'
) collate = utf8mb4_general_ci row_format = DYNAMIC;

USE simlect_search;
create table if not exists search_hot_keyword
(
    keyword     varchar(100)         not null comment '热搜词' primary key,
    sort        int        default 0 not null comment '排序',
    status      tinyint(1) default 1 not null comment '0停用 1启用',
    update_time datetime             null
) comment '运营配置热搜' collate = utf8mb4_general_ci row_format = DYNAMIC;

create table if not exists user_search_keyword
(
    id          bigint auto_increment primary key,
    user_id     varchar(15)  null comment 'NULL=仅统计热搜',
    keyword     varchar(100) not null comment '关键词',
    search_time datetime     not null comment '搜索时间'
) comment '用户/全局搜索词' collate = utf8mb4_general_ci row_format = DYNAMIC;

create table if not exists rag_question
(
    question_id      int auto_increment comment '自增ID' primary key,
    question         varchar(150)  null comment '问题',
    similar_question varchar(1000) null comment '相似问题',
    answer           text          null comment '答案',
    create_time      datetime      null comment '创建时间'
) comment 'rag问题' collate = utf8mb4_general_ci row_format = DYNAMIC;

# 分库表归属

> 一域一库；跨库禁止直接调 Mapper，统一 Feign / MQ。

## simlect_user
user_info, user_address, user_member_profile, user_sign_record, user_sign_record_detail,
user_notification, user_product_favorite, user_browse_history,
**image_moderation_record**（写入侧在 user；见 `01_user.sql`）,
**local_message_outbox**, **mq_compensation_log**（HIGH MQ：通知/浏览/签到；见 `13_mq_infra_per_service.sql`）

## simlect_product
product_info, product_property_value, product_sku（库存在 stock 库）, sys_category, sys_product_property,
**local_message_outbox**, **mq_compensation_log**（HIGH MQ：RAG/ES 索引同步；见 `13_mq_infra_per_service.sql`）

## simlect_stock
sku_stock（见 `03_stock.sql`）

## simlect_cart
product_cart

## simlect_order
order_info, order_item, order_comment, order_coupon_rel, comment_report,
**order_logistics_info**, **order_logistics_info_record**（见 `05_order.sql`；Gateway 物流路由 `lb://simlect-order`）,
**local_message_outbox**, **mq_compensation_log**（见 `05_order.sql` / `12_order_outbox.sql`）

## simlect_pay
pay_trade_record

## simlect_coupon
discount_coupon, user_coupon

## simlect_search
search_hot_keyword, user_search_keyword, rag_question,
**local_message_outbox**, **mq_compensation_log**（见 `13_mq_infra_per_service.sql`）

## simlect_agent
agent_message, agent_session_memory

## simlect_admin
admin_audit_log, statistics_info, sensitive_word,
local_message_outbox, mq_compensation_log（管理端补偿重放；见 `10_admin.sql`）

> `image_moderation_record` 以 **user** 库为准（写入侧）；admin 经 Feign 访问。

## 分层命名约定

| 位置 | 包名 | 说明 |
|------|------|------|
| Maven 坐标 | `com.simlect:*` | 全模块 `groupId` |
| 各业务微服务 `app` | `com.simlect.biz` / `biz.impl` | 领域业务实现（订单、商品、用户等） |
| `Simlect-common` | `com.simlect.service` / `service.impl` | 跨服务基础设施（密码、Outbox、MQ 补偿等） |
| Feign 契约 | `com.simlect.api` | `*-api` 模块；降级工具统一用 `com.simlect.api.support.FeignFallbackResponses` |
| ES 索引 | `simlect-index` / `simlect_vectorstore` | 商品关键词索引 / RAG 向量索引 |

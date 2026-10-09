# Simlect 上线清单（Go-Live）

## 结论口径

完成下列 **必做项** 并冒烟通过后，可视为 **READY WITH CONDITIONS**（Seata AT 默认开启；跨库另有 Feign + Outbox/补偿兜底）。

生产建议启动 Seata Server，并保持 `SEATA_ENABLED=true`（默认）。若临时关闭分布式事务，显式设 `SEATA_ENABLED=false`，此时带 `@GlobalTransactional` 的下单路径行为取决于 Seata 客户端是否仍尝试连 Server——本地无 Seata 时应关闭开关。

## 0. 构建

```bash
export JAVA_HOME=...   # JDK 17
cd Simlect/Simlect-backend
mvn -q package -DskipTests
# 确认各模块 target/*-1.0.0.jar 存在
```

## 1. 数据库（按序）

```text
00_create_databases.sql
00b_nacos_seata_databases.sql   # 首次：nacos / seata 库
14_nacos.sql / 15_seata.sql / 16_seata_undo_log.sql
01_user.sql … 10_admin.sql
13_mq_infra_per_service.sql   # user/product/search Outbox + mq_compensation_log
```

表归属见 `sql/TABLE_OWNERSHIP.md`。

## 2. 基础设施

- MySQL 8（各 simlect_* 库）
- Redis
- RabbitMQ（建议磁盘持久化）
- Nacos 2.x（8848）
- Elasticsearch（search/RAG；可先不上线搜索）
- Sentinel Dashboard（可选）

## 3. 环境变量

复制 `deploy/env.production.example`，至少改掉：

- `SIMLECT_PRODUCTION_READY=true`
- `SIMLECT_INTERNAL_TOKEN`
- `MYSQL_PASSWORD` / `RABBIT_PASSWORD`
- `ADMIN_PASSWORD`
- `PROJECT_FOLDER` / `PROJECT_DOMAIN`
- 支付宝证书与 `ALIPAY`_*（若开放支付）

## 4. 启动顺序

1. gateway:8080
2. user / product / stock / cart / coupon / order / pay / search / admin
3. Python MCP Server:7060（`start-mcp.bat` / `python -m app.mcp_server`；**无热重载，发版或改工具后须重启**）
4. Python agent:7050（`JAVA_WEB_URL` 与 `MCP_SERVER_URL` 指向 Gateway / MCP）
5. Nginx 反代（见 `deploy/nginx.simlect.conf.example`）
6. 前端 production 构建，相对路径 `/api`、`/admin-api`

> Java 根包与 Maven `groupId` 均为 `com.simlect`。ES 商品索引 `simlect-index`，向量索引默认 `simlect_vectorstore`（`VECTOR_INDEX` / Agent `ES_INDEX`）。密钥仅环境变量 / `.env`，勿写入 IDE Run Configuration。

## 5. Smoke Test

- Nacos 服务全部 UP  
- C 端注册/登录  
- 商品列表与详情  
- 加购 → 下单 → 支付回调（或沙箱）  
- 订单超时关单 / Outbox 有 SENT 记录  
- 管理端登录与订单列表  
- 管理端首页今日数据 / 库存预警（依赖 order、user、product、stock 均 UP）  
- Agent HTTP/WS 经 Gateway；MCP `tools/list` 可用（`:7060/mcp`）
- 对话：订单卡 / 商品卡正常；搜索未命中文案为<暂未找到>而非误报<找到 N 个>

## 6. 安全确认

- 业务端口（8084–8111、7050）不对公网  
- 未使用 `-Ddev` / `SIMLECT_DEV_LOGIN_BYPASS=true`  
- Nginx 未反代 `/actuator`  
- 各服务启动日志出现"生产就绪校验通过"


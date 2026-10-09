# Simlect 本地中间件（Docker）一键启动

同一网络：`simlect-net`（MySQL / Redis / RabbitMQ / Nacos / ES / Sentinel / **Seata**）。

## 首次初始化库（必做）

MySQL healthy 后，在 PowerShell：

```powershell
# 在仓库根目录 Simlect/ 下执行
$mysql = { param($f) Get-Content $f -Raw -Encoding UTF8 | docker exec -i simlect-mysql mysql -uroot -proot --default-character-set=utf8mb4 }

& $mysql .\sql\00_create_databases.sql
& $mysql .\sql\00b_nacos_seata_databases.sql
& $mysql .\sql\14_nacos.sql
& $mysql .\sql\15_seata.sql
& $mysql .\sql\16_seata_undo_log.sql
# 再按 GO_LIVE 执行 01…10、13
```

或运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\deploy\init-mysql-meta.ps1
```

## 一键启动

```powershell
powershell -ExecutionPolicy Bypass -File .\deploy\start-middleware.ps1
```

```powershell
cd .\deploy
docker compose -f docker-compose.middleware.yml up -d
docker compose -f docker-compose.middleware.yml ps
```

## 端口与账号

| 服务 | 地址 | 账号 / 说明 |
|------|------|-------------|
| MySQL | `127.0.0.1:3306` | root / root；库含 `nacos`、`seata` |
| Redis | `127.0.0.1:6379` | |
| RabbitMQ | `5672` / http://localhost:15672 | simlect / simlect |
| Nacos | http://localhost:8848/nacos | MySQL 持久化，鉴权关闭 |
| Elasticsearch | http://127.0.0.1:9200 | 自建镜像含 **analysis-ik**（`simlect-elasticsearch:9.2.1-ik`） |
| Sentinel | http://127.0.0.1:8858 | sentinel / sentinel |
| **Seata** | TC `8091` / 控制台 `7091` | 控制台 seata / seata；注册到 Nacos `SEATA_GROUP`；镜像自建（驱动在 `/seata-server/libs`） |

容器互通主机名：`mysql`、`redis`、`rabbitmq`、`nacos`、`elasticsearch`、`sentinel`、`seata-server`。  
宿主机上的 Java 微服务仍连 `127.0.0.1`（Seata TC 已映射 `8091`）。

## Seata 事务组

- 模式：**AT**（`seata.data-source-proxy-mode: AT` + 自动数据源代理）
- 客户端仅 order/stock/coupon/cart/pay 引入 Seata；admin 等不引入
- 客户端 `tx-service-group`: `simlect_tx_group` → `127.0.0.1:8091`（file registry + grouplist）
- 下单入口：`OrderInfoServiceImpl.postOrder` 使用 `@GlobalTransactional`
- 业务库 `undo_log`：`simlect_order` / `simlect_stock` / `simlect_coupon` / `simlect_cart` / `simlect_pay`（`sql/16_seata_undo_log.sql`）

## 业务侧环境变量（补充）

```text
SEATA_TX_GROUP=simlect_tx_group
NACOS_ADDR=127.0.0.1:8848
```

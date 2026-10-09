# Simlect 完整版启动（中间件全开 + 微服务全开）

## 机器要求（务必看）


| 配置                | 能否「完整版流畅」                 |
| ----------------- | ------------------------- |
| **32G 内存**        | 完全够用，中间件已按舒适档配置           |
| **16G 内存 / 4 核+** | 可全开                       |
| 8G / 4 核          | 能勉强拉起，易卡顿/OOM，**不建议当完整版** |


完整版内存粗算（32G 舒适档中间件）：

- 系统 + Docker ≈ 2G  
- 中间件合计约 10～12G（含 MySQL/ES/Seata 等）  
- 11 个 Java 服务 ≈ 3～5G  
- **32G 本机可全开，无需刻意压内存**

---

## 一、中间件（Docker，全开）

```powershell
powershell -ExecutionPolicy Bypass -File .\deploy\start-middleware.ps1
```

或：

```powershell
cd .\deploy
docker compose -f docker-compose.middleware.yml up -d --force-recreate
docker compose -f docker-compose.middleware.yml ps
```

应看到 7 个容器：`mysql / redis / rabbitmq / nacos / es / sentinel / seata`。

---

## 二、Java 微服务（全部 `*Application`，按顺序）

**不要启动** `*/api`、`Simlect-common`。

IDE 运行时在 VM options 加对应 `-Xms/-Xmx`；或 PowerShell：

```powershell
$env:JAVA_TOOL_OPTIONS="-XX:+UseG1GC -XX:MaxGCPauseMillis=50 ..."
```


| 顺序  | 入口类                  | 模块                    | 端口   | 建议 JVM（16G 机）       |
| --- | -------------------- | --------------------- | ---- | ------------------- |
| 1   | `GatewayApplication` | `Simlect-gateway`     | 8080 | `-Xms128m -Xmx256m` |
| 2   | `UserApplication`    | `Simlect-user/app`    | 8105 | `-Xms128m -Xmx320m` |
| 3   | `ProductApplication` | `Simlect-product/app` | 8099 | `-Xms128m -Xmx320m` |
| 4   | `StockApplication`   | `Simlect-stock/app`   | 8102 | `-Xms128m -Xmx256m` |
| 5   | `CartApplication`    | `Simlect-cart/app`    | 8084 | `-Xms128m -Xmx256m` |
| 6   | `CouponApplication`  | `Simlect-coupon/app`  | 8087 | `-Xms128m -Xmx256m` |
| 7   | `OrderApplication`   | `Simlect-order/app`   | 8093 | `-Xms128m -Xmx384m` |
| 8   | `PayApplication`     | `Simlect-pay/app`     | 8096 | `-Xms128m -Xmx256m` |
| 9   | `SearchApplication`  | `Simlect-search`      | 8108 | `-Xms128m -Xmx384m` |
| 10  | `AdminApplication`   | `Simlect-admin`       | 8111 | `-Xms128m -Xmx256m` |


命令行示例（购物车）：

```powershell
$env:JAVA_TOOL_OPTIONS="-Xms128m -Xmx256m"
cd .\Simlect-backend\Simlect-cart\app
mvn -q spring-boot:run
```

等 Nacos 里实例都 UP 后再开前端。

---

## 三、前端

```powershell
cd .\Simlect-front\Simlect-web
npm run dev
```

API 走网关：`http://127.0.0.1:8080`（以前端 `.env` 为准）。

---

## 四、环境变量（与 Docker 对齐）

```text
MYSQL_HOST=127.0.0.1
MYSQL_USER=root
MYSQL_PASSWORD=123456
REDIS_HOST=127.0.0.1
REDIS_PORT=6379
RABBIT_HOST=127.0.0.1
RABBIT_PORT=5672
RABBIT_USER=guest
RABBIT_PASSWORD=guest
NACOS_ADDR=127.0.0.1:8848
ES_URIS=http://127.0.0.1:9200
VECTOR_INDEX=simlect_vectorstore
SENTINEL_DASHBOARD=127.0.0.1:8858
SEATA_ENABLED=true
```

Agent / MCP（另开终端）：

```powershell
cd Simlect-backend\Simlect-agent
.\start-mcp.bat
.\start.bat
```

> MCP **无热重载**：改搜索/工具代码后必须重启 `start-mcp.bat`，否则对话仍可能走旧逻辑。

---

## 五、验收清单

- `docker compose ps` 六个中间件 Up（ES/Nacos healthy）
- Nacos：[http://localhost:8848/nacos](http://localhost:8848/nacos) 能看到各服务实例
- 网关 `8080` 通；前端能登录/浏览/加购
- 搜索走 ES（索引 `simlect-index` / `simlect_vectorstore`）；下单走 order/pay/coupon
- Agent + MCP 工具可对话查单 / 搜商品
  - 查<我的订单>应出**订单卡片**（非仅表格）
  - 搜不存在品类应提示**暂未找到**并另荐，而非<搜索结果找到 N 个>无关品
  - 正文不应出现裸 `[{"productId":...}]` JSON

一键中间件脚本：`[start-middleware.ps1](./start-middleware.ps1)`  
中间件说明：`[MIDDLEWARE_DOCKER.md](./MIDDLEWARE_DOCKER.md)`
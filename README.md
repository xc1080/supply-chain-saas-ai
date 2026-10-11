# supply-chain-saas-ai

智能家居供应链 SaaS 学习工程：Java 单体负责业务事实，Python 提供商城适配与受约束的 AI Agent，两个 Vue 前端分别服务商家和消费者。

本轮实际执行的测试、订单验收和验证边界见 [工程验收记录](工程验收记录.md)。

本仓库的部署入口以本文件为准。`ks-inventory-system`、`Simlect-AI-Mall` 保留上游介绍和参考代码，不能将两份上游 README 当作当前系统的独立启动说明。

## 当前业务权威

| 组件 | 职责 | 本机已有学习环境 |
|---|---|---|
| `ks-inventory-system/ruoyi-admin` | 商品/SKU、采购、ERP库存、仓库分配、订单、支付操作、发货、售后、成本与费用；MySQL 为事实来源 | `127.0.0.1:8035` |
| `fusion-agent` | C端 API 适配、共享会话、检索、Agent规划/工具/审批任务；业务写入经过 Java 权限和事务检查 | `127.0.0.1:7050`，第二租户 `7051` |
| `ks-inventory-system/ks-vue3` | 商家控制台 | `127.0.0.1:5173` |
| `Simlect-AI-Mall/Simlect-front/Simlect-web` | 消费者商城，当前使用 `demo` 构建模式连接融合后的 API | `127.0.0.1:6001` |
| MySQL / Redis | ERP与交易账本 / 共享会话及准入控制 | `3306` / `6379` |

`Simlect-AI-Mall/Simlect-backend` 的原 Java 微服务和原 Agent 是保留的上游参考，当前融合部署不依赖它们，也不需要启动另一套订单或库存服务。

库存统一通过 ERP 业务单据、订单占用及库存流水解释，不能直接修改余额绕过单据。Java 校验租户、店铺与角色；Agent 的工具注册和模型输出不能代替这些检查。

## 金额与结算规则

- 金额以分计算，优惠和运费先分摊再生成订单快照；修改当前商品价格或店铺政策不修改历史订单。
- V17 新增 `shippingRefundPolicy`：新订单默认 `UNSHIPPED_ONLY`，未发货退款退其分摊运费，已发货退货只退优惠后货款；商家可选择 `PROPORTIONAL` 按件退分摊运费。已有金额快照保留原 `PROPORTIONAL` 规则。质量问题、全退退运费等其他规则尚未实现。
- `accountingStatus=COMPLETE` 表示订单经营核算具备完整依据；已批准费用按权责发生制计入结果。`expensePaymentStatus` 独立区分未支付、部分支付、已付清、无费用和待审核，不能由经营结果推断资金已结清。
- 新经营结果使用 `resultStatus=ACCOUNTED`；历史快照里的 `CLOSED` 保留原样，不代表已经查证费用付清。
- `allItemsRefunded` 与 `fullAmountRefunded` 分开表达；全部商品退完但保留运费时，不应显示全额退款。
- 支付与费用支付 Provider 为 `LOCAL_SANDBOX`，物流为模拟 Provider。验收证明业务联动，不能替代正式渠道的回调、资金账单和物流接入验收。

## 可重复的真实 MySQL 验收

需要 JDK 17（含 `javac`）、Maven 3.9、Python 3.12/3.13、MySQL 8 客户端与服务、Redis，以及可安装 `fusion-agent/requirements-lock.txt` 和 `requirements.txt` 的 Python 环境。前端另需要 Node.js 24 与 npm。

已有本机学习环境使用以下入口，不需要停止现有业务服务：

```powershell
powershell -File scripts/verify-local.ps1
```

需要同时验收混合负载时使用 `powershell -File scripts/verify-local.ps1 -MixedLoad`，或在 Python 入口追加 `--mixed-load`。该阶段只使用本次创建的私有数据库与应用，设置虚构商家的处理能力为每天 1000 件，执行 1000 个独立会话、目标 60 浏览请求/秒、120 秒内 200 笔订单；不会调大现有商家的处理能力。容量检查在创建访客和采购入库之前执行，锁内业务校验仍是最终裁决。

跨平台入口：

```bash
python scripts/verify_engineering.py --help
python scripts/verify_engineering.py --mysql-host 127.0.0.1 --mysql-port 13306 --redis-url redis://127.0.0.1:16379/0 --output logs/engineering-acceptance
```

管理员连接密码通过 `VERIFY_MYSQL_PASSWORD` 或私有 `--mysql-defaults` 文件提供，不写入仓库。没有现成基础设施时，可用 `scripts/compose.engineering.yml` 建立独立的 MySQL/Redis；该 Compose 只提供验收基础设施，应用进程由验收脚本启动。先设置一次性验收密码，再执行：

```bash
docker compose -p supply-chain-verification -f scripts/compose.engineering.yml up -d --wait
```

脚本在独立源码副本中打包，生成新的数据库名、凭证与 SQLite 文件，并使用独立端口。正常结束或失败时仅结束本次启动的进程；数据库与凭证在报告所指的私有目录中保留，便于调查。JSON/日志报告与私有凭证目录分开，不上传私有目录。

验收包括全新 ERP 建库与 V1–最新迁移、归档 V11 程序启动后升级、重复启动、DDL 后失败与显式重试、checksum 变更拦截，以及采购、优惠订单、支付、部分发货、寄回凭证、质检返库、退款、费用审批/分次支付、经营快照和只读 Agent 联调。另验证支付成功但响应丢失、应用重启、共享会话恢复、支付查询重试和未付款订单超时释放。

升级入口默认 `--historical-ref e6f22cc`，需要该 Git 历史。脚本通过 `git archive` 导出并构建旧程序，实际运行到 V11，再启动当前程序；遗留业务数据为合成验收数据。迁移 checksum 按文件原始字节计算，因此旧源码只在确认 SQL 内容一致后，在私有构建副本中对齐当前平台的换行。报告保存旧提交、旧产物 SHA 和每份资源的对齐记录；该测试证明同平台升级，不代替跨平台换行兼容验证。

结果在指定目录的 `engineering-result.json` 与每个场景的 `*-closing-business.json`；混合负载另有 `isolated-mixed-load.json`。必须检查 `passed`、`httpSmokeExecuted`、`latestMigration`、失败项及构建 SHA；`--migrations-only` 不能证明交易联调通过。报告目录需新建或为空，重复执行使用新的目录。

## 测试与构建门禁

```bash
mvn -f ks-inventory-system/pom.xml test
python fusion-agent/run_tests.py
python -m unittest discover -s scripts -p 'test_*.py' -v
npm --prefix ks-inventory-system/ks-vue3 ci
npm --prefix ks-inventory-system/ks-vue3 run build:prod
npm --prefix Simlect-AI-Mall/Simlect-front/Simlect-web ci
npm --prefix Simlect-AI-Mall/Simlect-front/Simlect-web run test:sku
node --test Simlect-AI-Mall/Simlect-front/Simlect-web/scripts/refund-policy.test.mjs
npm --prefix Simlect-AI-Mall/Simlect-front/Simlect-web run build:demo
```

Python 回归需要 Redis；`run_tests.py` 使用独立 SQLite、随机 Redis 命名空间和清空的模型密钥。AI 规则评测为 `fusion-agent/eval_ai_business.py`；`--live-model` 会调用已配置的真实模型，离线通过不能当作真实模型能力评测。

GitHub 自动验证定义在 `.github/workflows/engineering.yml`。文件存在不代表运行成功；以该提交的 Actions 结果与上传证据为准。本机执行结果同样不能冒充 GitHub Actions 已通过。

## 使用现有学习环境

保留的 Windows 启动入口是 `start-fusion-demo.ps1`，依赖本机私有 `runtime/` 配置和已构建产物；它不是全新机器的安装程序。详细端口、启动和检查说明见 [本地运行](本地运行.md)。全新环境首先使用上述隔离验收入口，避免复制其他人的私有数据与密钥。

`runtime/`、`.env`、日志、数据库、模型凭证、`target/` 和 `dist/` 不入 Git。没有配置模型时允许明确的规则/检索降级，不能将降级回答标为真实大模型调用。

容量测试入口为 `fusion-agent/smoke_mixed_load.py`，测试报告必须与硬件、流量模型和源码版本一起解读。1000个会话、60浏览请求/秒的测试，不等于1000个同时下单请求，也不能外推十万人秒杀容量。

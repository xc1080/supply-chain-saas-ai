# Simlect-web（C 端）

经 **Gateway :8080** 访问后端（开发代理已配置）。

| 服务 | 端口 |
|------|------|
| Gateway | 8080 |
| Python Agent | 7050（经 Gateway `/api/agent`、`/ws`） |

## 开发
```bash
# 1. 启动 Gateway + 各域服务 + Agent
# 2. 本目录
npm install
npm run dev
```

`.env.development`：
- `VITE_API_PROXY_TARGET=http://localhost:8080`
- `VITE_AGENT_PROXY_TARGET=http://localhost:8080`
- `VITE_WS_PROXY_TARGET=ws://localhost:8080`

## 生产
`.env.production` 使用相对路径 `/api`、`/ws`，由 Nginx 反代到 Gateway（见 `Simlect/deploy/nginx.simlect.conf.example`）。

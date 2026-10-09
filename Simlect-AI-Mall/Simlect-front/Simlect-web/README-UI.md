# Simlect-web · UI 说明

C 端前端（Vue 3），手机端与 PC 端页面均在本项目中维护。

## 目录

| 路径 | 说明 |
|------|------|
| `src/views/`、`src/views/pc/` | 页面 |
| `src/components/home/` | PC 主屏相关组件 |
| `src/components/layout/` | 顶栏、底栏、搜索 |
| `src/styles/` | 全局与平台样式 |
| `src/integrations/featureRegistry.ts` | 后端能力开关 |
| `src/api/modules.ts` | 后端接口 |

## 开发

```bash
npm install
npm run dev
```

开发环境 API 指向 Gateway `http://localhost:8080`（见 `.env.development`）。

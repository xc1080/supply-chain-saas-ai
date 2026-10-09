# Simlect-admin · UI 说明

运营后台（Vue），接口经 Gateway `/admin-api`。

## 目录

| 路径 | 说明 |
|------|------|
| `src/views/Layout.vue` | 主框架（侧栏 + 顶栏） |
| `src/assets/base.scss` | 黑白灰 CSS 变量 |
| `src/assets/simlect-admin.scss` | 表格 / 表单 / 卡片 |
| `src/assets/desktop-admin.scss` | 桌面端布局样式 |

## 开发

```bash
npm install
npm run dev
```

开发环境默认代理到 Gateway `http://localhost:8080`（见 `.env.development`）。

#!/usr/bin/env bash
# 用户端完整部署（含 PWA 图标 / 开屏图，缺了 iOS 会显示黑底「简」占位）
set -euo pipefail

HOST="${DEPLOY_HOST:-root@117.72.189.236}"
REMOTE="${DEPLOY_REMOTE:-/opt/app/www/web}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

cd "$ROOT"
npm run build

DIST="$ROOT/dist"
echo "==> 上传到 $HOST:$REMOTE"

# 远程目录须先存在，否则 Windows scp 上传子目录会报 path canonicalization failed
ssh "$HOST" "mkdir -p '$REMOTE/pwa'"

scp "$DIST/index.html" "$HOST:$REMOTE/"
scp -r "$DIST/assets" "$HOST:$REMOTE/"
scp "$DIST/sw.js" "$DIST/registerSW.js" "$DIST/manifest.webmanifest" "$HOST:$REMOTE/"
scp "$DIST"/workbox-*.js "$HOST:$REMOTE/"

# 静态资源（此前漏传会导致主屏幕图标 / 开屏失效）
scp "$DIST/apple-touch-icon.png" "$HOST:$REMOTE/"
scp "$DIST/favicon.svg" "$HOST:$REMOTE/" 2>/dev/null || true
scp "$DIST/icons.svg" "$HOST:$REMOTE/" 2>/dev/null || true
scp "$DIST"/pwa/* "$HOST:$REMOTE/pwa/"

echo "==> 部署完成"
echo "    iPhone 请删除旧主屏幕图标后重新「添加到主屏幕」"

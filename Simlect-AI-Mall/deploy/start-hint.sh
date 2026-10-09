#!/usr/bin/env bash
# 本地/服务器一键提示：请按模块分别启动 JAR（示例）
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BACKEND="$ROOT/Simlect-backend"
JAVA_OPTS="${JAVA_OPTS:--Xms256m -Xmx512m}"

echo "Ensure env loaded (SIMLECT_PRODUCTION_READY, SIMLECT_INTERNAL_TOKEN, ...)"
echo "Starting gateway..."
# java $JAVA_OPTS -jar "$BACKEND/Simlect-gateway/target/simlect-gateway-1.0.0.jar" &
echo "Then start: user product stock cart coupon order pay logistics search admin"
echo "Agent: cd $BACKEND/Simlect-agent && uvicorn app.main:app --host 0.0.0.0 --port 7050"
echo "See deploy/GO_LIVE.md for full checklist."

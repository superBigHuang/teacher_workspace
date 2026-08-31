#!/bin/bash
# 查看服务运行状态与端口监听
# 用法: bash status.sh
source "$(dirname "$0")/env.sh"

echo "===== systemd 服务 ====="
for svc in "$SERVICE_NAME" nginx mysql; do
  printf "%-18s %s\n" "$svc" "$(systemctl is-active $svc 2>/dev/null) / 开机自启:$(systemctl is-enabled $svc 2>/dev/null || echo n/a)"
done

echo "===== 端口监听 ====="
ss -tlnp 2>/dev/null | grep -E ":(80|$BACKEND_PORT|3306)\b" | awk '{print $4, $6}' || echo "未发现监听端口"

echo "===== HTTP 探测 ====="
curl -s -o /dev/null -w "前端  http://127.0.0.1/            -> %{http_code}\n" --connect-timeout 5 http://127.0.0.1/ || true
curl -s -o /dev/null -w "后端  /api/auth/login (POST)     -> %{http_code}\n" --connect-timeout 5 \
  -X POST -H "Content-Type: application/json" -d '{"username":"x","password":"x"}' \
  http://127.0.0.1:$BACKEND_PORT/api/auth/login || true

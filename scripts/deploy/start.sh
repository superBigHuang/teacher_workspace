#!/bin/bash
# 启动后端服务与 nginx
# 用法: bash start.sh
set -e
source "$(dirname "$0")/env.sh"

[ -f "$JAR_PATH" ] || { err "后端未构建（$JAR_PATH 不存在），请先运行 build.sh"; exit 1; }
[ -d "$DIST_DIR" ] || { err "前端未构建（$DIST_DIR 不存在），请先运行 build.sh"; exit 1; }

log "启动后端服务 ${SERVICE_NAME} ..."
systemctl daemon-reload
systemctl enable "$SERVICE_NAME" >/dev/null 2>&1
systemctl start "$SERVICE_NAME"

log "启动/重载 nginx ..."
systemctl enable nginx >/dev/null 2>&1
nginx -t
systemctl start nginx 2>/dev/null || systemctl reload nginx

sleep 3
bash "$DEPLOY_DIR/status.sh"
log "已启动：前端 http://<服务器IP>/ ，后端端口 $BACKEND_PORT"

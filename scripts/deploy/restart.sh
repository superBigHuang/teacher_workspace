#!/bin/bash
# 重启后端服务并重载 nginx（日常发布最常用）
# 用法: bash restart.sh
set -e
source "$(dirname "$0")/env.sh"

log "重启后端服务 ${SERVICE_NAME} ..."
systemctl daemon-reload
systemctl restart "$SERVICE_NAME"

log "重载 nginx ..."
nginx -t && systemctl reload nginx

sleep 3
bash "$DEPLOY_DIR/status.sh"
log "重启完成"

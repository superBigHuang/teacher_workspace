#!/bin/bash
# 停止后端服务与 nginx
# 用法: bash stop.sh
source "$(dirname "$0")/env.sh"

log "停止后端服务 ${SERVICE_NAME} ..."
systemctl stop "$SERVICE_NAME" 2>/dev/null || warn "后端服务未在运行"

log "停止 nginx ..."
systemctl stop nginx 2>/dev/null || warn "nginx 未在运行"

log "已停止（MySQL 不受影响）"

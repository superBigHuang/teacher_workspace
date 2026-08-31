#!/bin/bash
# 更新部署：拉取最新代码 -> 构建 -> 重启（日常发布一条命令）
# 用法: bash update.sh
set -e
source "$(dirname "$0")/env.sh"

[ -d "$APP_DIR/.git" ] || { err "代码未克隆，请先运行 setup.sh"; exit 1; }

log "拉取最新代码（git pull）..."
cd "$APP_DIR"
git config core.fileMode false
git pull

log "重新构建..."
bash "$DEPLOY_DIR/build.sh"

log "重启服务..."
bash "$DEPLOY_DIR/restart.sh"

log "更新完成"

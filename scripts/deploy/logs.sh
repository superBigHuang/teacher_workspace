#!/bin/bash
# 查看后端日志（实时跟踪，Ctrl+C 退出）
# 用法: bash logs.sh          实时跟踪
#       bash logs.sh 100      查看最近 100 行
source "$(dirname "$0")/env.sh"

if [ -n "$1" ]; then
  tail -n "$1" "$LOG_DIR/backend.log"
else
  tail -f "$LOG_DIR/backend.log"
fi

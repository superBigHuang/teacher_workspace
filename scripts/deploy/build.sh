#!/bin/bash
# 构建后端 jar 与前端静态文件
# 用法: bash build.sh
set -e
source "$(dirname "$0")/env.sh"

[ -d "$APP_DIR" ] || { err "代码未克隆到 $APP_DIR，请先运行 setup.sh"; exit 1; }
command -v "$MVN_BIN" >/dev/null 2>&1 || { err "Maven 不可用: $MVN_BIN"; exit 1; }
command -v "$NPM_BIN" >/dev/null 2>&1 || { err "npm 不可用: $NPM_BIN"; exit 1; }

log "构建后端（Maven 打包，跳过测试）..."
cd "$APP_DIR/backend"
"$MVN_BIN" -q -DskipTests package
# Spring Boot 重打包后 target 下唯一可执行胖 jar（.original 不匹配 *.jar）
BUILT_JAR="$(ls target/*.jar | grep -v sources | head -1)"
cp -f "$BUILT_JAR" "$JAR_PATH"
log "后端 jar 就绪: $JAR_PATH ($(du -h "$JAR_PATH" | cut -f1))"

log "构建前端（npm install + vite build）..."
cd "$APP_DIR/frontend"
"$NPM_BIN" install --no-audit --no-fund --silent
"$NPM_BIN" run build --silent
# 原子替换：先构建到新目录再切换，避免半成品被 nginx 访问
rm -rf "$DIST_DIR.new"
cp -r dist "$DIST_DIR.new"
rm -rf "$DIST_DIR"
mv "$DIST_DIR.new" "$DIST_DIR"
log "前端静态文件就绪: $DIST_DIR"

log "构建完成"

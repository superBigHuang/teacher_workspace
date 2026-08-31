#!/bin/bash
# 首次部署初始化：安装 MySQL/nginx、克隆代码、注册服务、构建并启动
# 用法: bash setup.sh
set -e
source "$(dirname "$0")/env.sh"

echo "=========================================="
echo " 班主任工作平台 —— 首次部署"
echo "=========================================="

log "1/6 安装 MySQL、nginx、git（apt 直连系统源）..."
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq mysql-server nginx git >/dev/null

log "2/6 配置 MySQL root 密码为 root（与 application.yml 一致，库由后端自动创建）..."
if ! mysql -uroot -proot -e "SELECT 1" >/dev/null 2>&1; then
  mysql <<'SQL'
ALTER USER 'root'@'localhost' IDENTIFIED WITH mysql_native_password BY 'root';
FLUSH PRIVILEGES;
SQL
  log "MySQL root 密码已设置"
else
  log "MySQL root/root 已可用，跳过"
fi
systemctl enable --now mysql >/dev/null

log "3/6 克隆代码到 $APP_DIR ..."
mkdir -p "$APP_HOME"
if [ -d "$APP_DIR/.git" ]; then
  warn "仓库已存在，跳过 clone（更新请用 update.sh）"
else
  git clone "$REPO_URL" "$APP_DIR"
fi

log "4/6 注册 systemd 后端服务..."
sed -e "s#__APP_HOME__#$APP_HOME#g" \
    -e "s#__JAVA_BIN__#$JAVA_BIN#g" \
    -e "s#__JAR_PATH__#$JAR_PATH#g" \
    -e "s#__LOG_DIR__#$LOG_DIR#g" \
    "$DEPLOY_DIR/teacher-backend.service" > /etc/systemd/system/${SERVICE_NAME}.service

log "5/6 注册 nginx 站点..."
sed -e "s#__DIST_DIR__#$DIST_DIR#g" \
    -e "s#__BACKEND_PORT__#$BACKEND_PORT#g" \
    "$DEPLOY_DIR/nginx-teacher.conf" > /etc/nginx/conf.d/teacher-workplatform.conf
rm -f /etc/nginx/sites-enabled/default
nginx -t

log "6/6 构建并启动..."
bash "$DEPLOY_DIR/build.sh"
bash "$DEPLOY_DIR/start.sh"

log "首次部署完成！浏览器访问: http://<服务器IP>/  （登录密码见 README）"

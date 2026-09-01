#!/bin/bash
# 申请 Let's Encrypt 免费 HTTPS 证书并配置 nginx（自动 80 -> 443 跳转、自动续期）
#
# 前置条件（缺一不可）：
#   1. 域名已完成 ICP 备案（大陆服务器）；
#   2. 域名 A 记录已解析到本机公网 IP；
#   3. 云安全组/防火墙已放行 443 端口。
#
# 用法:
#   bash ssl.sh                 # 无邮箱注册（证书到期无邮件提醒，靠自动续期）
#   bash ssl.sh you@example.com # 带邮箱（到期前会发提醒邮件）
set -e
source "$(dirname "$0")/env.sh"

NGINX_CONF="/etc/nginx/conf.d/teacher-workplatform.conf"

command -v certbot >/dev/null 2>&1 || {
  log "安装 certbot ..."
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq && apt-get install -y -qq certbot python3-certbot-nginx >/dev/null
}

# 确保 nginx 配置的 server_name 与域名一致（certbot 据此匹配 server 块）
if ! grep -q "server_name $SERVER_NAME" "$NGINX_CONF"; then
  sed -i "s/server_name .*;/server_name $SERVER_NAME;/" "$NGINX_CONF"
fi
nginx -t && systemctl reload nginx

EMAIL_ARG="--register-unsafely-without-email"
[ -n "$1" ] && EMAIL_ARG="-m $1"

log "为 $SERVER_NAME 申请证书并配置 HTTPS ..."
certbot --nginx -d "$SERVER_NAME" \
  --non-interactive --agree-tos $EMAIL_ARG --redirect

# 启用自动续期（certbot 包通常已自带 systemd timer，这里确保开启）
systemctl enable --now certbot.timer >/dev/null 2>&1 || true
log "验证自动续期 ..."
certbot renew --dry-run >/dev/null 2>&1 && log "自动续期正常" || warn "续期演练异常，请检查 certbot"

log "HTTPS 配置完成：https://$SERVER_NAME （HTTP 已自动跳转 HTTPS）"

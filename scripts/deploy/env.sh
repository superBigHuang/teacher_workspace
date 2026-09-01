#!/bin/bash
# 班主任工作平台 —— 部署公共变量与工具函数
# 被其他脚本 source 使用，不单独执行

# ===== 部署目录布局 =====
export APP_HOME="/opt/teacher_workspace"     # 部署根目录
export APP_DIR="$APP_HOME/app"               # git 仓库（源码）
export JAR_PATH="$APP_HOME/backend.jar"      # 后端可执行 jar（固定名，systemd 引用）
export DIST_DIR="$APP_HOME/dist"             # 前端静态文件（nginx root）
export LOG_DIR="$APP_HOME/logs"              # 日志目录

# ===== 仓库与服务 =====
export REPO_URL="git@gitee.com:superBigHuang/teacher_workspace.git"
export BACKEND_PORT=8088
export SERVICE_NAME="teacher-backend"
# 访问域名（ICP 备案通过后用于 HTTPS；未备案/仅 IP 访问时不影响 HTTP）
export SERVER_NAME="${SERVER_NAME:-teach.pagekithub.com}"

# ===== 工具链（vfox 管理）=====
# 脚本自包含：不依赖登录 shell 的 profile.d，直接导出 JAVA_HOME 与 PATH
export JAVA_HOME="$HOME/.vfox/sdks/java"
export PATH="$JAVA_HOME/bin:$HOME/.vfox/sdks/maven/bin:$HOME/.vfox/sdks/nodejs/bin:$PATH"
export JAVA_BIN="$JAVA_HOME/bin/java"
export MVN_BIN="$HOME/.vfox/sdks/maven/bin/mvn"
export NPM_BIN="$HOME/.vfox/sdks/nodejs/bin/npm"

mkdir -p "$LOG_DIR"

log() { echo -e "\033[32m[deploy]\033[0m $*"; }
warn() { echo -e "\033[33m[warn]\033[0m $*"; }
err() { echo -e "\033[31m[error]\033[0m $*" >&2; }

# 脚本所在目录（templates 与各脚本同级）
DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

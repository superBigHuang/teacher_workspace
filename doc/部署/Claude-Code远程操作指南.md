# Claude Code 远程操作服务器指南

> 面向「在另一个 Claude Code 窗口里操作线上服务器」的交接文档。新窗口可直接读本文件上手。
> 最近更新：2026-09-16

## ★ 快速开始

### A. agent 就跑在服务器本机（如 Hermes，推荐用这段，自包含）

> 注意：本指南文件在本地是未提交文件，**服务器仓库里没有**，服务器本机 agent 读不到它。所以给它的提示词必须自包含，不能让它"去读仓库里的 doc"。直接整段复制：

```
你是这台 Linux 服务器的运维 agent（你已运行在服务器上，所有命令直接执行，不要用 ssh 连本机）。任务：把「中学班主任工作平台」更新到 Gitee master 最新版本并验证。全部回复用中文。

【环境（都在本机）】
- 应用根目录：/opt/teacher_workspace ；git 仓库：/opt/teacher_workspace/app
- 后端 jar：/opt/teacher_workspace/backend.jar；前端：/opt/teacher_workspace/dist；外部配置：/opt/teacher_workspace/application.yml；上传目录：uploads/；日志：logs/backend.log
- systemd 服务：teacher-backend(端口 8088，仅 127.0.0.1)、nginx(80/443)、mysql(3306，仅 127.0.0.1)
- 唯一公网入口：https://www.novastarhub.cn （直连公网 IP 打不开是正常的，已加固）；同机还跑着一个 PHP 图床(easyimagehub.duckdns.org)，与本系统共存，不要动它。
- 数据库：MySQL 8，库名 teacher_workspace，root/root。

【部署步骤】
1. 先看状态：bash /opt/teacher_workspace/app/scripts/deploy/status.sh；并 cd /opt/teacher_workspace/app && git log --oneline -3 && git status -sb，确认当前在 master、工作区干净。
2. 发布：cd /opt/teacher_workspace/app && git config core.fileMode false && bash scripts/deploy/update.sh 2>&1 | tail -50（它会 git pull→mvn 打包→npm 构建→原子切 dist→重启后端+reload nginx，约 1~2 分钟）。
3. 发布后逐项验证（整段跑）：
for i in $(seq 1 25); do code=$(curl -s -o /dev/null -w "%{http_code}" -X POST http://127.0.0.1/api/auth/login -H "Content-Type: application/json" -d "{}" || true); [ "$code" != "000" ] && break; sleep 2; done; echo "后端就绪:$code（200/401 正常，000=没起来）"
cd /opt/teacher_workspace/app && echo "== 本次提交 ==" && git log --oneline -5
for u in / /boss /radish /pet; do curl -s -o /dev/null -w "$u -> %{http_code} %{content_type}\n" http://127.0.0.1$u; done
echo "-- /uploads 应是 application/json，不是 text/html --"; curl -sI http://127.0.0.1/uploads/pets/__probe__.png | grep -i content-type
echo "-- 外部配置存活（应看到 trust-proxy: true 和 gpt-5.4）--"; grep -E "trust-proxy|model:" /opt/teacher_workspace/application.yml
curl -s -o /dev/null -w "域名 HTTPS -> %{http_code}\n" https://www.novastarhub.cn/
4. 汇报：本次新上线的提交区间（旧 HEAD..新 HEAD）、每一项验证结果。任何一项不对就停下报我。

【红线（必须遵守）】
- 只做运维：跑脚本、重启、看日志、只读排查。不编辑 app/ 里任何源码，不执行 git commit/reset/checkout/merge，不在服务器切 feature 分支。服务器永远只跟踪 master。
- update.sh 拉的是 master。若本次要发的功能还没合并 push 到 origin/master，停下告诉我，不要在服务器上合并或切分支。
- 数据库：只读 SELECT 可以；任何 INSERT/UPDATE/DELETE/DDL 一律先把 SQL 写清楚交给我本人执行，你不要直接改数据。
- 不要删/改 /opt/teacher_workspace/application.yml（权限 600，含密钥）和 uploads/ 目录。
- 不要覆盖 nginx 配置里的手工定制：/uploads/ 反代、/boss 与 /radish 的 4 行 SPA 回退、.mp4 的 7 天缓存；不要删 80/443 那两个 default_server（return 444 和 ssl_reject_handshake，用来拒绝直连 IP）。改 nginx 前先 cp 备份，nginx -t 通过才 reload。
- 8088 不对公网；不要改云安全组/防火墙。健康检查只用 127.0.0.1。
- 测登录安全机制只用不存在的假账号（如 __probe__），绝不要拿 admin 试错密码（连错 3 次要验证码、5 次锁 10 分钟）。
- 任何不可逆操作（rm -rf、DROP、systemctl disable、force 操作）先问我。失败就停下，不要自行回滚。
- 临时脚本放 /root 下，不要放进 app/ 仓库；脚本用 LF 换行。

读完先用三句话复述你理解的红线，并报告第 1 步的状态，等我说"开始"再执行第 2 步发布。
```

### B. agent 在你自己电脑上、通过 ssh 操作服务器

此时 agent 能读到本地仓库里的这份文档，用短提示词即可。把下面整段发给它：

```
你是我的服务器运维助手。先读仓库里的 doc/部署/Claude-Code远程操作指南.md，之后只通过 ssh root@124.221.186.190 '...' 操作线上服务器，严格遵守该文档第 4 节「红线」。

本次任务：拉取 Gitee master 最新代码并重新部署「中学班主任工作平台」。
1. 先 bash scripts/deploy/status.sh 看三服务状态
2. 按文档 §2 跑 update.sh 发布
3. 用 §2 的「发布后验证」脚本逐项核对：后端就绪轮询、本次 git log、/ /boss /radish 为 200、/uploads 返回 application/json、外部配置 trust-proxy 存活、https://www.novastarhub.cn 正常
4. 汇报本次新上线的提交区间和每项验证结果

约束：任何一步失败就停下报我，不要自行 git reset/回滚/改安全组；不改业务代码；不直连写库（要改数据先把 SQL 给我本人执行）；不动 application.yml 和 uploads；不覆盖 nginx 手工定制（/uploads、boss/radish 回退、mp4 缓存）和两个加固 default server；8088 不对公网；测登录机制只用不存在的假账号，别锁 admin。

注意：服务器 update.sh 拉的是 master。开始前先确认本次要发的提交已合并并 push 到 origin/master；若还在 feature 分支，告诉我，不要在服务器上切分支。读完文档后，先用三句话复述你理解的红线，再动手。
```

> 日常复用：每次新窗口只发这段即可，细节都在本文档。要发的功能必须先进 master——服务器永远只跟踪 master，不在服务器上切 feature 分支。

## 0. 服务器速览

| 项 | 值 |
|---|---|
| 公网 | `root@124.221.186.190`（腾讯云 Ubuntu 24.04，大陆机房） |
| 访问 | **https://www.novastarhub.cn/**（唯一正式入口，已备案+HTTPS；公网直连 IP 的 80/443 已被 nginx 拒绝、ping 已禁） |
| 登录账号 | `admin`（密码由用户持有，**不写入任何文档**；默认 123456 早已失效） |
| 应用目录 | `/opt/teacher_workspace/`：`app/`(git 仓库)、`backend.jar`、`dist/`(前端)、`application.yml`(外部配置)、`uploads/`(用户上传)、`logs/` |
| 代码仓库 | Gitee `superBigHuang/teacher_workspace`，服务器用只读 DeployKey 拉取 |
| systemd 服务 | `teacher-backend`(8088，仅本机)、`nginx`(80)、`mysql`(3306，仅本机) |
| 运维脚本 | `/opt/teacher_workspace/app/scripts/deploy/`：`update.sh`(日常发布)、`status.sh`、`logs.sh`、`restart.sh`、`build.sh`、`ssl.sh`(备案后) |
| 数据库 | MySQL 8，库名 `teacher_workspace`，root/root，**只监听 127.0.0.1** |

---

## 1. 两种连接方式（任选）

### 方式 A：本机开 Claude Code，用 ssh 远程操作（推荐）

Claude Code 跑在你电脑上，通过 `ssh`/`scp` 对服务器发指令。服务器不需要能访问 Claude 服务，最安全。

1. 新开一个 Claude Code 窗口（同机已配免密，直接能用）。
2. 第一句话给它：
   > 读 `doc/部署/Claude-Code远程操作指南.md`，然后只通过 `ssh root@124.221.186.190 '...'` 操作服务器，遵守第 4 节红线。
3. 连通性自测：`ssh root@124.221.186.190 'bash /opt/teacher_workspace/app/scripts/deploy/status.sh'`

**换一台电脑用**：先把新电脑公钥加到服务器（本机已配可跳过）：
```powershell
# Windows PowerShell（新电脑上）
ssh-keygen -t ed25519                                   # 一路回车；已有密钥跳过
Get-Content $env:USERPROFILE\.ssh\id_ed25519.pub | ssh root@124.221.186.190 "mkdir -p ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys"
```
> 服务器上 `/root/.ssh/id_ed25519` 是拉 Gitee 的 DeployKey，**不要覆盖/删除**，只往 `authorized_keys` 追加。

### 方式 B：登录到服务器，在服务器里跑 claude

服务器已装 Node v26、`@anthropic-ai/claude-code`、`ccswitch`，并有 mihomo 代理（`127.0.0.1:7890`）。

```bash
ssh root@124.221.186.190
which claude claude.exe          # 确认命令名（历史上 bin 名可能是 claude.exe）
claude --version
# 若报网络错误，先导出代理再启动（交互登录通常已由 /etc/profile.d/proxy.sh 配好）：
export HTTPS_PROXY=http://127.0.0.1:7890 HTTP_PROXY=http://127.0.0.1:7890
claude
```
服务器内的 claude 直接碰服务器文件，风险更高：**让它在 `/root/` 下的临时目录工作，不要在 `/opt/teacher_workspace/app` 仓库里做写操作**（见红线 1）。

---

## 2. 日常发布（最常用）

前置：代码已由开发窗口 `git push` 到 Gitee master。

```bash
ssh root@124.221.186.190 'cd /opt/teacher_workspace/app && git config core.fileMode false && bash scripts/deploy/update.sh 2>&1 | tail -50'
```
`update.sh` 做：git pull → mvn 打 jar → npm 构建到 `dist.new` → 原子切 dist → 重启后端 + reload nginx。约 1~2 分钟。

**发布后验证（无需登录密码的一套，复制即用）**：
```bash
ssh root@124.221.186.190 'bash -s' <<"EOF"
for i in $(seq 1 25); do
  code=$(curl -s -o /dev/null -w "%{http_code}" -X POST http://127.0.0.1/api/auth/login -H "Content-Type: application/json" -d "{}" || true)
  [ "$code" != "000" ] && break; sleep 2
done
echo "后端就绪: $code（200/401 都算应答正常，000=没起来）"
echo "== 本次拉到的提交 =="; cd /opt/teacher_workspace/app && git log --oneline -5
echo "== 页面/资源 =="
for u in / /boss /radish /pet /boss/monster1.png; do
  curl -s -o /dev/null -w "$u -> %{http_code} %{content_type}\n" http://127.0.0.1$u
done
echo "== /uploads 反代存活（应 application/json，不是 text/html）=="
curl -sI http://127.0.0.1/uploads/pets/__probe__.png | grep -i content-type
echo "== 外部配置存活（model 应 gpt-5.4）=="
grep -E "trust-proxy|model:" /opt/teacher_workspace/application.yml
EOF
```
需要登录态的接口（dashboard 等）让用户在浏览器验证，或当次向用户索取密码，用完不要落盘。

---

## 3. 常用运维命令

```bash
ssh root@124.221.186.190 'bash /opt/teacher_workspace/app/scripts/deploy/status.sh'   # 三服务+端口+HTTP 探测
ssh root@124.221.186.190 'bash /opt/teacher_workspace/app/scripts/deploy/logs.sh 200'  # 最近 200 行后端日志
ssh root@124.221.186.190 'systemctl restart teacher-backend'                           # 只重启后端（改外部配置后）
ssh root@124.221.186.190 'nginx -t && systemctl reload nginx'                          # 改 nginx 后（先 -t）
ssh root@124.221.186.190 'ss -tlnp | grep -E "80|8088|3306"'                           # 看端口监听
```

**回滚**：让开发窗口 `git revert <坏提交>` 并 push，再跑一次 `update.sh`。不要在服务器上 `git reset --hard`/强制拉取覆盖。

**动效/大屏调试的正确姿势**：服务器是运行环境，不是开发环境——不要直接改 `/opt/teacher_workspace/app` 里的源码（会造成 git 脏工作区，下次 update 冲突，且不入库会丢）。动画改动走「本地 `frontend/` 开发（`npm run dev`）→ push → update.sh」。上线后强刷浏览器（Ctrl+F5）；构建文件名带 hash，正常会自动更新。大屏路由：`/boss`(打怪兽)、`/radish`(拔萝卜)、`/pet`(宠物园)、`/m`(手机遥控)。

---

## 4. 红线（必须遵守）

1. **只做运维**（发布/重启/日志/只读排查/验证）。业务代码由开发窗口在本地改并 push；不在服务器 `app/` 里编辑源码、不 `git commit/reset/checkout`。
2. **不直连数据库做写操作**。MySQL 不给远程；需要改数据/改表时，把 SQL 脚本交给用户本人执行。只读排查也优先走接口。
3. **外部配置 `/opt/teacher_workspace/application.yml`**（权限 600，含 JWT 密钥/数据库/AI Key）不入库、不删、不整体覆盖；`update.sh` 不会动它。确需修改：先 `cp` 备份、保持两空格 YAML 缩进、改后 `systemctl restart teacher-backend`。
4. **`uploads/` 不随 git**，是用户实际上传图片，删除/迁移前必须先问并备份。
5. **nginx 线上配置有三处手工定制**（仓库模板可能没同步，别用模板整体覆盖线上）：
   - `location /uploads/` 反代到 8088；
   - `location = /boss`、`/boss/`、`/radish`、`/radish/` 四行强制回退 SPA（与同名静态资源目录冲突，否则大屏 403）；
   - 主站 443 块的 `location ~* \.mp4$ { expires 7d; }`（大屏视频 7 天缓存）。
   改 nginx：先 `cp 配置 配置.bak.时间戳`，再 `nginx -t`，通过才 `reload`。配 HTTPS（certbot 会复制 server 块）后核对这两处在 443 块也存在。
6. **8088 端口绝不对公网开放**（安全组只放 22/80/443）。外部配置 `app.trust-proxy=true` 依赖「只经过 nginx」，一旦 8088 暴露，攻击者可伪造 X-Forwarded-For 绕过登录失败锁定。
7. **源站已加固（勿回退）**：只经域名 `https://www.novastarhub.cn` 提供服务；nginx 的 80/443 各有一个 default server 拒绝直连 IP/陌生 Host（`return 444`、`ssl_reject_handshake on`），服务器已禁 ICMP（ping 不通）。不要删掉这两个 default server、不要把公网 IP 加回业务块的 `server_name`、不要放开 ICMP。健康检查在**服务器本机**用 `127.0.0.1`（仍 200），公网 IP 打不开是正常的。改云安全组/防火墙前先问用户。
8. **测试登录安全机制用不存在的假账号**（如 `__probe__`）。别拿 `admin` 试错密码——连续失败 3 次要验证码、5 次锁 10 分钟（账号+IP 维度）。
9. 任何不可逆动作（`rm -rf`、`git reset --hard`、DROP/DELETE、`systemctl disable`、改安全组/防火墙）先向用户确认。

---

## 5. 已知坑

- **update.sh 尾部探测显示 `-> 000`**：多为重启瞬间的竞态，不代表失败。用第 2 节的就绪轮询复核（通常 1~2 次就 200）。
- **nginx reload 后几秒内仍命中旧行为**：旧 worker 有 graceful 窗口，等几秒再验证，别急着判故障。
- **非交互 ssh 不加载 `/etc/profile.d/`**（vfox 的 java/mvn/node、代理）。`update.sh` 已自包含导出 PATH；自己临时跑 mvn/npm/claude 报"命令不存在"或网络错时，先 `source /etc/profile.d/proxy.sh`，或用绝对路径 `/root/.vfox/sdks/...`。
- **Windows 远程执行**：复杂逻辑写成 .sh 用 `scp` 传上去跑，先 `sed -i 's/\r$//'` 去 CRLF；远程命令里的 `$变量` 易被本地 PowerShell 解析，用单引号包住远程脚本或 heredoc。
- **`git pull` 被文件权限改动阻塞**：`update.sh` 已执行 `git config core.fileMode false`；手动进去操作也先确保该配置存在。
- **前端裂图**：内置素材在 `frontend/public/`（pets/boss/radish），必须先 push 再 update 重新构建进 dist 才会更新；返回 `text/html` 当图片即 dist 缺文件。自创皮肤走 `/uploads/`（后端静态映射），裂图查第 4 节红线 5 的反代。

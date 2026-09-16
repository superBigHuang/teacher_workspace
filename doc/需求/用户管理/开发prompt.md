# 开发任务书：用户管理（多账号 + 角色 + 注册渠道）

> 本文是自包含开发 prompt：你是开发执行者，需求分析已完成，按本文实现即可，不要重新做需求决策。
> 基线文档（必读，设计细节以此为准）：`doc/需求/用户管理/需求说明.md`
> 分支：从 **master** 新建 `feature/user-admin`（不要在当前 `feature/quiz-bank-studio` 上开发）。

## 0. 一句话需求

admin 登录后出现"用户管理"页：查看账号列表、新增账号（选角色、选注册渠道）、重置密码为 123456、停用/启用账号、切换角色。新账号登录后看到自己独立的空班级（数据隔离已存在，不要重做）。

## 1. 项目环境

- 仓库根：`C:\java_workspace\teacher_workspace`
- 后端：`backend/`，Spring Boot 3.3.5 + JDK17 + JdbcTemplate（**不是 MyBatis/JPA**）+ MySQL，端口 8088，上下文路径带 `/api`
- 前端：`frontend/`，Vue3 `<script setup>` + Element Plus + Vite，dev 端口 5173
- 数据库由后端启动自动建表/迁移（`schema.sql` + `DataInitializer`），**不需要手工执行 DDL**
- 统一响应包装：参照现有 Controller（返回 `{code:200,...}` 风格，先读一个 Controller 确认）
- 密码加密：`new BCryptPasswordEncoder()`（spring-security-crypto，无 Security 过滤链）

## 2. 已确认决策（不要改动）

1. 角色两档：`ADMIN`（管理员，有用户管理）/ `TEACHER`（普通老师，默认；业务功能全开，仅差用户管理入口）。
2. 初始密码与重置密码统一 **123456**。
3. 注册渠道建号时**必选三选一**：`friend`=朋友介绍 / `xhs`=小红书 / `school`=同校老师。
4. 账号**只停用不删除**，不提供 DELETE 接口；业务数据永久保留。
5. 初始 admin 账号固定 ADMIN，不可被停用/降级。

## 3. 现状锚点（已核实，直接用）

### 后端

- `backend/src/main/resources/schema.sql:6` —— `sys_user` 建表（现仅 id/username/password_hash/display_name/email/school/created_at）
- `backend/src/main/java/com/teacher/platform/config/DataInitializer.java`
  - `ensureColumn(...)` 幂等加列模式（约 :53-54，email/school 就是这么补的）——本次 4 个新列照此办理
  - admin 播种约 :415-419（表空才插）；启动后取 admin id 调 UserDataSeeder（约 :421-425）
- `backend/.../common/AuthInterceptor.java:22-42` —— JWT 校验后 `request.setAttribute("uid", ...)` + username；注册在 `config/WebConfig.java:38-42`（放行 /api/auth/login、/api/auth/captcha、/api/remote/events）
- `backend/.../common/JwtUtil.java:45` —— claim 只有 uid+username（**不需要改 JWT**）
- `backend/.../controller/BaseController.java:15` —— `protected Long uid()`，全部 Controller 继承它
- `backend/.../auth/AuthController.java`
  - 登录 :50-98（查询 :76-77，成功响应 :93 `{token,displayName,username}`；已有失败锁定/验证码逻辑勿动）
  - `GET /auth/me` :130-136；`POST /auth/change-password` :139-151
- `backend/.../config/UserDataSeeder.java:21` —— `seedIfEmpty(uid, name)` 新账号默认数据播种，建号成功后调用
- `backend/.../controller/AdminController.java` —— 现有 `/api/admin/reset-business` 无角色校验（**不要动它**，但新 Controller 必须真鉴权）

### 前端

- `frontend/src/router/index.js:21-33` —— MainLayout children 路由；:40-45 beforeEach 仅查 token，放行 /login、/m、/radish
- `frontend/src/layout/MainLayout.vue:174-192` —— `navs` 菜单数组（普通项与 group 分组两种结构），渲染 :19-43；底部用户区 :49-64
- `frontend/src/views/Login.vue:63-68` —— saveAuth 存 localStorage：`token`/`displayName`/`username`（手机端 `Mobile.vue:333-338` 同 key）
- `frontend/src/api/http.js` —— baseURL `/api`，自动带 Bearer，code!==200 报错，401 清 token 跳登录
- 页面范式照搬 `frontend/src/views/QuizManage.vue`（筛选栏 + el-table + el-pagination + 新增弹窗 + 行内 link 按钮 + ElMessageBox 二次确认）
- `frontend/src/views/Settings.vue:425` —— 调接口/表单校验风格参考

## 4. 实现清单

### 4.1 后端

1. **sys_user 加 4 列**（在 DataInitializer 用 ensureColumn 幂等迁移，启动自动生效）：
   ```sql
   role VARCHAR(16) NOT NULL DEFAULT 'TEACHER'
   enabled TINYINT NOT NULL DEFAULT 1
   source VARCHAR(16) NULL          -- friend/xhs/school
   last_login_at DATETIME NULL
   ```
   迁移后把存量 admin 置为 ADMIN：`UPDATE sys_user SET role='ADMIN' WHERE username='admin'`（幂等，仅在 role 还是默认值或启动迁移段内执行一次，参考现有播种写法）。
2. **AuthInterceptor**：解析 uid 后增加一次主键查询 `SELECT role, enabled FROM sys_user WHERE id=?`；查无或 enabled=0 → 写 401 JSON（msg 明确"账号已停用，请联系管理员"）；把 role 放入 request attribute。
3. **BaseController**：加 `protected String role()` 与 `requireAdmin()`（非 ADMIN 抛 403，返回 JSON 错误体，风格与现有异常/错误响应一致）。
4. **新增 `controller/AdminUserController.java`**，`@RequestMapping("/api/admin/users")`，每个写/读方法开头 `requireAdmin()`：
   - `GET ""` 列表：id/username/displayName/role/enabled/source/createdAt/lastLoginAt，按 id 升序，账号量小不分页
   - `POST ""` 新增：入参 username（必填、唯一，冲突返回明确错误）、displayName、role（默认 TEACHER）、source（必填，枚举校验 friend/xhs/school）；密码固定 BCrypt(123456)；入库后调 `UserDataSeeder.seedIfEmpty(新id, displayName)`
   - `POST "/{id}/reset-password"`：重置 BCrypt(123456)
   - `PUT "/{id}/status"`：enabled 0/1 切换
   - `PUT "/{id}/role"`：ADMIN/TEACHER 切换
   - **自我保护**：当前登录人不能停用自己、不能把自己降级（后端硬校验返回 400）；username='admin' 不可停用/降级（同样硬校验）
5. **AuthController 登录**：查询加 enabled=1 条件，停用账号返回"账号已停用，请联系管理员"；登录成功 UPDATE last_login_at；登录响应与 `/auth/me` 返回体增加 role/enabled/source。

### 4.2 前端

1. **新增 `frontend/src/views/Users.vue`**（照 QuizManage.vue）：
   - 表格列：账号、显示名、角色（el-tag）、渠道（朋友介绍/小红书/同校老师 中文映射 tag）、状态（启用/停用）、创建时间、最近登录、操作
   - 操作：重置密码（ElMessageBox 二次确认，成功提示"密码已重置为 123456"）、停用/启用（二次确认）、角色切换（下拉或按钮组，对自己/admin 隐藏）
   - 新增弹窗：账号*、显示名*、角色下拉（默认"普通老师"）、渠道下拉（必选）；不提供密码框（固定 123456）
2. **router/index.js**：children 加 `{ path: 'users', name: 'users', meta: { title: '用户管理' }, component: () => import('../views/Users.vue') }`；beforeEach 中非 ADMIN 访问 users 重定向 /dashboard（role 从 localStorage 读）。
3. **MainLayout.vue**：navs 中新增"用户管理"项（放在"设置"附近的系统分组，图标用 👥/⚙️ 风格的 emoji），仅 ADMIN 可见（navs 改 computed 或模板加 v-if）；进入页面时若 /auth/me 能取到 role 以其为准，localStorage 为缓存。
4. **Login.vue**（Mobile.vue 同步存 key 即可，手机页不做管理功能）：登录成功多存一个 `role`；http.js 401 拦截维持现状，停用提示用后端 msg。
5. 普通老师账号登录后侧边栏无该菜单，其余功能零影响。

### 4.3 不做的事（范围红线）

- 不做模块级细粒度权限、不做自助注册、不做删除账号、不做渠道统计报表
- 不改任何业务 Controller 的 SQL（student/pet/score 等 user_id 隔离已就绪）
- 不改全局共享表 period/quick_note_rule/school_dict 的共享策略（已知项，本期不动）
- 不改宠物、拔萝卜、题库等无关模块

## 5. 代码规范红线

- 所有新增方法、字段写**中文注释**；超过 30 行的方法写功能/参数说明。
- KISS：不引入新依赖、不造权限框架、不抽象用不上的扩展点。
- SQL 兼容 MySQL 5.7（禁 CTE/窗口函数）；只用 JdbcTemplate。
- **禁止写任何直连数据库的脚本**（JDBC 客户端/sqlcmd 等）；DDL 与种子数据只走 DataInitializer/schema.sql 应用启动机制。
- 提交只 add 本需求相关文件，**严禁 `git add -A`/`git add .`**。当前工作区有与本需求无关的改动（`doc/部署/部署手册.md` 修改、两张 `*_白底参考.jpg`、Claude-Code 运维指南 md），一律不要提交。
- commit message 结尾加：
  `Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>`
- E2E 如新增用例：用**固定测试账号**（如 `e2e_user_teacher`），可重复执行（建号接口对已存在用户名要先重置/复用或跳过），用例结束把账号停用状态复原；**不得物理删除任何业务数据，不得污染 admin 的真实学生**。

## 6. 验证（全部通过才算完成）

1. 后端编译测试：`cd backend && mvn -q compile`（有测试则 `mvn test`）。
2. 前端构建：`cd frontend && npm run build` 通过。
3. E2E：`npx playwright test --grep "用户管理"`（如新增），全站回归跑一遍现有用例确认无破坏。
4. 手动双账号走查（核心）：
   - admin 登录 → 侧边栏有"用户管理"；新增一个"普通老师/小红书"账号
   - 退出，用新账号/123456 登录 → 看不到用户管理菜单，手输 /users 被跳回工作台，看到的是独立空班级（不是 admin 的 40 个学生），排座/花名册/宠物等业务正常
   - 普通老师调 `/api/admin/users` 返回 403
   - admin 重置该账号密码 → 旧密码失效、123456 可登录
   - admin 停用该账号 → 该账号已登录页面下一次请求 401、重新登录提示"账号已停用"；启用后恢复
   - admin 对自己的停用/降级操作不可用（按钮隐藏且后端 400）
   - admin 升级前签发的旧 token 不强制重新登录

## 7. 完成后汇报格式

用中文简明汇报：①改动文件清单（后端/前端分组）②新增接口清单 ③验证命令与结果（粘贴关键输出）④手动走查结果 ⑤遗留问题/建议。不要改需求基线文档的决策，发现设计疑问先停下在汇报中提出。

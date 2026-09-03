# 开发分支开工任务书：消息推送（钉钉/企微 webhook + Bark）+ 两处 UI 修复

> 2026-09-03。粘贴给开发分支执行。
> **先读基线**：`doc/需求/消息推送/需求说明.md`（含已拍板决策，冲突以本任务书为准）。
> 本任务 = 后端推送服务 + 早 8 点定时 + 设置/待办配置界面 + 2 个 UI 修复。**不要**接请假/成绩/宠物事件（留开关位即可）。

---

## 一、已拍板决策（不要再问）

1. 渠道三个都做：**企业微信机器人 webhook（主推）、钉钉机器人 webhook（加签可选）、Bark（iOS）**。
2. 本期事件只做两个，都走**每天早 8:00 定时**：①待办到期/逾期汇总；②成就早报（先 scan 再推过去一天新成就）。其余事件（请假/成绩/宠物/每日早报）在 `push_events` 留键、设置卡留开关（标注"即将支持"，禁用或仅展示）。
3. 待办页点状态 chip **弹轻量配置窗**（不跳页）。
4. 多渠道**都发**。
5. webhook/key **不明文回显**（照抄 AI key 的掩码/`__clear__` 模式）。
6. 推送消息文案统一含"**班主任**"三字（兼容钉钉"关键词"安全设置）。

---

## 二、数据库（schema.sql 幂等新增，不加列、不改老表）

```sql
CREATE TABLE IF NOT EXISTS push_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  channel VARCHAR(16) NOT NULL,        -- wecom / dingtalk / bark
  biz_ref VARCHAR(64),                 -- 幂等键：todo-daily:2026-09-03 / ach:<saId>:<tier>
  ok TINYINT DEFAULT 1,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_push_user (user_id),
  KEY idx_push_biz (user_id, channel, biz_ref)
);
```

配置全部复用现有 `setting(user_id,k,v)`，新增键：
- `push_enabled`（'1'/'0'，总开关）
- `push_wecom_url`（企微机器人完整 webhook）
- `push_dingtalk_url`、`push_dingtalk_secret`（钉钉 webhook；加签 secret 可空）
- `push_bark_key`、`push_bark_server`（Bark key；server 可空，默认 `https://api.day.app`）
- `push_events`（JSON 字符串，本期 `{"todo":1,"achievement":1,"leave":0,"score":0,"pet":0,"daily":0}`）

---

## 三、后端

### 3.1 开启定时
- `PlatformApplication.java` 类上加 `@EnableScheduling`（`org.springframework.scheduling.annotation.EnableScheduling`）。

### 3.2 新建 `service/PushService.java`（@Service，JdbcTemplate + 注入 AchievementScanner）

**不要继承 BaseController**（定时线程无请求上下文）。所有方法显式传 `uid`。

- `String setting(Long uid, String k)`：`SELECT v FROM setting WHERE user_id=? AND k=?`，异常/空返回 ""。
- `boolean eventOn(Long uid, String event)`：解析 `push_events` JSON（用 Jackson ObjectMapper，参考 AchievementScanner 里的用法），缺省按 todo=1/achievement=1。
- 渠道配置判定：`wecom` 已配置 = `push_wecom_url` 非空；`dingtalk` = `push_dingtalk_url` 非空；`bark` = `push_bark_key` 非空。
- **核心方法**：`void pushToUser(Long uid, String title, String content, String bizRef)`
  - 读 `push_enabled`，非 '1' 直接返回。
  - 对三个渠道逐一：该渠道已配置 → 若 `bizRef` 非空且 `push_log` 已存在 `(uid,channel,bizRef)` 则跳过；否则调用对应 send；**发送成功**后 `INSERT INTO push_log(user_id,channel,biz_ref,ok)`。
  - 每个渠道独立 try/catch，失败 `log.warn` 记录、**不抛异常**（一个渠道挂不影响别的、不影响业务）。
- **测试方法**：`Map<String,Object> testPush(Long uid, String channel)`：向指定渠道发 `"🔔 测试成功，班主任工作台推送已连通 ✅"`，返回 `{ok:true}` 或 `{ok:false,message:错误摘要}`；不写 push_log。
- 三个底层发送（用 **JDK11 `java.net.http.HttpClient`**，不引新依赖；超时 10 秒）：
  - `sendWecom(url, content)`：POST url，body `{"msgtype":"text","text":{"content":content}}`，Content-Type application/json；解析返回 JSON `errcode==0` 为成功。
  - `sendDingtalk(url, secret, content)`：若 secret 非空，算加签——`timestamp=System.currentTimeMillis()/1000`，`stringToSign=timestamp+"\n"+secret`，HmacSHA256 签名后 Base64 再 URLEncode 得 `sign`，拼到 url `&timestamp=..&sign=..`；POST body `{"msgtype":"text","text":{"content":content}}`；返回 `errcode==0` 成功。
  - `sendBark(key, server, title, body)`：server 空用 `https://api.day.app`；GET（或 POST JSON 均可，简单用 GET）`server + "/" + key + "/" + enc(title) + "/" + enc(body)`；HTTP 2xx 且返回含 `"code":200` 视为成功（Bark JSON 有 code 字段，保守判 2xx 即可）。
  - 加签用 `javax.crypto.Mac`("HmacSHA256") + `java.util.Base64` + `URLEncoder.encode`。
- 方法 >30 行或新增方法补中文注释（项目约定）。

### 3.3 新建 `service/PushScheduler.java`（@Service）

- `@Scheduled(cron="0 0 8 * * ?")` 方法 `dailyPush()`：
  1. `SELECT DISTINCT user_id FROM setting WHERE k='push_enabled' AND v='1'` 得 uid 列表。
  2. 每个 uid try/catch 包裹（单用户报错不影响其他）：
     - **待办**（`eventOn(uid,"todo")`）：查 `SELECT title,due_date FROM todo WHERE user_id=? AND done=0 AND due_date IS NOT NULL AND due_date <= CURDATE() ORDER BY due_date`。分两组：`due_date = CURDATE()` 今日到期、`due_date < CURDATE()` 已逾期。拼成一条文案，如：
       `📋 班主任待办提醒\n【今日到期】①xxx ②xxx\n【已逾期】①yyy（N 条）`；两组都空则不发。`pushToUser(uid, "班主任待办", content, "todo-daily:"+今天日期)`。
     - **成就**（`eventOn(uid,"achievement")`）：先 `achievementScanner.scan(uid)`（幂等，算出隔夜新成就）；再查本学期未推过的新成就：
       `SELECT sa.id sid, sa.tier, sa.awarded_at, s.name sname, d.name dname FROM student_achievement sa JOIN student s ON s.id=sa.student_id JOIN achievement_def d ON d.id=sa.def_id WHERE sa.user_id=? AND sa.term=? AND sa.awarded_at >= DATE_SUB(NOW(), INTERVAL 26 HOUR)`（term 取法同 scanner：`termStats.termRange(uid)` 拼 `start~end`；可直接注入 TermStatsService）。
       内存过滤掉 push_log 已有 `ach:<sa.id>:<tier>` 的（按渠道无关的 bizRef 存在性判断一次即可）；拼文案 `🏅 班主任成就早报\n过去一天新成就：\n小明 劳动能手🥈\n小红 大满贯🥇（共 N 人次）`；空则不发；逐条/汇总调用 pushToUser，**汇总一条** bizRef 用 `ach-daily:{今天日期}` 去重整份早报，同时为每条写 `ach:<saId>:<tier>` 明细去重（避免升级/隔天重复）。
       - KISS 落地：早报整体用 bizRef `ach-daily:{date}` 保证一天一条；明细 `ach:<saId>:<tier>` 也写 push_log（三个渠道各写），这样窗口内已推过的人次不再进文案。实现：先查 push_log 里 `biz_ref LIKE 'ach:%'` 当天/窗口内已有的 saId:tier 集合，过滤。
  3. 全部异常 log.warn，不中断。

### 3.4 新建 `controller/PushController.java`（@RestController `/api/push`，继承 BaseController）

照抄 `AiController` 的掩码/upsert/`__clear__` 模式：
- `GET /config`：返回 `{enabled, events:{todo,achievement,leave,score,pet,daily}, wecom:{configured,hint}, dingtalk:{configured,hasSecret,hint}, bark:{configured,server}}`。configured 为布尔；hint 为后 4 位掩码（`••••xxxx`，复用 mask 逻辑）；**不返回完整 URL/key**。
- `PUT /config`：body 含 enabled、events（对象，存为 JSON 字符串到 `push_events`）、各渠道 url/key/secret。密钥类字段（wecom_url/dingtalk_url/dingtalk_secret/bark_key）：空串=不修改，`"__clear__"`=删除该键，非空=upsert。`push_enabled`、`push_bark_server`、`push_events` 正常 upsert（空 events 不动）。
- `POST /test`：body `{channel}`（wecom/dingtalk/bark），调 `pushService.testPush(uid(), channel)` 返回 `{ok,message}`。
- 私有 `upsertSetting/str/mask` 可从 AiController 复制（或抽到 BaseController，KISS 起见复制私有方法即可）。

---

## 四、前端（Vue3 `<script setup>` + Element Plus；http 拦截器已返回 body.data）

### 4.1 Settings.vue —— 新增「🔔 消息推送」卡片

- 在 `.cards` 网格里加一张 candy-card（可跨两列 `grid-column: 1 / -1`，内容较多）。
- 结构参考现有 AI 助手卡（load/save/test + el-alert 反馈）：
  - 顶部总开关 `el-switch`（push_enabled）+ 右侧教程链接 `<a href="https://www.kdocs.cn/l/cer5TG9NdxvQ" target="_blank">📖 不会配置？看图文教程</a>`。
  - 三个渠道分区（el-divider 或小标题），每个：一个输入框（企微/钉钉填 webhook、Bark 填 Key；已配置时显示占位 `已配置（••••xxxx）`，留空=不改动）+ 该渠道「发送测试」按钮（调 POST /test，成功 ElMessage.success、失败 ElMessage.error 显示后端 message）。钉钉多一个"加签 secret（选填）"输入框；Bark 多一个"自建服务器（选填，默认 api.day.app）"。
    - 文案傻瓜化：企微"在企业微信群里 添加群机器人→复制 Webhook 地址粘到这里"；钉钉同理；Bark"App Store 装 Bark，打开复制 Key"。
  - 事件开关 el-checkbox-group：✅待办到期提醒、✅学生成就早报（这两个可用）；⏳病假预警、⏳成绩导入、⏳宠物动态、⏳每日早报（disabled，标注"即将支持"）。
  - 底部「保存」按钮 → PUT /api/push/config；保存后 ElMessage 成功。
- onMounted 拉 `GET /api/push/config` 回填（只有掩码布尔，密钥框留空）。

### 4.2 Settings.vue —— 修复密码卡大片留白（一行 CSS）

- `.cards { ... }` 样式块加 **`align-items: start;`**（原来是默认 stretch，把矮的"修改密码"卡拉到和左列等高，下方一片白）。加后各卡按自身内容高度、顶对齐。

### 4.3 Todo.vue —— 推送状态 chip + 轻量配置弹窗

- 工具栏（标题/筛选附近右侧）加一个按钮/chip：
  - 已配置且 enabled：`🔔 推送已开启`（type success）；
  - 否则：`🔔 开启到期提醒`（type primary/warning，高亮引导）。
  - onMounted 拉 `GET /api/push/config` 得状态（复用，失败静默当未开启）。
- 点击弹 `el-dialog`「消息推送」轻量配置：
  - 渠道选择（el-radio：企业微信/钉钉/Bark）+ 对应 webhook/key 输入框 + 「发送测试」按钮 + 教程链接 + 一句"完整事件开关在 个人设置→消息推送"。
  - 「保存并开启」→ PUT /api/push/config（enabled=1 + 该渠道字段），成功后刷新 chip 状态、ElMessage 成功。
  - KISS：弹窗只做"选一个渠道 + 填地址 + 开启"；高级项（多渠道、事件开关）去 Settings 卡片。
- 新增待办选了截止日期且推送已开时，可在日期框下给一句灰色小字"将在到期当天早上 8 点提醒你 📲"（可选，低成本）。

### 4.4 Todo.vue —— 修复新增区错位/边框乱（截图1）

- 根因：`.add-card{display:flex;align-items:center}` 一行塞 `el-input(append 分类,max-width:520)+el-date-picker(width160,margin-left)+el-button(margin-left)`，超宽换行后 margin-left 错位、append 槽/日期/按钮高度圆角不统一。
- 改法（KISS）：
  - `.add-card :deep(.el-card__body)`（或直接 .add-card）用 `display:flex; flex-wrap:wrap; gap:10px; align-items:center;`，**去掉** date-picker/button 上的 `margin-left:10px`。
  - 内容输入框 `flex:1; min-width:220px`（占满剩余、可换行）；分类下拉、日期、添加按钮固定宽度。
  - 统一控件高度/圆角：给 input/date-picker/button 同一 size（如 default），append 槽沿用 Element 默认即可，确保三者高度一致、圆角统一（主题 14px 风格）；换行后 gap 保持对齐不错位。

---

## 五、E2E（e2e/tests/e2e.spec.js，临时数据 + afterAll 清理，不污染 admin 真实数据）

> **不要在 E2E 里真发第三方 webhook**（外网/密钥不可控）。推送的"发送成功"靠手动真机验证；E2E 只测配置存取/掩码/状态与 UI。

1. **推送配置**：`PUT /api/push/config` 带 `{enabled:true, events:{todo:1,achievement:1}, wecomUrl:'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=testkey123'}`；再 `GET /api/push/config` 断言 `enabled===true`、`wecom.configured===true`、且返回里**不含**完整 `testkey123`（掩码/不下发明文）。
2. **清理**：afterAll 发 `PUT /api/push/config` 带 `wecomUrl:'__clear__'`、`enabled:false`（或直接 DELETE setting 里 push_* 键对应的用户配置——优先用 __clear__ 接口），恢复未开启状态，避免 chip 残留"已开启"。
3. **Todo 页 UI**：打开待办页，chip 存在（未开启文案"开启到期提醒"）；点 chip 弹窗能打开、有渠道单选/输入框/教程链接。
4. **待办新增区**：断言输入框/日期/添加按钮都可见且在 `.add-card` 内（布局类，不强断言像素）。
5. **Settings 页**：`.cards` 计算样式 `align-items` 为 `start`（或断言"修改密码"卡高度不被拉伸到等于左列最高卡——简单断言 CSS 即可）；消息推送卡片存在、有总开关/三渠道/教程链接/事件开关。
6. 定时任务 8 点不在测试窗口触发，**不为定时写 E2E**；成就早报逻辑靠代码审查 + 手动验证。

---

## 六、工程约定

- 后端 JDK17 + Spring Boot 3.3.5 + JdbcTemplate；新增类/方法补中文注释，方法 >30 行写详细说明。HTTP 用 JDK11 `java.net.http.HttpClient`，**不加 Maven 依赖**。
- 改完重启后端（schtasks TeacherBackend / start-backend.bat；.bat 须 CRLF+ASCII——本任务一般不改 bat）。
- 前端 `npm run build` 必须通过。
- KISS：不接本期外的事件；不做主渠道；推送失败静默；密钥不明文回显。
- 提交只含本需求文件（后端 PlatformApplication/PushService/PushScheduler/PushController/schema.sql + 前端 Settings.vue/Todo.vue + E2E + 本文档），**不要**夹带工作区其他无关改动（nginx-conf、部署手册、models 素材删除等）。commit trailer：`Co-Authored-By: Claude Code <noreply@anthropic.com>`。
- 完成后在 `需求说明.md` 末尾补"落地结果"段（改了哪些文件、怎么验证的）。

## 七、手动验收（真机，E2E 覆盖不到）

1. 企微/钉钉/Bark 各配一个真实地址，点"发送测试"，手机收到测试消息。
2. 钉钉加签 secret 填了也能收到（验证签名）；不填用关键词方式时文案含"班主任"能收到。
3. 建一条截止日期=今天的待办 + 一条昨天截止未完成的待办，临时把 cron 改成近几分钟（或写个临时触发）验证早报文案：今日到期/已逾期分组正确、重复跑不重发（push_log 幂等）。
4. 成就早报：让某生新达标一个成就，scan 后早报含该生、重复跑不重复推。
5. 密码卡留白消失；待办新增区窄屏换行也对齐、边框统一。

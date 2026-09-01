# 开发分支开工任务书：宠物园 v1.1 —— 全屏进化演出 + 自定义宠物 + 特效增强

> 2026-09-01。粘贴给开发分支执行。
> 前置：v1 已交付（宠物园合并在座次表 Seat.vue，逻辑在 composables/usePets.js，后端 PetService/PetController）。
> 设计依据：`doc/需求/班级宠物园/进化演出与自定义宠物方案.md`、`特效方案.md`（先读）。

---

## 任务总览（3 块，约 3 天）

1. 全屏进化演出（座次表页，升级时 6 秒分镜，6 套特效主题）
2. 自定义宠物皮肤（老师上传 6 张图 + 选特效主题，内嵌 AI 造宠教程）
3. 日常特效增强（飘星/乌云下雨/今日之星/光环，替换头晕紫怪）

唯一新依赖：`npm i canvas-confetti`（~6KB）。**不引 three.js / 视频 / lottie。**

---

## 一、后端

### 1.1 新表 pet_skin（schema.sql 幂等）

```sql
pet_skin(id, user_id BIGINT NULL,        -- NULL=内置全局皮肤
  skin_key VARCHAR(32),                  -- 内置 'dragon'；自定义 'u'+id
  name VARCHAR(32), built_in TINYINT DEFAULT 0, fx_theme VARCHAR(16) DEFAULT 'gold',
  img_egg VARCHAR(255), img_1 VARCHAR(255), img_2 VARCHAR(255),
  img_3 VARCHAR(255), img_4 VARCHAR(255), img_5 VARCHAR(255),
  created_at DATETIME,
  UNIQUE KEY uk_skin_key (skin_key))
```
- 内置种子（DataInitializer 全局播种，不按用户复制）：dragon / 云小龙 / built_in=1 / fx_theme='gold' / img_* = `/pets/dragon_{egg,1..5}.webp`。
- pet_skin **不进** USER_TABLES 删用户数据逻辑（内置行 user_id NULL；自定义行随用户删——可加入 USER_TABLES 按 user_id 删）。

### 1.2 pet 表加列（幂等迁移）
- `evolve_pending TINYINT DEFAULT 0`：跨页面/自动联动导致升级时置 1。

### 1.3 PetService 改造
- `award(...)` 内部检测升级（levelOf(exp) 变大）时：`evolve_pending=1`；返回值携带升级信息（定义 record/Map：studentId, fromLevel, toLevel）。
- 每次加分最多升 1 级（阈值 40/100/180/280，单次 ±5），fromLevel=toLevel-1。
- 毕业流程清 evolve_pending。

### 1.4 PetController 接口变更/新增
- `GET /pet/pets` 返回新增：
  - 每只宠物带 `skinKey`、`fxTheme`（join pet_skin）；
  - `pendingEvolves: [{studentId, name, petName, toLevel, skinKey, fxTheme}]`（evolve_pending=1 的，fromLevel 前端用 toLevel-1）。
- `POST /pet/evaluate`、`POST /pet/evaluate-batch`、`POST /pet/score-awards/grant` 返回体新增 `levelUps: [{studentId, name, petName, fromLevel, toLevel, skinKey, fxTheme}]`（同步路径立即演出用）；这些路径 award 后**不置** pending（同步会演掉）；其余自动联动路径（考勤/作业/纪律/速记）置 pending。
- `POST /pet/evolve-seen {studentId}`：清 evolve_pending。
- `GET /pet/skins`：内置皮肤 + 本账号自定义皮肤，返回 `[{skinKey, name, builtIn, fxTheme, imgs:{egg,1,2,3,4,5}}]`。
- `POST /pet/skin`：`{name, fxTheme}` → 建行（user_id=uid，skin_key='u'+id，img 暂空），返回 skinKey。
- `POST /pet/skin/upload`：multipart，参数 skinKey + stage(egg/1/2/3/4/5) + file；校验：归属本账号、png/jpg/webp、≤5MB；落地 `{upload-dir}/pets/{uid}/{skinKey}_{stage}.{ext}`，更新对应 img_* 列，返回 `{url}`。
- `PUT /pet/skin/{id}`（改 name/fxTheme）、`DELETE /pet/skin/{id}`（被 pet 引用时禁删，返回提示）。

### 1.5 上传基建
- application.yml 加 `app.upload-dir: ${UPLOAD_DIR:./uploads}`；WebConfig 加 `addResourceHandlers`：`/uploads/**` → `file:./uploads/`（用配置路径，末尾带 /）。
- 注意 .gitignore 加 uploads/。

---

## 二、前端

### 2.1 全屏进化演出组件 `components/EvolutionShow.vue`
- fixed 全屏 overlay（z-index 3000+），props 接收一幕 `{studentId, name, petName, fromLevel, toLevel, skinKey, fxTheme}`，6 秒分镜，点击跳过：
  1. **0–0.5s 聚光**：黑色 65% 暗化遮罩；聚光灯（radial-gradient）打到目标座位——Seat.vue 座位格与未排座卡都加 `:data-sid="studentId"`，用 `document.querySelector('[data-sid="X"]')` + getBoundingClientRect 定位，目标格高亮放大；底部字幕"⚠ 进化能量汇聚中……"
  2. **0.5–1.5s 起飞**：克隆该格宠物图（img src 取皮肤 fromLevel 图），absolute 定位从座位位置 FLIP 弧线飞到屏幕中央放大到 ~280px（CSS transition transform 0.8s cubic-bezier，加轻微旋转）；原座位留淡光圈
  3. **1.5–2.5s 蓄力**：中央旧形态抖动加剧（shake keyframes）+ 脚下光环旋转加速 + 主题色光柱自顶部射下（linear-gradient 1.5s 淡入）+ 12~16 个粒子 emoji（按主题）从四周向中心汇聚
  4. **2.5–3.0s 爆闪**：白光层 opacity 脉冲 2 次（0.1s 间隔）；第 2 闪时 img 瞬间换 toLevel 形态图
  5. **3.0–4.5s 揭晓**：新形态 scale 0.6→1 弹簧（cubic-bezier 回弹）定格；confetti 三波（左斜/右斜/中散，主题色）；大字"✨ 进 化 成 功 ✨"，副标题"**{name} 的 {petName} 从 Lv.{fromLevel} 进化为 Lv.{toLevel}！**"
  6. **4.5–6s 回落**：中央图淡出，目标座位格金光闪 3 次（box-shadow 脉冲）
- 队列：usePets 新增 `evolveQueue` 与 `EvolutionShow` 实例；来源①同步接口 levelUps（evaluate/batch/grant 响应后入队）②/pets 的 pendingEvolves（loadPets 后入队，演完一个调 /pet/evolve-seen）；逐个播，幕间 0.3s。
- 6 主题色值（fxTheme → 光柱色 / confetti 色组 / 粒子 emoji）：
  - gold 祥云金（默认）：`#FFD98A` / `['#FFD700','#FFB347','#FFF6E5']` / ✨⭐
  - fire 烈焰红：`#FF8A5C` / `['#FF5A3C','#FF9A3C','#FFD23C']` / 🔥💥
  - ice 冰晶蓝：`#8ECFFF` / `['#7EC8FF','#C9ECFF','#FFFFFF']` / ❄️💎
  - thunder 雷霆紫：`#B78CFF` / `['#A06BFF','#FFE066','#FFFFFF']` / ⚡🌩
  - forest 翡翠绿：`#7FE0A8` / `['#6FD08C','#FFB3D9','#FFFFFF']` / 🍃🌸
  - candy 彩虹糖：`#FF9EC4` / `['#FF9EC4','#9EC8FF','#BFF0C8','#FFE08A','#D9B8FF','#FFC9A3']` / 🌈🎉

### 2.2 自定义宠物皮肤
- usePets：加载 `GET /pet/skins` 存 skins；`petImg(p)` 改为按 p.skinKey 查 skins 映射（egg/1..5），查不到兜底 dragon；领养 doAdopt 传选中的 skinKey。
- **领养弹窗改造**：顶部横向"选种族"皮肤卡（内置云龙 + 本账号自创皮肤，卡面显示 Lv5 形态缩略图 + 名称 + 主题色点），末尾一张"➕ 自创宠物"卡；选中后下方 6 阶段缩略图轮播预览；再起名确认。
- **自创宠物弹窗**（新组件 `SkinMakerDlg.vue`）：
  - 6 个上传槽位（🥚蛋 / Lv1~5），el-upload 自定义 http-request 调 `/pet/skin` 建皮肤拿 skinKey 后逐张 `/pet/skin/upload`；蛋可缺省（用默认 dragon_egg）；槽位显示上传后缩略图可重传。
  - 特效主题 6 选 1（色点+emoji+名称，选中高亮）。
  - **一键去白底**：图片选中后前端 canvas 处理——从四角 flood fill（BFS，RGB 与白色差 <30 视为背景，alpha 置 0，边缘 1px 羽化），处理完预览；若检测到背景非白（角落像素偏色）提示"背景较复杂，建议先用 remove.bg 抠图再上传"。
  - 教程折叠区「🎨 不会画？用 AI 生成教程」：工具推荐豆包/即梦；6 条阶段提示词（文案取自 `doc/需求/班级宠物园/AI绘图prompt.md` 的「★当前执行」章节，原样内嵌，每条带"复制"按钮）；规格说明（1:1、≥1024px、白底、全身居中留白 10%、风格后缀统一）。
  - 保存后皮肤出现在领养选择里。
- 荣誉榜 rank-img、详情弹窗、Roster 角标等所有 petImg 调用点统一走皮肤映射（grep `dragon_` 确认无写死路径残留，dragon_1.webp 仅出现在内置种子与领养弹窗默认预览）。

### 2.3 日常特效增强（替换 v1 头晕紫怪）
- 加分反馈：飘 `+N`（金色弹跳上升）+ 2~3 个 ⭐✨ 迸溅 + 宠物图 Q 弹（scale 1.1→0.95→1，0.4s）；扣分：飘灰红 `-N` + 图缩一下 + 💦 一滴汗淡出（FloatFx 扩展现有 floats）。
- 批量评价：被勾选卡错峰 0.1s 依次飘字。
- 当日状态（座位格与未排座卡都生效）：
  - dizzy 改为**小乌云**：头顶 ☁️ 缓慢浮动，每 3 秒掉一滴 💧（CSS translateY 下落循环），去掉 💫 紫怪
  - frozen：保留冷色滤镜冰蓝边框，加 3 片 ❄️ 雪花慢飘
  - broken：保留灰度裂纹
  - 新增 `starToday`（后端 /pets 计算：当日加分 log 合计 ≥5）：头顶 🌈 每 5 秒淡入 2 秒 + 卡片金边微闪（box-shadow 呼吸）
- 常驻气场：Lv4 脚下淡金光环（conic-gradient 圈，8s 旋转，opacity .25）；Lv5 光环 5s + 3~5 个 ✨ 随机淡入淡出 + 金色描边。
- 兑换成功：🪙🪙🪙 三枚金币抛物线蹦出（CSS keyframes）。

### 2.4 后端 /pets statusFlags 补充
- 现有 dizzy/frozen/broken 保留；新增 `starToday`（当日 source 为正分的 log 合计 ≥5）。

---

## 三、E2E（e2e/tests/e2e.spec.js 追加，临时测试生 + afterAll 清理）

1. 给临时生领养后加分至升级：断言全屏演出 overlay 出现、"进化成功"文案与"Lv.2"字样、confetti canvas 存在；点击跳过后消失、宠物图变为 dragon_2
2. 跨页 pending：API 直接造一次自动联动升级（或关页面场景模拟 pending=1）→ 刷新座次表 → 演出自动播放 → 调 evolve-seen 后 pendingEvolves 为空
3. 自创皮肤：建皮肤 → 上传 6 张（用 public/pets 现有 webp 代传）→ 选主题 → 新临时生领养该皮肤 → 断言图片 URL 为 /uploads/... 且 fxTheme 生效
4. 小乌云/今日之星标记：扣分后 dizzy 真、☁️ 元素可见；连加 5 分 starToday 真
5. npm run build 通过

## 四、约定
- 后端：extends BaseController（uid()）、JdbcTemplate、schema.sql 幂等（元数据判断加列）；后端重启用计划任务 `schtasks /end|/run /tn TeacherBackend`。
- 前端：Vue3 `<script setup>` + Element Plus + theme.css 马卡龙变量；动画只用 transform/opacity；confetti 用后销毁 canvas。
- 上传目录 uploads/ 不入 git。

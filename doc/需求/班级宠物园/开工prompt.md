# 开发分支开工任务书：班级宠物园 v1

> 2026-09-01。粘贴给开发分支执行。需求基线：`doc/需求/班级宠物园/需求说明.md`（先读全文）。

---

## 任务：班级宠物园（游戏化积分激励）v1

学生领养守护宠物，老师日常登记（考勤/作业/纪律/成绩）自动转化为宠物成长值，"记录即评价"零额外操作；满级毕业获徽章，积分可在小商店兑换。奶油糖果马卡龙风格。

### 〇、素材（已就位，勿引 three.js / 视频）

- 素材已在 `frontend/public/pets/`：`dragon_egg.webp` `dragon_1.webp` ~ `dragon_5.webp`（768px 透明底 WebP，全套 361KB）。
- 唯一宠物线 **云小龙**，`pet_key='dragon'`；形态由等级推导：未领养显示蛋 `dragon_egg.webp`，Lv1~5 对应 `dragon_1.webp`~`dragon_5.webp`。
- 用法：`<img :src="'/pets/dragon_' + (pet ? pet.level : 'egg') + '.webp'">`；卡片常驻 CSS 呼吸动画（上下浮动 + 轻微摇摆，keyframes 自写）。
- 蛋（未领养）也用 dragon_egg.webp + 呼吸动画。

### 一、数据库（schema.sql 幂等建表；新表全部加入 DataInitializer USER_TABLES 白名单）

6 张表，全部 user_id 隔离：

```sql
pet(id, user_id, student_id UNIQUE, pet_key VARCHAR(16), pet_name VARCHAR(32),
    level INT DEFAULT 1, exp INT DEFAULT 0, adopted_at DATETIME, graduated_count INT DEFAULT 0)
pet_rule(id, user_id, category VARCHAR(8), name VARCHAR(32), score INT, icon VARCHAR(8),
    enabled TINYINT DEFAULT 1, auto_source VARCHAR(32) DEFAULT NULL)  -- AUTO_ATTENDANCE_LATE 等，NULL=纯手动
pet_log(id, user_id, student_id, rule_id NULL, score INT, source VARCHAR(24),  -- MANUAL/AUTO_ATTENDANCE/AUTO_HOMEWORK/AUTO_DISCIPLINE/AUTO_SCORE
    biz_ref VARCHAR(64) NULL, remark VARCHAR(128), created_at DATETIME)
pet_badge(id, user_id, student_id, pet_key, pet_name, created_at DATETIME)
pet_shop_item(id, user_id, name VARCHAR(32), icon VARCHAR(8), price INT, stock INT DEFAULT -1)  -- -1=不限
pet_exchange(id, user_id, student_id, item_id, cost INT, status VARCHAR(8) DEFAULT '待兑现', created_at DATETIME)  -- 待兑现/已兑现
```

- UserDataSeeder 播种 pet_rule 预置评价项（~35 项，清单见需求说明 B 节：学习 11 项 +、行为 18 项 +、扣分项 8 个），新用户注册即有；admin 存量账号也 INSERT IGNORE 补齐。
- 设置项 `pet_auto_deduct` 默认 1（UserDataSeeder 播种）。

### 二、后端

**PetService 核心逻辑**：
- 升级曲线（累计 exp 阈值）：Lv2=40、Lv3=100、Lv4=180、Lv5=280；加分后重算 level，满级 exp 不溢出 280。
- 扣分：exp 下限 0、**不降级**；请假不扣分。
- `award(uid, studentId, ruleId或{score,icon,name}, source, bizRef, remark)`：biz_ref 幂等（同 source+biz_ref 已存在 log 则跳过，防重复扣分）；写 pet_log + 更新 pet.exp/level；未领养学生不产生积分（log 照写？——不，未领养跳过，领养后历史分不补）。
- 毕业 `graduate(uid, studentId)`：仅 Lv5 可毕业；graduated_count+1、写 pet_badge（pet_key/pet_name）、pet 重置为 level=1/exp=0（v1 只有 dragon，毕业即重新开始成长，pet_name 保留或清空让重新起名均可）。

**PetController（/api/pet，extends BaseController 取 uid()）**：
- `GET /pets`：全班宠物状态（studentId/name/gender + pet 或 null + level/exp/graduatedCount + 当日负面状态标记：dizzy/frozen/broken，后端按 pet_log 今日/近7天扣分算出）
- `POST /adopt` {studentId, petKey='dragon', petName}
- `POST /evaluate` {studentId, ruleId}；`POST /evaluate-batch` {studentIds:[], ruleId}（source=MANUAL）
- `POST /revoke` {logId}：回退该条 log 的 exp（不删业务记录；AUTO_* 也可撤，撤后 biz_ref 幂等不再自动补——撤回即删除该 log 行）
- `GET /logs` ?studentId&date：流水（含规则名/学生名/来源/时间）
- `POST /graduate` {studentId}
- `GET /ranking`：荣誉榜，按累计成长值（pet.exp + 毕业折算？v1 按 exp + graduated_count*280 总值）排序；前三金🥇银🥈铜🥉；后 1/3 不返回名次（只返回等级）
- 评价项：`GET /rules`、`POST /rule`、`PUT /rule/{id}`、`DELETE /rule/{id}`（预置项可停用/改分，不物理删预置也行——简单起见都物理删+停用字段并存）
- 商店：`GET /shop-items`、`POST /shop-item`、`PUT /shop-item/{id}`、`DELETE /shop-item/{id}`；`POST /exchange` {studentId,itemId}（校验 exp≥price、库存，扣 exp 写 pet_log source=MANUAL remark="兑换xxx"？——兑换扣分别写 log，单独 pet_exchange 即可，exp 扣减同步）、`PUT /exchange/{id}/fulfill`
- 成绩发奖：`GET /score-awards?examId=` 返回待发清单（该考试满分+3/达优秀线+2/较对比考试排名进步≥5名+2，与 ScoreController 现有优秀线设置复用；每名学生取最高一项不叠加）、`POST /score-awards/grant` {items:[{studentId,score,reason}]}（source=AUTO_SCORE，biz_ref='exam_{examId}' 幂等）

**自动联动钩子**（挂在现有登记保存处，统一走 PetService.award，开关 pet_auto_deduct=0 时跳过）：
| 来源 | 触发 | 分 | auto_source |
|---|---|---|---|
| 考勤 | 迟到 | -1 | AUTO_ATTENDANCE（biz_ref=attendance.id） |
| 考勤 | 缺勤 | -2 | AUTO_ATTENDANCE |
| 作业 | 未交 | -1 | AUTO_HOMEWORK（biz_ref=homework_check.id） |
| 纪律 | 违纪登记（每条涉及学生） | -2 | AUTO_DISCIPLINE（biz_ref=discipline_log.id） |
| 速记 | 解析落库上述三类 | 同上 | 随来源 |
| 请假 | 不扣分 | 0 | — |
- 考勤/作业/纪律记录被删除时，对应 AUTO log 一并删除并回退 exp（保持数据一致）。

### 三、前端

- 新增 `views/PetGarden.vue`，路由 `pet` + 侧边栏「🐾 宠物园」（马卡龙橙色调，位置在值日表附近）。
- **宠物卡网格**（复用 .stu-card 风格）：
  - 未领养：dragon_egg.webp + 「🥚 领养精灵」按钮 → 领养弹窗（展示 dragon_1 大图 + 起中文名输入，v1 只有一条龙）
  - 已领养：形态 webp（/pets/dragon_{level}.webp）+ CSS 呼吸浮动 + 宠物名 + Lv 徽章 + exp 进度条（当前/本级阈值）+ 学生名；负面状态特效（见下）
  - 点卡 → 详情弹窗：大图、等级、成长值、徽章墙（pet_badge 列表🥇）、最近流水、「评价」按钮；Lv5 显示「 🎓 毕业」按钮 → 毕业仪式弹窗（大图 + 金色光效 CSS 粒子 + 确认）→ 徽章+1 回蛋态
- **评价**：详情弹窗/卡片上「评价」→ 评价弹窗（分类 tab：学习/行为/健康/其他 + emoji 项卡片网格，点项即生效 +1/-2 飘字动画）；顶部「批量评价」→ 先勾选多张学生卡再选项，一次生效。
- **负面状态（短期，次日重置，纯前端按 /pets 返回标记渲染）**：
  - dizzy（当日有扣分 log）：宠物头顶转圈星星（CSS）+ 卡片轻微晃动
  - frozen（exp 被扣到 0 且当日有扣分）：图片蓝色滤镜（filter: hue-rotate/brightness 冷色调）+ 冰蓝边框
  - broken（近 7 天扣分累计 ≥100）：灰度滤镜 grayscale(1) + CSS 裂纹覆盖层（可用 linear-gradient 裂纹或 emoji 🩹）
  - 全部仅视觉，次日 log 日期翻篇自动消失
- **顶部按钮**：🏆 荣誉榜（弹窗：排名表前三奖牌 emoji、宠物名、等级、徽章数；后 1/3 无名次）、🛍 小商店（弹窗双 tab：商品管理（增删改名称/emoji/价格/库存）+ 兑换记录（选学生兑换、待兑现/点「兑现」））、⚙ 评价项管理（弹窗：分类列表增删改、停用开关）。
- **Roster 融合**：学生卡角标显示宠物小图（24px）+ Lv；档案抽屉新增「🐾 宠物」页签/区：形态图、等级/成长值、徽章墙、最近 5 条流水。
- **Settings.vue**：加「宠物自动扣分」开关（pet_auto_deduct），关闭后登记不自动扣。
- 成绩导入后发奖入口：Score.vue 考试操作区加「🎁 宠物发奖」按钮 → 弹窗调 /pet/score-awards 展示待发清单（学生/原因/分值）→ 勾选确认发放。

### 四、E2E（e2e/tests/e2e.spec.js 追加用例）

临时测试生全链路（afterAll 清理，不碰 40 名真实学生）：
1. 领养（断言卡片出 Lv1 图）
2. 手动加分到升级（连加至 exp≥40，断言 Lv2、形态图 dragon_2）
3. 批量评价多生
4. 自动联动：给临时生登记迟到 → 断言 pet_log 出现 -1 且 exp 回退；设置关开关后再登记不扣
5. 撤回评价 → exp 回滚
6. 小商店：建商品 → 兑换 → 积分扣减/库存扣减 → 兑现
7. 毕业：exp 加满到 Lv5 → 毕业 → 徽章数 +1、回 Lv1
8. Roster 角标/档案宠物区可见

### 五、约定

- 后端：extends BaseController（uid() 取 JWT）、JdbcTemplate、schema.sql 幂等（CREATE TABLE IF NOT EXISTS + 元数据判断加列）、新表进 DataInitializer USER_TABLES。
- 前端：Vue3 `<script setup>` + Element Plus + theme.css 马卡龙变量；axios 走现有封装。
- 不引入 three.js、不引入视频播放。
- 后端重启用计划任务：`schtasks /end /tn TeacherBackend` 然后 `/run`（不要用后台任务跑 mvn）。
- 素材文件已在 frontend/public/pets/，无需再下载。

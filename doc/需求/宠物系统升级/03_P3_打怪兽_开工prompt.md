# 开发分支开工任务书：P3 每日打怪兽（daily_boss + boss_hit + /boss 大屏 + /m 控制卡 + SSE）

> 2026-09-05。粘贴给开发分支执行。
> **先读基线**：`doc/需求/宠物系统升级/需求说明.md`（P3 规格见"二、B. 每日通关 · 打怪兽"，92~245 行；冲突以本任务书为准）。
> 本任务 = **只做 P3**：daily_boss/boss_hit 两表 + BossController 五接口 + /boss 大屏页 + /m 控制卡 + SSE 三动作 + E2E 11k 段。**不做** P4/P5 PK。

---

## 一、已拍板决策（不要再问）

1. **建表**：daily_boss / boss_hit 进 `schema.sql` 尾部（幂等 CREATE IF NOT EXISTS，DDL 原文照搬需求说明 181~209 行，不改字段）。
2. **ESCAPED 懒处理**：查询时判断 `boss_date < 今天 且 status='ACTIVE'` → 返回时置为 ESCAPED，**不落库**（KISS；次日 start 建 uk 新记录自然刷新）。
3. **奖励走 ruleId=null 直传**（复刻 AUTO_SCORE 模式）：`petService.award(uid, sid, null, score, "BOSS", bizRef, remark)`。
   - **原因**：UserDataSeeder.seedPetRules 对已有规则的老账号 `cnt>0 return` 不补播，pet_rule 预置不可靠。
   - 分值走 setting 键（settingInt 读，缺省有默认值）：`boss.pass_score=5`、`boss.kill_score=10`、`boss.kill_all_score=2`；`boss.hp_total=100`、`boss.hit_per_pass=10`。
   - biz_ref：PASS `boss:{date}:hit:{hitId}`；击杀 `boss:{date}:kill:{studentId}`；全班 `boss:{date}:ALL:{studentId}`（幂等靠 uk）。
   - 失败 0 分不写流水。source=BOSS 非同步升级源 → 触发升级会置 evolve_pending=1 攒队列由 PC 补播（现成机制，不动）。
4. **答案防泄漏**：答案只经手机 /m 调 `/quiz/draw` 获得（P2 已含 answer）；大屏接口与 SSE payload **不下发 answer**。
5. **Boss 形象**：emoji 池 `👾🐲👹🦖🦑👽🤖` 每日随机 + 随机名字池（"小怪兽阿呜""钢铁大王"等）；v1 emoji，不接 AI 绘图。
6. **start 幂等**：今日已存在（uk user_id+boss_date）直接返回现有记录；当天 KILLED 后再 start 也返回已杀的那只（当日不复活）。
7. **SSE 动作**（RemoteHub.dispatch，payload 剔除 answer）：
   - `boss.state`：{boss 全量字段 + question, pickedStudent, stats} —— 判定/出题后推，大屏整体刷新
   - `boss.pick`：{studentId, name} —— 抽人高亮
   - `boss.killed`：{killStudentName, stats} —— 爆炸 + confetti + 击杀者通报
8. **/boss 路由挂法**：独立全屏路由（非 MainLayout 子路由），fixed inset:0 深色舞台（参照 /radish）；**正常鉴权**（教室电脑是老师已登录浏览器，需 token 调 API+SSE）——不加免登白名单。
9. **抽人**：RANDOM=班主任班学生随机；MANUAL=指定 studentId。抽人后记内存态（不落库），judge 带上 studentId。

## 二、后端

### 2.1 schema.sql 追加（照需求说明 181~209 行 DDL 原文）

### 2.2 新建 `BossController.java`（挂 /api/boss，extends BaseController，注入 JdbcTemplate + PetService + RemoteHub）

```
GET  /boss/today                            今日 Boss（无则 data=null；懒判 ESCAPED；含今日战绩 stats）
POST /boss/start                            {categoryId, difficulty} 幂等创建；随机 emoji+名字；读 setting hp_total/hit_per_pass
POST /boss/pick-student                     {mode:RANDOM|MANUAL, studentId?} → SSE boss.pick；返回学生
POST /boss/draw                             复用 QuizController 逻辑或转发（手机端出题；记当前题 quizId 内存态；SSE boss.state 含 question 无 answer）
POST /boss/judge                            {studentId, quizId, result:PASS|FAIL}
                                            → 写 boss_hit（PASS 记 damage）→ PASS 时 award +5 + HP 扣减 + SSE boss.state
                                            → HP<=0：status=KILLED + kill_student_id + 击杀 award +10 + 全班 award +2 + SSE boss.killed
GET  /boss/log?date=yyyy-MM-dd              当日 boss + 命中明细
```

注意：
- 当前题/当前答题人存控制器内存 Map<uid, BossSession>（单教师单班场景，重启丢失可重抽，KISS 不落库）。
- judge 时 quizId 必须属于内存态当前题（防重放乱序；E2E 幂等重放断言靠 biz_ref）。
- 全班 +2 循环 award：只给有宠物的学生发（award 内部未领养自动 affected=0 跳过）。
- HP 扣减用 `UPDATE ... SET hp_left = hp_left - ? WHERE id=? AND status='ACTIVE'` 防击杀后继续打。

### 2.3 setting 默认值

零 DDL：全部走 `settingInt(uid, key, def)` 的 def 默认值（100/10/5/10/2），不 INSERT seedSettings（老师要改再进 setting 表）。

## 三、前端

### 3.1 `/boss` 大屏页（新 view `views/Boss.vue` + 独立路由）

- fixed inset:0 深色舞台（#1a1a2e 系参照 Radish）；顶部"怪兽来啦！"。
- Boss emoji 字号 200px+ 摆动动画（transform）；HP 条渐变红缩短；扣血抖动 + 飘 -10。
- 题目卡（无答案）+ 当前答题人 + 今日战绩（✅×N ❌×N 击杀王）。
- 击杀：Boss 爆炸（scale+opacity）+ canvas-confetti 金雨（Radish 三发模式）+ "xxx 击杀！" 3 秒。
- SSE：onRemoteCmd 监听 boss.state / boss.pick / boss.killed；进入页面拉 /boss/today 初始化；15s 轮询兜底。
- 全程只用 transform/opacity 动画。

### 3.2 `/m` 控制卡（Mobile.vue）

- 入口卡片"👾 今日打怪兽"→ Boss 控制面板：
  - 开始（选分类下拉 GET /quiz/categories + 难度）→ POST /boss/start
  - 🎲 出题（POST /boss/draw 显示题目+**答案**）→ 大屏出题
  - 👉 抽人（随机 / 点名选学生）
  - ✅ 通过 / ❌ 未过 → POST /boss/judge
- 走查 /m 现有卡片布局模式追加。

## 四、不做（后续期做）

- /pk 大屏与手机控制卡（P4/P5）。
- Boss v2 AI 绘图形象、boss.escaped SSE 推送（v1 懒判即可）。
- 题库学生自测模式。

## 五、验收（对应需求说明 P3 验收标准）

- 每日 Boss 首次 start 自动创建；重复 start 幂等返回现有 ✓
- PASS 扣 HP +10 + 学生 +5；FAIL 0 分 ✓
- HP=0 击杀三档奖励（击杀者 +10、全班 +2、之前 PASS 已实时 +5）✓
- biz_ref 幂等（重放 judge 不重复发奖）✓
- 次日懒判 ESCAPED，新 start 刷新 ✓
- /boss 大屏动画流畅（transform/opacity）✓
- E2E 11k 段全绿 ✓

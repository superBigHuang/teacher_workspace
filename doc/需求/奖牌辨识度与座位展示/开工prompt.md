# 开发分支开工任务书：奖牌进阶（铜→银→金）+ 辨识度 + 座位挂奖牌 + 宠物联动成就

> 2026-09-02。粘贴给开发分支执行。
> **先读基线**：`doc/需求/奖牌辨识度与座位展示/需求说明.md`（完整背景/数据模型/验收），本任务书是可直接执行的落地清单，与基线冲突处以本任务书为准。
> 改动集中在：后端 AchievementScanner/AchievementController/DataInitializer/schema + 前端 BadgeIcon/Roster/AchievementBoard/Seat。**不新增侧边栏菜单、不改宠物/座位/成绩业务逻辑**，成就是附加视图层 + 扫描规则。

---

## 一、已拍板决策（不要再问）

1. **同一成就可铜→银→金进阶**：一枚可进阶成就 = 一条定义带三档阈值；学生一学期只持有一枚 = 当前最高档；升级 = UPDATE 该行的档。
2. tier 从"定义固有"变为"学生获得记录上的当前档"：`student_achievement` 加 `tier` 列，`achievement_def` 加 `tiered` 列；老数据 tier=NULL，**展示/计点一律回退 `def.tier`**（不回填、不破坏）。
3. **只升不降、不回收**：达更高档才 UPDATE；档≤持有档（含新考试排名回落、宠物毕业后等级重置）保持不动。
4. 升级时 `awarded_at` 刷新为当前档达成日期。
5. **大满贯也进阶**：仍"全部集齐"才发；档位 = 所有"可进阶 AUTO 成就"当前持有档的**最低档（木桶）**；固定档成就（单科状元/班委/萌宠相伴等）只要求"持有"，不参与取 min。大满贯档位：集齐→🥉铜、可进阶全≥银→🥈银、可进阶全=金→🥇金。**大满贯点数 = 基础档点数 ×5**（铜 50/银 150/金 300）。PLATINUM 枚举与 100 点保留兼容老数据，本期大满贯不再用 PLATINUM。
6. 座位宠物上方挂**本学期**得分最高的 **top3** 奖牌；座位设置「🏅奖牌」开关**默认开**；简易模式隐藏、打印不打。
7. 连续全勤的三档（4 周/8 周/整学期）本期**按学期粒度**：无周粒度考勤，"学期无迟到无缺勤"即按最高档（金）颁发；周粒度考勤是以后的事，阶梯先定义好。
8. 点数：普通 铜10/银30/金60；大满贯 ×5 = 50/150/300；白金(老)=100。

---

## 二、数据库（幂等，可重复执行）

用 DataInitializer 现有的 `ensureColumn(table, column, columnDef)`（JDBC 元数据判列是否存在）加两列：

- `achievement_def` 加：`tiered TINYINT NOT NULL DEFAULT 0 COMMENT '1=可进阶（铜银金三档），0=固定档'`
- `student_achievement` 加：`tier VARCHAR(16) NULL COMMENT '当前持有档 BRONZE/SILVER/GOLD/PLATINUM；NULL=老数据回退 def.tier'`

> 不要改唯一键 `uk_sa(user_id,student_id,def_id,term)`——一枚成就一学期一条，进阶靠 UPDATE tier。

---

## 三、后端

### 3.1 DataInitializer：内置成就迁移 + 新播种（幂等）

现有 `seedBuiltinAchievements()` 是 `if count(user_id=0)>0 return`，**老库不会更新**，需在其后追加两段幂等逻辑：

**(a) 把 4 条内置改为可进阶**（按 rule_type 定位，UPDATE 幂等）：

| 成就 | rule_type | rule_params（三档，min=最低达成值，越大越好） | def.tier |
|---|---|---|---|
| 劳动能手 | DUTY_COUNT | `{"tiers":[{"tier":"BRONZE","min":10},{"tier":"SILVER","min":20},{"tier":"GOLD","min":30}]}` | BRONZE |
| 进步之星 | RANK_UP | `{"tiers":[{"tier":"BRONZE","min":3},{"tier":"SILVER","min":5},{"tier":"GOLD","min":10}]}` | BRONZE |
| 满分达人 | SCORE_FULL | `{"tiers":[{"tier":"BRONZE","min":1},{"tier":"SILVER","min":2},{"tier":"GOLD","min":3}]}`（min=满分科目数） | BRONZE |
| 连续全勤 | CONTINUOUS_ATTENDANCE | `{"tiers":[{"tier":"BRONZE","min":4},{"tier":"SILVER","min":8},{"tier":"GOLD","min":20}],"weeks":4}`（min=连续周数；本期学期粒度满足即金） | BRONZE |

> 这 4 条统一置 `tiered=1`、`tier='BRONZE'`（入门档）、写 rule_params。描述更新为体现三档（如劳动能手"参与值日劳动：🥉10 次 / 🥈20 次 / 🥇30 次"）。

**(b) 新增 5 条 AUTO 内置**（按 name 幂等 INSERT，user_id=0、builtin=1、kind='AUTO'）：

| 名称 | rule_type | 图标(public/badges) | tiered | 三档 rule_params / 说明 |
|---|---|---|---|---|
| 萌宠相伴 | PET_ADOPTED | sparkles | 0 固定🥉 | 已领养宠物（pet.adopted_at 非空）即得，固定 BRONZE |
| 萌宠成长 | PET_LEVEL | star | 1 | `{"tiers":[{"tier":"BRONZE","min":3},{"tier":"SILVER","min":4},{"tier":"GOLD","min":5}]}`（min=宠物当前 level；毕业后等级重置不降级，靠只升不降保留最高档） |
| 毕业典礼 | PET_GRADUATE | grad-cap | 1 | `{"tiers":[{"tier":"BRONZE","min":1},{"tier":"SILVER","min":2},{"tier":"GOLD","min":3}]}`（min=本学期毕业次数，取 pet_badge 本学期行数） |
| 小店达人 | PET_SHOP | gem | 1 | `{"tiers":[{"tier":"BRONZE","min":1},{"tier":"SILVER","min":3},{"tier":"GOLD","min":5}]}`（min=本学期兑换次数，取 pet_exchange 本学期 created_at 行数） |
| 成绩前列 | RANK_TOP | medal-1st | 1 | `{"tiers":[{"tier":"BRONZE","min":10},{"tier":"SILVER","min":5},{"tier":"GOLD","min":3}]}`（min=最近一次考试总分排名，≤min 即达成；并列同名次都算） |

**(c) 新增约 8 条内置手动模板**（kind='MANUAL'、builtin=1、tiered=0，开箱即授、**不扫描、不进大满贯**；按 name 幂等 INSERT）：

| 名称 | 图标 | tier |
|---|---|---|
| 体育之星 | sports-medal | SILVER |
| 艺术之星 | party | SILVER |
| 助人为乐 | glowing-star | SILVER |
| 科技小达人 | rocket | BRONZE |
| 音乐之星 | bell | BRONZE |
| 文明礼仪 | sparkles | BRONZE |
| 阅读之星 | memo | BRONZE |
| 演讲达人 | pencil | BRONZE |

> 图标只能从 `frontend/public/badges/` 现有 22 枚里取文件名（不含 .png）：bell books broom check crown gem glowing-star grad-cap hundred medal-1st medal-2nd medal-3rd memo party pencil rocket shield sparkles sports-medal star sun trophy；不同成就允许复用同名图标。手动模板 builtin=1 不可删但可停用；老师也能再自建自定义成就（现状逻辑）。
> 改造后内置总数：固定 AUTO 7 + 可进阶 AUTO 7 + 大满贯 1 + 手动模板 8 ≈ 23 条。

### 3.2 AchievementScanner：三档评估 + 新数据源 + 升级 + 大满贯木桶

- **Eval 扩展**：现 `Eval(met, progress)` 增加达成档字段（如 `tier`：BRONZE/SILVER/GOLD/null）。
  - 可进阶规则：算出实际值 v，按 rule_params.tiers 取"满足 min 的最高档"（v≥min 才算），返回该 tier；一档都没到 tier=null 并给指向铜档的进度文本。
  - 固定档规则：met 时 tier=def.tier。
- **新增规则 case**：
  - `PET_ADOPTED`：met=该生有 pet 且 adopted_at 非空。
  - `PET_LEVEL`：v=pet.level（宠物 1~5）。数据源：一次 `SELECT student_id,level FROM pet WHERE user_id=? AND class_id? `（pet 按 user+student；班级学生名单同 TermStatsService）。内存 Map。
  - `PET_GRADUATE`：v=本学期 `pet_badge` 行数（created_at 在 term_start~term_end）。一条 SQL `WHERE user_id=? AND created_at BETWEEN ? AND ?` group by student_id。
  - `PET_SHOP`：v=本学期 `pet_exchange` 行数（created_at 学期区间，status 不限）。
  - `RANK_TOP`：复用最近一次考试明细（loadLatestExam 已一次查回全班），按学生汇总总分、降序得排名 rank（1 起），并列同名次；铜=rank≤10、银≤5、金≤3（即 tiers min 分别 10/5/3，rank≤min 达成）。
  - 其余现有规则（PERFECT_* / SUBJECT_TOP / ALL_EXCELLENT / SCORE_FULL[改为数满分科目数] / DUTY_COUNT / RANK_UP / CONTINUOUS_ATTENDANCE[学期粒度满足即金] / COMMITTEE）保持，可进阶的 4 条改走三档评估。
  - `SCORE_FULL` 口径改：v=最近考试"满分科目数"（分数≥fullScore 的科目数），三档 1/2/3。
- **颁发/升级**（替换现 `INSERT IGNORE` 只发逻辑）：对每个启用中非 COLLECTOR 的 AUTO 定义 × 每个学生，算出达成档 t：
  - 无记录 且 t!=null → INSERT（tier=t, source='AUTO', awarded_at=今天），计入 newAwards（庆祝"获得"）。
  - 有记录（老记录 tier=NULL 视同 def.tier）且 t 的点数 > 持有档点数 → UPDATE `tier=t, awarded_at=今天`，计入 newAwards（庆祝"升级为 X 牌"，带 oldTier/newTier）。
  - 否则不动（不降级、不回收）。
- **大满贯 COLLECTOR 最后评定**：先收集本学期该生所有"启用中、非大满贯 AUTO 定义"的持有记录；
  - 若有任一未持有 → 不发/不升级（已发的大满贯也不回收）。
  - 全部持有 → 大满贯档 = 其中 `tiered=1` 定义当前档的 min（木桶；固定档不取 min）。无 tiered 定义时按铜。
  - 大满贯记录 tier 存 BRONZE/SILVER/GOLD；按上面"颁发/升级"同样规则只升不降。
- 进度文本（evaluateStudent 给徽章墙用）：可进阶指向下一档，如"值日 15 次，当前🥉，再 5 次升🥈"；满档"已达最高🥇"；大满贯未集齐显示"还差 N 枚集齐"，集齐未升档显示"可进阶奖牌全部到🥈可升🥈大满贯"。
- newAwards 元素加 `tier` 与 `kind`（'award' 新获得 / 'upgrade' 升级）供前端区分庆祝文案。

### 3.3 AchievementController

- **点数统计统一改 Java 计算**（替换 board 里写死的 SQL `CASE tier WHEN...`，否则大满贯 ×5 无法表达）：读出每枚奖牌的 tier + def.rule_type，点数 = `rule_type=='COLLECTOR' ? TIER_POINTS[tier]*5 : TIER_POINTS[tier]`（tier 为 NULL 用 def.tier）。影响：`GET /board` 的 rankings(points/bronze/silver/gold/platinum 计数按当前档)、`GET /student/{id}` 的 stats.points 与四色计数。
- **新增 `GET /api/achievement/seat-medals`**：内部先 `scanner.scan(uid())`（静默、幂等，保证新发/升级及时上屏，不抛错）；然后一条 SQL 查全班本学期 `student_achievement sa JOIN achievement_def d ON d.id=sa.def_id JOIN student s ON s.id=sa.student_id`，取 sa.student_id、d.icon、d.name、`COALESCE(sa.tier,d.tier) tier`、d.rule_type、sa.awarded_at；按"有效点数（COLLECTOR ×5）降序、awarded_at 倒序"在内存每生取前 3；返回 `{ "<studentId>": [ {icon,name,tier} , ... ] }`（或 `[{studentId,medals:[...]}]`，前端好用即可）。
- `GET /student/{id}` 徽章墙 currentBadges：每枚 badge 的 tier 用 `COALESCE(sa.tier, d.tier)`；可进阶定义带三档门槛（tiers）返回，供前端悬停卡展示；进度文本用 scanner 的新逻辑。
- `GET /defs` / `board` 的 defs：可进阶定义返回 `tiered` 与 `rule_params.tiers`；编辑接口（PUT /def/{id}）内置可进阶成就的阈值编辑改为三档（前端传 tiers）。

---

## 四、前端（Vue3 `<script setup>` + Element Plus；http 拦截器已返回 body.data）

### 4.1 BadgeIcon.vue 配色拉开（辨识度）

底托渐变改：
- `.tier-bronze`：`linear-gradient(135deg,#c98a5b,#8a5326)`（古铜棕）
- `.tier-silver`：`linear-gradient(135deg,#f4f6fa,#a9b3c2)`（纯银灰，去紫）
- `.tier-gold`：`linear-gradient(135deg,#fff0a0,#f5b800)`（亮金黄）
- `.tier-platinum`：保留 `linear-gradient(135deg,#b8eeff,#e6dcff 45%,#ffd4ec)`
- 跳杯 glow 色同步微调即可。

### 4.2 Roster.vue

- 统计圆点颜色改：铜 `#a8642f`、银 `#9aa6b8`、金 `#f0b400`、白金 `#5fb5e8`。
- 徽章墙顶部加**等级图例**：🥉入门达成 · 🥈稳定优秀 · 🥇卓越标杆 · 🏆大满贯。
- 徽章墙/抽屉成就 tab：badge 的 tier 用后端返回的当前档；可进阶成就悬停卡展示三档门槛（🥉10次/🥈20次/🥇30次）+ 当前档 + 下一档进度；升级后颜色随当前档。

### 4.3 AchievementBoard.vue

- 定义行：可进阶成就显示"🥉🥈🥇"三档标识与三档门槛（替代单一 tier 标签）；固定档显示原等级标签。
- 内置可进阶成就编辑：阈值改为三档 min 输入；手动模板（builtin MANUAL）正常出现在"授予学生"可选列表。
- 🔄扫描/导入后的 ElNotification 庆祝：区分 `kind`——新获得"🎉 X 获得「成就名」（🥉/🥈/🥇）"；升级"⬆️ X 的「成就名」升级为🥈银牌！"；大满贯升级单独突出"🏆 X 的大满贯升至🥇金牌（300 点）！"。
- 点数排行：四色计数/点数读后端（已含 ×5）。

### 4.4 Seat.vue（座位挂奖牌）

- `disp` 默认值 `fields` 增加 **`medal: 1`**；`FIELD_KEYS` 数组加 `'medal'`；座位设置弹窗「显示」区 el-checkbox-group 加一项：`<el-checkbox value="medal">🏅 奖牌</el-checkbox>`（放在"毕业🎓角标"附近）。老 seat_display 无 medal 键时分层兜底默认 1。
- onMounted（与现有 /seat/grid、/pet/pets 并行）调 `GET /achievement/seat-medals`，建 `seatMedalsMap`（sid → 奖牌数组）。
- 座位卡 `.seat-pet` 块**顶部**（宠物正上方，截图橙框位置）加：
  ```html
  <div v-if="!disp.simple && disp.fields.medal && seatMedalsOf(s.studentId).length" class="seat-medals">
    <BadgeIcon v-for="(m,i) in seatMedalsOf(s.studentId)" :key="i"
      :icon="m.icon" :tier="m.tier" :earned="true" :animate="false" :size="20" :name="m.name" />
  </div>
  ```
  - import BadgeIcon（`../components/BadgeIcon.vue`）。
  - `.seat-medals`：横排 flex、居中、gap 2px、绝对定位于座位卡顶部（z-index 高于宠物但不挡姓名）；≤3 枚；`title` 用 BadgeIcon 已有 name/alt（img alt=name）即可，或外层包 `:title="m.name"`。
  - 简易模式 `disp.simple` 整块不渲染（与现有 🎓/Lv/🌈 一致）；**打印区 `.seat-print-area` 不加奖牌**（只打姓名+学号）。
  - 座位卡点击仍开宠物详情，奖牌不单独绑事件（`@pointerdown` 在外层 seat 上，无需 stop）。
- 预览/打印缩略图不受影响。

---

## 五、E2E（e2e/tests/e2e.spec.js，沿用现有"临时数据 + afterAll 清理，不污染 admin 40 真实学生"）

在成就相关步骤（现 11e）追加/扩展：
1. API：创建一枚 E2E 自定义可进阶验证——或直接对内置 `DUTY_COUNT` 类用 scan 验证：调 `POST /achievement/scan` 两次，第二次 `newAwards` 长度幂等（不重复）；断言返回元素含 `tier` 字段与 `kind`。
2. 座位奖牌：打开座次表 → 座位设置确认有「🏅奖牌」勾选项（默认勾选）→ 座位卡上 `.seat-medals` 存在（admin 有已颁发成就，至少部分学生有奖牌），且每个 `.seat-medals` 内 BadgeIcon 数量 ≤3；取消勾选后 `.seat-medals` 消失；重新勾选恢复（偏好持久化由现有 watch 覆盖，afterAll 已把 seat_display 写回默认 JSON，记得默认 JSON 的 fields 里**加 medal:1**，与前端默认一致）。
3. `GET /achievement/seat-medals` 返回结构：每生奖牌 ≤3、tier ∈ 合法值、大满贯（若有）点数排在前。
4. 打印媒体断言保持：`.seat-print-area` 内**不含**奖牌元素。
5. afterAll：E2E 自建自定义成就定义仍按现有逻辑删（name 含 E2E）；内置新增的模板/宠物成就是 user_id=0 全局数据，**不要删**；seat_display 默认 JSON 补 `medal:1`。
6. 备注：`/ai/test` 若遇第三方中转 503（model_not_found）是外部故障、与本需求无关，跑套件时可临时放宽该行断言，**提交前恢复** `expect(tBody.data.ok).toBe(true)`。

---

## 六、工程约定

- 后端 JDK17 + Spring Boot 3.3.5 + JdbcTemplate；uid() 取 BaseController；新增方法/字段写中文注释，方法 >30 行补详细说明。改完后端重启服务（schtasks TeacherBackend / start-backend.bat，.bat 须 CRLF+ASCII）。
- 前端 `npm run build` 必须通过。
- KISS：不新增文件/菜单/依赖；BadgeIcon 复用；SQL 一次查全班内存聚合，杜绝 N+1。
- 默认值必须兼容老用户：seat_display 缺 medal 按 1；老成就记录 tier=NULL 回退 def.tier，界面与升级前一致。
- 提交只含本需求文件（后端 4 + 前端 4 + 本需求文档），**不要**夹带工作区其他无关改动（nginx/部署手册/models 等）；commit trailer：`Co-Authored-By: Claude Code <noreply@anthropic.com>`。
- 完成后更新 `需求说明.md` 的落地结果段。

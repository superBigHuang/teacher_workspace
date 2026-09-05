# 开发分支开工任务书：P1 宠物独立页 `/pet`（含本周榜前三领奖台）

> 2026-09-04。粘贴给开发分支执行。
> **先读基线**：`doc/需求/宠物系统升级/需求说明.md`（含已拍板决策；冲突以本任务书为准）。
> 本任务 = **只做 P1**：新建 `/pet` 独立页（领奖台 + 全员宠物墙 + 4 个榜单 tab）+ Seat.vue 入口瘦身 + 新增 1 个周榜 API。**不做** P2 题库、P3 打怪兽、P4/P5 PK。

---

## 一、已拍板决策（不要再问）

1. **路由**：`/pet`，挂在 MainLayout 子路由，菜单图标 🐾，名称"宠物园"。
2. **周榜口径**：`SUM(pet_log.score)` 且 `score>0` 且 `created_at >= 本周一 00:00`。**严禁读 `pet.exp`**（商店兑换扣 exp 不写 pet_log，会造成口径错乱）。
3. **本周起点**：本周一 00:00:00（本地时区），MySQL 计算。
4. **数据源**：单一新接口 `GET /api/pet/board?type=weekly|total|level` 返回全部学生 + 全部榜单所需字段，前端只调一次，按 tab 在前端切排序，**不发 4 次请求**。
5. **领奖台**：仅 weekly tab 显示；不足 3 人时对应位置显示"虚位以待"。
6. **动效**：领奖台入屏 0.6s + 一次 canvas-confetti 金色雨；全程只用 `transform/opacity`。
7. **Seat.vue 保留**：座次格子 / 单个学生点宠物评价 / 批量评价 / 进化演出（EvolutionShow）。**移除**：🏆 荣誉榜入口、🛍 商店入口、⚙ 评价项入口（三个按钮搬到 `/pet` 页顶部），对应弹窗组件文件不删（`/pet` 页复用），只删 Seat.vue 里的引用。
8. ** `/pet` 页同样监听 `pets.changed / evolve.play` SSE 并挂载 EvolutionShow**：保证老师在大屏 `/pet` 页也能看到进化演出（复用 MainLayout 已有的全局监听，无需新增）。

---

## 二、后端（只加一个接口，不加表）

### 2.1 `PetController.java` 新增

```java
/** 宠物园榜单：weekly=本周累计得分 / total=累计总值 / level=等级优先；前端按 type 切排序，一次拉全 */
@GetMapping("/board")
public Result<List<Map<String, Object>>> board(@RequestParam(defaultValue = "weekly") String type) {
    // 见下方 SQL
}
```

**SQL（一次查全，不在 SQL 里排序，由 Java/前端按 type 排）**：

```sql
SELECT
  s.id            AS studentId,
  s.name          AS name,
  s.gender        AS gender,
  s.stu_no        AS stuNo,
  p.pet_key       AS petKey,
  p.pet_name      AS petName,
  p.level         AS level,
  p.exp           AS exp,
  p.hatched       AS hatched,
  p.graduated_count AS graduatedCount,
  IFNULL(w.weeklyScore, 0) AS weeklyScore,
  (SELECT COUNT(*) FROM committee c
     WHERE c.user_id=s.user_id AND c.student_id=s.id AND c.role='组长') AS isLeader
FROM student s
JOIN pet p ON p.student_id=s.id AND p.user_id=s.user_id
LEFT JOIN (
  SELECT student_id, SUM(score) weeklyScore
  FROM pet_log
  WHERE user_id=? AND score>0
    AND created_at >= DATE_SUB(CURDATE(), INTERVAL WEEKDAY(CURDATE()) DAY)
  GROUP BY student_id
) w ON w.student_id=s.id
WHERE s.user_id=? AND s.class_id=?
```

> `WEEKDAY(CURDATE())` 返回周一=0…周日=6，`DATE_SUB(CURDATE(), INTERVAL WEEKDAY(CURDATE()) DAY)` 即本周一 00:00。**MySQL 5.7 兼容，无 CTE、无窗口函数**。

**排序在 Java 里做**（KISS，省得写 4 个 SQL）：

- `weekly` → `weeklyScore DESC, exp DESC, stu_no`
- `total`  → `(exp + graduatedCount*280) DESC, exp DESC, stu_no`
- `level`  → `level DESC, exp DESC, stu_no`

返回字段就一套，前端拿去排序 / 切片即可。

**不新增表，不改老表。**

---

## 三、前端

### 3.1 新增 `frontend/src/views/Pet.vue`

路由 `{ path: 'pet', name: 'pet', meta: { title: '宠物园' }, component: () => import('../views/Pet.vue') }`，挂到 MainLayout 的 children 里；侧边栏菜单插到"座次表"之后。

**页面结构**（自上而下）：

```
┌──────────────────────────────────────────────────┐
│ 🐾 宠物园    [本周榜|总榜|等级榜]   ⚙评价项 🛍商店 │
├──────────────────────────────────────────────────┤
│              🏆 本周前三名（仅 weekly tab 显示）   │
│         🥈        🥇        🥉                    │
│      (左矮)   (中最高 scale1.15)  (右矮)          │
├──────────────────────────────────────────────────┤
│  全员宠物墙  [全部|已破壳|孵化中|本周进步]          │
│  8 列 grid，卡片含：宠物图 / 姓名 / 宠物名 / Lv   │
│  / 本周 +N / exp 进度条 / 👑组长 / 🎓×N / 🥚      │
└──────────────────────────────────────────────────┘
```

### 3.2 关键实现细节

- **API 调用**：`onMounted` 调一次 `GET /api/pet/board?type=weekly` 拿全量；切 tab 时**前端复排，不重发请求**。
- **领奖台**：
  - 前三名卡片用 `badges/medal-1st.png / medal-2nd.png / medal-3rd.png` 挂在卡片左上角。
  - 中央第一名 `transform: scale(1.15)` + `z-index:2`。
  - 入屏动画：`@keyframes podiumIn { from { transform: translateY(40px); opacity:0 } }`，三张卡片依次延迟 0.1s / 0.2s / 0.3s。
  - 金色雨：`canvas-confetti` 一次性爆发（参考 EvolutionShow.vue 现有用法），仅首次进入页面时播一次。
  - 不足 3 人：对应位置渲染占位卡 "虚位以待"。
- **全员宠物墙**：
  - 8 列 grid，宠物图取 `pets/{petKey}_{level}.webp`，未破壳（hatched=0）显示 `pets/{petKey}_egg.webp`。
  - 卡片内容：宠物图 / 学生姓名 / 宠物名 / `Lv{level}` / 本周 +N（绿色，仅 weekly tab 强调）/ exp 进度条（`exp/280`，渐变色）。
  - 角标：👑（isLeader=1，右上角）/ 🎓×N（graduatedCount>0，左上角）/ 🥚（孵化中，图替代）。
  - 筛选 chip：全部 / 已破壳（hatched=1）/ 孵化中（hatched=0）/ 本周进步（weeklyScore>0）。
  - 点击卡片：弹出**复用 Seat.vue 的 PetDetailDlg 组件**（把它从 Seat.vue 抽到 `frontend/src/components/pet/PetDetailDlg.vue`，Seat 和 Pet 都 import）。**不要重写**。
- **性能**：
  - 图片 `loading="lazy"`。
  - 所有动画只用 `transform/opacity`，**禁止** 对 box-shadow / filter 做 transition。
  - 40 张卡片同时渲染不应有掉帧；不做虚拟列表（KISS）。
- **SSE 兼容**：Pet.vue 不需要自己挂 EvolutionShow；MainLayout 已有全局监听，进化演出在任何页都会播（核对一下，若没有就把 EvolutionShow 挂到 MainLayout，一处挂载全局生效）。
- **顶部按钮**：
  - ⚙ 评价项 → 弹 RulesDlg（从 Seat.vue 抽到 `components/pet/RulesDlg.vue`）
  - 🛍 商店 → 弹 ShopDlg（同上抽取）

### 3.3 Seat.vue 瘦身

- 删除顶部工具栏三个按钮：`🏆 荣誉榜`、`🛍 小商店`、`⚙ 评价项`（第 10-12 行附近）。
- 删除对应 `openRanking / openShop / openRules` 方法、`rankDlg / shopDlg / rulesDlg` ref、模板里的 `<el-dialog v-model="rankDlg/shopDlg/rulesDlg">` 三段。
- 删除 `openSkinMaker / skinMakerDlg` 相关**只删入口按钮**，SkinMakerDlg 组件文件保留（`/pet` 页皮肤管理入口未来再加，本期不加）。
- **保留**：座次格子、宠物小图标、点宠评价（evalDlg）、批量评价、毕业（graduateDlg）、领养（adoptDlg）、皮肤制作器（如果座上有点入口就保留）、EvolutionShow。
- 页面标题从 `🪑 座次表 · 🐾 宠物园` 改回 `🪑 座次表`。

### 3.4 usePets.js

- 不改动对外 API；如有需要新增一个 `loadBoard(type)` 方法封装 `GET /api/pet/board`，返回 Promise；其余逻辑（addFloat / evolve / evaluate 等）原样保留。

---

## 四、E2E（e2e/tests/e2e.spec.js 追加一段，临时数据 + afterAll 清理）

> 不要污染 admin 的 40 个真实学生。E2E 里临时创建 3~5 个测试学生 + 宠物 + pet_log，afterAll 全部删掉。

1. **API**：`GET /api/pet/board?type=weekly` 返回数组，字段含 `studentId / weeklyScore / exp / level / hatched / graduatedCount / isLeader`；测试学生分数符合预期排序。
2. **路由**：访问 `/pet` 200；侧边栏能看到"宠物园"。
3. **领奖台**：weekly tab 下，存在 3 个领奖台卡片，且第一个的 `weeklyScore` ≥ 第二个 ≥ 第三个。
4. **宠物墙**：grid 卡片数 == 测试学生数；点击第一张卡片能弹出详情对话框。
5. **筛选 chip**：切到"孵化中"时只显示 hatched=0 的卡。
6. **Seat.vue 瘦身**：访问 `/seat`，**断言不存在** "🏆 荣誉榜"、"🛍 小商店"、"⚙ 评价项" 三个按钮；存在座次格子。
7. **tab 切换不发请求**：用 page.route 拦截计数，切到"总榜"再切回"本周榜"，断言 `/api/pet/board` 只被调一次。

---

## 五、工程约定

- 后端 JDK17 + Spring Boot 3.3.5 + JdbcTemplate；新增方法补中文注释，>30 行写详细说明。
- 改完重启后端（`schtasks /run /tn TeacherBackend` 或 `start-backend.bat`；.bat 必须 CRLF+ASCII）。
- 前端 `npm run build` 必须通过。
- **SQL 执行**：所有 DDL/DML 走已配置只读 SQL MCP；本任务**无 DDL**，只有查询。
- 提交只含本需求文件（后端 PetController.java + 前端 router/index.js / views/Pet.vue / views/Seat.vue / usePets.js / 新增 components/pet/* + E2E + 本文档），**不要夹带** 工作区其他无关改动（nginx-teacher.conf、部署手册.md、models 素材删除、cutout/webp 等）。commit trailer：`Co-Authored-By: Claude Code <noreply@anthropic.com>`。
- 完成后在 `doc/需求/宠物系统升级/需求说明.md` 末尾补"P1 落地结果"段（改了哪些文件、怎么验证的）。

---

## 六、手动验收

1. 访问 `/pet`：领奖台三个位次正确（本周得分前三名），台位有 1/2/3 奖牌 PNG，入屏动画 + 金色雨只播一次。
2. 切到"总榜"和"等级榜"：宠物墙排序变化，领奖台隐藏。
3. 筛"孵化中"：只显示蛋。
4. 点任意宠物卡：弹出详情（含皮肤、徽章、最近 pet_log）。
5. 点顶部 ⚙ 评价项 / 🛍 商店：弹窗正常工作（这两个弹窗来自 Seat.vue 抽出的组件）。
6. 访问 `/seat`：座次表正常，**没有**荣誉榜/商店/评价项三个按钮。
7. 打开 `/pet` 后让某学生宠物升级：大屏能正常播进化演出（EvolutionShow）。
8. 性能：40 人班级，滚动宠物墙不卡顿；切 tab 无闪烁。

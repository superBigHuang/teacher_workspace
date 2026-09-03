# 开发分支开工任务书：列组长（👑 小组长标识与展示）

> 2026-09-02。粘贴给开发分支执行。
> **先读基线**：`doc/需求/组长与小组/需求说明.md`。本任务书是可直接执行的落地清单，与基线冲突以本任务书为准。
> 改动很小：后端 **SeatController 加 1 个接口（零表变更）** + 前端 **Seat.vue**（指派模式 + 👑角标 + 显示开关 + 打印）。不改宠物/成绩/值日业务逻辑。

---

## 一、需求与已拍板决策（不要再问）

1. **组 = 座位的一"列"**（纵向一列学生为一组，第 1 列…第 N 列各一个组长）。
2. 组长由老师在该列**任意座位**指派（不一定第一排）；一列至多 1 个组长。
3. 本期**只做标识与展示**：👑角标 + 设/取消 + 自动进班委名单/评语/成就；不做小组积分、批量登记、自由学习小组。
4. **零数据库变更**：组长 = `committee` 表里一条 `role='组长'` 的记录（复用现有班委表）。
5. **不存"第几列"**：组长属哪列，实时按他当前座位 `seat_assignment.col_no` 推导；随机排座/换座后 👑 自动跟人，无数据一致性维护。
6. 👑 角标**常驻显示**（含简易模式——纯座位表/代课老师最需要看谁是组长）；座位设置可开关、默认开；**打印座次表带 👑**。

---

## 二、后端（SeatController，零 schema 变更）

新增一个接口 `POST /api/seat/leader`，body `{ studentId }`，逻辑：

1. `uid=uid()`，`cid=homeroomClassId()`（SeatController 已有这两个私有方法，直接用）。
2. 查该生当前座位列：
   `SELECT col_no FROM seat_assignment WHERE user_id=? AND class_id=? AND student_id=?`
   - 查不到（未排座）→ `return Result.fail("请先给该生安排座位")`。
3. 查该生是否已是组长：
   `SELECT id FROM committee WHERE user_id=? AND student_id=? AND role='组长'`
   - **已是组长 → 取消**：删除该条，`return Result.ok(Map.of("canceled", true))`。
4. **不是组长 → 设为该列组长**：
   - 查同列所有学生 id：`SELECT student_id FROM seat_assignment WHERE user_id=? AND class_id=? AND col_no=?`
   - 删同列旧组长（保证一列一个；用 NOT IN/IN 排除当前生也行，当前生本就不是组长）：
     `DELETE FROM committee WHERE user_id=? AND role='组长' AND student_id IN (<同列 student_id 列表>)`
   - 插入：`INSERT INTO committee(user_id, student_id, role) VALUES(?,?, '组长')`
   - `return Result.ok(Map.of("canceled", false, "col", col))`。

> 说明：组长记录就是班委记录，因此「班委担当」成就（AchievementScanner 的 COMMITTEE 判定 committee 有记录）、评语职务素材（TermStatsService 取 committee roles）、值日表·班委名单（DutyController `/api/duty/committee`）**全部自动覆盖，无需另改**。
> 查询组长列表**不加新接口**：前端复用现成 `GET /api/duty/committee`（返回 `[{id, studentId, role, name, stuNo}]`），过滤 `role==='组长'` 即可。

---

## 三、前端（Seat.vue，Vue3 `<script setup>` + Element Plus；http 拦截器已返回 body.data）

### 3.1 显示偏好字段

- `dispDefault().fields` 增加 **`leader: 1`**（默认开）；`FIELD_KEYS` 数组加 `'leader'`（位置放在 `'medal'` 附近）。老 seat_display 无 leader 键时由现有分层兜底逻辑默认 1。
- 座位设置弹窗「显示」区 el-checkbox-group 加一项（放在 `🏅 奖牌` 那行附近）：
  `<el-checkbox value="leader">👑 组长</el-checkbox>`

### 3.2 指派模式（仿现有"批量评价"按钮）

- 加 `const leaderMode = ref(false)`。
- 工具栏（`📝 批量评价` 按钮附近）加一个模式按钮：
  ```html
  <el-button round :type="leaderMode ? 'success' : 'info'" @click="leaderMode = !leaderMode">
    {{ leaderMode ? '✅ 完成指派' : '👑 组长' }}
  </el-button>
  ```
- 指派模式下应禁止拖拽换座（避免误拖）：座位 `:draggable` 条件加 `&& !leaderMode`（现为 `!!s.studentId && !batchMode`，改为 `!!s.studentId && !batchMode && !leaderMode`）。
- 指派模式和批量模式互斥（开一个关另一个即可，可选；KISS 也可共存，但建议开 leaderMode 时若 batchMode 则 toggleBatch 关）。

### 3.3 点击座位设/取消组长

在 `onSeatUp(e)` 里，位移阈值拦截（`<6px`）之后、`if (!s.studentId) return` 之后，**最前面**插入指派分支（优先于简易模式 return 和 onCardClick）：
```js
if (leaderMode.value) {
  if (s.studentId) await toggleLeader(s.studentId)
  return            // 指派模式不弹宠物窗；简易模式下也能指派
}
```
- `toggleLeader(sid)`：`await http.post('/seat/leader', { studentId: sid })`，成功后**重新拉取组长集合**（或 ElMessage 提示"已设为第 N 列组长"/"已取消组长"），失败（未排座）弹后端 fail 消息。

### 3.4 组长数据加载

- 加 `const leaderIds = ref(new Set())` 和 `async function loadLeaders()`：
  `const list = await http.get('/duty/committee') || []`；
  `leaderIds.value = new Set(list.filter(c => c.role === '组长').map(c => c.studentId))`，try/catch 静默。
- 页面初始化拉座位数据处（现有 `loadSeatMedals`/onMounted 附近，与 /seat/grid、/pet/pets 并行）调用 `loadLeaders()`；`toggleLeader` 成功后也调一次刷新。
- 辅助：`const isLeader = sid => leaderIds.value.has(sid)`。

### 3.5 座位卡 👑 角标（常驻，含简易模式）

- 👑 **放在 `.seat-pet` 块之外**（与 `.seat-name` 同层，在座位卡顶部），这样简易模式（`.seat-pet` 整块隐藏）下组长标识仍在：
  ```html
  <span v-if="s.studentId && disp.fields.leader && isLeader(s.studentId)" class="seat-leader">👑</span>
  ```
- `.seat-leader` 样式：绝对定位座位卡**右上角**（或顶部居中、奖牌行旁，视觉不挡姓名/宠物即可），font-size ~16px，z-index 高于宠物；`pointer-events:none`（不挡拖拽/点击）。
- 参考现有角标：`.seat-flag.flag-grad`（🎓）在 `.seat-pet` 内；组长角标因为要常驻，放 `.seat-pet` 外、座位卡容器内。

### 3.6 打印区带 👑

- 打印座位 `.print-seat`（`.seat-print-area` 内，现有 `print-name`/`print-stuno` 处）在姓名旁加：
  `<span v-if="disp.fields.leader && isLeader(s.studentId)" class="print-leader">👑</span>`
- 打印用同一 `isLeader`；黑白省墨模式下 👑 emoji 不受影响（emoji 自带色，可接受；不需额外样式）。
- 预览缩略图（.pv-board）**不用**加组长（缩略只示意姓名/行列）。

### 3.7 班委页（Duty.vue）

- 无需功能改动：组长 role='组长' 已随 `GET /duty/committee` 出现在班委名单。可选美化：把"组长"与其他班委分组或显示"第 N 列组长"（列号可由座位推，本期可不做，KISS）。

---

## 四、E2E（e2e/tests/e2e.spec.js，沿用"临时数据 + afterAll 清理，不污染 admin 真实学生"）

在座次表步骤（现步骤 14）追加：
1. 工具栏可见「👑 组长」按钮；点击进入指派模式（按钮变"✅ 完成指派"）。
2. 取两个**同列**、已排座的座位（用 `/seat/grid` 返回的 seats，找 col 相同的两个 studentId）：
   - 点第一个座位 → 该座位出现 `.seat-leader`（👑）；
   - 点同列第二个座位 → 第二个出现 `.seat-leader`，**第一个的 `.seat-leader` 消失**（同列只留一个）；
   - 再点第二个 → 取消，`.seat-leader` 消失。
3. 开关持久化：座位设置「显示」区有「👑 组长」勾选；取消勾选后 `.seat-leader` 隐藏（先设一个组长再测），刷新后保持；afterAll 已把 seat_display 写回默认 JSON，**默认 JSON 的 fields 里补 `leader:1`**（与前端默认一致）。
4. 打印：`emulateMedia('print')` 下，设了组长的座位 `.print-seat` 内含 👑（`.print-leader` 存在）。
5. **afterAll 清理组长记录**：拉 `GET /duty/committee`，对 `role==='组长'` 的逐条 `DELETE /duty/committee/{id}`（幂等、失败不阻断），避免给 admin 真实学生留下组长职务。
6. 备注：`/ai/test` 若遇第三方中转 503 是外部故障，跑套件时可临时放宽该行断言，**提交前恢复** `expect(tBody.data.ok).toBe(true)`。

---

## 五、工程约定

- 后端 JDK17 + Spring Boot 3.3.5 + JdbcTemplate；uid()/homeroomClassId() 用 SeatController 现有方法；新增方法写中文注释。改完重启服务（schtasks TeacherBackend / start-backend.bat，.bat 须 CRLF+ASCII）。
- 前端 `npm run build` 必须通过。
- KISS：不新增表/列/文件/依赖；组长复用 committee；组长集合复用 /duty/committee。
- 默认值兼容老用户：seat_display 缺 leader 按 1；无 role='组长' 记录即暂无组长。
- 提交只含本需求文件（后端 SeatController + 前端 Seat.vue + E2E + 本需求文档），**不要**夹带工作区其他无关改动；commit trailer：`Co-Authored-By: Claude Code <noreply@anthropic.com>`。
- 完成后更新 `需求说明.md` 落地结果段。

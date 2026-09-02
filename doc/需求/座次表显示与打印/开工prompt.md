# 开发分支开工任务书：座次表 v2 —— 设置收纳 · 简易模式 · 显示定制 · 打印

> 2026-09-02。粘贴给开发分支执行。
> 需求基线：`doc/需求/座次表显示与打印/需求说明.md`（先读）。
> 改动文件集中在 `frontend/src/views/Seat.vue` + 全局打印 CSS；后端仅设置项透传，无表结构变更。

## 已拍板决策

1. 行数/列数/过道全部收进「⚙ 座位设置」弹窗，过道标签与下拉配对；工具栏只留入口按钮。
2. 打印支持整页旋转 180°（平铺讲台视角），打印弹窗内带**预览简图**示意旋转。
3. 名字位置三选一：竖排贴左（现状）/ 横排居中 / 横排贴底。
4. 简易模式下未排座学生区简化为纯名单。
5. 打印时**空位不显示**（不画空框、不显示"— 空 —"，行列位置保留）；点打印直接调 `window.print()` 走浏览器打印。

---

## 一、后端（很小）

- 显示偏好存 setting 表，key=`seat_display`，v 为 JSON 字符串（VARCHAR(500) 够用），per-user。
- `SettingsController`：`GET /api/settings` 返回体带上 `seatDisplay`（原样 JSON 字符串或解析对象均可）；`POST /api/settings` 接收 `seatDisplay` 字段并 upsert（照现有 `pet_auto_deduct` 的写法）。无默认行，前端读不到用默认值。

## 二、前端：工具栏与设置弹窗

### 2.1 工具栏瘦身（Seat.vue 模板）

- 删除工具栏上的：行数控件、列数控件、过道1/2/3 标签与三个 el-select（共 7 个控件）。
- 新增两个按钮：`⚙ 设置`（打开 seatCfgDlg）、`🖨 打印`（打开 printDlg）。其余按钮不动。

### 2.2 ⚙ 座位设置弹窗（新 el-dialog，width 520px）

分三区，改完即时保存：

1. **布局**：行数、列数 el-input-number（1~15），change 走现有 saveConfig。
2. **过道**：三行配对排列，每行：`过道N` 文字 + el-select（无/第1~N列后），绑定 aisleModel[0..2]，change 走 onAisle。
3. **显示**：
   - 顶部醒目 switch：**简易模式**（只看座位，不显示宠物）。
   - 字段勾选（el-checkbox-group）：☐学号、☑性别底色（男蓝女粉，取消=统一白底）、☑Lv 徽章、☑毕业🎓角标、☑行列标签（第X排/第X列/过道）、☑未排座学生区。
   - 名字位置（el-radio-group）：竖排贴左 / 横排居中 / 横排贴底。

### 2.3 偏好状态与持久化

- Seat.vue 内建 `disp` reactive（或新建 `composables/useSeatDisplay.js`，二选一，KISS 优先直接写 Seat.vue）：
  ```js
  // 默认值 = 现状（宠物模式、竖排名、性别色、标签与角标全开）
  const disp = reactive({
    simple: 0,
    fields: { stuNo: 0, gender: 1, lv: 1, badge: 1, colLabel: 1, unseated: 1 },
    namePos: 'v-left'   // v-left | h-center | h-bottom
  })
  ```
- onMounted 时 GET /api/settings 读 seatDisplay，Object.assign 兜底合并；任意变更（watch deep）即 POST 保存（即时存，无需保存按钮）。
- 行数/列数/过道仍走 `/seat/config`（现状不动）。

### 2.4 座位卡显示逻辑

座位格与未排座卡统一受 `disp` 控制：

- **简易模式 simple=1**：
  - 座位格：不渲染宠物 `<img>`、☁️乌云/❄️/🌈/光环/✨/🩹/🎓 全部特效与角标、float 飘字、checkbox 保留（批量评价在简易模式仍可用）；
  - 座位格只显示姓名（横排居中，名字位置强制按 disp.namePos，简易模式默认 h-center——若用户手动改过 namePos 则尊重）；勾选学号时姓名旁/下方加学号小字；
  - 未排座区：从宠物卡网格变为**纯名单网格**（小格子：姓名 + 可选学号，无宠物图/领养按钮/进度条；点击仍可打开宠物详情？——简易模式下点击不弹宠物弹窗，纯查看）；
  - 图例行隐藏"男生/女生/过道"之外的宠物提示文案。
- **非简易模式**：现状全部保留，字段勾选细调：
  - `fields.gender=0`：座位格/卡片去掉男蓝女粉底色，统一白底灰边；
  - `fields.badge=0`：隐藏毕业 🎓 角标；`fields.lv=0`：未排座卡隐藏 Lv 徽章；
  - `fields.colLabel=0`：隐藏第X排/第X列/过道标签；
  - `fields.unseated=0`：整个未排座区隐藏；
  - `fields.stuNo=1`：姓名旁显示学号（座位格竖排时学号横排小字放在姓名下方或格子底部，不溢出即可）。
- **名字位置**（座位格加 `:class="disp.namePos"`，CSS 切换）：
  - `v-left`：现状（姓名竖排贴左，宠物大图占右）；
  - `h-center`：姓名横排居中加粗，宠物图缩小在名字下方；无宠物（空位/简易模式）时姓名垂直水平居中；
  - `h-bottom`：宠物图在上，姓名横排贴卡片底部居中。
- 孵化中蛋的暖黄光晕/点击摇晃在简易模式下同样隐藏（蛋图都不显示）。

## 三、前端：打印

### 3.1 🖨 打印弹窗（新 el-dialog，width 460px）

- 选项：
  - switch **倒转 180°（平铺讲台用）**，默认关；
  - switch **黑白省墨**，默认开；
  - switch **附未排座学生名单**，默认开。
- **预览简图**：弹窗内一个迷你示意棋盘（CSS 画：粉色/灰色"讲台"横条 + 3×4 个小灰格 + 格内"张三"样例字），倒转开关切换时简图 `transform: rotate(180deg)` 带 0.4s 过渡，讲台条在上方/下方一目了然。
- 按钮：`🖨 开始打印`（primary）。

### 3.2 打印区与打印样式

- Seat.vue 模板末尾加打印容器（屏幕上不可见，仅打印媒体显示）：
  ```html
  <div class="seat-print-area" :class="{ flip: printOpt.flip, mono: printOpt.mono }">
    <div class="print-podium">🏫 讲台</div>
    <div class="print-board" :style="boardGridStyle">
      <!-- 行列标签按 fields.colLabel；座位格只渲染 姓名(+学号)；空位 class=print-empty -->
    </div>
    <div v-if="printOpt.withUnseated && unSeatedPets.length" class="print-unseated">
      未排座学生：姓名、姓名……（横排逗号分隔）
    </div>
  </div>
  ```
- 打印内容规则：
  - **只打姓名+学号**（学号跟随 fields.stuNo），不打宠物图/特效/徽章（贴墙与平铺讲台场景，省墨清晰）；
  - 性别底色：黑白模式=白底黑框灰字；彩色模式（黑白关）=男蓝女粉浅底；
  - **空位**：`.print-empty { visibility: hidden }`——不画框不显示"空"字，网格位置保留（行列不错位）；
  - 倒转：`.seat-print-area.flip .print-board, .seat-print-area.flip .print-podium { transform: rotate(180deg); }`（讲台条与棋盘一起转，名单不转）。
- 全局 CSS（theme.css 末尾或新增 print.css 引入）：
  ```css
  /* 屏幕上打印区隐藏 */
  .seat-print-area { display: none; }
  @media print {
    @page { size: landscape; margin: 10mm; }
    body.seat-printing .app-sidebar, body.seat-printing .app-header,
    body.seat-printing .page-toolbar, body.seat-printing .legend,
    body.seat-printing .podium, body.seat-printing .board-grid,
    body.seat-printing .unseat-wrap, body.seat-printing .el-overlay,
    body.seat-printing .batch-bar { display: none !important; }
    body.seat-printing .seat-print-area { display: block; }
    .seat-print-area.mono { --boy-bg:#fff; --girl-bg:#fff; color:#000; }
    .seat-print-area.mono .print-seat { border: 1.5px solid #000; background: #fff; }
  }
  ```
  （选择器按 MainLayout 实际 class 名对齐：侧边栏/顶栏容器 class 自行 grep 确认。）
- 打印动作：
  ```js
  function doPrint() {
    document.body.classList.add('seat-printing')
    nextTick(() => {
      window.print()
      window.onafterprint = () => document.body.classList.remove('seat-printing')
    })
  }
  ```
- 打印偏好（flip/mono/withUnseated）并入 seat_display JSON 的 `print` 字段一起持久化。

## 四、E2E（e2e/tests/e2e.spec.js 追加）

1. ⚙ 设置弹窗：打开 → 改行数为 5、过道1 设第2列后 → 保存后棋盘行数/过道列正确（断言 grid 行数与过道 gutter 数量），刷新后保留。
2. 简易模式：打开开关 → 座位格内无 `img.seat-pet-img` 元素、无 ☁️/光环元素；未排座区为名单样式（无 img）；关闭后恢复。
3. 名字位置：切 h-center → 座位格带 h-center class；切回 v-left 恢复。
4. 打印弹窗：倒转开关切换 → 预览简图/打印区带 flip class；`page.emulateMedia({ media: 'print' })` 后打印区可见、空位格不可见（boundingBox 为 0 或 visibility hidden）、工具栏隐藏；黑白 class 存在。
5. 偏好持久化：刷新页面后简易模式/名字位置设置仍在。
6. `npm run build` 通过。

## 五、约定

- 前端：Vue3 `<script setup>` + Element Plus + theme.css 马卡龙变量；动画只用 transform/opacity。
- 不动座位数据/拖拽/随机排座/宠物任何业务逻辑；简易模式与打印均为视图层。
- 默认值必须等于现状（老用户无 seat_display 时界面与现在一致）。

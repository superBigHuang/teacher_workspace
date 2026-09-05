# 开发分支开工任务书：P2 题库（quiz_category + quiz_bank + 录入/导入/抽题）

> 2026-09-05。粘贴给开发分支执行。
> **先读基线**：`doc/需求/宠物系统升级/需求说明.md`（题库设计见"二、B. 每日通关 · 打怪兽"→"题库（老师自建）"小节；冲突以本任务书为准）。
> 本任务 = **只做 P2**：题库两张表 + 管理弹窗 + 6 个接口。**不做** P3 打怪兽、P4/P5 PK（抽题接口 draw 本期做好，供 P3/P4 调用）。

---

## 一、已拍板决策（不要再问）

1. **建表**：quiz_category / quiz_bank 直接进 `schema.sql`（项目幂等建表机制，重启自动建，DDL 原文照搬需求说明，不改字段）。
2. **UI 入口**：`/pet` 页顶部工具栏加「📚 题库」按钮 → 大弹窗（width 960px）`components/quiz/QuizDlg.vue`，与 P1 评价项/商店弹窗同模式（独立弹窗组件，数据自己拉，不依赖 usePets）。
3. **分类管理**：左栏卡片列表（emoji + 名称 + 题目数）；支持新增/改名/改 emoji/停用启用；**删除仅限空分类**（有题目返回错误提示）。
4. **题目管理**：右栏表格（题干/难度/答案/已用次数/最近使用/操作）；筛选=分类+难度+关键字；新增/编辑用内嵌小表单；删除 DELETE 确认。
5. **Excel 导入**：POST multipart，列顺序 = `分类 | 题目 | 答案 | 难度`（首行表头跳过）；分类名不存在则自动创建（难度列空/非 1~3 默认 1）；返回 `{导入条数, 跳过条数, 自动建分类数}`。不做模板下载（弹窗内文字说明列格式即可）。
6. **抽题 draw**：`{categoryId, difficulty}` → `ORDER BY (last_used_at IS NULL) DESC, last_used_at ASC, RAND() LIMIT 1`；抽中即 `use_count+1, last_used_at=NOW()`（**30 天内用过的排最后**——不排除，降权）；没题返回空 data（不报错）。
7. **答案可见性**：管理接口/弹窗正常返回 answer（老师自用）；draw 返回答案（P3 手机端用）。大屏侧 P3 再做答案遮蔽。
8. **难度口径**：1=易 / 2=中 / 3=难，前端徽章色 绿/橙/红。
9. **E2E**：11j 段（API 全链路 + 弹窗走查 + XLSX 导入复用 e2e 已有 XLSX 构造先例 + draw 降权断言：两题一旧一新，draw 两次先返回旧题）。

## 二、后端

### 2.1 schema.sql 追加（原文照搬需求说明 DDL）

### 2.2 新建 `QuizController.java`（挂 /api/quiz，extends BaseController）

```
GET  /quiz/categories                     分类列表（含每分类题目数 questionCount）
POST /quiz/category                       {id?, name, icon, enabled} 增/改
POST /quiz/category/delete/{id}           删除（有题目则报错）
GET  /quiz/list?categoryId=&difficulty=&kw=&page=1&size=50   题目分页 {rows,total}
POST /quiz/save                           {id?, categoryId, question, answer, difficulty} 增/改
DELETE /quiz/{id}                         删题目
POST /quiz/import                         multipart Excel 导入
POST /quiz/draw                           {categoryId, difficulty} 抽一题
```

注意：
- 全部按 uid 隔离（与其他表一致）。
- draw 只在 enabled=1 的分类/题目里抽。
- 导入按分类名找 `quiz_category`（enabled 不限），找不到建（icon 默认 📚）。
- 题干重复不校验（老师自建，KISS）。

## 三、前端

- `/pet` 工具栏：「📚 题库」按钮（在 ⚙评价项 左侧）。
- `components/quiz/QuizDlg.vue`：v-model 控制；左栏分类（含"全部分类"虚拟项）+ 右栏题目表格 + 导入按钮 + 内嵌题目表单（el-dialog 内嵌 el-form，编辑时填充）。
- 引入后 Pet.vue 挂载一行 `<QuizDlg v-model="quizDlg" />`。

## 四、不做（后续期做）

- /boss /pk 大屏与手机控制卡（P3/P4）。
- AI 批量生成（留口子：导入接口结构不变）。
- 学生自测模式。

## 五、验收

- 分类增改停删（空分类才可删）✓
- 题目增改删 + 筛选 ✓
- Excel 导入（自动建分类、难度缺省、跳过空行）✓
- draw：难度过滤 + 最久未用优先 + 抽中计数 ✓
- E2E 全绿 ✓

import { test, expect } from '@playwright/test'
import * as XLSX from 'xlsx'
import fs from 'fs'
import path from 'path'

// 全站 E2E：登录一次，按模块逐步走查，每步带断言。
// 依赖后端 8088 + 前端 5173 已启动；当前库为真实花名册（40 人，男20女20）。
// 写操作原则：考勤/纪律/速记等针对临时「E2E测试生」，成绩导入用 E2E* 考试，
// 结束后在 afterAll 与删除步骤中统一清理，不污染真实学生档案。

const PWD = '123456'
const API = '/api'
const EXAM_NAMES = ['E2E摸底考', 'E2E月考', 'E2E学期中考']
// E2E 专用注册账号：固定账号名，重复运行时注册会失败但登录仍成功，库中只留一个测试账号
const NU = 'e2e_teacher'
const NU_PWD = 'e2e123456'
const NU_PWD2 = 'e2e654321'

let token = ''
let nuToken = ''         // E2E 注册新账号的 token（数据隔离验证用）
let students = []        // 真实学生名单（来自 API）
let firstStudent = ''    // 第一个真实学生姓名，用于搜索
let testStudentId = null // 临时测试学生 id
let testStudentId2 = null // 临时测试学生2（自创皮肤领养验证用）

const nav = (page, name) => page.locator('.nav-item', { hasText: name }).first()
const authHeader = () => ({ Authorization: 'Bearer ' + token })

// 生成一次考试的 xlsx（学号|姓名|语文|数学|英语）并导入，返回 {examId,students,subjects}
async function importExam(request, name, date, bump) {
  const aoa = [['学号', '姓名', '语文', '数学', '英语']]
  students.forEach((s, i) => {
    aoa.push([
      s.stu_no, s.name,
      String(60 + ((i * 7 + bump) % 40)),
      String(55 + ((i * 11 + bump) % 45)),
      String(65 + ((i * 5 + bump) % 35)),
    ])
  })
  const wb = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(wb, XLSX.utils.aoa_to_sheet(aoa), '成绩')
  const buffer = XLSX.write(wb, { type: 'buffer', bookType: 'xlsx' })
  const resp = await request.post(API + '/score/import', {
    headers: authHeader(),
    multipart: {
      file: {
        name: name + '.xlsx',
        mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        buffer,
      },
      examName: name,
      examDate: date,
    },
  })
  const body = await resp.json()
  expect(body.code).toBe(200)
  return body.data
}

test.describe('班主任工作台 全站走查（真实40人数据）', () => {
  test.beforeAll(async ({ request }) => {
    const login = await request.post(API + '/auth/login', { data: { username: 'admin', password: PWD } })
    token = (await login.json()).data.token
    const r = await request.get(API + '/roster/students', { headers: authHeader() })
    students = (await r.json()).data
    firstStudent = students[0]?.name || ''
  })

  test.afterAll(async ({ request }) => {
    // 清理 E2E 临时数据：临时考试、E2E/速记待办、测试学生（幂等，失败不阻断）
    try {
      const ex = (await (await request.get(API + '/score/exams', { headers: authHeader() })).json()).data || []
      for (const e of ex) if (EXAM_NAMES.includes(e.name)) {
        await request.delete(API + '/score/exam/' + e.id, { headers: authHeader() })
      }
    } catch (e) { /* 忽略 */ }
    try {
      const td = (await (await request.get(API + '/todo', { headers: authHeader() })).json()).data || []
      for (const t of td) {
        const title = String(t.title || '')
        if (title.includes('E2E') || title.includes('班会') || t.source === 'QUICK') {
          await request.delete(API + '/todo/' + t.id, { headers: authHeader() })
        }
      }
    } catch (e) { /* 忽略 */ }
    try {
      // 请假单据兜底清理（正常流程已在步骤内删除，此处防中途失败残留）
      if (testStudentId) {
        const ll = (await (await request.get(API + '/leave/list',
          { headers: authHeader(), params: { studentId: testStudentId } })).json()).data || []
        for (const l of ll) {
          await request.delete(API + '/leave/' + l.id, { headers: authHeader() })
        }
      }
    } catch (e) { /* 忽略 */ }
    try {
      // 评语兜底清理（正常流程已在步骤内删除，此处防中途失败残留）
      if (testStudentId) await request.delete(API + '/comment/' + testStudentId, { headers: authHeader() })
    } catch (e) { /* 忽略 */ }
    try {
      // 成就兜底清理：删除 E2E 自定义成就定义（连带获得记录），防中途失败残留
      const dd = (await (await request.get(API + '/achievement/defs', { headers: authHeader() })).json()).data || []
      for (const d of dd) {
        if (!d.builtin && String(d.name || '').includes('E2E')) {
          await request.delete(API + '/achievement/def/' + d.id, { headers: authHeader() })
        }
      }
    } catch (e) { /* 忽略 */ }
    try {
      // 宠物园兜底：删除 E2E 测试商品（兑换/流水/宠物随学生级联删除），恢复自动扣分开关
      const items = (await (await request.get(API + '/pet/shop-items', { headers: authHeader() })).json()).data || []
      for (const it of items) {
        if (String(it.name || '').includes('E2E')) {
          await request.delete(API + '/pet/shop-item/' + it.id, { headers: authHeader() })
        }
      }
      await request.post(API + '/dashboard/settings', { headers: authHeader(), data: { pet_auto_deduct: '1' } })
    } catch (e) { /* 忽略 */ }
    try {
      // 恢复 AI 启用（11c 步骤临时关闭走模板；admin 为真实使用账号）
      await request.put(API + '/ai/config', { headers: authHeader(), data: { enabled: true } })
    } catch (e) { /* 忽略 */ }
    try {
      if (testStudentId) await request.delete(API + '/roster/student/' + testStudentId, { headers: authHeader() })
    } catch (e) { /* 忽略 */ }
    try {
      // 自创皮肤验证用的临时学生（宠物随学生级联删除）
      if (testStudentId2) await request.delete(API + '/roster/student/' + testStudentId2, { headers: authHeader() })
    } catch (e) { /* 忽略 */ }
    try {
      // E2E 自创皮肤清理：须在学生删除之后（被宠物引用时后端禁删）
      const sk = (await (await request.get(API + '/pet/skins', { headers: authHeader() })).json()).data || []
      for (const s of sk) {
        if (!s.builtIn && String(s.name || '').includes('E2E')) {
          await request.delete(API + '/pet/skin/' + s.id, { headers: authHeader() })
        }
      }
    } catch (e) { /* 忽略 */ }
  })

  test('登录 → 8 大模块 → 日常速记 → 删除验证 → 刷新保活', async ({ page, request }) => {
    // ============ 0. 登录 / 注册页 ============
    await test.step('登录页：注册 Tab 含邀请码；错误密码提示；账号密码登录进入', async () => {
      await page.goto('/')
      await expect(page).toHaveURL(/\/login/)
      // 注册 Tab：账号/密码/确认密码/邀请码 四个输入框
      await page.locator('.tab', { hasText: '注册' }).click()
      await expect(page.getByPlaceholder('邀请码（选填）')).toBeVisible()
      await expect(page.getByPlaceholder('确认密码')).toBeVisible()
      await page.locator('.tab', { hasText: '登录' }).click()
      // 错误密码 → 报错提示
      await page.getByPlaceholder('账号').fill('admin')
      await page.getByPlaceholder('密码').first().fill('000000')
      await page.getByRole('button', { name: /进入工作台/ }).click()
      await expect(page.locator('.el-message--error').first()).toBeVisible()
      // 正确密码 → 进入工作台
      await page.getByPlaceholder('密码').first().fill(PWD)
      await page.getByRole('button', { name: /进入工作台/ }).click()
      await expect(page).toHaveURL(/\/dashboard/)
      await expect(page.locator('.greet-title')).toBeVisible()
      // 侧边栏底部用户区显示
      await expect(page.locator('.user-area .user-name')).toBeVisible()
    })

    // ============ 0b. 注册新账号 / 数据隔离 / 学校共享记忆 / 改密码（API 级） ============
    await test.step('账号体系：注册新账号 → 数据独立 → 学校记忆跨账号共享 → 改密码', async () => {
      const nuAuth = () => ({ Authorization: 'Bearer ' + nuToken })
      // 注册（首次运行成功；重复运行账号已存在则忽略，后续登录验证）
      await request.post(API + '/auth/register',
        { data: { username: NU, password: NU_PWD, inviteCode: 'E2E' } })
      const login = await request.post(API + '/auth/login', { data: { username: NU, password: NU_PWD } })
      const lb = await login.json()
      expect(lb.code).toBe(200)
      nuToken = lb.data.token

      // 数据隔离：新账号花名册为空（admin 有 40 人）
      const rs = await request.get(API + '/roster/students', { headers: nuAuth() })
      expect((await rs.json()).data).toHaveLength(0)
      // 新账号脚手架：2 个默认班级
      const cs = await request.get(API + '/timetable/classes', { headers: nuAuth() })
      expect((await cs.json()).data.length).toBeGreaterThanOrEqual(2)
      // 保存资料：学校写入共享记忆
      const up = await request.put(API + '/settings/profile',
        { headers: nuAuth(), data: { displayName: 'E2E老师', school: 'E2E糖果中学' } })
      expect((await up.json()).code).toBe(200)
      // 跨账号共享：admin 也能搜到这所学校
      const sc = await request.get(API + '/settings/schools',
        { headers: authHeader(), params: { q: 'E2E糖果' } })
      const names = (await sc.json()).data.map(s => s.name || s)
      expect(names).toContain('E2E糖果中学')
      // 改密码：新密码可登录，旧密码被拒；最后改回保证用例幂等
      const cp = await request.post(API + '/auth/change-password',
        { headers: nuAuth(), data: { oldPassword: NU_PWD, newPassword: NU_PWD2 } })
      expect((await cp.json()).code).toBe(200)
      const l2 = await request.post(API + '/auth/login', { data: { username: NU, password: NU_PWD2 } })
      expect((await l2.json()).code).toBe(200)
      const lBad = await request.post(API + '/auth/login', { data: { username: NU, password: NU_PWD } })
      expect((await lBad.json()).code).not.toBe(200)
      await request.post(API + '/auth/change-password',
        { headers: nuAuth(), data: { oldPassword: NU_PWD2, newPassword: NU_PWD } })
    })

    // ============ 0c. 个人设置页 ============
    await test.step('个人设置：侧边栏入口进入，五个设置卡（含 AI 助手）可见', async () => {
      await page.locator('.action-item', { hasText: '个人设置' }).click()
      await expect(page).toHaveURL(/\/settings/)
      await expect(page.locator('.candy-card')).toHaveCount(5)
      await expect(page.locator('.candy-card h3', { hasText: '修改密码' })).toBeVisible()
      await expect(page.locator('.candy-card h3', { hasText: '我的班级' })).toBeVisible()
      await expect(page.locator('.candy-card h3', { hasText: '我的学科' })).toBeVisible()
      await expect(page.locator('.candy-card h3', { hasText: 'AI 助手' })).toBeVisible()

      // 学期起止日期保存 + 学期素材聚合（评语/通知单/档案/请假共用底层）
      const upTerm = await request.put(API + '/settings/profile', {
        headers: authHeader(),
        data: { termStart: '2026-09-01', termEnd: '2027-01-31' },
      })
      expect((await upTerm.json()).code).toBe(200)
      const mat = await request.get(API + '/term/materials', { headers: authHeader() })
      const matBody = await mat.json()
      expect(matBody.code).toBe(200)
      expect(matBody.data.termStart).toBe('2026-09-01')
      expect(matBody.data.students).toHaveLength(40)
      const m0 = matBody.data.students[0]
      for (const k of ['late', 'absent', 'leaveDays', 'homeworkMissing', 'discipline', 'commCount', 'dutyCount', 'roles', 'exams']) {
        expect(m0).toHaveProperty(k)
      }
      const one = await request.get(API + '/term/material', {
        headers: authHeader(), params: { studentId: m0.studentId },
      })
      expect((await one.json()).data.studentId).toBe(m0.studentId)

      // ---- AI 接入层：配置结构/key 不明取/关闭走模板/测试连接接口 ----
      const cfg = await request.get(API + '/ai/config', { headers: authHeader() })
      const cfgBody = await cfg.json()
      expect(cfgBody.code).toBe(200)
      for (const k of ['enabled', 'baseUrl', 'model', 'hasDefaultKey', 'hasUserKey', 'keyHint']) {
        expect(cfgBody.data).toHaveProperty(k)
      }
      // 响应中绝不含 apiKey 明文字段
      expect(JSON.stringify(cfgBody.data)).not.toContain('apiKey')

      // 关闭 AI → 评语/寄语均降级模板，内容含学生真实姓名
      await request.put(API + '/ai/config', { headers: authHeader(), data: { enabled: false } })
      const gc = await request.post(API + '/ai/generate', {
        headers: authHeader(), data: { scene: 'comment', studentId: m0.studentId },
      })
      const gcBody = await gc.json()
      expect(gcBody.code).toBe(200)
      expect(gcBody.data.source).toBe('template')
      expect(gcBody.data.content.length).toBeGreaterThan(50)
      expect(gcBody.data.content).toContain(m0.name)
      const gm = await request.post(API + '/ai/generate', {
        headers: authHeader(), data: { scene: 'message', studentId: m0.studentId },
      })
      const gmBody = await gm.json()
      expect(gmBody.data.source).toBe('template')
      expect(gmBody.data.content.length).toBeGreaterThan(0)
      // 恢复启用（留空字段回退内置共享星火服务）
      await request.put(API + '/ai/config', { headers: authHeader(), data: { enabled: true } })
      // 测试连接：内置共享 key，真实星火服务应连通（ok=true，约 1~2s）
      const t = await request.post(API + '/ai/test', { headers: authHeader(), data: {} })
      const tBody = await t.json()
      expect(tBody.code).toBe(200)
      expect(tBody.data.ok).toBe(true)

      // 回到工作台，继续后续模块走查
      await nav(page, '工作台').click()
      await expect(page).toHaveURL(/\/dashboard/)
    })

    // ============ 1. 工作台（只读） ============
    await test.step('工作台：问候卡/统计卡(40人)/工作卡/节假日', async () => {
      await expect(page.getByText('班主任 ·')).toBeVisible()
      await expect(page.locator('.mini-stat')).toHaveCount(4)
      await expect(page.locator('.mini-stat .ms-num').first()).toContainText('40')
      await expect(page.locator('.work-card')).toHaveCount(6)
      await expect(page.locator('.work-card.holiday .holiday-name')).toBeVisible()
    })

    // ============ 2. 花名册 ============
    await test.step('花名册：统计 40/20/20、搜索、性别筛选、档案抽屉', async () => {
      await nav(page, '花名册').click()
      await expect(page).toHaveURL(/\/roster/)
      await expect(page.locator('.page-title')).toContainText('学生花名册')
      const nums = page.locator('.stat-card .num')
      await expect(nums.nth(0)).toHaveText('40')
      await expect(nums.nth(1)).toHaveText('20')
      await expect(nums.nth(2)).toHaveText('20')
      await expect.poll(async () => await page.locator('.stu-grid .stu-card').count(), { timeout: 10000 }).toBe(40)
      // 搜索第一个真实学生 → 1 张
      await page.getByPlaceholder('搜索姓名/学号').fill(firstStudent)
      await expect.poll(async () => await page.locator('.stu-grid .stu-card').count(), { timeout: 10000 }).toBe(1)
      await page.getByPlaceholder('搜索姓名/学号').fill('')
      await expect.poll(async () => await page.locator('.stu-grid .stu-card').count(), { timeout: 10000 }).toBe(40)
      // 男生筛选 → 20
      await page.locator('.el-radio-button', { hasText: '男生' }).click()
      await expect.poll(async () => await page.locator('.stu-grid .stu-card').count(), { timeout: 10000 }).toBe(20)
      await page.locator('.el-radio-button', { hasText: '全部' }).click()
      await expect.poll(async () => await page.locator('.stu-grid .stu-card').count(), { timeout: 10000 }).toBe(40)
      // 打开第一个学生档案抽屉
      await page.locator('.stu-grid .stu-card').first().click()
      const drawer = page.locator('.el-drawer', { hasText: '的档案' })
      await expect(drawer).toBeVisible()
      await expect(drawer.getByText('家长联系方式')).toBeVisible()
      await drawer.locator('.el-drawer__close-btn').click()
      await expect(drawer).toBeHidden()
    })

    // ============ 3. 座次表 ============
    await test.step('座次表：随机排座后 40 人落位、讲台/空座位', async () => {
      await nav(page, '座次表').click()
      await expect(page).toHaveURL(/\/seat/)
      await expect(page.locator('.podium')).toContainText('讲台')
      await page.getByRole('button', { name: /随机排座/ }).click()
      await expect(page.locator('.el-message--success').first()).toBeVisible()
      await expect.poll(async () =>
        await page.locator('.seat.boy, .seat.girl').count(), { timeout: 15000 }).toBeGreaterThan(35)
      await expect(page.locator('.seat.empty-seat').first()).toBeVisible()
    })

    // ============ 4. 值日表·班委（任务到人） ============
    await test.step('值日表：五日卡、每人有明确任务标签、添加弹窗含任务下拉', async () => {
      await nav(page, '值日表').click()
      await expect(page).toHaveURL(/\/duty/)
      await expect(page.locator('.day-card')).toHaveCount(5)
      // 20 名值日学生，人人带具体事项标签（不是「未分配」）
      await expect.poll(async () =>
        await page.locator('.duty-person').count(), { timeout: 15000 }).toBeGreaterThan(10)
      const personCount = await page.locator('.duty-person').count()
      const taskCount = await page.locator('.duty-person .dp-task').count()
      expect(taskCount).toBe(personCount)
      const firstTask = (await page.locator('.duty-person .dp-task').first().textContent() || '').trim()
      expect(['扫地', '擦黑板', '倒垃圾', '擦窗户', '整理讲台']).toContain(firstTask)
      // 值日事项总项标签存在
      await expect(page.locator('.task-tag').first()).toBeVisible()
      // 添加值日弹窗：含「任务」下拉（验证后取消，不写库）
      await page.getByRole('button', { name: '添加值日' }).click()
      const addDlg = page.locator('.el-dialog', { hasText: '添加值日' })
      await expect(addDlg).toBeVisible()
      await expect(addDlg.getByText('任务', { exact: true })).toBeVisible()
      await addDlg.getByRole('button', { name: '取消' }).click()
      await expect(addDlg).toBeHidden()
    })

    // ============ 5. 成绩分析（API 导入两次考试） ============
    await test.step('成绩分析：导入两次考试 → 科目统计卡/图表/明细', async () => {
      await importExam(request, 'E2E摸底考', '2026-07-01', 0)
      await importExam(request, 'E2E月考', '2026-08-01', 13)
      await nav(page, '成绩分析').click()
      await expect(page).toHaveURL(/\/score/)
      await expect.poll(async () =>
        await page.locator('.score-card').count(), { timeout: 20000 }).toBeGreaterThanOrEqual(3)
      await expect.poll(async () =>
        await page.locator('canvas').count(), { timeout: 20000 }).toBeGreaterThanOrEqual(2)
      await expect.poll(async () =>
        await page.locator('.el-table__row').count(), { timeout: 20000 }).toBeGreaterThan(35)
    })

    await test.step('成绩分析：选择对比考试 → 进退步对比表', async () => {
      await page.locator('.el-select').nth(1).click()
      await page.getByRole('option', { name: /E2E摸底/ }).click()
      await expect(page.getByText('考试进退步对比')).toBeVisible()
      await expect(page.locator('.el-tag').filter({ hasText: /↑|↓/ }).first()).toBeVisible()
    })

    // ============ 5b. 家长通知单 ============
    await test.step('家长通知单：报告数据包 40 人字段齐全、等级合法、排名连续；打印页可打开', async () => {
      const exAll = (await (await request.get(API + '/score/exams', { headers: authHeader() })).json()).data
      const e1 = exAll.find(e => e.name === 'E2E月考')
      const e0 = exAll.find(e => e.name === 'E2E摸底考')
      const rep = await request.get(API + '/score/report', {
        headers: authHeader(), params: { examId: e1.id, compareExamId: e0.id },
      })
      const rb = await rep.json()
      expect(rb.code).toBe(200)
      expect(rb.data.students).toHaveLength(40)
      const s0 = rb.data.students[0]
      for (const k of ['stuNo', 'name', 'subjects', 'total', 'rank', 'totalStudents', 'rankChange', 'attendance', 'homeworkMissing', 'disciplineCount', 'comment']) {
        expect(s0).toHaveProperty(k)
      }
      expect(s0.subjects.length).toBeGreaterThanOrEqual(3)
      for (const sub of s0.subjects) {
        for (const k of ['score', 'classAvg', 'classMax', 'grade']) expect(sub).toHaveProperty(k)
        expect(['A', 'B', 'C', '—']).toContain(sub.grade)
      }
      // 排名连续 1..N（E2E 考试全员有成绩）
      const ranked = rb.data.students.filter(s => s.rank).sort((a, b) => a.rank - b.rank)
      ranked.forEach((s, i) => expect(s.rank).toBe(i + 1))
      // 打印页独立路由可打开（无侧边栏布局，未登录态不跳登录页）
      await page.goto('/print/report')
      await expect(page.locator('.report-root')).toBeVisible()
      await page.goto('/dashboard')
    })

    // ============ 6. 家长联系方式 ============
    await test.step('家长联系方式：通讯录、搜索、沟通弹窗', async () => {
      await nav(page, '家长联系方式').click()
      await expect(page).toHaveURL(/\/parent/)
      const contactRows = page.locator('.el-card', { hasText: '家长通讯录' }).locator('.el-table__row')
      await expect.poll(async () => await contactRows.count(), { timeout: 15000 }).toBeGreaterThan(35)
      await page.getByPlaceholder('搜索学生/家长/电话').fill(firstStudent)
      await expect.poll(async () => await contactRows.count(), { timeout: 10000 }).toBe(1)
      await page.getByPlaceholder('搜索学生/家长/电话').fill('')
      await expect.poll(async () => await contactRows.count(), { timeout: 10000 }).toBeGreaterThan(35)
      await page.locator('.el-card', { hasText: '家长通讯录' }).getByRole('button', { name: /沟通/ }).first().click()
      const dlg = page.locator('.el-dialog', { hasText: '家校沟通管理' })
      await expect(dlg).toBeVisible()
      await expect(dlg.getByText('添加沟通记录')).toBeVisible()
      await page.keyboard.press('Escape')
      await expect(dlg).toBeHidden()
    })

    // ============ 7. 课程表 ============
    await test.step('课程表：周课表网格、科目图例、班级 tab', async () => {
      await nav(page, '课程表').click()
      await expect(page).toHaveURL(/\/timetable/)
      await expect.poll(async () =>
        await page.locator('.tt-cell').count(), { timeout: 15000 }).toBeGreaterThan(30)
      await expect.poll(async () =>
        await page.locator('.tt-cell.filled').count(), { timeout: 15000 }).toBeGreaterThan(20)
      await expect.poll(async () =>
        await page.locator('.legend-chip').count(), { timeout: 10000 }).toBeGreaterThanOrEqual(10)
      await expect.poll(async () =>
        await page.locator('.el-tabs__item').count(), { timeout: 10000 }).toBeGreaterThanOrEqual(2)
    })

    // ============ 8. 待办事项 ============
    await test.step('待办事项：新增、勾选完成', async () => {
      await nav(page, '待办事项').click()
      await expect(page).toHaveURL(/\/todo/)
      const title = 'E2E自动测试待办_' + Date.now()
      await page.getByPlaceholder(/新增待办/).fill(title)
      await page.getByRole('button', { name: '添加' }).click()
      await expect(page.locator('.el-message--success').first()).toBeVisible()
      const item = page.locator('.todo-item', { hasText: title })
      await expect(item).toBeVisible()
      await item.locator('.el-checkbox').click()
      await expect.poll(async () =>
        await item.evaluate(el => el.classList.contains('done')), { timeout: 10000 }).toBeTruthy()
    })

    // ============ 9. 准备临时测试学生（写操作都针对它） ============
    await test.step('准备：API 创建临时测试学生 E2E测试生', async () => {
      const resp = await request.post(API + '/roster/student', {
        headers: authHeader(),
        data: { stuNo: '999', name: 'E2E测试生', gender: '男' },
      })
      testStudentId = (await resp.json()).data
      expect(testStudentId).toBeTruthy()
    })

    // ============ 10. 工作台速记（考勤/纪律/沟通/待办 多归类） ============
    await test.step('工作台：一句话速记归类并一键保存（关联测试生）', async () => {
      await page.goto('/dashboard')
      await page.getByPlaceholder('输入速记内容，回车智能归类...')
        .fill('E2E测试生今天迟到了，上课说话违纪，已电话联系家长，记得准备班会PPT')
      await page.getByRole('button', { name: '归类' }).click()
      const dlg = page.locator('.el-dialog', { hasText: '速记智能归类预览' })
      await expect(dlg).toBeVisible()
      await expect(dlg.getByText('早读考勤')).toBeVisible()
      await expect(dlg.getByText('家校沟通')).toBeVisible()
      await dlg.getByRole('button', { name: '一键保存' }).click()
      await expect(page.locator('.el-message--success').first()).toBeVisible()
      await expect(dlg).toBeHidden()
    })

    // ============ 11. 班级日常 ============
    await test.step('班级日常：考勤标记测试生迟到并保存', async () => {
      await page.goto('/daily')
      await expect(page.locator('.page-title')).toContainText('班级日常')
      await expect.poll(async () =>
        await page.locator('.att-item').count(), { timeout: 15000 }).toBeGreaterThan(35)
      const testItem = page.locator('.att-item', { hasText: 'E2E测试生' })
      await testItem.getByText('迟到').click()
      await expect(testItem).toHaveClass(/st-warn/)
      await page.getByRole('button', { name: '保存考勤' }).click()
      await expect(page.locator('.el-message--success').first()).toBeVisible()
    })

    await test.step('班级日常：作业收缴选科目后加载名单', async () => {
      await page.getByText('作业收缴').click()
      const hwPane = page.locator('.el-tab-pane').filter({ hasText: '勾选表示' })
      await hwPane.locator('.el-select').first().click()
      await page.getByRole('option', { name: '数学' }).click()
      await expect.poll(async () =>
        await page.locator('.hw-item').count(), { timeout: 15000 }).toBeGreaterThan(35)
    })

    await test.step('班级日常：课堂纪律页签可切换', async () => {
      await page.getByText('课堂纪律').click()
      await expect(page.locator('.el-table').first()).toBeVisible()
    })

    // ============ 11b. 请假闭环（UI 页签/工作台卡 + API 全链路） ============
    await test.step('请假闭环：登记联动考勤(不覆盖手动标记)→工作台卡→销假→汇总→删除回滚', async () => {
      // UI：请假管理页签可切换
      await page.locator('.el-tabs__item', { hasText: '请假管理' }).click()
      await expect(page.locator('.leave-h').first()).toContainText('请假中')

      // 本地日期（避免 UTC toISOString 跨天偏差）
      const fmt = d => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
      const d0 = fmt(new Date())
      const d1 = fmt(new Date(Date.now() + 86400000))
      const d2 = fmt(new Date(Date.now() + 2 * 86400000))

      // 登记病假 2 天（今天~明天）
      const cr = await request.post(API + '/leave', {
        headers: authHeader(),
        data: { studentId: testStudentId, leaveType: '病假', startDate: d0, endDate: d1, days: 2, reason: 'E2E感冒发烧', notifyMethod: '微信' },
      })
      const leaveId = (await cr.json()).data
      expect(leaveId).toBeTruthy()

      // 请假中列表含该单据且未超期
      const act = (await (await request.get(API + '/leave/active', { headers: authHeader() })).json()).data
      const mine = act.find(a => a.id === leaveId)
      expect(mine).toBeTruthy()
      expect(mine.overdue).toBe(false)

      // 联动考勤：明天空白日期自动置"请假"；今天手动标记的"迟到"保留不覆盖
      const attT = (await (await request.get(API + '/daily/attendance', { headers: authHeader(), params: { date: d1 } })).json()).data
      expect(attT.students.find(s => s.id === testStudentId).status).toBe('请假')
      const att0 = (await (await request.get(API + '/daily/attendance', { headers: authHeader(), params: { date: d0 } })).json()).data
      expect(att0.students.find(s => s.id === testStudentId).status).toBe('迟到')

      // 工作台请假提醒卡可见
      await page.goto('/dashboard')
      await expect(page.locator('.leave-card')).toBeVisible()
      await expect(page.locator('.leave-card')).toContainText('请假中')

      // 续假：编辑结束日期延长到后天，新区间考勤自动补"请假"
      const up = await request.put(API + '/leave/' + leaveId, {
        headers: authHeader(),
        data: { endDate: d2, days: 3, reason: 'E2E续假一天' },
      })
      expect((await up.json()).code).toBe(200)
      const attD2 = (await (await request.get(API + '/daily/attendance', { headers: authHeader(), params: { date: d2 } })).json()).data
      expect(attD2.students.find(s => s.id === testStudentId).status).toBe('请假')

      // 销假
      const rr = await request.post(API + '/leave/' + leaveId + '/return', { headers: authHeader(), data: {} })
      expect((await rr.json()).code).toBe(200)
      const act2 = (await (await request.get(API + '/leave/active', { headers: authHeader() })).json()).data
      expect(act2.find(a => a.id === leaveId)).toBeFalsy()

      // 学期汇总：测试生病假累计 2 天
      const sum = (await (await request.get(API + '/leave/summary', { headers: authHeader() })).json()).data
      const sm = sum.students.find(s => s.studentId === testStudentId)
      expect(sm).toBeTruthy()
      expect(Number(sm.sickDays)).toBeGreaterThanOrEqual(2)

      // 删除单据 → 联动考勤精确回滚：明天不再是"请假"
      const dr = await request.delete(API + '/leave/' + leaveId, { headers: authHeader() })
      expect((await dr.json()).code).toBe(200)
      const att2 = (await (await request.get(API + '/daily/attendance', { headers: authHeader(), params: { date: d1 } })).json()).data
      expect(att2.students.find(s => s.id === testStudentId).status).not.toBe('请假')
    })

    // ============ 11c. 期末评语 ============
    await test.step('期末评语：素材齐全、生成草稿→编辑保存→刷新仍在→删除；评语册打印页可打开', async () => {
      // 本步骤生成走规则模板（秒回、不依赖外网）；真实星火链路已在 0c 测试连接 + 手工验证
      await request.put(API + '/ai/config', { headers: authHeader(), data: { enabled: false } })
      // 学期内考试（成长档案走势验证用），afterAll 按 EXAM_NAMES 清理
      await importExam(request, 'E2E学期中考', '2026-09-15', 25)

      // 全班素材接口：40+ 人、字段齐全
      const mm = await (await request.get(API + '/comment/materials', { headers: authHeader() })).json()
      expect(mm.code).toBe(200)
      expect(mm.data.students.length).toBeGreaterThanOrEqual(40)
      const m0 = mm.data.students[0]
      for (const k of ['stuNo', 'name', 'late', 'absent', 'leaveDays', 'homeworkMissing', 'discipline', 'commCount', 'rank', 'rankChange', 'rankSegment']) {
        expect(m0).toHaveProperty(k)
      }

      // UI：评语页打开、名单渲染、单人生成草稿（不落库，不污染真实学生）
      await nav(page, '期末评语').click()
      await expect(page).toHaveURL(/\/comment/)
      await expect.poll(async () =>
        await page.locator('.cm-item').count(), { timeout: 15000 }).toBeGreaterThanOrEqual(40)
      await page.locator('.cm-item').first().click()
      await page.getByRole('button', { name: '✨ 生成草稿' }).click()
      await expect.poll(async () =>
        (await page.locator('.cm-editor textarea').inputValue()).length,
        { timeout: 90000 }).toBeGreaterThan(50)

      // API 全链路：测试生生成→编辑保存→再查仍在→评语册含该生→删除
      const gb = await (await request.post(API + '/comment/generate',
        { headers: authHeader(), data: { studentId: testStudentId } })).json()
      expect(gb.code).toBe(200)
      expect(gb.data.content.length).toBeGreaterThan(50)
      const edited = gb.data.content + '【E2E编辑标记】'
      const sv = await request.put(API + '/comment/save',
        { headers: authHeader(), data: { studentId: testStudentId, content: edited, source: 'manual' } })
      expect((await sv.json()).code).toBe(200)
      const mm2 = await (await request.get(API + '/comment/materials', { headers: authHeader() })).json()
      expect(mm2.data.saved[String(testStudentId)]).toBeTruthy()
      expect(mm2.data.saved[String(testStudentId)].content).toContain('E2E编辑标记')
      const lk = await (await request.get(API + '/comment/list', { headers: authHeader() })).json()
      expect(lk.data.list.find(x => x.studentId === testStudentId)).toBeTruthy()
      const dl = await request.delete(API + '/comment/' + testStudentId, { headers: authHeader() })
      expect((await dl.json()).code).toBe(200)
      const mm3 = await (await request.get(API + '/comment/materials', { headers: authHeader() })).json()
      expect(mm3.data.saved[String(testStudentId)]).toBeFalsy()

      // 评语册打印页独立路由可打开
      await page.goto('/print/comments')
      await expect(page.locator('.book-root')).toBeVisible()
    })

    // ============ 11d. 成长档案 ============
    await test.step('成长档案：档案接口结构齐全、时间线含迟到；抽屉档案页签数字卡+走势图', async () => {
      // API：测试生档案（步骤11手动标记了今天迟到，步骤10速记写了纪律/沟通）
      const ar = await (await request.get(API + '/comment/archive',
        { headers: authHeader(), params: { studentId: testStudentId } })).json()
      expect(ar.code).toBe(200)
      for (const k of ['material', 'trend', 'timeline']) expect(ar.data).toHaveProperty(k)
      expect(ar.data.timeline.some(t => t.type === 'attendance')).toBe(true)
      // 真实学生学期内有考试 → 走势含 E2E学期中考
      const realSid = students[0].id
      const ar2 = await (await request.get(API + '/comment/archive',
        { headers: authHeader(), params: { studentId: realSid } })).json()
      expect(ar2.data.trend.some(t => t.name === 'E2E学期中考')).toBe(true)

      // UI：花名册第一个真实学生抽屉 → 成长档案页签（上一步停在打印页，直接 goto）
      await page.goto('/roster')
      await expect.poll(async () =>
        await page.locator('.stu-grid .stu-card').count(), { timeout: 15000 }).toBeGreaterThan(35)
      await page.locator('.stu-grid .stu-card').first().click()
      const drawer = page.locator('.el-drawer', { hasText: '的档案' })
      await expect(drawer).toBeVisible()
      await drawer.locator('.el-tabs__item', { hasText: '成长档案' }).click()
      await expect.poll(async () =>
        await drawer.locator('.arc-cards').count(), { timeout: 15000 }).toBe(1)
      // 学期内考试 → ECharts 走势图渲染
      await expect.poll(async () =>
        await drawer.locator('canvas').count(), { timeout: 15000 }).toBeGreaterThanOrEqual(1)
      await page.keyboard.press('Escape')
      await expect(drawer).toBeHidden()
    })

    // ============ 11e. 学生成就系统 ============
    await test.step('成就系统：自定义成就→授予→徽章墙可见→扫描幂等→撤销→消失；成就榜视图', async () => {
      // --- API 链路：创建自定义成就 ---
      const cr = await (await request.post(API + '/achievement/def', {
        headers: authHeader(),
        data: { name: 'E2E体育之星', icon: 'party', tier: 'GOLD', description: 'E2E 自动化测试用成就' },
      })).json()
      expect(cr.code).toBe(200)
      const defId = cr.data.id
      expect(defId).toBeTruthy()

      // 授予测试生（带备注）
      const aw = await (await request.post(API + '/achievement/award', {
        headers: authHeader(),
        data: { defId, studentIds: [testStudentId], remark: 'E2E测试授予' },
      })).json()
      expect(aw.data.awarded).toBe(1)

      // 学生徽章墙可见
      const wall = await (await request.get(API + '/achievement/student/' + testStudentId,
        { headers: authHeader() })).json()
      expect(wall.code).toBe(200)
      const mine = wall.data.currentBadges.find(b => b.defId === defId)
      expect(mine).toBeTruthy()
      expect(mine.earned).toBe(true)
      expect(mine.remark).toBe('E2E测试授予')
      expect(wall.data.stats.total).toBeGreaterThanOrEqual(1)
      expect(wall.data.stats.points).toBeGreaterThanOrEqual(60)

      // 扫描返回数组；连续第二次扫描新颁发为 0（幂等）
      const s1 = await (await request.post(API + '/achievement/scan', { headers: authHeader() })).json()
      expect(Array.isArray(s1.data.newAwards)).toBe(true)
      const s2 = await (await request.post(API + '/achievement/scan', { headers: authHeader() })).json()
      expect(s2.data.count).toBe(0)

      // 撤销 → 徽章墙变为未获得
      const defs = await (await request.get(API + '/achievement/defs', { headers: authHeader() })).json()
      const def = defs.data.find(d => d.id === defId)
      const rec = def.earners.find(e => e.studentId === testStudentId)
      expect(rec).toBeTruthy()
      const rv = await (await request.delete(API + '/achievement/revoke/' + rec.recordId,
        { headers: authHeader() })).json()
      expect(rv.code).toBe(200)
      const wall2 = await (await request.get(API + '/achievement/student/' + testStudentId,
        { headers: authHeader() })).json()
      expect(wall2.data.currentBadges.find(b => b.defId === defId).earned).toBe(false)

      // 删除自定义成就（连带获得记录）
      const dl = await (await request.delete(API + '/achievement/def/' + defId,
        { headers: authHeader() })).json()
      expect(dl.code).toBe(200)
      const defs2 = await (await request.get(API + '/achievement/defs', { headers: authHeader() })).json()
      expect(defs2.data.some(d => d.id === defId)).toBe(false)

      // --- UI 链路：成就榜视图 ---
      await page.goto('/roster')
      await expect.poll(async () =>
        await page.locator('.stu-grid .stu-card').count(), { timeout: 15000 }).toBeGreaterThan(35)
      await page.locator('.filter-bar .el-radio-button', { hasText: '成就榜' }).click()
      const board = page.locator('.ab-root')
      await expect(board).toBeVisible()
      await expect.poll(async () =>
        await board.locator('.ab-def-row').count(), { timeout: 15000 }).toBeGreaterThanOrEqual(11)
      // 点数排行视图
      await board.locator('.el-radio-button', { hasText: '点数排行' }).click()
      await expect.poll(async () =>
        await board.locator('.ab-rank-row').count(), { timeout: 10000 }).toBeGreaterThan(35)
      // 手动扫描按钮
      await board.locator('.el-radio-button', { hasText: '成就榜' }).click()
      await board.getByRole('button', { name: /扫描/ }).click()
      await expect(board.locator('.ab-def-row').first()).toBeVisible()

      // --- UI 链路：学生抽屉「我的成就」徽章墙 + 角标 ---
      await page.locator('.filter-bar .el-radio-button', { hasText: '花名册' }).click()
      await page.getByPlaceholder('搜索姓名/学号').fill('E2E测试生')
      await expect.poll(async () =>
        await page.locator('.stu-grid .stu-card').count(), { timeout: 10000 }).toBe(1)
      await expect(page.locator('.stu-grid .stu-badge').first()).toBeVisible()
      await page.locator('.stu-grid .stu-card').first().click()
      const drawer = page.locator('.el-drawer', { hasText: '的档案' })
      await expect(drawer).toBeVisible()
      await drawer.locator('.el-tabs__item', { hasText: '我的成就' }).click()
      await expect.poll(async () =>
        await drawer.locator('.aw-grid .aw-cell').count(), { timeout: 15000 }).toBeGreaterThanOrEqual(11)
      await expect(drawer.locator('.aw-summary')).toBeVisible()
      await page.keyboard.press('Escape')
      await expect(drawer).toBeHidden()
    })

    // ============ 11f. 班级宠物园 ============
    await test.step('座次表宠物：领养→Lv2升级→批量评价→考勤自动扣分与开关→撤回回滚→商店兑换兑现→满级毕业徽章；座位卡UI与花名册宠物档案', async () => {
      const j = async (fn, url, data) => (await fn(url, data ? { headers: authHeader(), data } : { headers: authHeader() })).json()
      const apiGet = (url) => j(request.get.bind(request), API + url)
      const apiPost = (url, data) => j(request.post.bind(request), API + url, data)
      const apiPut = (url, data) => j(request.put.bind(request), API + url, data)
      /** 取测试生最新宠物状态（/pet/pets 返回 {pets, pendingEvolves}） */
      const petOf = async () => {
        const data = (await apiGet('/pet/pets')).data
        const list = data.pets || data
        return list.find(p => p.studentId === testStudentId)
      }

      // ① 领养 → UI 座次表页座位上显示 Lv1 形态（dragon_1；若未排座则在下方宠物卡区）
      let r = await apiPost('/pet/adopt', { studentId: testStudentId, petKey: 'dragon', petName: 'E2E小龙' })
      expect(r.code).toBe(200)
      let me = await petOf()
      expect(me.petId).toBeTruthy()
      expect(me.level).toBe(1)
      await page.goto('/seat')
      const myCard = page.locator('.seat, .pet-card', { hasText: 'E2E测试生' })
      await expect.poll(async () => await myCard.count(), { timeout: 15000 }).toBe(1)
      await expect(myCard.locator('img[src*="dragon_1.webp"]')).toBeVisible()

      // 评价项：+3「平时测验满分」、+5「拾金不昧(贵重)」
      const rules = (await apiGet('/pet/rules')).data
      const rule3 = rules.find(x => x.score === 3)
      const rule5 = rules.find(x => x.score === 5)
      expect(rule3).toBeTruthy()
      expect(rule5).toBeTruthy()

      // ② 加分至 exp≥40 → 自动升 Lv2（+5 共 8 次=40），UI 形态变 dragon_2
      for (let i = 0; i < 8; i++) {
        r = await apiPost('/pet/evaluate', { studentId: testStudentId, ruleId: rule5.id })
        expect(r.data.affected).toBe(1)
      }
      me = await petOf()
      expect(me.exp).toBe(40)
      expect(me.level).toBe(2)
      await page.reload()   // 刷新让前端拉取最新等级
      await expect(myCard.locator('img[src*="dragon_2.webp"]')).toBeVisible()

      // ③ 批量评价：同一评价项发多生（此处 1 人），affected=1、exp+3
      r = await apiPost('/pet/evaluate-batch', { ruleId: rule3.id, studentIds: [testStudentId] })
      expect(r.data.affected).toBe(1)
      me = await petOf()
      expect(me.exp).toBe(43)
      // UI：批量模式浮动条与勾选
      await page.getByRole('button', { name: /批量评价/ }).click()
      await expect(page.locator('.batch-bar')).toBeVisible()
      await myCard.locator('.pick-box').click()
      await expect(page.locator('.batch-bar', { hasText: '已选 1' })).toBeVisible()
      await page.getByRole('button', { name: /完成批量/ }).click()

      // ④ 考勤自动扣分：今日迟到 → AUTO_ATTENDANCE -1 流水、exp 43→42
      const today = new Date().toISOString().slice(0, 10)
      const tomorrow = new Date(Date.now() + 86400000).toISOString().slice(0, 10)
      r = await apiPost('/daily/attendance/save', { date: today, records: [{ studentId: testStudentId, status: '迟到' }] })
      expect(r.code).toBe(200)
      let logs = (await apiGet('/pet/logs?studentId=' + testStudentId)).data
      const attLog = logs.find(l => l.source === 'AUTO_ATTENDANCE' && l.score === -1)
      expect(attLog).toBeTruthy()
      me = await petOf()
      expect(me.exp).toBe(42)

      // 关闭自动扣分开关后再登记（明日迟到）→ 不产生扣分流水、exp 不变；随后恢复开关
      r = await apiPost('/dashboard/settings', { pet_auto_deduct: '0' })
      expect(r.code).toBe(200)
      await apiPost('/daily/attendance/save', { date: tomorrow, records: [{ studentId: testStudentId, status: '迟到' }] })
      logs = (await apiGet('/pet/logs?studentId=' + testStudentId)).data
      expect(logs.filter(l => l.source === 'AUTO_ATTENDANCE').length).toBe(1)
      me = await petOf()
      expect(me.exp).toBe(42)
      await apiPost('/dashboard/settings', { pet_auto_deduct: '1' })

      // ⑤ 撤回迟到扣分 → exp 回滚至 43
      r = await apiPost('/pet/revoke', { logId: attLog.id })
      expect(r.code).toBe(200)
      me = await petOf()
      expect(me.exp).toBe(43)

      // ⑥ 小商店：建品 → 兑换（扣 exp 5、库存 3→2、待兑现）→ 兑现
      r = await apiPost('/pet/shop-item', { name: 'E2E橡皮擦', icon: '🧽', price: 5, stock: 3 })
      expect(r.code).toBe(200)
      let items = (await apiGet('/pet/shop-items')).data
      const item = items.find(i => i.name === 'E2E橡皮擦')
      expect(item.stock).toBe(3)
      r = await apiPost('/pet/exchange', { studentId: testStudentId, itemId: item.id })
      expect(r.code).toBe(200)
      items = (await apiGet('/pet/shop-items')).data
      expect(items.find(i => i.id === item.id).stock).toBe(2)
      me = await petOf()
      expect(me.exp).toBe(38)
      let exs = (await apiGet('/pet/exchanges')).data
      const ex = exs.find(e => e.itemId === item.id && e.studentId === testStudentId)
      expect(ex.status).toBe('待兑现')
      r = await apiPut('/pet/exchange/' + ex.id + '/fulfill')
      expect(r.code).toBe(200)
      exs = (await apiGet('/pet/exchanges')).data
      expect(exs.find(e => e.id === ex.id).status).toBe('已兑现')
      // UI：小商店弹窗展示商品
      await page.getByRole('button', { name: /小商店/ }).click()
      await expect(page.locator('.shop-card', { hasText: 'E2E橡皮擦' })).toBeVisible()
      await page.keyboard.press('Escape')

      // ⑦ 加满至 Lv5（exp 280）→ 毕业：毕业徽章 +1、重置 Lv1/exp0
      me = await petOf()
      const times = Math.ceil((280 - me.exp) / rule5.score)
      for (let i = 0; i < times; i++) {
        await apiPost('/pet/evaluate', { studentId: testStudentId, ruleId: rule5.id })
      }
      me = await petOf()
      expect(me.level).toBe(5)
      expect(me.exp).toBe(280)
      r = await apiPost('/pet/graduate', { studentId: testStudentId })
      expect(r.code).toBe(200)
      me = await petOf()
      expect(me.level).toBe(1)
      expect(me.exp).toBe(0)
      expect(me.graduatedCount).toBe(1)

      // ⑧ UI：花名册宠物角标 + 档案抽屉「🐾 宠物」区（形态图/流水）
      await page.goto('/roster')
      await page.getByPlaceholder('搜索姓名/学号').fill('E2E测试生')
      await expect.poll(async () => await page.locator('.stu-grid .stu-card').count(), { timeout: 10000 }).toBe(1)
      await expect(page.locator('.stu-pet')).toBeVisible()
      await page.locator('.stu-grid .stu-card').first().click()
      const drawer = page.locator('.el-drawer', { hasText: '的档案' })
      await expect(drawer).toBeVisible()
      await drawer.locator('.el-tabs__item', { hasText: '宠物' }).click()
      await expect(drawer.locator('.pet-box')).toBeVisible()
      await expect(drawer.locator('.pet-arc-img')).toBeVisible()
      await expect.poll(async () => await drawer.locator('.pet-log-row').count(), { timeout: 10000 }).toBeGreaterThan(0)
      await page.keyboard.press('Escape')
    })

    // ============ 11h. 进化演出 + 日常特效 + 自创皮肤 ============
    await test.step('宠物进化：UI评价触发全屏进化演出（揭晓文案/confetti/点击跳过/形态切换）；今日之星彩虹与扣分乌云；自创皮肤建传领养全链路；pendingEvolves通道', async () => {
      const j = async (fn, url, data) => (await fn(url, data ? { headers: authHeader(), data } : { headers: authHeader() })).json()
      const apiGet = (url) => j(request.get.bind(request), API + url)
      const apiPost = (url, data) => j(request.post.bind(request), API + url, data)

      // 评价项：+5「拾金不昧(贵重)」升级用；负分项触发乌云特效
      const rules = (await apiGet('/pet/rules')).data
      const rule5 = rules.find(x => x.score === 5)
      const ruleMinus = rules.find(x => x.score < 0)
      expect(rule5).toBeTruthy()
      expect(ruleMinus).toBeTruthy()

      // ① 全屏进化演出：UI 连点 8 次 +5（毕业重置后 exp 0→40），第 8 次升 Lv2 触发 6 秒分镜
      await page.goto('/seat')
      const myCard = page.locator('.seat, .pet-card', { hasText: 'E2E测试生' })
      await expect.poll(async () => await myCard.count(), { timeout: 15000 }).toBe(1)
      await myCard.click()  // 打开宠物详情弹窗
      for (let i = 0; i < 8; i++) {
        await page.getByRole('button', { name: /评价加分/ }).click()
        // +5 规则在「行为」分类，评价弹窗默认开「学习」tab
        await page.locator('.el-dialog .el-tabs__item', { hasText: rule5.category }).first().click()
        await page.locator('.el-dialog .rule-card', { hasText: rule5.name }).first().click()
        await expect(page.locator('.el-dialog', { hasText: '给宠物评价' })).toBeHidden()
      }
      // 演出断言：遮罩 → 揭晓文案（等至 phase5）→ confetti canvas → 点击跳过 → 形态变 Lv2
      const overlay = page.locator('.evo-overlay')
      await expect(overlay).toBeVisible({ timeout: 10000 })
      await expect(overlay.locator('.evo-title')).toHaveText(/进\s*化\s*成\s*功/, { timeout: 10000 })
      await expect(overlay.locator('.evo-sub')).toContainText('Lv.2')
      await expect(page.locator('canvas').first()).toBeAttached()
      await overlay.click({ position: { x: 40, y: 40 } })  // 点击任意处跳过
      await expect(overlay).toBeHidden({ timeout: 5000 })
      await expect(myCard.locator('img[src*="dragon_2.webp"]')).toBeVisible()

      // ② 日常特效：当日正分 40≥5 → 今日之星彩虹；再扣一分 → 小乌云下雨
      await expect(myCard.locator('.wx-star')).toBeVisible()
      await page.getByRole('button', { name: /评价加分/ }).click()
      await page.locator('.el-dialog .el-tabs__item', { hasText: ruleMinus.category }).first().click()
      await page.locator('.el-dialog .rule-card', { hasText: ruleMinus.name }).first().click()
      await expect(page.locator('.el-dialog', { hasText: '给宠物评价' })).toBeHidden()
      await expect(myCard.locator('.wx-cloud')).toBeVisible({ timeout: 8000 })
      await expect(myCard.locator('.wx-cloud .wx-drop')).toBeAttached()
      await page.keyboard.press('Escape')

      // ③ pendingEvolves 通道（跨页/自动联动补播的防御性设计）：字段为数组 + evolve-seen 200
      const petsResp = (await apiGet('/pet/pets')).data
      expect(Array.isArray(petsResp.pendingEvolves)).toBeTruthy()
      const seen = await apiPost('/pet/evolve-seen', { studentId: testStudentId })
      expect(seen.code).toBe(200)

      // ④ 自创皮肤：建行 → 上传 6 阶段图（内置 webp 代传）→ 回读校验 → 临时生 UI 领养
      const skResp = await apiPost('/pet/skin', { name: 'E2E冰麒麟', fxTheme: 'ice' })
      expect(skResp.code).toBe(200)
      const skinKey = skResp.data.skinKey
      const petDir = path.resolve(__dirname, '../../frontend/public/pets')
      for (const stage of ['egg', '1', '2', '3', '4', '5']) {
        const file = stage === 'egg' ? 'dragon_egg.webp' : `dragon_${stage}.webp`
        const up = await request.post(API + '/pet/skin/upload', {
          headers: authHeader(),
          multipart: {
            skinKey, stage,
            file: { name: file, mimeType: 'image/webp', buffer: fs.readFileSync(path.join(petDir, file)) }
          }
        })
        const uj = await up.json()
        expect(uj.code).toBe(200)
        expect(uj.data.url).toContain('/uploads/')
      }
      const skins = (await apiGet('/pet/skins')).data
      const mine = skins.find(s => s.skinKey === skinKey)
      expect(mine.fxTheme).toBe('ice')
      for (const k of ['egg', '1', '2', '3', '4', '5']) expect(mine.imgs[k]).toContain('/uploads/')

      // 第二个临时学生（不污染 40 名真实学生；afterAll 级联清理）
      const add2 = await apiPost('/roster/student', { stuNo: '998', name: 'E2E皮肤生', gender: '女' })
      expect(add2.code).toBe(200)
      testStudentId2 = add2.data

      // UI 领养自创皮肤：蛋卡 → 领养弹窗选「E2E冰麒麟」→ 6 阶段预览全走 /uploads → 起名领养
      await page.goto('/seat')
      const card2 = page.locator('.seat, .pet-card', { hasText: 'E2E皮肤生' })
      await expect.poll(async () => await card2.count(), { timeout: 15000 }).toBe(1)
      await card2.click()
      const adoptDlg = page.locator('.el-dialog', { hasText: '领养守护精灵' })
      await expect(adoptDlg).toBeVisible()
      const customCard = adoptDlg.locator('.skin-card', { hasText: 'E2E冰麒麟' })
      await expect(customCard).toBeVisible()
      await customCard.click()
      await expect.poll(async () => await adoptDlg.locator('.skin-preview img[src*="/uploads/"]').count(),
        { timeout: 5000 }).toBe(6)
      await adoptDlg.locator('input').fill('E2E冰冰')
      await adoptDlg.getByRole('button', { name: /破壳领养/ }).click()
      await expect(adoptDlg).toBeHidden()
      // 领养后卡片形态图为自创皮肤上传图（/uploads/ 静态映射）
      await expect(card2.locator('img[src*="/uploads/"]')).toBeVisible({ timeout: 10000 })
      const p2 = (await apiGet('/pet/pets')).data.pets.find(p => p.studentId === testStudentId2)
      expect(p2.skinKey).toBe(skinKey)
    })

    // ============ 11g. 头像与班级设置 ============
    await test.step('头像与班级：emoji 头像保存/弹窗选择/侧边栏显示；任课班新增后删除（自清理）', async () => {
      // 头像：API 保存 🦊 → profile 读回 → 侧边栏显示
      let r = await request.put(API + '/settings/profile', { headers: authHeader(), data: { avatar: '🦊' } })
      expect((await r.json()).code).toBe(200)
      const p = (await (await request.get(API + '/settings/profile', { headers: authHeader() })).json()).data
      expect(p.avatar).toBe('🦊')
      await page.goto('/dashboard')
      await expect(page.locator('.user-avatar')).toContainText('🦊')
      // UI：点头像弹窗选 🐰 → 侧边栏即时变 🐰
      await page.locator('.user-avatar').click()
      await expect(page.locator('.avatar-grid')).toBeVisible()
      await page.locator('.avatar-opt', { hasText: '🐰' }).click()
      await expect(page.locator('.user-avatar')).toContainText('🐰')
      // 还原默认头像
      await request.put(API + '/settings/profile', { headers: authHeader(), data: { avatar: '🐻' } })

      // 任课班：新增 E2E临时班 → classes 含 → 删除 → 不含（连带课表清理，步骤内自清理）
      const add = await request.post(API + '/timetable/class',
        { headers: authHeader(), data: { name: 'E2E临时班', type: 'SUBJECT' } })
      const cid = (await add.json()).data
      let classes = (await (await request.get(API + '/timetable/classes', { headers: authHeader() })).json()).data
      expect(classes.find(c => c.id === cid)).toBeTruthy()
      await request.delete(API + '/timetable/class/' + cid, { headers: authHeader() })
      classes = (await (await request.get(API + '/timetable/classes', { headers: authHeader() })).json()).data
      expect(classes.find(c => c.id === cid)).toBeFalsy()
    })

    // ============ 12. 刷新保持登录 ============
    await test.step('刷新页面后保持登录态（不跳回登录页）', async () => {
      await page.goto('/dashboard')
      await page.reload()
      await expect(page).toHaveURL(/\/dashboard/)
      await expect(page.locator('.greet-title')).toBeVisible()
    })

    // ============ 13. 花名册删除学生（验证需求 + 清理测试生） ============
    await test.step('花名册：档案抽屉删除测试生（二次确认 + 级联）', async () => {
      await nav(page, '花名册').click()
      await page.getByPlaceholder('搜索姓名/学号').fill('E2E测试生')
      await expect.poll(async () => await page.locator('.stu-grid .stu-card').count(), { timeout: 10000 }).toBe(1)
      await page.locator('.stu-grid .stu-card').first().click()
      const drawer = page.locator('.el-drawer', { hasText: '的档案' })
      await expect(drawer).toBeVisible()
      await drawer.getByRole('button', { name: '删除' }).click()
      const mb = page.locator('.el-message-box', { hasText: '删除' })
      await expect(mb).toBeVisible()
      await mb.getByRole('button', { name: '确定删除' }).click()
      // 删除后搜索结果为空
      await expect.poll(async () => await page.locator('.stu-grid .stu-card').count(), { timeout: 10000 }).toBe(0)
      // 11h 自创皮肤验证用的 E2E皮肤生为辅助数据，API 删除（宠物级联）；reload 后统计回到真实 40 人
      if (testStudentId2) {
        await request.delete(API + '/roster/student/' + testStudentId2, { headers: authHeader() })
        testStudentId2 = null
        await page.reload()
      }
      await expect.poll(async () =>
        (await page.locator('.stat-card .num').nth(0).textContent() || '').trim(), { timeout: 10000 }).toBe('40')
      testStudentId = null
    })
  })
})

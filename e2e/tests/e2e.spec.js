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
let seatConfigBak = null  // 座次表 v2：admin 真实座次布局备份（rows/cols/aisles），afterAll 恢复
// 注：显示偏好 seat_display 不做备份/恢复——步骤 14 会逐项翻转，afterAll 始终写回默认 JSON，
// 避免"中途失败时备份已带 flip=1"导致污染随每次运行自我延续
let e2ePushWrote = false  // 消息推送：E2E 是否写过测试 webhook（仅 admin 未配置时才写；afterAll 仅此时清理，防误删真实推送配置）
let periodBaselineIds = [] // 课表节次：开跑前现有节次 id（全局共享表，新增节次 step 后 afterAll 兜底删除）

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
    // 座次表 v2：备份 admin 真实座次布局与显示偏好（步骤 14 会改，afterAll 必须恢复）
    const cfg = (await (await request.get(API + '/seat/config', { headers: authHeader() })).json()).data
    seatConfigBak = { rows: cfg.rows, cols: cfg.cols, aisles: cfg.aisles || [] }
    // 课表节次基线（全局 period 表；新增节次 step 与 afterAll 据此 diff 清理）
    periodBaselineIds = ((await (await request.get(API + '/timetable/periods', { headers: authHeader() })).json()).data || [])
      .map(p => p.id)
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
      // 课表节次兜底：删除 E2E 新增的节次（period 为全局共享表），顺延时长恢复默认 10/40
      const now = (await (await request.get(API + '/timetable/periods', { headers: authHeader() })).json()).data || []
      for (const p of now) {
        if (!periodBaselineIds.includes(p.id)) {
          await request.delete(API + '/timetable/period/' + p.id, { headers: authHeader() })
        }
      }
      await request.post(API + '/dashboard/settings', {
        headers: authHeader(), data: { period_break_min: '10', period_class_min: '40' },
      })
    } catch (e) { /* 忽略 */ }
    try {
      // 消息推送清理：仅当 E2E 写过测试 webhook 才清（admin 已配真实渠道时全程跳过，掩码无法备份还原）
      if (e2ePushWrote) {
        await request.put(API + '/push/config', { headers: authHeader(), data: {
          enabled: false, wecomUrl: '__clear__',
        } })
      }
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
    try {
      // 列组长兜底清理：步骤 14 指派测试会在 admin 真实学生上建 committee role='组长' 记录，逐条删除（幂等、不阻断）；
      // 严格只删 role='组长'，不碰班长/学习委员等真实班委
      const cm = (await (await request.get(API + '/duty/committee', { headers: authHeader() })).json()).data || []
      for (const c of cm) {
        if (c.role === '组长') await request.delete(API + '/duty/committee/' + c.id, { headers: authHeader() })
      }
    } catch (e) { /* 忽略 */ }
    try {
      // 座次表 v2：恢复 admin 真实布局；显示偏好始终写回默认 JSON（flip:simple 等必须归零，
      // 不能恢复"备份值"——备份可能在步骤 14 中途失败时已带 flip=1，会随每次运行自我延续污染）
      if (seatConfigBak) {
        await request.post(API + '/seat/config', { headers: authHeader(), data: seatConfigBak })
      }
      const dispDefault = JSON.stringify({
        simple: 0, fields: { stuNo: 0, gender: 1, lv: 1, badge: 1, medal: 1, leader: 1, colLabel: 1, unseated: 1 },
        namePos: 'h-bottom', print: { flip: 0, mono: 1, withUnseated: 1 }
      })
      await request.put(API + '/settings/seat-display', {
        headers: authHeader(), data: { v: dispDefault }
      })
    } catch (e) { /* 忽略 */ }
    try {
      // 速记匹配测试的临时学生兜底（正常已在 step finally 删除；真实 40 学生名不含 E2E）
      for (const s of ((await (await request.get(API + '/roster/students', {
        headers: authHeader(), params: { keyword: 'E2E' } })).json()).data || [])) {
        await request.delete(API + '/roster/student/' + s.id, { headers: authHeader() })
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
    await test.step('个人设置：五个设置卡；消息推送入口在修改密码旁、点开为右侧半页抽屉', async () => {
      await page.locator('.action-item', { hasText: '个人设置' }).click()
      await expect(page).toHaveURL(/\/settings/)
      await expect(page.locator('.candy-card')).toHaveCount(5)
      await expect(page.locator('.candy-card h3', { hasText: '修改密码' })).toBeVisible()
      await expect(page.locator('.candy-card h3', { hasText: '我的班级' })).toBeVisible()
      await expect(page.locator('.candy-card h3', { hasText: '我的学科' })).toBeVisible()
      await expect(page.locator('.candy-card h3', { hasText: 'AI 助手' })).toBeVisible()

      // 消息推送：入口按钮在"修改密码"卡标题旁，点击滑出右侧半页抽屉（开关/三渠道/教程/事件开关）
      const pwdCard = page.locator('.candy-card', { hasText: '修改密码' })
      await expect(pwdCard.locator('.push-entry')).toBeVisible()
      await pwdCard.locator('.push-entry').click()
      const pushDrawer = page.locator('.push-drawer')
      await expect(pushDrawer).toBeVisible()
      await expect(pushDrawer.locator('.el-switch')).toBeVisible()
      await expect(pushDrawer.getByRole('link', { name: /图文教程/ })).toBeVisible()
      for (const t of ['企业微信群机器人', '钉钉群机器人', 'Bark']) {
        await expect(pushDrawer.getByText(t).first()).toBeVisible()
      }
      await expect(pushDrawer.locator('.el-checkbox', { hasText: '待办到期' })).toBeVisible()
      await expect(pushDrawer.locator('.el-checkbox', { hasText: '学生成就早报' })).toBeVisible()
      await expect(pushDrawer.locator('.el-checkbox.is-disabled', { hasText: '即将支持' }).first()).toBeVisible()
      await pushDrawer.locator('.el-drawer__close-btn').click()
      await expect(pushDrawer).toBeHidden()
      // .cards 两列瀑布流（卡片紧凑紧挨，修豆腐块松散/密码卡大片留白）
      const cardsCols = await page.locator('.cards').evaluate(el => getComputedStyle(el).columnCount)
      expect(cardsCols).toBe('2')

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

      // ---- 消息推送：配置结构/密钥掩码不明取/未配置渠道测试失败（不发真实 webhook）----
      const push0 = (await (await request.get(API + '/push/config', { headers: authHeader() })).json()).data
      for (const ch of ['wecom', 'dingtalk', 'bark']) {
        expect(push0[ch]).toHaveProperty('configured')
        expect(push0[ch]).toHaveProperty('hint')
      }
      expect(push0.events.todo).toBe(1)
      expect(push0.events.achievement).toBe(1)
      expect(push0.events.leave).toBe(0)
      if (!push0.wecom.configured) {
        // admin 未配过企微才写测试 webhook（已配真实渠道则跳过——掩码无法备份还原，不能覆盖真实配置）
        const putPush = await request.put(API + '/push/config', { headers: authHeader(), data: {
          enabled: true, events: { todo: 1, achievement: 1 },
          wecomUrl: 'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=testkey123-e2e',
        } })
        expect((await putPush.json()).code).toBe(200)
        e2ePushWrote = true
      }
      const push1Raw = await (await request.get(API + '/push/config', { headers: authHeader() })).text()
      const push1 = JSON.parse(push1Raw).data
      expect(push1.wecom.configured).toBe(true)
      expect(push1.wecom.hint).toContain('••••')
      // 响应体绝不含 webhook 明文
      expect(push1Raw).not.toContain('testkey123-e2e')
      // Bark 未配置 → 测试推送 ok:false（不会真发第三方）
      const barkT = (await (await request.post(API + '/push/test',
        { headers: authHeader(), data: { channel: 'bark' } })).json()).data
      expect(barkT.ok).toBe(false)

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
      // 导入弹窗（与成绩/家长导入统一样式：选择文件 + 格式提示）
      await page.getByRole('button', { name: '导入', exact: true }).click()
      const rosterImp = page.locator('.el-dialog', { hasText: '导入花名册' })
      await expect(rosterImp).toBeVisible()
      await expect(rosterImp.locator('.el-upload .el-button')).toBeVisible()
      await expect(rosterImp.locator('.import-tip')).toContainText('学号')
      await page.keyboard.press('Escape')
      await expect(rosterImp).toBeHidden()
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
      // 主动选中本次导入的 E2E月考（默认选日期最新考试，可能是老师的真实考试，科目数不定）
      await page.locator('.el-select').nth(0).click()
      await page.getByRole('option', { name: /E2E月考/ }).click()
      await expect.poll(async () =>
        await page.locator('.score-card').count(), { timeout: 20000 }).toBeGreaterThanOrEqual(3)
      await expect.poll(async () =>
        await page.locator('canvas').count(), { timeout: 20000 }).toBeGreaterThanOrEqual(2)
      await expect.poll(async () =>
        await page.locator('.el-table__row').count(), { timeout: 20000 }).toBeGreaterThan(35)
      // 防 /score/line 请求死循环回归：加载完成后请求数应稳定（间隔 1.5s 两次计数相等且总量 <10）
      const lineCount = () => page.evaluate(() =>
        performance.getEntriesByType('resource').filter(r => r.name.includes('/score/line')).length)
      await page.waitForTimeout(2500)
      const c1 = await lineCount()
      await page.waitForTimeout(1500)
      const c2 = await lineCount()
      expect(c2).toBe(c1)
      expect(c1).toBeLessThan(10)
    })

    await test.step('成绩分析：选择对比考试 → 进退步对比表 + 两图表对比曲线', async () => {
      await page.locator('.el-select').nth(1).click()
      await page.getByRole('option', { name: /E2E摸底/ }).click()
      await expect(page.getByText('考试进退步对比')).toBeVisible()
      await expect(page.locator('.el-tag').filter({ hasText: /↑|↓/ }).first()).toBeVisible()
      // 选了对比考试：折线/分数段两图表请求都带 compareExamId（对比曲线数据）
      await expect.poll(() => page.evaluate(() =>
        performance.getEntriesByType('resource').some(r => r.name.includes('/score/line') && r.name.includes('compareExamId'))),
        { timeout: 10000 }).toBe(true)
      await expect.poll(() => page.evaluate(() =>
        performance.getEntriesByType('resource').some(r => r.name.includes('/score/distribution') && r.name.includes('compareExamId'))),
        { timeout: 10000 }).toBe(true)
      // 分数段图可切科目：卡片内下拉选「数学」→ 请求带 subject
      const barCard = page.locator('.el-card', { hasText: '分数段分布' })
      await barCard.locator('.el-select').click()
      await page.getByRole('option', { name: '数学', exact: true }).click()
      await expect.poll(() => page.evaluate(() =>
        performance.getEntriesByType('resource').some(r => r.name.includes('/score/distribution') && r.name.includes('subject='))),
        { timeout: 8000 }).toBe(true)
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
      // 无用入口已移除：工具栏无「沟通记录/清空记录」按钮，页内不再平铺全量沟通记录表
      await expect(page.getByRole('button', { name: '沟通记录' })).toHaveCount(0)
      await expect(page.getByRole('button', { name: '清空记录' })).toHaveCount(0)
      await expect(page.locator('.el-card', { hasText: '家校沟通记录' })).toHaveCount(0)
      // 家长 Excel 导入：工具栏按钮 → 弹窗（选择文件 + 格式提示，与成绩/花名册导入统一样式）
      await expect(page.getByRole('button', { name: /导入家长/ })).toBeVisible()
      await page.getByRole('button', { name: /导入家长/ }).click()
      const impDlg = page.locator('.el-dialog', { hasText: '导入家长联系方式' })
      await expect(impDlg).toBeVisible()
      await expect(impDlg.locator('.el-upload .el-button')).toBeVisible()
      await expect(impDlg.locator('.import-tip')).toContainText('学号')
      await page.keyboard.press('Escape')
      await expect(impDlg).toBeHidden()

      await page.locator('.el-card', { hasText: '家长通讯录' }).getByRole('button', { name: /沟通/ }).first().click()
      const dlg = page.locator('.el-dialog', { hasText: '家校沟通管理' })
      await expect(dlg).toBeVisible()
      await expect(dlg.getByText('添加沟通记录')).toBeVisible()
      await page.keyboard.press('Escape')
      await expect(dlg).toBeHidden()

      // 编辑家长联系方式：改住址→保存→表格生效→原值复原（不污染真实学生）
      const listCard = page.locator('.el-card', { hasText: '家长通讯录' })
      const firstRow = listCard.locator('.el-table__row').first()
      const origAddr = (await firstRow.locator('td').nth(4).innerText()).trim()
      await firstRow.getByRole('button', { name: /编辑/ }).click()
      const eDlg = page.locator('.el-dialog', { hasText: '编辑家长联系方式' })
      await expect(eDlg).toBeVisible()
      await eDlg.getByPlaceholder('家庭住址').fill('E2E测试住址')
      await eDlg.getByRole('button', { name: '保存' }).click()
      await expect(eDlg).toBeHidden({ timeout: 8000 })
      await expect.poll(async () =>
        (await listCard.locator('.el-table__row').first().innerText()).includes('E2E测试住址'),
        { timeout: 8000 }).toBe(true)
      // 复原原住址
      await listCard.locator('.el-table__row').first().getByRole('button', { name: /编辑/ }).click()
      await expect(eDlg).toBeVisible()
      await eDlg.getByPlaceholder('家庭住址').fill(origAddr)
      await eDlg.getByRole('button', { name: '保存' }).click()
      await expect(eDlg).toBeHidden({ timeout: 8000 })
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

    // ============ 7b. 自定义节次：新增自动命名/时间顺延 + 时间选择框 + 时长可配 ============
    await test.step('课程表：自定义节次新增自动命名与时间顺延、时间选择框、课间/上课时长可配', async () => {
      const toMin = hhmm => { const [h, m] = String(hhmm).split(':').map(Number); return h * 60 + m }
      const fmt = min => { const x = ((Math.round(min) % 1440) + 1440) % 1440; return String(Math.floor(x / 60)).padStart(2, '0') + ':' + String(x % 60).padStart(2, '0') }
      const listApi = async () => (await (await request.get(API + '/timetable/periods', { headers: authHeader() })).json()).data || []
      const beforeIds = (await listApi()).map(p => p.id)
      try {
        await nav(page, '课程表').click()
        await page.getByRole('button', { name: '自定义节次' }).click()
        const dlg = page.locator('.el-dialog', { hasText: '自定义节次' })
        await expect(dlg).toBeVisible()
        // 开始/结束为时间选择框（el-time-picker 根 class 含 el-date-editor），非文本输入
        await expect(dlg.locator('.el-date-editor').first()).toBeVisible()
        // 时长配置：课间 / 每节上课 两个 el-input-number
        const nums = dlg.locator('.el-input-number')
        await expect(nums).toHaveCount(2)

        const list0 = await listApi()
        const lastEnd = list0[list0.length - 1].endTime
        const maxN = list0.reduce((m, p) => {
          const mt = String(p.name || '').match(/^第(\d+)节$/); return mt ? Math.max(m, Number(mt[1])) : m
        }, 0)
        // 默认课间 10 / 上课 40：点新增节次
        await dlg.getByRole('button', { name: '新增节次' }).click()
        const expStart = fmt(toMin(lastEnd) + 10)
        const expEnd = fmt(toMin(expStart) + 40)
        await expect.poll(async () => {
          const nw = (await listApi()).find(p => !beforeIds.includes(p.id))
          return nw ? `${nw.name}|${nw.startTime}|${nw.endTime}` : ''
        }, { timeout: 10000 }).toBe(`第${maxN + 1}节|${expStart}|${expEnd}`)

        // 改时长：课间 15、上课 45（fill + Tab 触发 @change 保存）
        const numInputs = nums.locator('input')
        await numInputs.nth(0).fill('15'); await page.keyboard.press('Tab')
        await numInputs.nth(1).fill('45'); await page.keyboard.press('Tab')
        await expect(page.locator('.el-message--success').first()).toBeVisible({ timeout: 5000 })
        // 再新增一节：基准为上一新节结束，按 15/45 顺延
        const newLastEnd = (await listApi()).find(p => p.name === `第${maxN + 1}节`).endTime
        await dlg.getByRole('button', { name: '新增节次' }).click()
        const expStart2 = fmt(toMin(newLastEnd) + 15)
        const expEnd2 = fmt(toMin(expStart2) + 45)
        await expect.poll(async () => {
          const second = (await listApi()).find(p => p.name === `第${maxN + 2}节`)
          return second ? `${second.startTime}|${second.endTime}` : ''
        }, { timeout: 10000 }).toBe(`${expStart2}|${expEnd2}`)
        // 时长已持久化到设置
        const saved = (await (await request.get(API + '/dashboard/settings', { headers: authHeader() })).json()).data
        expect(String(saved.period_break_min)).toBe('15')
        expect(String(saved.period_class_min)).toBe('45')
        await page.keyboard.press('Escape')
      } finally {
        // 清理本 step 新增节次 + 恢复时长默认（afterAll 另有同款兜底）
        for (const p of await listApi()) {
          if (!beforeIds.includes(p.id)) await request.delete(API + '/timetable/period/' + p.id, { headers: authHeader() })
        }
        await request.post(API + '/dashboard/settings', {
          headers: authHeader(), data: { period_break_min: '10', period_class_min: '40' },
        })
      }
    })

    // ============ 7c. 座次表宠物园：批量评价列头按列全选/半选/取消 ============
    await test.step('座次表：批量评价列头勾选框按列全选、半选、取消', async () => {
      await page.goto('/seat')
      await page.getByRole('button', { name: /批量评价/ }).click()
      // 列头勾选框出现（棋盘内每列一个），初始已选 0
      const colPicks = page.locator('.board-grid .col-pick')
      await expect.poll(async () => await colPicks.count(), { timeout: 10000 }).toBeGreaterThan(0)
      const pickNum = page.locator('.pick-num')
      await expect(pickNum).toHaveText('0')
      // 找一个有学生的列：逐列点列头直到已选数 > 0（空列点了不选中）
      let chosen = -1
      const colCount = await colPicks.count()
      for (let i = 0; i < colCount; i++) {
        await colPicks.nth(i).click()
        if (Number(await pickNum.innerText()) > 0) { chosen = i; break }
      }
      expect(chosen).toBeGreaterThanOrEqual(0)
      // 该列头为全选态
      await expect(colPicks.nth(chosen)).toHaveClass(/is-checked/)
      // 再点取消 → 已选归 0、列头非全选
      await colPicks.nth(chosen).click()
      await expect.poll(async () => await pickNum.innerText(), { timeout: 5000 }).toBe('0')
      await expect(colPicks.nth(chosen)).not.toHaveClass(/is-checked/)
      // 半选：找一列 ≥2 名学生，全选后取消其中 1 个座位 → 该列头变半选
      let bigCol = -1
      for (let i = 0; i < colCount; i++) {
        await colPicks.nth(i).click()               // 选该列，读该列人数
        const n = Number(await pickNum.innerText())
        await colPicks.nth(i).click()               // 取消回 0
        if (n >= 2) { bigCol = i; break }
      }
      expect(bigCol).toBeGreaterThanOrEqual(0)
      await colPicks.nth(bigCol).click()            // 全选该列
      const total = Number(await pickNum.innerText())
      expect(total).toBeGreaterThanOrEqual(2)
      // 取消该列中 1 个已勾选座位（未破壳蛋图会拦截真实点击，用 dispatchEvent 直接触发 checkbox）
      const checkedBox = page.locator('.board-grid .seat .pick-box.is-checked').first()
      await expect(checkedBox).toBeVisible()
      await checkedBox.dispatchEvent('click')
      await expect.poll(async () => await pickNum.innerText(), { timeout: 5000 }).toBe(String(total - 1))
      // 半选态：Element Plus 的 is-indeterminate class 在内部 .el-checkbox__input 上（外层 label 体现为 aria-checked=mixed）
      await expect(colPicks.nth(bigCol).locator('.el-checkbox__input')).toHaveClass(/is-indeterminate/)
      await expect(colPicks.nth(bigCol)).toHaveAttribute('aria-checked', 'mixed')
      // 退出批量模式（纯前端选择，不提交评价、不写库）
      await page.getByRole('button', { name: /完成批量/ }).click()
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

      // 推送状态 chip + 轻量配置弹窗（不跳页；两种文案兼容 admin 是否已开启）
      const chip = page.locator('.push-chip')
      await expect(chip).toBeVisible()
      await expect(chip).toHaveText(/推送已开启|开启到期提醒/)
      await chip.click()
      const pushDlg = page.locator('.el-dialog', { hasText: '开启到期提醒推送' })
      await expect(pushDlg).toBeVisible()
      await expect(pushDlg.locator('.el-radio')).toHaveCount(3)
      await expect(pushDlg.getByRole('link', { name: /图文教程/ })).toBeVisible()
      await expect(pushDlg.getByRole('button', { name: '发送测试' })).toBeVisible()
      // 切到 Bark：出现设备 Key 输入框
      await pushDlg.locator('.el-radio', { hasText: 'Bark' }).click()
      await expect(pushDlg.getByPlaceholder(/设备 Key/)).toBeVisible()
      await pushDlg.getByRole('button', { name: '取消' }).click()
      await expect(pushDlg).toBeHidden()
      // 新增区布局修复：输入框/日期/添加按钮同卡片内可见（flex-wrap，不再错位）
      const addCard = page.locator('.add-card')
      await expect(addCard.getByPlaceholder(/新增待办/)).toBeVisible()
      await expect(addCard.locator('.el-date-editor')).toBeVisible()
      await expect(addCard.getByRole('button', { name: '添加' })).toBeVisible()
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

    // ============ 9b. 家长联系方式 Excel 导入（按学号 upsert，临时学生零污染） ============
    await test.step('家长联系方式：Excel 导入按学号更新父母电话/住址，学号不存在计数', async () => {
      const aoa = [
        ['学号', '姓名', '住址', '父亲', '父亲电话', '母亲', '母亲电话'],
        ['999', 'E2E测试生', 'E2E导入住址', 'E2E爸爸', '13800000001', 'E2E妈妈', '13800000002'],
        ['999999', '不存在的学生', '', 'x', '1', '', ''],
      ]
      const wb = XLSX.utils.book_new()
      XLSX.utils.book_append_sheet(wb, XLSX.utils.aoa_to_sheet(aoa), '家长')
      const buffer = XLSX.write(wb, { type: 'buffer', bookType: 'xlsx' })
      const imp = await request.post(API + '/parent/import', {
        headers: authHeader(),
        multipart: {
          file: {
            name: '家长.xlsx',
            mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
            buffer,
          },
        },
      })
      const impBody = await imp.json()
      expect(impBody.code).toBe(200)
      expect(impBody.data.updated).toBe(1)
      expect(impBody.data.notFound).toBe(1)
      // 通讯录里临时学生家长信息已更新
      const contacts = (await (await request.get(API + '/parent/contacts',
        { headers: authHeader(), params: { keyword: 'E2E测试生' } })).json()).data
      const row = contacts.find(c => c.stu_no === '999')
      expect(row).toBeTruthy()
      expect(row.address).toContain('E2E导入住址')
      const phones = (row.parents || []).map(p => p.phone).join(',')
      expect(phones).toContain('13800000001')
      expect(phones).toContain('13800000002')
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

    // ============ 10b. 速记学生匹配：长名子串不误命中短名；多人命中拆分各一条 ============
    await test.step('速记：长名子串不误命中；多人命中每名学生各一条', async () => {
      // 临时短名学生："E2E测" 是 "E2E测试生" 的子串
      const shortId = (await (await request.post(API + '/roster/student', {
        headers: authHeader(), data: { stuNo: '997', name: 'E2E测', gender: '男' },
      })).json()).data
      expect(shortId).toBeTruthy()
      try {
        const parse = async content => (await (await request.post(API + '/dashboard/quicknote/parse', {
          headers: authHeader(), data: { content },
        })).json()).data.segments
        const namesOf = segs => segs.flatMap(s => s.students).map(x => x.name)
        // ① 子串去重：只提长名"E2E测试生"时，短名"E2E测"不被误命中
        const names1 = namesOf(await parse('E2E测试生今天迟到'))
        expect(names1).toContain('E2E测试生')
        expect(names1).not.toContain('E2E测')
        // ② 短名独立出现：两人都提到 → 长名移除后剩余文本仍含短名，两人都命中
        const names2 = namesOf(await parse('E2E测和E2E测试生今天都迟到'))
        expect(names2).toContain('E2E测')
        expect(names2).toContain('E2E测试生')
        // ③ UI 多人拆分：早读考勤为每名学生各生成一条 entry（不再静默取 students[0]）
        await page.goto('/dashboard')
        await page.getByPlaceholder('输入速记内容，回车智能归类...').fill('E2E测和E2E测试生今天都迟到')
        await page.getByRole('button', { name: '归类' }).click()
        const pdlg = page.locator('.el-dialog', { hasText: '速记智能归类预览' })
        await expect(pdlg).toBeVisible()
        await expect(pdlg.locator('.entry')).toHaveCount(2)
        // 两条分别预选两名学生（el-select 选中态显示「姓名（学号）」，全角括号）
        await expect(pdlg.getByText('E2E测（997）')).toBeVisible()
        await expect(pdlg.getByText('E2E测试生（999）')).toBeVisible()
        await pdlg.getByRole('button', { name: '取消' }).click()
        await expect(pdlg).toBeHidden()
      } finally {
        await request.delete(API + '/roster/student/' + shortId, { headers: authHeader() })
      }
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

    await test.step('班级日常：作业收缴选科目后加载名单（科目只列我教的）', async () => {
      await page.getByText('作业收缴').click()
      const hwPane = page.locator('.el-tab-pane').filter({ hasText: '勾选表示' })
      await hwPane.locator('.el-select').first().click()
      // 科目下拉只列我担任的科目（需求：不再统一语数英），选第一个可用科目
      const firstOpt = page.locator('.el-select-dropdown:visible .el-select-dropdown__item').first()
      await expect(firstOpt).toBeVisible({ timeout: 8000 })
      await firstOpt.click()
      await expect.poll(async () =>
        await page.locator('.hw-item').count(), { timeout: 15000 }).toBeGreaterThan(35)
    })

    await test.step('班级日常：课堂纪律页签可切换', async () => {
      await page.getByText('课堂纪律').click()
      await expect(page.locator('.el-table').first()).toBeVisible()
    })

    // ============ 11c. 工作台卡片 × 日常联动（三卡名单 + 点击登记跳 tab） ============
    await test.step('工作台联动：API 构造测试生违纪/作业未交 → stats 三类名单含测试生', async () => {
      const today = new Date().toISOString().slice(0, 10)
      // 纪律：直接 API 给测试生登记两条（不依赖速记的学生自动匹配），用于卡片去重 + 悬停看多条
      for (const content of ['上课说话', '上课吃零食']) {
        const r = await request.post(API + '/daily/discipline', {
          headers: authHeader(),
          data: { date: today, studentIds: [testStudentId], content },
        })
        expect((await r.json()).code).toBe(200)
      }
      // 作业未交：用 E2E 专用科目名（homework/save 按「日期+科目」整组重写，专用名不碰 admin 真实科目记录）
      const hwSubject = 'E2E作业科目'
      const save = await request.post(API + '/daily/homework/save', {
        headers: authHeader(),
        data: { date: today, subject: hwSubject, missingIds: [testStudentId] },
      })
      expect((await save.json()).code).toBe(200)
      const stats = (await (await request.get(API + '/dashboard/stats', { headers: authHeader() })).json()).data
      // 早读异常名单（步骤 11 已标记测试生迟到）
      expect(stats.attAbnormalList.some(a => a.name === 'E2E测试生' && a.status === '迟到')).toBe(true)
      // 纪律名单：测试生当天两条（卡片按姓名去重、悬停显示多条）
      const disItems = stats.disciplineList.filter(d => d.name === 'E2E测试生')
      expect(disItems.length).toBeGreaterThanOrEqual(2)
      expect(disItems.some(d => d.content === '上课说话')).toBe(true)
      // 作业未交名单（姓名+科目），未交人数按去重学生算
      expect(stats.homeworkList.some(h => h.name === 'E2E测试生' && h.subject === hwSubject)).toBe(true)
      expect(stats.homeworkMissing).toBeGreaterThanOrEqual(1)
    })

    await test.step('工作台联动：三卡显示测试生、悬停看违纪详情、点击登记跳对应 tab', async () => {
      await page.goto('/dashboard')
      const attCard = page.locator('.work-card.attendance')
      const disCard = page.locator('.work-card.discipline')
      const hwCard = page.locator('.work-card.homework')
      // 早读卡：姓名-状态 tag；纪律/作业卡：去重学生姓名 tag
      await expect(attCard.locator('.stu-tag', { hasText: 'E2E测试生' })).toBeVisible()
      await expect(disCard.locator('.stu-tag', { hasText: 'E2E测试生' })).toBeVisible()
      await expect(hwCard.locator('.stu-tag', { hasText: 'E2E测试生' })).toBeVisible()
      // 悬停纪律 tag → tooltip 列出该生当天全部违纪（多条都显示）
      // 卡片在 vuedraggable 内，tooltip 用受控 :visible（mouseenter 绑 el-tag 原生事件）；
      // popper class 为 .el-popper.el-tooltip（Element Plus 2.8，非旧版 .el-tooltip__popper）
      const disTag = disCard.locator('.stu-tag', { hasText: 'E2E测试生' }).first()
      await disTag.scrollIntoViewIfNeeded()
      await disTag.hover()
      const tip = page.locator('.el-popper.el-tooltip')
      await expect(tip.filter({ hasText: '上课说话' })).toBeVisible({ timeout: 8000 })
      await expect(tip.filter({ hasText: '上课吃零食' })).toBeVisible()
      // 点击登记 → 直达课堂纪律 tab
      await disCard.getByRole('button', { name: '点击登记' }).click()
      await expect(page).toHaveURL(/\/daily\?tab=dis/)
      await expect(page.locator('.el-tabs__item.is-active')).toContainText('课堂纪律')
      // 作业卡 → 作业收缴 tab
      await page.goto('/dashboard')
      await page.locator('.work-card.homework').getByRole('button', { name: '点击登记' }).click()
      await expect(page).toHaveURL(/\/daily\?tab=hw/)
      await expect(page.locator('.el-tabs__item.is-active')).toContainText('作业收缴')
    })

    await test.step('班级日常：作业科目只列我教的；违纪情况支持历史联想与手输新值', async () => {
      // —— 作业科目下拉：只列勾选了「我教的」(isMine) 的科目 ——
      // 临时科目验证勾选链路：建 isMine=true 科目应出现在下拉，取消勾选后消失（finally 删除，不污染真实科目）
      const tmpSub = (await (await request.post(API + '/timetable/subject', { headers: authHeader(),
        data: { name: 'E2E我教科目', teacher: '', color: '#cccccc', isMine: true } })).json()).data
      try {
        const subjBody = (await (await request.get(API + '/timetable/subjects', { headers: authHeader() })).json()).data
        const mine = subjBody.filter(s => s.isMine)
        const others = subjBody.filter(s => !s.isMine)
        expect(mine.some(s => s.name === 'E2E我教科目')).toBe(true)
        // el-tab-pane 顺序：att(0)/dis(1)/hw(2)/leave(3)；非 filterable 的 el-select 无原生 placeholder，用作用域 .el-select 定位
        await page.goto('/daily?tab=hw')
        const hwPane = page.locator('.el-tab-pane').nth(2)
        await hwPane.locator('.el-select').first().click()
        const hwOpts = page.locator('.el-select-dropdown:visible .el-select-dropdown__item')
        await expect(hwOpts.first()).toBeVisible({ timeout: 8000 })
        // 勾选「我教的」的临时科目出现；我教的都在、别人教的（语文/数学等）都不在
        await expect(hwOpts.filter({ hasText: 'E2E我教科目' })).toBeVisible({ timeout: 5000 })
        const hwTexts = await hwOpts.allInnerTexts()
        for (const m of mine) expect(hwTexts.some(t => t.includes(m.name))).toBe(true)
        for (const o of others) expect(hwTexts.some(t => t.includes(o.name))).toBe(false)
        await page.keyboard.press('Escape')
        // 取消勾选 → 重新进页面后临时科目从下拉消失
        await request.put(API + '/timetable/subject/' + tmpSub, { headers: authHeader(),
          data: { name: 'E2E我教科目', teacher: '', color: '#cccccc', isMine: false } })
        await page.goto('/daily?tab=hw')
        await page.locator('.el-tab-pane').nth(2).locator('.el-select').first().click()
        const hwOpts2 = page.locator('.el-select-dropdown:visible .el-select-dropdown__item')
        await expect(hwOpts2.first()).toBeVisible({ timeout: 8000 })
        await expect(hwOpts2.filter({ hasText: 'E2E我教科目' })).toHaveCount(0)
        await page.keyboard.press('Escape')
      } finally {
        await request.delete(API + '/timetable/subject/' + tmpSub, { headers: authHeader() })
      }
      // —— 违纪情况：可输入下拉（dis pane 第 1 个 select=学生多选，第 2 个=违纪情况）——
      await page.goto('/daily?tab=dis')
      const disPane = page.locator('.el-tab-pane').nth(1)
      const contentSel = disPane.locator('.el-select').nth(1)
      await contentSel.click()
      await expect(page.getByRole('option', { name: '上课说话', exact: true })).toBeVisible({ timeout: 8000 })
      await page.keyboard.press('Escape')
      // 手输新值：filterable 输入框打字 + 回车创建
      await contentSel.click()
      await contentSel.locator('input').fill('E2E违纪新情况XYZ')
      await page.keyboard.press('Enter')
      // 选违纪学生（多选下拉，选中后不自动关闭，选完 Escape）
      await disPane.locator('.el-select').first().click()
      // el-option label 格式为「姓名（学号）」；用精确 role 名避免匹配到同名子串/空名异常项
      await page.getByRole('option', { name: 'E2E测试生（999）', exact: true }).click()
      await page.keyboard.press('Escape')
      await disPane.getByRole('button', { name: '登记', exact: true }).click()
      await expect(page.locator('.el-message--success').first()).toBeVisible()
      // 新值已进入历史下拉
      await contentSel.click()
      await expect(page.getByRole('option', { name: 'E2E违纪新情况XYZ', exact: true })).toBeVisible({ timeout: 8000 })
      await page.keyboard.press('Escape')
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
      // newAwards 元素携带 tier（持有档）与 kind（award 新获得 / upgrade 升档）
      for (const a of s1.data.newAwards || []) {
        expect(['BRONZE', 'SILVER', 'GOLD', 'PLATINUM']).toContain(a.tier)
        expect(['award', 'upgrade']).toContain(a.kind)
      }

      // 座位奖牌接口：内部先静默扫描；返回 sid → 奖牌数组，每生 ≤3 枚，元素含 icon/name/tier
      const sm = await (await request.get(API + '/achievement/seat-medals', { headers: authHeader() })).json()
      expect(sm.code).toBe(200)
      expect(typeof sm.data === 'object' && sm.data !== null).toBe(true)
      expect((sm.data[testStudentId] || []).length).toBeGreaterThanOrEqual(1) // 刚授予的 GOLD 自定义成就必在
      for (const list of Object.values(sm.data)) {
        expect(Array.isArray(list)).toBe(true)
        expect(list.length).toBeLessThanOrEqual(3)
        for (const m of list) {
          expect(m.icon).toBeTruthy()
          expect(m.name).toBeTruthy()
          expect(['BRONZE', 'SILVER', 'GOLD', 'PLATINUM']).toContain(m.tier)
        }
      }

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
      // 内置新增：8 条 MANUAL 模板（builtin=1，afterAll 清理不删）+ 可进阶 AUTO 带三档 tiers
      expect(defs2.data.some(d => d.builtin === 1 && d.name === '体育之星')).toBe(true)
      const rankUpDef = defs2.data.find(d => d.rule_type === 'RANK_UP')
      expect(rankUpDef).toBeTruthy()
      expect(rankUpDef.tiered).toBe(1)
      expect(JSON.parse(rankUpDef.rule_params).tiers).toHaveLength(3)

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
      // 角标依赖该生「持有徽章」：自动成就只奖励正面行为（测试生有迟到/违纪/作业未交，均不满足），
      // 故显式授予一枚内置手动成就（体育之星）来确定性验证角标；授予记录随测试生 afterAll 级联清理
      const builtinDefs = (await (await request.get(API + '/achievement/defs', { headers: authHeader() })).json()).data
      const manualDef = builtinDefs.find(d => d.builtin === 1 && d.name === '体育之星')
      expect(manualDef).toBeTruthy()
      const awUi = await (await request.post(API + '/achievement/award', {
        headers: authHeader(),
        data: { defId: manualDef.id, studentIds: [testStudentId], remark: 'E2E角标验证' },
      })).json()
      expect(awUi.data.awarded).toBe(1)
      // 重新进花名册触发静默扫描 + 徽章角标拉取（goto 默认花名册视图）
      await page.goto('/roster')
      await expect.poll(async () =>
        await page.locator('.stu-grid .stu-card').count(), { timeout: 15000 }).toBeGreaterThan(35)
      await page.getByPlaceholder('搜索姓名/学号').fill('E2E测试生')
      await expect.poll(async () =>
        await page.locator('.stu-grid .stu-card').count(), { timeout: 10000 }).toBe(1)
      await expect(page.locator('.stu-grid .stu-badge').first()).toBeVisible({ timeout: 15000 })
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

      // ① 领养：当天为「孵化中蛋」——蛋图/暖光/✨、点击只提示不弹窗、hatched=0、
      //    未跨天 hatch-seen 守卫生效、蛋中加分只累计不进化；force 破壳（dev 开关模拟次日）后显 Lv1
      let r = await apiPost('/pet/adopt', { studentId: testStudentId, petKey: 'dragon', petName: 'E2E小龙' })
      expect(r.code).toBe(200)
      let me = await petOf()
      expect(me.petId).toBeTruthy()
      expect(me.level).toBe(1)
      expect(me.hatched).toBe(0)

      // 评价项：+3「平时测验满分」、+5「拾金不昧(贵重)」（蛋态/破壳后加分共用）
      const rules = (await apiGet('/pet/rules')).data
      const rule3 = rules.find(x => x.score === 3)
      const rule5 = rules.find(x => x.score === 5)
      expect(rule3).toBeTruthy()
      expect(rule5).toBeTruthy()

      await page.goto('/seat')
      const myCard = page.locator('.seat, .pet-card', { hasText: 'E2E测试生' })
      await expect.poll(async () => await myCard.count(), { timeout: 15000 }).toBe(1)
      // 蛋态 UI：蛋图（dragon_egg）+ 暖光浮动 class + 右上角 ✨
      await expect(myCard.locator('img.egg[src*="dragon_egg.webp"]')).toBeVisible()
      await expect(myCard.locator('.egg-spark')).toBeVisible()
      // 点击蛋：ElMessage 提示「明天再来」，不弹详情/领养窗（无可见弹窗遮罩；关闭的弹窗 DOM 仍常驻）
      await myCard.click()
      await expect(page.locator('.el-message', { hasText: '明天再来看看' })).toBeVisible({ timeout: 5000 })
      await expect(page.locator('.el-overlay:visible')).toHaveCount(0)

      // 当天未跨自然日：hatch-seen 不带 force → 后端跨天守卫 changed=false，hatched 仍 0
      r = await apiPost('/pet/hatch-seen', { studentId: testStudentId })
      expect(r.data.changed).toBe(false)
      me = await petOf()
      expect(me.hatched).toBe(0)

      // 蛋期间加分：成长值照常累计（流水/飘字正常）但不进化——levelUps 为空、level 仍 1
      r = await apiPost('/pet/evaluate', { studentId: testStudentId, ruleId: rule5.id })
      expect(r.data.affected).toBe(1)
      expect((r.data.levelUps || []).length).toBe(0)
      me = await petOf()
      expect(me.exp).toBe(5)
      expect(me.level).toBe(1)
      expect(me.hatched).toBe(0)

      // dev 开关 force 破壳（模拟次日首次打开座次表）：changed=true、hatched=1、按累计 exp 结算（5 分仍 Lv1）
      r = await apiPost('/pet/hatch-seen', { studentId: testStudentId, force: true })
      expect(r.data.changed).toBe(true)
      me = await petOf()
      expect(me.hatched).toBe(1)
      expect(me.level).toBe(1)
      await page.reload()
      await expect(myCard.locator('img[src*="dragon_1.webp"]')).toBeVisible()

      // ② 加分至 exp≥40 → 自动升 Lv2（蛋中已 +5，再 +5 共 7 次合计 40），UI 形态变 dragon_2
      for (let i = 0; i < 7; i++) {
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

      // 11f 末尾毕业典礼后宠物轮回为新蛋（hatched=0）：force 破壳模拟次日，否则点击蛋只提示不弹窗
      const hatchR = await apiPost('/pet/hatch-seen', { studentId: testStudentId, force: true })
      expect(hatchR.data.changed).toBe(true)

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
      // 内置 6 套皮肤齐全：skin_key/中文名/fx_theme 与 DataInitializer 种子一致
      const expectBuiltin = {
        dragon: ['云小龙', 'gold'], firedragon: ['烈焰神龙', 'fire'], catdragon: ['灵翼喵龙', 'ice'],
        candy: ['糖果天使龙', 'candy'], forest: ['青叶森龙', 'forest'], moonrabbit: ['月华仙兔', 'thunder']
      }
      const builtin = skins.filter(s => s.builtIn)
      expect(builtin.length).toBe(6)
      for (const [key, [name, fx]] of Object.entries(expectBuiltin)) {
        const s = builtin.find(x => x.skinKey === key)
        expect(s).toBeTruthy()
        expect(s.name).toBe(name)
        expect(s.fxTheme).toBe(fx)
      }

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
      // 内置 6 张种族卡：逐一点击，6 阶段预览图全部真实加载（naturalWidth>0，回归线上 /pets 裂图）
      for (const key of ['dragon', 'firedragon', 'catdragon', 'candy', 'forest', 'moonrabbit']) {
        await adoptDlg.locator(`.skin-card:has(img[src*="/pets/${key}_5"])`).click()
        await expect.poll(async () => {
          const imgs = adoptDlg.locator('.skin-preview img')
          // evaluateAll 回调在浏览器上下文执行，key 需作为第二参数显式传入
          return await imgs.evaluateAll((nodes, k) =>
            nodes.length === 6 && nodes.every(n => n.complete && n.naturalWidth > 0)
              && nodes.every(n => n.src.includes(`/pets/${k}_`)) ? 6 : 0, key)
        }, { timeout: 10000 }).toBe(6)
      }
      const customCard = adoptDlg.locator('.skin-card', { hasText: 'E2E冰麒麟' })
      await expect(customCard).toBeVisible()
      await customCard.click()
      await expect.poll(async () => await adoptDlg.locator('.skin-preview img[src*="/uploads/"]').count(),
        { timeout: 5000 }).toBe(6)
      await adoptDlg.locator('input').fill('E2E冰冰')
      await adoptDlg.getByRole('button', { name: /破壳领养/ }).click()
      await expect(adoptDlg).toBeHidden()
      // 领养后为孵化中蛋：卡片显示自创皮肤蛋图（egg 阶段同样传在 /uploads/）+ 孵化中标记
      await expect(card2.locator('img.egg[src*="/uploads/"]')).toBeVisible({ timeout: 10000 })
      await expect(card2.locator('.egg-badge', { hasText: '孵化中' })).toBeVisible()
      const p2 = (await apiGet('/pet/pets')).data.pets.find(p => p.studentId === testStudentId2)
      expect(p2.skinKey).toBe(skinKey)
      expect(p2.hatched).toBe(0)
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

    // ============ 12.7 手机遥控（SSE 通道 / 手机页 / 待播进化播放 / 远程切页） ============
    await test.step('手机遥控：手机端加分攒待播、一键播放进化、远程切页/找人高亮', async () => {
      // --- 数据准备：E2E遥控生(997) + 领养宠物 ---
      const add3 = await request.post(API + '/roster/student', {
        headers: authHeader(), data: { stuNo: '997', name: 'E2E遥控生', gender: '男' }
      })
      const sid3 = (await add3.json()).data
      await request.post(API + '/pet/adopt', {
        headers: authHeader(), data: { studentId: sid3, petKey: 'dragon', petName: 'E2E遥控龙' }
      })
      // 领养后为孵化中蛋：force 破壳（dev 开关），否则蛋中加分不进化、不产生 pendingEvolves
      await request.post(API + '/pet/hatch-seen', {
        headers: authHeader(), data: { studentId: sid3, force: true }
      })
      const rulesResp = await (await request.get(API + '/pet/rules', { headers: authHeader() })).json()
      const rulePlus = (rulesResp.data || []).filter(r => r.score > 0).sort((a, b) => b.score - a.score)[0]
      const timesToLv2 = Math.ceil(40 / rulePlus.score)

      try {
        // --- 链路 A：PC 开座次表（MainLayout 已建 SSE 遥控通道） ---
        await page.goto('/seat')
        await page.waitForTimeout(2000) // 等 ticket→EventSource 建连、置 remoteActive

        // 手机端 defer 连加到升级：升级置 evolve_pending 攒待播队列，PC 不自动弹演出
        for (let i = 0; i < timesToLv2; i++) {
          await request.post(API + '/pet/evaluate', {
            headers: authHeader(), data: { studentId: sid3, ruleId: rulePlus.id, defer: true }
          })
        }
        const petsResp1 = (await (await request.get(API + '/pet/pets', { headers: authHeader() })).json()).data
        expect(petsResp1.pendingEvolves.some(u => u.studentId === sid3)).toBeTruthy()
        // 遥控在线：1.5s 内演出不自动弹出
        await page.waitForTimeout(1500)
        expect(await page.locator('.evo-overlay').count()).toBe(0)

        // 手机发"全部播放"指令 → PC 连播进化演出 → 跳过后 evolve-seen 清场
        await request.post(API + '/remote/cmd', {
          headers: authHeader(), data: { action: 'evolve.playAll', payload: {} }
        })
        await expect(page.locator('.evo-overlay')).toBeVisible({ timeout: 8000 })
        await expect(page.locator('.evo-title')).toContainText(/进\s*化\s*成\s*功/)
        await expect(page.locator('.evo-sub')).toContainText('Lv.2')
        await page.locator('.evo-overlay').click({ position: { x: 40, y: 40 } })
        await expect.poll(async () => {
          const d = (await (await request.get(API + '/pet/pets', { headers: authHeader() })).json()).data
          return d.pendingEvolves.some(u => u.studentId === sid3)
        }, { timeout: 10000 }).toBeFalsy()

        // --- 链路 B：远程切页 + 找人高亮（用已排座的真实学生，遥控生未排座） ---
        await request.post(API + '/remote/cmd', {
          headers: authHeader(), data: { action: 'navigate', payload: { path: '/roster' } }
        })
        await expect(page).toHaveURL(/\/roster/, { timeout: 5000 })
        const grid = (await (await request.get(API + '/seat/grid', { headers: authHeader() })).json()).data
        const seatedSid = grid.seats.find(s => s.studentId)?.studentId
        await request.post(API + '/remote/cmd', {
          headers: authHeader(), data: { action: 'spotlight', payload: { studentId: seatedSid } }
        })
        await expect.poll(async () =>
          await page.locator('.remote-spot').count(), { timeout: 8000 }).toBeGreaterThan(0)

        // --- 链路 C：手机页 /m（手机视口，同 context 共享登录态） ---
        const phone = await page.context().newPage()
        await phone.setViewportSize({ width: 390, height: 844 })
        await phone.goto('/m')
        await phone.waitForTimeout(1500) // 手机页 SSE 建连
        await expect(phone.locator('.m-pet', { hasText: 'E2E遥控生' })).toBeVisible({ timeout: 10000 })

        // 手机端点学生 → 规则大按钮加分（defer 走 REMOTE 来源）→ toast 可撤销
        await phone.locator('.m-pet', { hasText: 'E2E遥控生' }).click()
        await expect(phone.locator('.m-sheet')).toBeVisible()
        await phone.locator('.rule-btn.good').first().click()
        await expect(phone.locator('.m-toast')).toBeVisible({ timeout: 5000 })
        const logs1 = (await (await request.get(API + '/pet/logs',
          { headers: authHeader(), params: { studentId: sid3 } })).json()).data
        expect(logs1[0].source).toBe('REMOTE')
        await phone.locator('.toast-undo').click() // 撤销 → 成长值回退到 40
        await expect.poll(async () => {
          const d = (await (await request.get(API + '/pet/pets', { headers: authHeader() })).json()).data
          return d.pets.find(x => x.studentId === sid3)?.exp
        }, { timeout: 8000 }).toBe(40)

        // 手机端再连加到 Lv3（exp 40→100）→ 待播条出现 → 手机点"全部播放" → PC 演出
        const timesToLv3 = Math.ceil((100 - 40) / rulePlus.score)
        for (let i = 0; i < timesToLv3; i++) {
          await request.post(API + '/pet/evaluate', {
            headers: authHeader(), data: { studentId: sid3, ruleId: rulePlus.id, defer: true }
          })
        }
        await expect(phone.locator('.m-held-bar')).toBeVisible({ timeout: 12000 })
        await phone.locator('.held-play').click()
        await expect(page.locator('.evo-overlay')).toBeVisible({ timeout: 8000 })
        await expect(page.locator('.evo-sub')).toContainText('Lv.3')
        await page.locator('.evo-overlay').click({ position: { x: 40, y: 40 } })
        await expect.poll(async () => {
          const d = (await (await request.get(API + '/pet/pets', { headers: authHeader() })).json()).data
          return d.pendingEvolves.some(u => u.studentId === sid3)
        }, { timeout: 10000 }).toBeFalsy()
        await phone.close()

        // --- 登录卡：独立 context（清 token 不影响主 context 的 PC 连接） ---
        const ctx2 = await page.context().browser().newContext({ viewport: { width: 390, height: 844 } })
        const p2 = await ctx2.newPage()
        await p2.goto('/m')
        await expect(p2.locator('.m-login')).toBeVisible()
        await p2.locator('.m-input').nth(0).fill('admin')
        await p2.locator('.m-input').nth(1).fill(PWD)
        await p2.locator('.m-btn-primary').click()
        await expect(p2.locator('.m-grid')).toBeVisible({ timeout: 10000 })
        await ctx2.close()
      } finally {
        // 清理：遥控生删除（宠物/流水随学生级联），保证步骤 13 统计回到 40 人
        await request.delete(API + '/roster/student/' + sid3, { headers: authHeader() })
      }
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

    // ============ 14. 座次表 v2：设置收纳 · 简易模式 · 名字位置 · 打印 ============
    await test.step('座次表v2：工具栏瘦身/设置弹窗改布局与过道/简易模式/名字位置/打印倒转黑白/偏好持久化', async () => {
      await page.goto('/seat')
      // 组长记录先清场（保证幂等：库里可能有手动测试残留的组长，会干扰"点第一个=设组长"断言；afterAll 还会再清）
      const cmPre = (await (await request.get(API + '/duty/committee', { headers: authHeader() })).json()).data || []
      for (const c of cmPre) {
        if (c.role === '组长') await request.delete(API + '/duty/committee/' + c.id, { headers: authHeader() })
      }
      // 14.1 工具栏瘦身：⚙设置/🖨打印 可见，旧的行数/列数/过道控件已移除
      await expect(page.getByRole('button', { name: '座位设置' })).toBeVisible({ timeout: 10000 })
      await expect(page.getByRole('button', { name: '打印' })).toBeVisible()
      await expect(page.locator('.page-toolbar .el-input-number')).toHaveCount(0)
      await expect(page.locator('.page-toolbar .el-select')).toHaveCount(0)

      // 14.2 ⚙座位设置弹窗：行数改 5、过道1 切换，棋盘即时变化且持久化
      await page.getByRole('button', { name: '座位设置' }).click()
      const cfgDlg = page.locator('.el-dialog', { hasText: '座位设置' }).last()
      await expect(cfgDlg).toBeVisible()

      // 14.2b 🏅奖牌开关：默认勾选；座位卡顶部挂本学期 top3（每生 ≤3 枚）；取消消失、重勾恢复
      const medalCheck = cfgDlg.locator('.el-checkbox', { hasText: '奖牌' })
      await expect(medalCheck).toBeVisible()
      await expect(medalCheck.locator('.el-checkbox__input')).toHaveClass(/is-checked/)
      await expect.poll(async () =>
        await page.locator('.seat .seat-medals').count(), { timeout: 10000 }).toBeGreaterThanOrEqual(1)
      await expect.poll(async () => {
        const counts = await page.locator('.seat .seat-medals')
          .evaluateAll(els => els.map(e => e.childElementCount))
        return counts.every(c => c <= 3)
      }, { timeout: 5000 }).toBe(true)
      await medalCheck.click() // 取消勾选 → 奖牌整块消失
      await expect.poll(async () =>
        await page.locator('.seat .seat-medals').count(), { timeout: 8000 }).toBe(0)
      await medalCheck.click() // 重勾 → 恢复
      await expect.poll(async () =>
        await page.locator('.seat .seat-medals').count(), { timeout: 8000 }).toBeGreaterThanOrEqual(1)

      const rowsInput = cfgDlg.locator('.el-input-number').first()
      await rowsInput.locator('input').fill('5')
      await rowsInput.locator('input').blur()
      // 棋盘网格行数 = 1 标签行 + 5 座位行 = 6
      await expect.poll(async () =>
        (await page.locator('.board-grid').evaluate(el => getComputedStyle(el).gridTemplateRows)).split(' ').length,
        { timeout: 8000 }).toBe(6)
      // 过道1：先设「无」（gutter 减 1），再设「第1列后」（gutter 加回）
      const gutterCount = () => page.locator('.board-grid .aisle-gutter').count()
      const nGutter = await gutterCount()
      const aisleSel = cfgDlg.locator('.el-select').first()
      await aisleSel.click()
      await page.locator('.el-select-dropdown:visible .el-select-dropdown__item', { hasText: '无' }).first().click()
      await expect.poll(gutterCount, { timeout: 8000 }).toBe(Math.max(0, nGutter - 1))
      await aisleSel.click()
      await page.locator('.el-select-dropdown:visible .el-select-dropdown__item', { hasText: '第1列后' }).first().click()
      await expect.poll(gutterCount, { timeout: 8000 }).toBe(Math.max(0, nGutter - 1) + 1)
      // 弹窗内过道标签与下拉配对（3 个「过道N」标签）
      await expect(cfgDlg.locator('.cfg-item-label', { hasText: '过道' })).toHaveCount(3)

      // 14.3 简易模式：宠物图/特效整块消失，座位格自动横排居中（h-center）
      await cfgDlg.locator('.el-switch').first().click()
      await expect.poll(async () => page.locator('.seat .seat-pet-img').count(), { timeout: 8000 }).toBe(0)
      await expect(page.locator('.seat .wx-aura')).toHaveCount(0)
      await expect.poll(async () => page.locator('.seat .seat-medals').count(), { timeout: 8000 }).toBe(0) // 简易模式奖牌随宠物块隐藏
      await expect(page.locator('.seat.h-center').first()).toBeVisible()
      // 未排座区（若有人）：纯名单网格、无 img、无领养按钮
      const unseatWrap = page.locator('.unseat-wrap')
      if (await unseatWrap.count()) {
        await expect(unseatWrap.locator('.unseated-list')).toBeVisible()
        await expect(unseatWrap.locator('img')).toHaveCount(0)
        await expect(unseatWrap.locator('button')).toHaveCount(0)
      }

      // 14.4 名字位置（完整模式三档可切；简易模式强制 h-center）：先关简易模式，默认 h-bottom → v-left → h-center → h-bottom，再重开简易模式（14.5 刷新断言依赖简易模式仍开）
      await cfgDlg.locator('.el-switch').first().click() // 关简易模式
      await expect.poll(async () => page.locator('.seat .seat-pet-img').count(), { timeout: 8000 }).toBeGreaterThan(0)
      await expect(page.locator('.seat.h-bottom').first()).toBeVisible({ timeout: 5000 }) // 偏好默认贴底
      await cfgDlg.locator('.el-radio-button', { hasText: '竖排贴左' }).click()
      await expect(page.locator('.seat.v-left').first()).toBeVisible({ timeout: 5000 })
      await cfgDlg.locator('.el-radio-button', { hasText: '横排居中' }).click()
      await expect(page.locator('.seat.h-center').first()).toBeVisible({ timeout: 5000 })
      await cfgDlg.locator('.el-radio-button', { hasText: '横排贴底' }).click()
      await expect(page.locator('.seat.h-bottom').first()).toBeVisible({ timeout: 5000 })
      await cfgDlg.locator('.el-switch').first().click() // 重开简易模式
      await expect.poll(async () => page.locator('.seat .seat-pet-img').count(), { timeout: 8000 }).toBe(0)
      await cfgDlg.locator('.el-dialog__close').last().click()
      await expect(cfgDlg).toBeHidden()

      // 14.5 偏好持久化：刷新后简易模式仍生效
      await page.reload()
      await expect.poll(async () => page.locator('.seat .seat-pet-img').count(), { timeout: 10000 }).toBe(0)

      // 14.5b 👑 列组长：指派模式设/同列替换/取消（当前为简易模式——👑 角标常驻、指派同样可用）
      const leaderGrid = (await (await request.get(API + '/seat/grid', { headers: authHeader() })).json()).data
      const byCol = {}
      for (const s of leaderGrid.seats) if (s.studentId) (byCol[s.col] ||= []).push(s.studentId)
      const leaderColNo = Number(Object.keys(byCol).find(col => byCol[col].length >= 2))
      expect(leaderColNo, '座位表应存在至少 2 人的列').toBeTruthy()
      const colPair = byCol[leaderColNo]
      const [sidA, sidB] = colPair
      const leaderNameA = leaderGrid.seats.find(s => s.studentId === sidA)?.name
      const crownOf = sid => page.locator(`.seat[data-sid="${sid}"] .seat-leader`)
      // 工具栏按钮（限定 .page-toolbar，避开指派提示条内同名的"完成指派"按钮）
      const leaderBtn = page.locator('.page-toolbar').getByRole('button', { name: /组长|完成指派/ })
      await expect(leaderBtn).toContainText('组长')          // 未进入指派态
      await leaderBtn.click()
      await expect(leaderBtn).toContainText('完成指派')      // 进入指派模式：按钮文案切换
      await expect(page.locator('.batch-bar', { hasText: '指派模式' })).toBeVisible()
      await expect(page.locator('.seat[data-sid]').first()).toHaveAttribute('draggable', 'false') // 指派模式禁拖拽
      // 点第一个座位 → 该生成为组长、出 👑
      await page.locator(`.seat[data-sid="${sidA}"]`).click()
      await expect.poll(async () => await crownOf(sidA).count(), { timeout: 8000 }).toBe(1)
      // 点同列第二个 → 同列替换：第二个出 👑、第一个 👑 消失
      await page.locator(`.seat[data-sid="${sidB}"]`).click()
      await expect.poll(async () => await crownOf(sidB).count(), { timeout: 8000 }).toBe(1)
      await expect.poll(async () => await crownOf(sidA).count(), { timeout: 8000 }).toBe(0)
      // 再点第二个 → 取消、👑 消失
      await page.locator(`.seat[data-sid="${sidB}"]`).click()
      await expect.poll(async () => await crownOf(sidB).count(), { timeout: 8000 }).toBe(0)
      // 重新点第一个，留一个组长供开关/打印断言
      await page.locator(`.seat[data-sid="${sidA}"]`).click()
      await expect.poll(async () => await crownOf(sidA).count(), { timeout: 8000 }).toBe(1)
      // 退出指派模式
      await leaderBtn.click()
      await expect(leaderBtn).toContainText('组长')
      await expect(page.locator('.batch-bar', { hasText: '指派模式' })).toBeHidden()

      // 14.5c 👑 组长显示开关：默认开；取消勾选 → 👑 隐藏且刷新保持；重勾恢复（14.6 打印断言依赖开启）
      await page.getByRole('button', { name: '座位设置' }).click()
      let leaderDlg = page.locator('.el-dialog', { hasText: '座位设置' }).last()
      await expect(leaderDlg).toBeVisible()
      const leaderCheck = leaderDlg.locator('.el-checkbox', { hasText: '组长' })
      await expect(leaderCheck.locator('.el-checkbox__input')).toHaveClass(/is-checked/)
      await expect(crownOf(sidA)).toHaveCount(1)
      await leaderCheck.click() // 取消勾选 → 👑 隐藏
      await expect.poll(async () => await crownOf(sidA).count(), { timeout: 8000 }).toBe(0)
      await leaderDlg.locator('.el-dialog__close').last().click()
      await page.reload() // 刷新保持隐藏
      await expect.poll(async () => await page.locator('.seat .seat-leader').count(), { timeout: 10000 }).toBe(0)
      await page.getByRole('button', { name: '座位设置' }).click()
      leaderDlg = page.locator('.el-dialog', { hasText: '座位设置' }).last()
      await leaderDlg.locator('.el-checkbox', { hasText: '组长' }).click() // 重勾恢复
      await expect.poll(async () => await page.locator(`.seat[data-sid="${sidA}"] .seat-leader`).count(), { timeout: 8000 }).toBe(1)
      await leaderDlg.locator('.el-dialog__close').last().click()

      // 14.6 🖨打印弹窗：倒转开关 → 预览简图与打印区带 flip class；黑白默认开 → mono
      await page.getByRole('button', { name: '打印' }).click()
      const pDlg = page.locator('.el-dialog', { hasText: '打印座次表' }).last()
      await expect(pDlg).toBeVisible()
      await expect(page.locator('.seat-print-area.mono')).toHaveCount(1)
      // 奖牌仅屏幕展示：打印区不含奖牌
      await expect(page.locator('.seat-print-area .seat-medals')).toHaveCount(0)
      await expect(page.locator('.seat-print-area .badge-icon')).toHaveCount(0)
      // 组长 👑 打印：设了组长的座位，打印区姓名旁带 👑（受同一显示开关控制，14.5c 末已重勾开启）
      await expect.poll(async () =>
        await page.locator('.seat-print-area .print-leader').count(), { timeout: 5000 }).toBeGreaterThanOrEqual(1)
      // 预览缩略：行号 3 个 + 两头列号 4×2=8 个；格内为真实学生姓名（不再是全"张三"）
      await expect(pDlg.locator('.pv-row')).toHaveCount(3)
      await expect(pDlg.locator('.pv-col')).toHaveCount(8)
      await expect.poll(async () => {
        const names = await pDlg.locator('.pv-cell:not(.empty)').allTextContents()
        return names.some(n => n.trim() && n.trim() !== '张三')
      }, { timeout: 5000 }).toBeTruthy()
      // 打印区列号两头都印（讲台侧 + 远侧），总数远多于单列列号
      await expect.poll(async () => page.locator('.seat-print-area .col-label').count(), { timeout: 5000 })
        .toBeGreaterThan(10)
      // 倒转开关：按行标签精确定位；先确保起始为"关"（is-checked）再点一次变"开"，
      // 不依赖偏好初始态——残留 flip=1 时单次点击会取反为关，导致 .flip 断言误失败
      const flipSwitch = pDlg.locator('.cfg-row', { hasText: '倒转' }).locator('.el-switch')
      if (await flipSwitch.evaluate(el => el.classList.contains('is-checked'))) {
        await flipSwitch.click()
      }
      await flipSwitch.click()
      await expect(pDlg.locator('.pv-board.flip')).toBeVisible({ timeout: 5000 })
      await expect(page.locator('.seat-print-area.flip')).toHaveCount(1)

      // 14.7 打印媒体：body.seat-printing + print 媒体下只显示打印区；空位 visibility:hidden 保网格
      await page.evaluate(() => document.body.classList.add('seat-printing'))
      await page.emulateMedia({ media: 'print' })
      await expect(page.locator('.seat-print-area')).toBeVisible()
      await expect(page.locator('.page-toolbar')).toBeHidden()
      await expect(page.locator('.board-grid')).toBeHidden()
      await expect(page.locator('.print-seat:not(.print-empty)').first()).toBeVisible()
      const emptyPrint = page.locator('.print-seat.print-empty').first()
      if (await emptyPrint.count()) await expect(emptyPrint).toBeHidden() // visibility:hidden
      await page.emulateMedia({ media: 'screen' })
      await page.evaluate(() => document.body.classList.remove('seat-printing'))
      await expect(page.locator('.seat-print-area')).toBeHidden() // 屏幕态恢复隐藏
      await pDlg.locator('.el-dialog__close').last().click()

      // 14.8 值日·班委页：组长不进班委名单标签，单独「学习小组」区按座位列显示组长+组员
      await page.goto('/duty')
      const committeeCard = page.locator('.el-card', { hasText: '班委名单' }).last()
      await expect.poll(async () =>
        (await committeeCard.locator('.committee-tag').allTextContents()).filter(t => t.includes('组长')).length,
        { timeout: 10000 }).toBe(0)
      const groupCard = page.locator('.el-card', { hasText: '学习小组' })
      await expect(groupCard).toBeVisible()
      // 小组卡总数 = 有学生的列数
      const seatedCols = new Set(leaderGrid.seats.filter(s => s.studentId).map(s => s.col)).size
      await expect.poll(async () => await groupCard.locator('.group-card').count(), { timeout: 10000 }).toBe(seatedCols)
      // 组长所在列卡：标题「第 N 列」、组长行 👑姓名、组长外有组员行
      const targetCol = groupCard.locator('.group-card', { hasText: `第 ${leaderColNo} 列` })
      await expect(targetCol.locator('.group-leader')).toContainText(leaderNameA)
      await expect(targetCol.locator('.group-member').first()).toBeVisible()
    })
  })
})

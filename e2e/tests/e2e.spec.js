import { test, expect } from '@playwright/test'

// 全站 E2E：登录一次，按模块逐步走查，每步带断言。
// 依赖后端 8088 + 前端 5173 已启动，且已灌入演示数据（scripts/seed.mjs）。

const PWD = '123456'

// 侧边栏导航
const nav = (page, name) => page.locator('.nav-item', { hasText: name }).first()

test.describe('班主任工作台 全站走查', () => {
  test('登录 → 8 大模块 → 班级日常 → 刷新保活', async ({ page }) => {
    // ============ 0. 登录 ============
    await test.step('登录页：错误密码提示 + 正确密码进入', async () => {
      await page.goto('/')
      await expect(page).toHaveURL(/\/login/)
      // 错误密码
      await page.getByPlaceholder('请输入密码').fill('000000')
      await page.getByRole('button', { name: '进入' }).click()
      await expect(page.locator('.el-message--error').first()).toBeVisible()
      // 正确密码
      await page.getByPlaceholder('请输入密码').fill(PWD)
      await page.getByRole('button', { name: '进入' }).click()
      await expect(page).toHaveURL(/\/dashboard/)
      await expect(page.locator('.greet-title')).toBeVisible()
    })

    // ============ 1. 工作台 ============
    await test.step('工作台：问候卡/统计卡/工作卡/节假日', async () => {
      await expect(page.getByText('班主任 ·')).toBeVisible()
      // 4 张统计卡
      await expect(page.locator('.mini-stat')).toHaveCount(4)
      // 学生总数 50
      await expect(page.locator('.mini-stat .ms-num').first()).toContainText('50')
      // 6 张工作卡
      await expect(page.locator('.work-card')).toHaveCount(6)
      // 节假日倒计时
      await expect(page.locator('.work-card.holiday .holiday-name')).toBeVisible()
    })

    await test.step('工作台：一句话速记智能归类并一键保存', async () => {
      await page.getByPlaceholder('输入速记内容，回车智能归类...').fill('路飞今天迟到了，已电话联系家长')
      await page.getByRole('button', { name: '归类' }).click()
      // 归类预览弹窗
      const dlg = page.locator('.el-dialog', { hasText: '速记智能归类预览' })
      await expect(dlg).toBeVisible()
      // 应拆出 早读考勤 + 家校沟通 两个模块
      await expect(dlg.getByText('早读考勤')).toBeVisible()
      await expect(dlg.getByText('家校沟通')).toBeVisible()
      await dlg.getByRole('button', { name: '一键保存' }).click()
      await expect(page.locator('.el-message--success').first()).toBeVisible()
      await expect(dlg).toBeHidden()
    })

    // ============ 2. 花名册 ============
    await test.step('花名册：统计卡 50/25/25、搜索、性别筛选、档案抽屉', async () => {
      await nav(page, '花名册').click()
      await expect(page).toHaveURL(/\/roster/)
      await expect(page.locator('.page-title')).toContainText('学生花名册')
      // 统计卡数字
      const nums = page.locator('.stat-card .num')
      await expect(nums.nth(0)).toHaveText('50')
      await expect(nums.nth(1)).toHaveText('25')
      await expect(nums.nth(2)).toHaveText('25')
      // 50 张学生卡
      await expect(page.locator('.stu-grid .stu-card')).toHaveCount(50)
      // 搜索「路飞」→ 1 张
      await page.getByPlaceholder('搜索姓名/学号').fill('路飞')
      await expect(page.locator('.stu-grid .stu-card')).toHaveCount(1)
      await page.getByPlaceholder('搜索姓名/学号').fill('')
      await expect(page.locator('.stu-grid .stu-card')).toHaveCount(50)
      // 男生筛选 → 25
      await page.locator('.el-radio-button', { hasText: '男生' }).click()
      await expect(page.locator('.stu-grid .stu-card')).toHaveCount(25)
      await page.locator('.el-radio-button', { hasText: '全部' }).click()
      await expect(page.locator('.stu-grid .stu-card')).toHaveCount(50)
      // 打开第一个学生档案抽屉
      await page.locator('.stu-grid .stu-card').first().click()
      const drawer = page.locator('.el-drawer', { hasText: '的档案' })
      await expect(drawer).toBeVisible()
      await expect(drawer.getByText('家长联系方式')).toBeVisible()
      await drawer.locator('.el-drawer__close-btn').click()
      await expect(drawer).toBeHidden()
    })

    // ============ 3. 座次表 ============
    await test.step('座次表：随机排座后男女座位落位、讲台/过道', async () => {
      await nav(page, '座次表').click()
      await expect(page).toHaveURL(/\/seat/)
      await expect(page.locator('.podium')).toContainText('讲台')
      await page.getByRole('button', { name: /随机排座/ }).click()
      await expect(page.locator('.el-message--success').first()).toBeVisible()
      // 有学生的座位（男蓝/女粉）应 > 0，50 名学生落位
      await expect.poll(async () =>
        await page.locator('.seat.boy, .seat.girl').count(), { timeout: 15000 }).toBeGreaterThan(40)
      // 存在空座位（7x8=56，50 人）
      await expect(page.locator('.seat.empty-seat').first()).toBeVisible()
    })

    // ============ 4. 值日表·班委 ============
    await test.step('值日表：五日卡、自动轮值落人、班委区', async () => {
      await nav(page, '值日表').click()
      await expect(page).toHaveURL(/\/duty/)
      await expect(page.locator('.day-card')).toHaveCount(5)
      await page.getByRole('button', { name: /自动轮值/ }).click()
      await expect(page.locator('.el-message--success').first()).toBeVisible()
      await expect.poll(async () =>
        await page.locator('.duty-person').count(), { timeout: 15000 }).toBeGreaterThan(10)
      // 值日事项标签
      await expect(page.locator('.task-tag').first()).toBeVisible()
    })

    // ============ 5. 成绩分析 ============
    await test.step('成绩分析：科目统计卡、图表、明细表', async () => {
      await nav(page, '成绩分析').click()
      await expect(page).toHaveURL(/\/score/)
      // 9 科统计卡
      await expect.poll(async () =>
        await page.locator('.score-card').count(), { timeout: 20000 }).toBeGreaterThanOrEqual(9)
      // 两张 ECharts 图
      await expect.poll(async () =>
        await page.locator('canvas').count(), { timeout: 20000 }).toBeGreaterThanOrEqual(2)
      // 明细表有学生行
      await expect.poll(async () =>
        await page.locator('.el-table__row').count(), { timeout: 20000 }).toBeGreaterThan(40)
    })

    await test.step('成绩分析：选择对比考试 → 进退步对比表', async () => {
      // 第二个 el-select 是「对比考试」
      await page.locator('.el-select').nth(1).click()
      await page.getByRole('option', { name: /摸底/ }).click()
      // 对比表出现，含「总分变化」列
      await expect(page.getByText('考试进退步对比')).toBeVisible()
      await expect(page.locator('.el-tag').filter({ hasText: /↑|↓/ }).first()).toBeVisible()
    })

    // ============ 6. 家长联系方式 ============
    await test.step('家长联系方式：通讯录、沟通弹窗', async () => {
      await nav(page, '家长联系方式').click()
      await expect(page).toHaveURL(/\/parent/)
      // 通讯录表（第一张卡）；页内还有第二张“沟通记录”表，需 scope 避免混算
      const contactRows = page.locator('.el-card', { hasText: '家长通讯录' }).locator('.el-table__row')
      await expect.poll(async () => await contactRows.count(), { timeout: 15000 }).toBeGreaterThan(40)
      // 搜索「路飞」→ 通讯录仅 1 行（家长名“路飞之父/母”同属该生，仍聚合为 1 行）
      await page.getByPlaceholder('搜索学生/家长/电话').fill('路飞')
      await expect.poll(async () => await contactRows.count(), { timeout: 10000 }).toBe(1)
      await page.getByPlaceholder('搜索学生/家长/电话').fill('')
      await expect.poll(async () => await contactRows.count(), { timeout: 10000 }).toBeGreaterThan(40)
      // 打开第一个学生的沟通弹窗
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
      // 课表单元格
      await expect.poll(async () =>
        await page.locator('.tt-cell').count(), { timeout: 15000 }).toBeGreaterThan(30)
      // 已排课程块（seed 排满周一~五）
      await expect.poll(async () =>
        await page.locator('.tt-cell.filled').count(), { timeout: 15000 }).toBeGreaterThan(20)
      // 科目图例
      await expect.poll(async () =>
        await page.locator('.legend-chip').count(), { timeout: 10000 }).toBeGreaterThanOrEqual(10)
      // 班级 tab（班主任班 + 任课班）
      await expect.poll(async () =>
        await page.locator('.el-tabs__item').count(), { timeout: 10000 }).toBeGreaterThanOrEqual(2)
    })

    // ============ 8. 待办事项 ============
    await test.step('待办事项：新增、勾选完成、逾期样式', async () => {
      await nav(page, '待办事项').click()
      await expect(page).toHaveURL(/\/todo/)
      const title = 'E2E自动测试待办_' + Date.now()
      await page.getByPlaceholder(/新增待办/).fill(title)
      await page.getByRole('button', { name: '添加' }).click()
      await expect(page.locator('.el-message--success').first()).toBeVisible()
      // 新条目出现
      const item = page.locator('.todo-item', { hasText: title })
      await expect(item).toBeVisible()
      // 勾选完成
      await item.locator('.el-checkbox').click()
      await expect.poll(async () =>
        await item.evaluate(el => el.classList.contains('done')), { timeout: 10000 }).toBeTruthy()
    })

    // ============ 9. 班级日常（侧边栏无入口，走路由） ============
    await test.step('班级日常：考勤 50 人登记并保存', async () => {
      await page.goto('/daily')
      await expect(page.locator('.page-title')).toContainText('班级日常')
      await expect.poll(async () =>
        await page.locator('.att-item').count(), { timeout: 15000 }).toBe(50)
      // 第一名学生标记为「迟到」
      await page.locator('.att-item').first().getByText('迟到').click()
      await expect(page.locator('.att-item').first()).toHaveClass(/st-warn/)
      await page.getByRole('button', { name: '保存考勤' }).click()
      await expect(page.locator('.el-message--success').first()).toBeVisible()
    })

    await test.step('班级日常：作业收缴选科目后加载名单', async () => {
      await page.getByText('作业收缴').click()
      // Element Plus 的 el-select 占位文案不在 input attribute 上：按作业面板内的下拉控件定位
      const hwPane = page.locator('.el-tab-pane').filter({ hasText: '勾选表示' })
      await hwPane.locator('.el-select').first().click()
      await page.getByRole('option', { name: '数学' }).click()
      await expect.poll(async () =>
        await page.locator('.hw-item').count(), { timeout: 15000 }).toBe(50)
    })

    await test.step('班级日常：课堂纪律登记（多选学生）', async () => {
      await page.getByText('课堂纪律').click()
      await expect(page.locator('.el-table')).toBeVisible()
    })

    // ============ 10. 刷新保持登录 ============
    await test.step('刷新页面后保持登录态（不跳回登录页）', async () => {
      await page.goto('/dashboard')
      await page.reload()
      await expect(page).toHaveURL(/\/dashboard/)
      await expect(page.locator('.greet-title')).toBeVisible()
    })
  })
})

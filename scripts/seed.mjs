// 演示数据种子脚本：登录 → 灌入 50 名学生 → 排课/排座/轮值/待办/沟通 → 生成并导入两次成绩
// 用法：node scripts/seed.mjs   （需后端运行在 8088，默认密码 123456）
import XLSX from 'xlsx'

const BASE = 'http://localhost:8088/api'

// ---- 登录 ----
const loginResp = await fetch(BASE + '/auth/login', {
  method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ password: '123456' })
})
const { data: login } = await loginResp.json()
const token = login.token
const H = { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token }
const post = (p, b) => fetch(BASE + p, { method: 'POST', headers: H, body: JSON.stringify(b) }).then(r => r.json())
const get = (p) => fetch(BASE + p, { headers: { Authorization: 'Bearer ' + token } }).then(r => r.json())

// ---- 50 名学生 ----
const boys = ['路飞','索隆','山治','乌索普','乔巴','弗兰奇','布鲁克','甚平','艾斯','萨博','龙','卡普','战国','黑胡子','红发','巴基','鹰眼','明哥','罗','基德','霍金斯','阿普','贝基','克力架','斯慕吉']
const girls = ['娜美','罗宾','女帝','佩罗娜','薇薇','白星','蕾贝卡','紫罗兰','baby5','莫奈','达斯琪','缇娜','加尔默罗','雪莉','布琳','戚风','罗拉','玛琪诺','可雅','古伊娜','诺琪高','贝尔梅尔','柯妮丝','谢丽','加洛特']
const places = ['海上餐厅巴拉蒂','最终之岛','和之国','水之都Water7','女儿岛亚马逊百合','空岛Skypiea']
let n = 0
for (const name of boys) await addStudent(++n, name, '男')
for (const name of girls) await addStudent(++n, name, '女')
async function addStudent(i, name, gender) {
  const no = '2024' + String(i).padStart(3, '0')
  await post('/roster/student', {
    stuNo: no, name, gender, address: places[i % places.length],
    parents: [
      { relation: '父亲', name: name + '之父', phone: '138' + String(10000000 + i) },
      { relation: '母亲', name: name + '之母', phone: '139' + String(20000000 + i) }
    ]
  })
}
console.log('✅ 学生灌入完成：50 人')

// ---- 排课（周一~周五 填满节次，循环科目） ----
const classes = (await get('/timetable/classes')).data
const home = classes.find(c => c.type === 'HOMEROOM')
const subjects = (await get('/timetable/subjects')).data
const teached = subjects.filter(s => s.name !== '自习')
const grid = (await get('/timetable/grid?classId=' + home.id)).data
const teachPeriods = grid.periods.filter(p => !p.isBreak)
let si = 0
for (let day = 1; day <= 5; day++) {
  for (const p of teachPeriods) {
    const sub = p.name.includes('自习') ? subjects.find(s => s.name === '自习') : teached[si++ % teached.length]
    await post('/timetable/cell', { classId: home.id, dayOfWeek: day, periodId: p.id, subjectId: sub.id })
  }
}
console.log('✅ 示例课表已排')

// ---- 随机排座 + 自动轮值 ----
await post('/seat/auto', {})
await post('/duty/auto', {})
console.log('✅ 随机排座 + 值日轮值完成')

// ---- 待办 ----
await post('/todo', { title: '周三班会准备诚信主题PPT', category: '班级', dueDate: '2026-09-02', source: 'MANUAL' })
await post('/todo', { title: '催交数学作业未交名单', category: '教学', dueDate: '2026-09-01', source: 'MANUAL' })
console.log('✅ 示例待办已加')

// ---- 沟通记录 ----
await post('/parent/log', { studentId: 1, contactDate: '2026-08-26', method: '电话', content: '上课吃零食，与家长沟通' })
await post('/parent/log', { studentId: 2, contactDate: '2026-08-26', method: '电话', content: '经常迟到，家长表示会督促' })
console.log('✅ 示例沟通记录已加')

// ---- 生成并导入两次成绩 ----
const subjNames = ['化学','历史','地理','政治','数学','物理','生物','英语','语文']
const roster = (await get('/roster/students')).data
async function uploadScore(examName, date, lift) {
  const aoa = [['学号', '姓名', ...subjNames]]
  for (const s of roster) {
    const row = [s.stu_no, s.name]
    for (let k = 0; k < subjNames.length; k++) {
      // 基础 55-95，第二次整体提升 lift 分
      let v = 55 + Math.round(Math.random() * 40) + lift + (Math.random() < 0.15 ? 8 : 0)
      row.push(Math.min(100, v))
    }
    aoa.push(row)
  }
  const ws = XLSX.utils.aoa_to_sheet(aoa)
  const wb = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(wb, ws, '成绩')
  const buf = XLSX.write(wb, { type: 'buffer', bookType: 'xlsx' })
  const fd = new FormData()
  fd.append('file', new Blob([buf], { type: 'application/octet-stream' }), examName + '.xlsx')
  fd.append('examName', examName)
  fd.append('examDate', date)
  const r = await fetch(BASE + '/score/import', { method: 'POST', headers: { Authorization: 'Bearer ' + token }, body: fd })
  const { data } = await r.json()
  console.log(`✅ 导入${examName}：`, data)
}
await uploadScore('测试成绩1（开学摸底）', '2026-08-20', 0)
await uploadScore('测试成绩2（第一次月考）', '2026-08-30', 6)

console.log('🎉 全部演示数据就绪。默认密码 123456，前端 http://localhost:5173')

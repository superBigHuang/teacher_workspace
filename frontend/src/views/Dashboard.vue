<template>
  <div>
    <el-row :gutter="16">
      <!-- 问候卡 -->
      <el-col :span="14">
        <el-card class="greet-card">
          <div class="greet-head">
            <div>
              <div class="greet-title">{{ greeting }}，{{ settings.teacher_name || '老师' }}～</div>
              <div class="greet-date">{{ todayStr }} ｜ {{ settings.term }}</div>
              <div class="greet-sub">数据每日持续保存 · 最近更新 {{ updatedAt }}</div>
              <div class="class-tags">
                <el-tag effect="dark" color="#f78fb3" class="ctag">班主任 · {{ homeroomName }}（{{ stats.studentTotal || 0 }}人）</el-tag>
                <el-tag effect="plain" class="ctag">任课 · {{ subjectClassName }}</el-tag>
              </div>
            </div>
            <el-button :icon="Edit" circle @click="editDlg = true" />
          </div>
          <div class="motto-row"><span class="motto-label">校训</span><span>{{ settings.school_motto }}</span></div>
          <div class="motto-row"><span class="motto-label">个人箴言</span><span>{{ settings.personal_motto }}</span></div>
        </el-card>
      </el-col>

      <!-- 一句话速记 -->
      <el-col :span="10">
        <el-card class="quick-card">
          <div class="quick-title">⚡ 一句话速记 <span class="quick-hint">（回车 → 智能拆分到模块）</span></div>
          <div class="quick-example">例：今天小明迟到，已跟家长电话沟通 / 周三班会准备诚信主题PPT / 数学作业未交 催一下</div>
          <div class="quick-desc">系统自动识别归属：学生档案 / 班级日志 / 家校沟通 / 待办，弹窗勾选后一键保存</div>
          <div class="quick-input">
            <el-input v-model="noteText" placeholder="输入速记内容，回车智能归类..." @keyup.enter="doParse" />
            <el-button type="primary" @click="doParse">归类</el-button>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 统计卡 -->
    <el-row :gutter="16" class="stat-cards">
      <el-col :span="6">
        <el-card class="mini-stat" shadow="hover" @click="go('/roster')">
          <div class="ms-icon" style="background:#f0e8fb">🎓</div>
          <div class="ms-num">{{ stats.studentTotal || 0 }} <small>+{{ stats.extraStudents || 0 }}</small></div>
          <div class="ms-label">班主任班 + 任课班</div>
          <div class="ms-link">点击管理档案 →</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="mini-stat" shadow="hover" @click="go('/timetable')">
          <div class="ms-icon" style="background:#fdeede">🗓️</div>
          <div class="ms-num">{{ stats.todayCourses || 0 }}</div>
          <div class="ms-label">今日课程</div>
          <div class="ms-link">含晚自习/周末 →</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="mini-stat" shadow="hover" @click="go('/todo')">
          <div class="ms-icon" style="background:#e4f6ec">✅</div>
          <div class="ms-num">{{ stats.todoOpen || 0 }}</div>
          <div class="ms-label">待办事项</div>
          <div class="ms-link">未完成 →</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="mini-stat" shadow="hover" @click="go('/parent')">
          <div class="ms-icon" style="background:#fde4ee">📞</div>
          <div class="ms-num">{{ stats.commPending || 0 }}</div>
          <div class="ms-label">家校沟通</div>
          <div class="ms-link">待跟进 →</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 请假提醒条（固定，不参与拖拽排序） -->
    <el-card class="leave-card" shadow="hover">
      <div class="lv-nums">
        <div class="lv-num"><b>🏖️ {{ leaveStat.activeCount || 0 }}</b><span>请假中</span></div>
        <div class="lv-num" :class="{ hot: leaveStat.todayReturnCount > 0 }">
          <b>{{ leaveStat.todayReturnCount || 0 }}</b><span>今日应销假</span>
        </div>
        <div class="lv-num" :class="{ hot: leaveStat.overdueCount > 0 }">
          <b class="danger">{{ leaveStat.overdueCount || 0 }}</b><span>未归校</span>
        </div>
      </div>
      <div class="lv-names">
        <template v-if="activeLeaves.length">
          <el-tag v-for="l in activeLeaves.slice(0, 8)" :key="l.id"
                  :type="l.overdue ? 'danger' : (l.returnToday ? 'warning' : 'info')"
                  effect="plain" class="lv-tag">
            {{ l.name }}·{{ l.leaveType }}·{{ l.endDate }}返校
          </el-tag>
          <span v-if="activeLeaves.length > 8" class="lv-more">等 {{ activeLeaves.length }} 人</span>
        </template>
        <span v-else class="empty-h">当前无人请假 🎉</span>
      </div>
      <div class="lv-actions">
        <el-button v-if="returnableLeaves.length" type="danger" size="small" @click="batchReturn">
          一键销假（{{ returnableLeaves.length }}）
        </el-button>
        <el-button size="small" @click="go('/daily')">请假管理 →</el-button>
      </div>
    </el-card>

    <!-- 工作卡片（可拖拽排序） -->
    <div class="section-title">📌 工作卡片 <span class="drag-hint">（拖动卡片可调整顺序）</span></div>
    <draggable v-model="cardOrder" :animation="200" :item-key="el => el" class="card-grid" @end="saveOrder">
      <template #item="{ element }">
        <el-card class="work-card" :class="element" shadow="hover">
          <!-- 早读考勤 -->
          <div v-if="element === 'attendance'" @click="go('/daily')">
            <div class="wc-head">⏰ 早读考勤 <el-tag size="small" type="success">{{ stats.attPresent || 0 }}/{{ stats.attTotal || 0 }}</el-tag></div>
            <div class="wc-body">今日全勤 {{ stats.attPresent || 0 }}/{{ stats.attTotal || 0 }} 人</div>
            <el-button type="primary" text class="wc-btn" @click.stop="go('/daily')">✏️ 点击登记</el-button>
          </div>
          <!-- 我的课表 -->
          <div v-else-if="element === 'timetable'" @click="go('/timetable')">
            <div class="wc-head">📖 我的课表</div>
            <div class="three-num">
              <div><b>{{ stats.todayCourses || 0 }}</b><span>今日课程</span></div>
              <div><b>{{ stats.todayRemaining || 0 }}</b><span>今日剩余</span></div>
              <div><b>{{ stats.weekRemaining || 0 }}</b><span>本周剩余</span></div>
            </div>
            <div class="period-list">
              <div v-for="c in todayCourses.slice(0, 3)" :key="c.name" class="period-row">
                <span>{{ c.name }}</span><span>{{ c.startTime }}</span><b :style="{color:c.color}">{{ c.subjectName }}</b>
              </div>
            </div>
            <el-button type="primary" text class="wc-link">查看完整课表 →</el-button>
          </div>
          <!-- 节假日倒计时 -->
          <div v-else-if="element === 'holiday'">
            <div class="wc-head">🏮 节假日倒计时</div>
            <div v-if="holidays.length" class="holiday-main">
              <div class="holiday-days">{{ holidays[0].days }}</div>
              <div class="holiday-unit">天后</div>
              <div class="holiday-name">{{ holidays[0].name }}</div>
              <div class="holiday-date">{{ holidays[0].date }}</div>
            </div>
            <div v-else class="holiday-main empty-h">近期暂无节假日</div>
            <div class="holiday-others">
              <el-tag v-for="h in holidays.slice(1, 4)" :key="h.id" effect="plain" class="ho-tag"
                      closable @close="delHoliday(h.id)">{{ h.name }} {{ h.days }}天</el-tag>
            </div>
          </div>
          <!-- 今日课程 -->
          <div v-else-if="element === 'todayCourse'" @click="go('/timetable')">
            <div class="wc-head">📖 今日课程</div>
            <div class="course-timeline">
              <div v-for="c in todayCourses" :key="c.name" class="timeline-row">
                <span class="tl-time">{{ c.startTime }}-{{ c.endTime }}</span>
                <span class="tl-tag" :style="{background:c.color}">{{ c.subjectName }}</span>
              </div>
              <div v-if="!todayCourses.length" class="empty-h">今日无排课</div>
            </div>
          </div>
          <!-- 课堂纪律 -->
          <div v-else-if="element === 'discipline'" @click="go('/daily')">
            <div class="wc-head">📝 课堂纪律 <el-tag size="small" type="info">{{ stats.disciplineToday || 0 }} 条</el-tag></div>
            <div class="wc-body">今日违纪记录 {{ stats.disciplineToday || 0 }} 条</div>
            <el-button type="primary" text class="wc-btn" @click.stop="go('/daily')">✏️ 点击登记</el-button>
          </div>
          <!-- 作业收缴 -->
          <div v-else-if="element === 'homework'" @click="go('/daily')">
            <div class="wc-head">📚 作业收缴 <el-tag size="small" :type="stats.homeworkMissing ? 'danger' : 'success'">{{ stats.homeworkMissing || 0 }} 人未交</el-tag></div>
            <div class="wc-body">今日作业未交 {{ stats.homeworkMissing || 0 }} 人</div>
            <el-button type="primary" text class="wc-btn" @click.stop="go('/daily')">✏️ 点击登记</el-button>
          </div>
        </el-card>
      </template>
    </draggable>

    <!-- 编辑问候/设置 -->
    <el-dialog v-model="editDlg" title="编辑工作台信息" width="460px">
      <el-form :model="settings" label-width="90px">
        <el-form-item label="教师姓名"><el-input v-model="settings.teacher_name" /></el-form-item>
        <el-form-item label="学期"><el-input v-model="settings.term" /></el-form-item>
        <el-form-item label="校训"><el-input v-model="settings.school_motto" /></el-form-item>
        <el-form-item label="个人箴言"><el-input v-model="settings.personal_motto" type="textarea" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDlg = false">取消</el-button>
        <el-button type="primary" @click="saveSettings">保存</el-button>
      </template>
    </el-dialog>

    <!-- 速记归类预览 -->
    <el-dialog v-model="parseDlg" title="⚡ 速记智能归类预览" width="620px">
      <div class="parse-tip">已自动识别并拆分到下列模块，勾选后一键保存：</div>
      <div v-for="(e, i) in entries" :key="i" class="entry">
        <el-checkbox v-model="e.checked">
          <el-tag size="small" effect="dark" :color="moduleColor(e.module)">{{ moduleLabel(e.module) }}</el-tag>
          <span class="entry-text">{{ e.text }}</span>
        </el-checkbox>
        <el-select v-if="needStudent(e.module)" v-model="e.studentId" filterable size="small"
                   placeholder="选择学生" class="entry-student">
          <el-option v-for="s in allStudents" :key="s.id" :label="s.name + '（' + s.stu_no + '）'" :value="s.id" />
        </el-select>
      </div>
      <el-empty v-if="!entries.length" description="未识别到内容" :image-size="60" />
      <template #footer>
        <el-button @click="parseDlg = false">取消</el-button>
        <el-button type="primary" @click="confirmSave">一键保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import draggable from 'vuedraggable'
import { Edit } from '@element-plus/icons-vue'
import http from '../api/http'

const router = useRouter()
const settings = reactive({})
const stats = reactive({})
const holidays = ref([])
const cardOrder = ref([])
const todayCourses = ref([])
const allStudents = ref([])
const homeroomName = ref('初一(1)班')
const subjectClassName = ref('初一(2)班')
const editDlg = ref(false)
const noteText = ref('')
const parseDlg = ref(false)
const entries = ref([])
const updatedAt = ref('')
const leaveStat = ref({})     // /leave/summary 计数
const activeLeaves = ref([])   // /leave/active 请假中名单

const MODULE_META = {
  ATTENDANCE: { label: '早读考勤', color: '#f0c96e' },
  COMM: { label: '家校沟通', color: '#f0a868' },
  HOMEWORK: { label: '作业收缴', color: '#7fb3f3' },
  DISCIPLINE: { label: '课堂纪律', color: '#c3a6e8' },
  TODO: { label: '待办事项', color: '#67c23a' }
}
const moduleLabel = m => MODULE_META[m]?.label || m
const moduleColor = m => MODULE_META[m]?.color || '#999'
const needStudent = m => ['ATTENDANCE', 'COMM', 'HOMEWORK', 'DISCIPLINE'].includes(m)

const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 6) return '凌晨好'
  if (h < 11) return '早上好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})
const todayStr = computed(() => {
  const d = new Date()
  const week = ['日', '一', '二', '三', '四', '五', '六'][d.getDay()]
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} 星期${week}`
})

async function loadSettings() {
  const s = await http.get('/dashboard/settings')
  Object.assign(settings, s)
}
async function loadStats() {
  Object.assign(stats, await http.get('/dashboard/stats'))
  updatedAt.value = new Date().toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}
async function loadHolidays() { holidays.value = await http.get('/dashboard/holidays') }
async function loadCards() { cardOrder.value = await http.get('/dashboard/cards') }
async function saveOrder() { await http.post('/dashboard/cards', { order: cardOrder.value }) }

async function loadTodayCourses() {
  const classes = await http.get('/timetable/classes')
  const home = classes.find(c => c.type === 'HOMEROOM') || classes[0]
  const subj = classes.find(c => c.type === 'SUBJECT')
  if (home) homeroomName.value = home.name
  if (subj) subjectClassName.value = subj.name
  if (!home) return
  const dow = new Date().getDay() || 7
  const grid = await http.get('/timetable/grid', { params: { classId: home.id } })
  const periodMap = Object.fromEntries(grid.periods.map(p => [p.id, p]))
  todayCourses.value = grid.cells
    .filter(c => c.dayOfWeek === dow)
    .map(c => ({ ...c, ...periodMap[c.periodId] }))
    .filter(c => c.name && !c.isBreak)
    .sort((a, b) => (a.sort || 0) - (b.sort || 0))
}

function go(path) { router.push(path) }

// ---------- 请假提醒 ----------
/** 需一键销假：今日应销假 + 已超期未归校 */
const returnableLeaves = computed(() => activeLeaves.value.filter(l => l.returnToday || l.overdue))

async function loadLeave() {
  leaveStat.value = await http.get('/leave/summary')
  activeLeaves.value = await http.get('/leave/active')
}

/** 工作台一键销假：逐个置 DONE 后刷新请假数据与统计 */
async function batchReturn() {
  for (const l of returnableLeaves.value) {
    await http.post(`/leave/${l.id}/return`, {})
  }
  ElMessage.success(`已为 ${returnableLeaves.value.length} 名学生销假`)
  await loadLeave()
  loadStats()
}

async function saveSettings() {
  await http.post('/dashboard/settings', {
    teacher_name: settings.teacher_name, term: settings.term,
    school_motto: settings.school_motto, personal_motto: settings.personal_motto
  })
  ElMessage.success('已保存')
  editDlg.value = false
}
async function delHoliday(id) {
  await http.delete('/dashboard/holiday/' + id)
  loadHolidays()
}

async function doParse() {
  if (!noteText.value.trim()) { ElMessage.warning('请输入速记内容'); return }
  const res = await http.post('/dashboard/quicknote/parse', { content: noteText.value })
  const list = []
  for (const seg of res.segments) {
    for (const module of seg.modules) {
      const matched = seg.students[0]
      const entry = {
        checked: true, module, text: seg.text,
        studentId: matched ? matched.id : null,
        studentName: matched ? matched.name : ''
      }
      if (module === 'ATTENDANCE') entry.status = inferAttStatus(seg.text)
      if (module === 'COMM') entry.method = seg.text.includes('微信') ? '微信' : (seg.text.includes('面谈') ? '面谈' : '电话')
      if (module === 'HOMEWORK') entry.subject = inferSubject(seg.text)
      list.push(entry)
    }
  }
  entries.value = list
  parseDlg.value = true
}
function inferAttStatus(t) {
  if (t.includes('请假')) return '请假'
  if (t.includes('缺勤') || t.includes('旷课')) return '缺勤'
  return '迟到'
}
function inferSubject(t) {
  const names = ['语文', '数学', '英语', '科学', '社会', '美术', '体育', '音乐', '信息技术']
  return names.find(n => t.includes(n)) || '作业'
}

async function confirmSave() {
  const picked = entries.value.filter(e => e.checked)
  if (!picked.length) { ElMessage.warning('未选择任何条目'); return }
  const missingStudent = picked.some(e => needStudent(e.module) && !e.studentId)
  if (missingStudent) { ElMessage.warning('有条目未关联学生，请选择学生或取消勾选'); return }
  const items = picked.map(e => ({
    module: e.module,
    studentId: e.studentId || null,
    content: e.text,
    title: e.text,
    status: e.status,
    method: e.method,
    subject: e.subject,
    category: '其他',
    dueDate: new Date().toISOString().slice(0, 10)
  }))
  await http.post('/dashboard/quicknote/save', { content: noteText.value, items })
  ElMessage.success(`已保存 ${items.length} 条到各模块`)
  parseDlg.value = false
  noteText.value = ''
  loadStats()
}

onMounted(async () => {
  await loadSettings()
  await loadStats()
  loadHolidays()
  loadCards()
  loadTodayCourses()
  loadLeave()
  allStudents.value = await http.get('/roster/students')
})
</script>

<style scoped>
.greet-card { background: linear-gradient(135deg, #fdeef4, #fdf3f7); border: 1px solid #fbd9e6; }
.greet-head { display: flex; justify-content: space-between; align-items: flex-start; }
.greet-title { font-size: 26px; font-weight: 800; color: #d65a8a; }
.greet-date { color: #8a8fa3; margin: 8px 0 2px; }
.greet-sub { color: #b0b3c0; font-size: 12px; }
.class-tags { margin-top: 12px; display: flex; gap: 10px; }
.ctag { border: none; }
.motto-row { margin-top: 12px; color: #6a6f82; }
.motto-label { display: inline-block; width: 70px; color: #aab0c0; }
.quick-card { height: 100%; border: 1px dashed #f0b8d0; }
.quick-title { font-weight: 800; color: #5a5f72; font-size: 16px; }
.quick-hint { color: #f06292; font-size: 13px; }
.quick-example { background: #f7f8fc; border-radius: 8px; padding: 10px; color: #7a7f92; font-size: 13px; margin: 10px 0; }
.quick-desc { color: #aab0c0; font-size: 12px; margin-bottom: 12px; }
.quick-input { display: flex; gap: 10px; }
.stat-cards { margin: 16px 0; }
.mini-stat { cursor: pointer; }
.mini-stat :deep(.el-card__body) { display: flex; flex-direction: column; gap: 4px; }
.ms-icon { width: 40px; height: 40px; border-radius: 10px; display: flex; align-items: center; justify-content: center; font-size: 20px; }
.ms-num { font-size: 30px; font-weight: 800; color: #4a4a55; }
.ms-num small { font-size: 14px; color: #b0b3c0; }
.ms-label { color: #8a8fa3; font-size: 14px; }
.ms-link { color: #c0c4d0; font-size: 12px; }
.section-title { font-size: 17px; font-weight: 800; color: #4a4a55; margin: 8px 0 14px; border-left: 4px solid #f78fb3; padding-left: 10px; }
.drag-hint { font-size: 12px; color: #b0b3c0; font-weight: 400; }
.card-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; }
.work-card { min-height: 230px; }
.wc-head { font-weight: 800; color: #5a5f72; display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; font-size: 16px; }
.wc-body { color: #8a8fa3; margin: 8px 0; }
.wc-btn, .wc-link { margin-top: 8px; padding: 0; }
.three-num { display: flex; text-align: center; gap: 8px; margin: 10px 0; }
.three-num > div { flex: 1; background: #f4f7fd; border-radius: 10px; padding: 10px 4px; }
.three-num b { display: block; font-size: 24px; color: #5b9bd5; }
.three-num span { font-size: 12px; color: #aab0c0; }
.period-row { display: flex; justify-content: space-between; font-size: 13px; color: #7a7f92; padding: 4px 0; }
.period-row b { font-size: 13px; }
.holiday-main { text-align: center; padding: 6px 0; }
.holiday-days { font-size: 52px; font-weight: 800; color: #f06292; line-height: 1; }
.holiday-unit { color: #aab0c0; }
.holiday-name { font-size: 18px; font-weight: 800; margin-top: 6px; }
.holiday-date { color: #aab0c0; font-size: 13px; }
.holiday-others { display: flex; gap: 8px; justify-content: center; margin-top: 14px; flex-wrap: wrap; }
.ho-tag { cursor: pointer; }
.course-timeline { display: flex; flex-direction: column; gap: 8px; }
.timeline-row { display: flex; align-items: center; gap: 10px; font-size: 13px; }
.tl-time { color: #aab0c0; width: 110px; }
.tl-tag { color: #fff; border-radius: 8px; padding: 4px 12px; font-weight: 600; }
.empty-h { color: #c0c4d0; text-align: center; padding: 20px 0; }
.entry { display: flex; align-items: center; gap: 8px; padding: 8px 0; border-bottom: 1px dashed #eef0f5; }
.entry-text { color: #5a5f72; }
.entry-student { width: 180px; margin-left: auto; }
.parse-tip { color: #8a8fa3; font-size: 13px; margin-bottom: 8px; }
/* 请假提醒条：奶油黄底，数字+名单+操作三段 */
.leave-card { margin-bottom: 16px; background: linear-gradient(135deg, #fff8e6, #fffdf5); border: 1px solid #f5e3b0; }
.leave-card :deep(.el-card__body) { display: flex; align-items: center; gap: 20px; }
.lv-nums { display: flex; gap: 10px; flex-shrink: 0; }
.lv-num { text-align: center; background: #fff; border-radius: 12px; padding: 6px 14px; min-width: 86px; border: 1px solid #f3e9cf; }
.lv-num b { display: block; font-size: 22px; color: #b08a2e; }
.lv-num b.danger { color: #e06060; }
.lv-num span { font-size: 12px; color: #aab0c0; }
.lv-num.hot { background: #fdecec; border-color: #f5c0c0; }
.lv-names { flex: 1; display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.lv-tag { margin: 0; }
.lv-more { color: #aab0c0; font-size: 13px; }
.lv-actions { flex-shrink: 0; display: flex; gap: 8px; }
</style>

<template>
  <div>
    <div class="page-toolbar">
      <h2 class="page-title">🗓️ 课程表</h2>
      <el-button text :icon="Edit" @click="renameClass">编辑班级名</el-button>
      <el-button text :icon="Plus" @click="addClass">添加任课班</el-button>
      <div class="spacer"></div>
      <el-button :icon="Setting" @click="openPeriodDlg">自定义节次</el-button>
      <el-button :icon="Collection" @click="subjectDlg = true">管理科目</el-button>
      <el-button type="danger" plain :icon="Delete" @click="clearGrid">清空</el-button>
    </div>

    <!-- 班级 tabs -->
    <el-tabs v-model="activeClass" @tab-change="loadGrid">
      <el-tab-pane v-for="c in classes" :key="c.id" :label="c.name + (c.type === 'HOMEROOM' ? '（班主任班）' : '（任课班）')" :name="String(c.id)" />
    </el-tabs>

    <!-- 课表网格 -->
    <div class="tt-wrap">
      <div class="tt-grid" :style="{ gridTemplateColumns: '120px repeat(7, 1fr)' }">
        <div class="tt-head corner">时间</div>
        <div v-for="(d, i) in dayNames" :key="d" class="tt-head" :class="{ weekend: i >= 5 }">{{ d }}</div>

        <template v-for="p in periods" :key="p.id">
          <div class="tt-period">
            <div class="pname">{{ p.name }}</div>
            <div class="ptime">{{ p.startTime }}-{{ p.endTime }}</div>
          </div>
          <!-- 课间/午休：整行占位 -->
          <div v-if="p.isBreak" class="tt-break" :style="{ gridColumn: '2 / -1' }">{{ p.name }}</div>
          <template v-else>
            <div v-for="d in 7" :key="d"
                 class="tt-cell"
                 :class="{ weekend: d >= 6, filled: cell(d, p.id) }"
                 @drop="onDrop($event, d, p)" @dragover.prevent
                 @click="cell(d, p.id) ? removeCell(d, p) : openPick(d, p)">
              <template v-if="cell(d, p.id)">
                <div class="course" :style="{ background: cell(d, p.id).color }">
                  <div class="cname">{{ cell(d, p.id).subjectName }}</div>
                  <div class="cteach">{{ cell(d, p.id).teacher }}</div>
                </div>
              </template>
              <el-icon v-else class="plus"><Plus /></el-icon>
            </div>
          </template>
        </template>
      </div>
    </div>

    <!-- 科目图例（可拖拽排课） -->
    <div class="legend-tip">● 科目图例（拖动到上方表格快速排课）</div>
    <div class="legend">
      <div v-for="s in subjects" :key="s.id" class="legend-chip"
           :style="{ background: s.color }" draggable="true"
           @dragstart="onDragStart($event, s.id)">
        {{ s.name }}<span class="chip-teacher">{{ s.teacher }}</span>
      </div>
    </div>

    <!-- 选科目弹窗 -->
    <el-dialog v-model="pickVisible" title="选择科目" width="360px">
      <div class="pick-grid">
        <div v-for="s in subjects" :key="s.id" class="pick-chip" :style="{ background: s.color }" @click="assign(s.id)">
          {{ s.name }} · {{ s.teacher }}
        </div>
      </div>
    </el-dialog>

    <!-- 科目管理 -->
    <el-dialog v-model="subjectDlg" title="管理科目" width="560px">
      <el-table :data="subjects" size="small" max-height="360">
        <el-table-column label="颜色" width="70">
          <template #default="{ row }"><el-color-picker v-model="row.color" @change="updSubject(row)" /></template>
        </el-table-column>
        <el-table-column label="科目" width="120"><template #default="{ row }"><el-input v-model="row.name" size="small" @change="updSubject(row)" /></template></el-table-column>
        <el-table-column label="任课教师"><template #default="{ row }"><el-input v-model="row.teacher" size="small" @change="updSubject(row)" /></template></el-table-column>
        <el-table-column label="操作" width="70"><template #default="{ row }"><el-button type="danger" text size="small" @click="delSubject(row)">删除</el-button></template></el-table-column>
      </el-table>
      <el-button type="primary" plain class="mt" :icon="Plus" @click="addSubject">新增科目</el-button>
    </el-dialog>

    <!-- 节次管理 -->
    <el-dialog v-model="periodDlg" title="自定义节次" width="580px">
      <!-- 新增节次的时间顺延规则：课间/每节上课 分钟（存个人设置，缺省 10/40） -->
      <div class="period-cfg">
        <span class="pcfg-label">新增节次顺延：</span>
        <span>课间 <el-input-number v-model="breakMin" :min="0" :max="60" size="small" controls-position="right" class="pcfg-num" @change="savePeriodCfg" /> 分钟</span>
        <span>每节上课 <el-input-number v-model="classMin" :min="10" :max="120" size="small" controls-position="right" class="pcfg-num" @change="savePeriodCfg" /> 分钟</span>
      </div>
      <el-table :data="periods" size="small" max-height="360">
        <el-table-column prop="name" label="节次" width="110" />
        <el-table-column label="开始" width="120"><template #default="{ row }"><el-time-picker v-model="row.startTime" format="HH:mm" value-format="HH:mm" size="small" class="period-time" @change="updPeriod(row)" /></template></el-table-column>
        <el-table-column label="结束" width="120"><template #default="{ row }"><el-time-picker v-model="row.endTime" format="HH:mm" value-format="HH:mm" size="small" class="period-time" @change="updPeriod(row)" /></template></el-table-column>
        <el-table-column label="课间" width="70"><template #default="{ row }"><el-switch v-model="row.isBreak" :active-value="1" :inactive-value="0" @change="updPeriod(row)" /></template></el-table-column>
        <el-table-column label="操作" width="70"><template #default="{ row }"><el-button type="danger" text size="small" @click="delPeriod(row)">删除</el-button></template></el-table-column>
      </el-table>
      <el-button type="primary" plain class="mt" :icon="Plus" @click="addPeriod">新增节次</el-button>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Edit, Plus, Setting, Collection, Delete } from '@element-plus/icons-vue'
import http from '../api/http'

const dayNames = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
const classes = ref([])
const activeClass = ref('1')
const periods = ref([])
const subjects = ref([])
const cells = ref([])
const subjectDlg = ref(false)
const periodDlg = ref(false)
const pickVisible = ref(false)
const pickTarget = ref({ day: 1, period: null })

async function loadClasses() {
  classes.value = await http.get('/timetable/classes')
  if (!classes.value.find(c => String(c.id) === activeClass.value) && classes.value.length) {
    activeClass.value = String(classes.value[0].id)
  }
  await loadGrid()
  subjects.value = await http.get('/timetable/subjects')
  periods.value = await http.get('/timetable/periods')
}
async function loadGrid() {
  const g = await http.get('/timetable/grid', { params: { classId: activeClass.value } })
  periods.value = g.periods
  cells.value = g.cells
}
loadClasses()

function cell(day, periodId) {
  return cells.value.find(c => c.dayOfWeek === day && c.periodId === periodId)
}

function onDragStart(e, subjectId) {
  e.dataTransfer.setData('subjectId', subjectId)
}
async function onDrop(e, day, period) {
  if (period.isBreak) return
  const subjectId = e.dataTransfer.getData('subjectId')
  if (subjectId) await assign(subjectId, day, period)
}
function openPick(day, period) {
  if (period.isBreak) return
  pickTarget.value = { day, period }
  pickVisible.value = true
}
async function assign(subjectId, day, period) {
  const d = day || pickTarget.value.day
  const p = period || pickTarget.value.period
  await http.post('/timetable/cell', { classId: activeClass.value, dayOfWeek: d, periodId: p.id, subjectId: Number(subjectId) })
  pickVisible.value = false
  await loadGrid()
}
async function removeCell(day, period) {
  await ElMessageBox.confirm('移除该节课程？', '提示', { type: 'warning' })
  await http.post('/timetable/cell', { classId: activeClass.value, dayOfWeek: day, periodId: period.id, subjectId: null })
  loadGrid()
}
async function clearGrid() {
  await ElMessageBox.confirm('清空该班全部排课？', '警告', { type: 'warning' })
  await http.post('/timetable/clear', null, { params: { classId: activeClass.value } })
  ElMessage.success('已清空')
  loadGrid()
}

// 班级
async function addClass() {
  const { value } = await ElMessageBox.prompt('输入任课班级名', '添加任课班', { inputPattern: /.+/, inputErrorMessage: '不能为空' })
  await http.post('/timetable/class', { name: value, type: 'SUBJECT' })
  ElMessage.success('已添加')
  await loadClasses()
}
async function renameClass() {
  const c = classes.value.find(x => String(x.id) === activeClass.value)
  const { value } = await ElMessageBox.prompt('修改班级名', '编辑班级名', { inputValue: c.name, inputPattern: /.+/ })
  await http.put(`/timetable/class/${c.id}`, { name: value })
  ElMessage.success('已保存')
  await loadClasses()
}

// 科目
async function addSubject() {
  await http.post('/timetable/subject', { name: '新科目', teacher: '', color: '#9fc5f8' })
  subjects.value = await http.get('/timetable/subjects')
}
async function updSubject(row) {
  await http.put('/timetable/subject/' + row.id, row)
  await loadGrid()
}
async function delSubject(row) {
  await http.delete('/timetable/subject/' + row.id)
  subjects.value = await http.get('/timetable/subjects')
  await loadGrid()
}

// 节次
/** HH:mm 字符串 → 分钟数；分钟数 → HH:mm（跨天自动进位/回绕），仅用于新增节次的时间顺延 */
function toMin(hhmm) { const [h, m] = String(hhmm || '0:0').split(':').map(Number); return h * 60 + (m || 0) }
function toHHmm(min) { const x = ((Math.round(min) % 1440) + 1440) % 1440; return String(Math.floor(x / 60)).padStart(2, '0') + ':' + String(x % 60).padStart(2, '0') }

// 新增节次的顺延时长（个人设置 period_break_min/period_class_min，缺省课间 10 / 上课 40）
const breakMin = ref(10)
const classMin = ref(40)
async function openPeriodDlg() {
  periodDlg.value = true
  try {
    const s = await http.get('/dashboard/settings')
    breakMin.value = Number(s.period_break_min) || 10
    classMin.value = Number(s.period_class_min) || 40
  } catch (e) { /* 读取失败用默认值 */ }
}
async function savePeriodCfg() {
  await http.post('/dashboard/settings', {
    period_break_min: String(breakMin.value), period_class_min: String(classMin.value)
  })
  ElMessage.success('已保存顺延时长')
}

async function addPeriod() {
  // 序号：接续现有「第 N 节」最大序号 +1（早自习/午休等非「第N节」命名不占序号）
  let maxN = 0
  for (const p of periods.value) {
    const mt = String(p.name || '').match(/^第(\d+)节$/)
    if (mt) maxN = Math.max(maxN, Number(mt[1]))
  }
  const name = '第' + (maxN + 1) + '节'
  // 时间顺延：开始 = 最后一个节次(按 sort，含课间)的结束 + 课间；一个节次都没有时从 08:00 起
  const last = periods.value.length ? periods.value[periods.value.length - 1] : null
  const start = last ? toMin(last.endTime) + breakMin.value : 8 * 60
  const startTime = toHHmm(start)
  const endTime = toHHmm(start + classMin.value)
  await http.post('/timetable/period', { name, startTime, endTime, isBreak: 0 })
  periods.value = await http.get('/timetable/periods')
  await loadGrid()
}
async function updPeriod(row) {
  await http.put('/timetable/period/' + row.id, row)
  await loadGrid()
}
async function delPeriod(row) {
  await http.delete('/timetable/period/' + row.id)
  await loadGrid()
  periods.value = await http.get('/timetable/periods')
}
</script>

<style scoped>
.tt-wrap { background: #fff; border-radius: 14px; padding: 12px; overflow-x: auto; }
.tt-grid { display: grid; gap: 6px; min-width: 900px; }
.tt-head { text-align: center; font-weight: 700; color: #7a7f92; padding: 8px 0; background: #f4f2fa; border-radius: 8px; }
.tt-head.weekend { background: #f3f3f5; color: #b0b3c0; }
.tt-period { text-align: center; background: #fafbfe; border-radius: 8px; padding: 6px 2px; }
.tt-period .pname { font-size: 13px; font-weight: 700; color: #5a5f72; }
.tt-period .ptime { font-size: 11px; color: #aab0c0; }
.tt-cell {
  min-height: 58px; border-radius: 8px; background: #fafbff; border: 1px dashed #e3e6f0;
  display: flex; align-items: center; justify-content: center; cursor: pointer; position: relative;
}
.tt-cell.weekend { background: #f7f7f9; }
.tt-cell .plus { color: #cfd4e0; font-size: 18px; }
.tt-cell:hover .plus { color: #f49ac0; }
.course { width: 100%; height: 100%; border-radius: 8px; color: #fff; display: flex; flex-direction: column; align-items: center; justify-content: center; }
.course .cname { font-weight: 700; font-size: 14px; }
.course .cteach { font-size: 11px; opacity: .92; }
.tt-break {
  background: repeating-linear-gradient(45deg, #f7f5fb, #f7f5fb 10px, #f1eef8 10px, #f1eef8 20px);
  border-radius: 8px; display: flex; align-items: center; justify-content: center;
  color: #b0a8c8; font-weight: 700; min-height: 34px;
}
.legend-tip { margin: 14px 4px 8px; color: #8a8fa3; font-size: 13px; }
.legend { display: flex; flex-wrap: wrap; gap: 10px; }
.legend-chip { color: #fff; border-radius: 10px; padding: 8px 14px; font-weight: 700; cursor: grab; user-select: none; }
.chip-teacher { font-weight: 400; font-size: 12px; margin-left: 6px; opacity: .9; }
.pick-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 10px; }
.pick-chip { color: #fff; border-radius: 10px; padding: 12px; text-align: center; cursor: pointer; font-weight: 600; }
.mt { margin-top: 12px; }
.period-cfg { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; margin-bottom: 12px; font-size: 13px; color: #5a5f72; }
.period-cfg .pcfg-label { font-weight: 700; color: #7a7f92; }
.period-cfg .pcfg-num { width: 92px; margin: 0 2px; }
.period-time { width: 104px; }
</style>

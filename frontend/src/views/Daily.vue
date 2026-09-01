<template>
  <div>
    <div class="page-toolbar">
      <h2 class="page-title">📋 班级日常登记</h2>
    </div>

    <el-tabs v-model="tab">
      <!-- 早读考勤 -->
      <el-tab-pane label="⏰ 早读考勤" name="att">
        <div class="bar">
          <el-date-picker v-model="att.date" type="date" value-format="YYYY-MM-DD" @change="loadAtt" />
          <span class="sum">应到 {{ att.students.length }} 人，
            异常 {{ attAbnormal }} 人，全勤率 {{ attRate }}%</span>
          <div class="spacer"></div>
          <el-button type="primary" @click="saveAtt">保存考勤</el-button>
        </div>
        <div class="att-grid">
          <div v-for="s in att.students" :key="s.id" class="att-item" :class="statusClass(s.status)">
            <div class="att-name">{{ s.name }} <small>{{ s.stuNo }}</small></div>
            <el-radio-group v-model="s.status" size="small">
              <el-radio-button value="出勤">出勤</el-radio-button>
              <el-radio-button value="迟到">迟到</el-radio-button>
              <el-radio-button value="请假">请假</el-radio-button>
              <el-radio-button value="缺勤">缺勤</el-radio-button>
            </el-radio-group>
          </div>
        </div>
      </el-tab-pane>

      <!-- 课堂纪律 -->
      <el-tab-pane label="📝 课堂纪律" name="dis">
        <el-card class="add-card">
          <div class="bar">
            <el-date-picker v-model="dis.date" type="date" value-format="YYYY-MM-DD" />
            <el-select v-model="dis.studentIds" multiple filterable collapse-tags placeholder="选择违纪学生（可多选）" style="min-width:320px">
              <el-option v-for="s in students" :key="s.id" :label="s.name + '（' + s.stu_no + '）'" :value="s.id" />
            </el-select>
            <el-input v-model="dis.content" placeholder="违纪情况，如：上课吃零食" style="flex:1" />
            <el-button type="primary" @click="saveDis">登记</el-button>
          </div>
        </el-card>
        <el-table :data="disList" stripe>
          <el-table-column prop="date" label="日期" width="120" />
          <el-table-column prop="name" label="学生" width="120" />
          <el-table-column prop="content" label="情况" />
          <el-table-column label="操作" width="80">
            <template #default="{ row }"><el-button type="danger" text @click="delDis(row.id)">删除</el-button></template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 作业收缴 -->
      <el-tab-pane label="📚 作业收缴" name="hw">
        <div class="bar">
          <el-date-picker v-model="hw.date" type="date" value-format="YYYY-MM-DD" @change="loadHw" />
          <el-select v-model="hw.subject" placeholder="选择科目" @change="loadHw" style="width:160px">
            <el-option v-for="s in subjects" :key="s.id" :label="s.name" :value="s.name" />
          </el-select>
          <span class="sum">未交 {{ hwMissing }} 人</span>
          <div class="spacer"></div>
          <el-button type="primary" @click="saveHw">保存</el-button>
        </div>
        <div class="hw-grid">
          <div v-for="s in hw.students" :key="s.id" class="hw-item" :class="{ missing: s.missing }" @click="s.missing = !s.missing">
            <el-checkbox :model-value="s.missing" /><span>{{ s.name }}</span>
          </div>
        </div>
        <p class="tip">勾选表示<b>未交</b>作业（默认已交）</p>
      </el-tab-pane>

      <!-- 请假管理 -->
      <el-tab-pane label="🏖️ 请假管理" name="leave">
        <el-card class="add-card">
          <div class="bar">
            <el-select v-model="leaveForm.studentId" filterable placeholder="选择学生" style="width:190px">
              <el-option v-for="s in students" :key="s.id" :label="s.name + '（' + s.stu_no + '）'" :value="s.id" />
            </el-select>
            <el-select v-model="leaveForm.leaveType" style="width:92px">
              <el-option label="病假" value="病假" /><el-option label="事假" value="事假" /><el-option label="公假" value="公假" />
            </el-select>
            <el-date-picker v-model="leaveForm.startDate" type="date" value-format="YYYY-MM-DD" @change="calcDays" />
            <span class="sum">至</span>
            <el-date-picker v-model="leaveForm.endDate" type="date" value-format="YYYY-MM-DD" @change="calcDays" />
            <el-input-number v-model="leaveForm.days" :min="0.5" :step="0.5" :precision="1" style="width:115px" />
            <span class="sum">天</span>
            <el-select v-model="leaveForm.notifyMethod" style="width:92px">
              <el-option label="微信" value="微信" /><el-option label="电话" value="电话" />
              <el-option label="书面" value="书面" /><el-option label="口头" value="口头" />
            </el-select>
            <el-input v-model="leaveForm.reason" placeholder="事由（选填）" style="flex:1;min-width:140px" />
            <el-button type="primary" @click="submitLeave">登记请假</el-button>
          </div>
        </el-card>

        <el-alert v-if="leaveSummary.overdueCount > 0" type="error" :closable="false" show-icon class="lv-alert"
          :title="`⚠️ ${leaveSummary.overdueCount} 名学生请假已到期未销假（未归校），请立即联系家长确认！`" />
        <el-alert v-else-if="leaveSummary.todayReturnCount > 0" type="warning" :closable="false" show-icon class="lv-alert"
          :title="`今日有 ${leaveSummary.todayReturnCount} 名学生应销假返校，可点「销假」或工作台一键销假`" />

        <h4 class="leave-h">🏖️ 请假中（{{ activeLeaves.length }}）</h4>
        <el-table :data="activeLeaves" stripe class="leave-table">
          <el-table-column label="学生" width="130">
            <template #default="{ row }">{{ row.name }} <small class="muted">{{ row.stuNo }}</small></template>
          </el-table-column>
          <el-table-column prop="leaveType" label="类型" width="70" />
          <el-table-column label="起止日期" width="210">
            <template #default="{ row }">{{ row.startDate }} ~ {{ row.endDate }}</template>
          </el-table-column>
          <el-table-column prop="days" label="天数" width="70" />
          <el-table-column prop="reason" label="事由" />
          <el-table-column prop="notifyMethod" label="告知方式" width="90" />
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <el-tag v-if="row.overdue" type="danger" effect="dark">⚠️ 未归校</el-tag>
              <el-tag v-else-if="row.returnToday" type="warning">今日应销假</el-tag>
              <el-tag v-else type="info">请假中</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="210">
            <template #default="{ row }">
              <el-button type="success" size="small" @click="doReturn(row)">销假</el-button>
              <el-button size="small" @click="openEdit(row)">编辑</el-button>
              <el-popconfirm title="删除单据？自动写入的请假考勤将一并清除" @confirm="delLeave(row)">
                <template #reference><el-button type="danger" size="small" text>删除</el-button></template>
              </el-popconfirm>
            </template>
          </el-table-column>
        </el-table>

        <h4 class="leave-h">📒 请假台账（本学期）</h4>
        <div class="bar">
          <el-date-picker v-model="monthFilter" type="month" value-format="YYYY-MM"
                          placeholder="按月份过滤" clearable @change="loadLedger" />
          <el-tag v-for="w in warnStudents" :key="w.studentId" type="danger" effect="plain" class="warn-tag">
            ⚠️ {{ w.name }} 病假累计 {{ w.sickDays }} 天（≥{{ leaveSummary.warnDays || 20 }} 天，按校规需上报/建议休学评估）
          </el-tag>
        </div>
        <el-table :data="ledger" stripe>
          <el-table-column label="学生" width="130">
            <template #default="{ row }">{{ row.name }} <small class="muted">{{ row.stuNo }}</small></template>
          </el-table-column>
          <el-table-column prop="leaveType" label="类型" width="70" />
          <el-table-column label="起止日期" width="210">
            <template #default="{ row }">{{ row.startDate }} ~ {{ row.endDate }}</template>
          </el-table-column>
          <el-table-column prop="days" label="天数" width="70" />
          <el-table-column prop="reason" label="事由" />
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <el-tag v-if="row.status === 'DONE'" type="success" size="small">已销假 {{ row.returnDate }}</el-tag>
              <el-tag v-else type="info" size="small">请假中</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 编辑请假单（续假：改结束日期即延长，考勤自动补写） -->
    <el-dialog v-model="editDlg" title="编辑请假单（续假改结束日期即可）" width="440px">
      <el-form label-width="90px">
        <el-form-item label="结束日期">
          <el-date-picker v-model="editForm.endDate" type="date" value-format="YYYY-MM-DD" @change="calcEditDays" />
        </el-form-item>
        <el-form-item label="天数">
          <el-input-number v-model="editForm.days" :min="0.5" :step="0.5" :precision="1" />
        </el-form-item>
        <el-form-item label="事由">
          <el-input v-model="editForm.reason" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDlg = false">取消</el-button>
        <el-button type="primary" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api/http'

const today = new Date().toISOString().slice(0, 10)
const tab = ref('att')
const students = ref([])
const subjects = ref([])

const att = reactive({ date: today, students: [] })
const dis = reactive({ date: today, studentIds: [], content: '' })
const disList = ref([])
const hw = reactive({ date: today, subject: '', students: [] })

http.get('/roster/students').then(d => students.value = d)
http.get('/timetable/subjects').then(d => subjects.value = d)

const attAbnormal = computed(() => att.students.filter(s => s.status !== '出勤').length)
const attRate = computed(() => att.students.length ? Math.round((att.students.length - attAbnormal.value) * 1000 / att.students.length) / 10 : 0)
const hwMissing = computed(() => hw.students.filter(s => s.missing).length)

function statusClass(st) {
  return { 'st-ok': st === '出勤', 'st-warn': st === '迟到', 'st-off': st === '请假' || st === '缺勤' }
}

async function loadAtt() {
  const d = await http.get('/daily/attendance', { params: { date: att.date } })
  att.students = d.students
}
async function saveAtt() {
  const records = att.students.map(s => ({ studentId: s.id, status: s.status }))
  await http.post('/daily/attendance/save', { date: att.date, records })
  ElMessage.success('考勤已保存')
}

async function loadDis() {
  disList.value = await http.get('/daily/discipline')
}
async function saveDis() {
  if (!dis.studentIds.length || !dis.content) { ElMessage.warning('请选择学生并填写情况'); return }
  await http.post('/daily/discipline', { date: dis.date, studentIds: dis.studentIds, content: dis.content })
  ElMessage.success('已登记')
  dis.studentIds = []; dis.content = ''
  loadDis()
}
async function delDis(id) { await http.delete('/daily/discipline/' + id); loadDis() }

async function loadHw() {
  if (!hw.subject) { hw.students = []; return }
  const d = await http.get('/daily/homework', { params: { date: hw.date, subject: hw.subject } })
  hw.students = d.students
}
async function saveHw() {
  if (!hw.subject) { ElMessage.warning('请选择科目'); return }
  const missingIds = hw.students.filter(s => s.missing).map(s => s.id)
  await http.post('/daily/homework/save', { date: hw.date, subject: hw.subject, missingIds })
  ElMessage.success('已保存')
}

// ---------- 请假管理 ----------
const leaveForm = reactive({ studentId: null, leaveType: '病假', startDate: today, endDate: today, days: 1, reason: '', notifyMethod: '微信' })
const activeLeaves = ref([])   // 请假中列表
const ledger = ref([])         // 台账（含已销假）
const leaveSummary = ref({})   // 学期汇总与工作台计数
const monthFilter = ref('')
// 病假超阈值学生（台账区红色预警标签）
const warnStudents = computed(() => (leaveSummary.value.students || []).filter(s => s.warn))

/** 起止日期变更时自动算天数（整天）；半天等特殊情况老师可手动改 */
function calcDays() {
  if (leaveForm.startDate && leaveForm.endDate) {
    const d = (new Date(leaveForm.endDate) - new Date(leaveForm.startDate)) / 86400000 + 1
    if (d > 0) leaveForm.days = d
  }
}

/** 加载请假页签全部数据：请假中、学期汇总、台账 */
async function loadLeave() {
  activeLeaves.value = await http.get('/leave/active')
  leaveSummary.value = await http.get('/leave/summary')
  await loadLedger()
}
async function loadLedger() {
  ledger.value = await http.get('/leave/list', { params: { month: monthFilter.value || undefined } })
}

/** 登记请假：后端会把区间内空白考勤自动置为"请假"（手动标记不覆盖） */
async function submitLeave() {
  if (!leaveForm.studentId) { ElMessage.warning('请选择学生'); return }
  await http.post('/leave', { ...leaveForm })
  ElMessage.success('请假已登记，区间考勤已自动置为请假')
  leaveForm.studentId = null; leaveForm.reason = ''
  await loadLeave(); loadAtt()
}

/** 销假：实际返校日默认今天；考勤历史留痕不改动 */
async function doReturn(row) {
  await http.post(`/leave/${row.id}/return`, {})
  ElMessage.success(`${row.name} 已销假返校`)
  await loadLeave(); loadAtt()
}

/** 误登记删除：后端同步回滚本单据自动写入的请假考勤 */
async function delLeave(row) {
  await http.delete(`/leave/${row.id}`)
  ElMessage.success('单据已删除，联动考勤已回滚')
  await loadLeave(); loadAtt()
}

// 编辑请假单（续假兜底）
const editDlg = ref(false)
const editForm = reactive({ id: null, startDate: '', endDate: '', days: 1, reason: '' })

function openEdit(row) {
  editForm.id = row.id
  editForm.startDate = row.startDate
  editForm.endDate = row.endDate
  editForm.days = row.days
  editForm.reason = row.reason || ''
  editDlg.value = true
}

/** 编辑时改结束日期自动重算天数（半天可手改） */
function calcEditDays() {
  if (editForm.startDate && editForm.endDate) {
    const d = (new Date(editForm.endDate) - new Date(editForm.startDate)) / 86400000 + 1
    if (d > 0) editForm.days = d
  }
}

/** 保存编辑：后端按结束日期变化补写/回滚联动考勤 */
async function saveEdit() {
  await http.put(`/leave/${editForm.id}`, {
    endDate: editForm.endDate, days: editForm.days, reason: editForm.reason,
  })
  ElMessage.success('已保存，考勤联动已更新')
  editDlg.value = false
  await loadLeave(); loadAtt()
}

loadAtt(); loadDis(); loadLeave()
</script>

<style scoped>
.bar { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; flex-wrap: wrap; }
.sum { color: #8a8fa3; font-size: 14px; }
.add-card { margin-bottom: 16px; }
.att-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 10px; }
.att-item { display: flex; align-items: center; justify-content: space-between; padding: 10px 14px; border-radius: 10px; background: #f6f9f4; border: 1px solid #e3f0e0; }
.att-item.st-warn { background: #fdf6e3; border-color: #f0e2a8; }
.att-item.st-off { background: #fdecec; border-color: #f5c0c0; }
.att-name { font-weight: 700; }
.att-name small { color: #aab0c0; font-weight: 400; margin-left: 4px; }
.hw-grid { display: grid; grid-template-columns: repeat(6, 1fr); gap: 10px; }
.hw-item { display: flex; align-items: center; gap: 8px; padding: 10px; border-radius: 10px; background: #f4f8f4; cursor: pointer; border: 1px solid #e0ecdf; }
.hw-item.missing { background: #fdecec; border-color: #f5b0b0; color: #e06060; font-weight: 700; }
.tip { color: #b0b3c0; font-size: 13px; }
.muted { color: #aab0c0; font-weight: 400; margin-left: 4px; }
.lv-alert { margin-bottom: 14px; }
.leave-h { margin: 18px 0 10px; color: #6b5b73; font-size: 15px; }
.leave-table { margin-bottom: 8px; }
.warn-tag { margin-right: 8px; margin-top: 4px; }
</style>

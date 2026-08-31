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
    </el-tabs>
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

loadAtt(); loadDis()
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
</style>

<template>
  <div>
    <div class="page-toolbar">
      <h2 class="page-title">📈 成绩分析</h2>
      <span>本次考试</span>
      <el-select v-model="curExam" style="width:170px" @change="loadAll">
        <el-option v-for="e in exams" :key="e.id" :label="e.name" :value="e.id" />
      </el-select>
      <span>对比考试</span>
      <el-select v-model="cmpExam" style="width:170px" @change="loadCompare" clearable>
        <el-option v-for="e in exams" :key="e.id" :label="e.name" :value="e.id" />
      </el-select>
      <el-button type="primary" :icon="Upload" @click="importDlg = true">导入成绩</el-button>
      <el-button :icon="Setting" @click="openStd">标准</el-button>
      <el-button type="danger" plain :icon="Delete" @click="delExam">删除考试</el-button>
    </div>

    <!-- 各科统计卡 -->
    <div class="score-grid">
      <div v-for="s in subjectStats" :key="s.subject" class="score-col">
        <div class="score-card">
          <div class="sc-subject">{{ s.subject }}</div>
          <div class="sc-avg">{{ s.avg }}</div>
          <div class="sc-range">最高 {{ s.max }} ｜ 最低 {{ s.min }}</div>
          <div class="sc-rates">
            <el-progress :percentage="s.passRate" :stroke-width="8" color="#67c23a" :show-text="false" />
            <span>及格率 {{ s.passRate }}%</span>
          </div>
          <div class="sc-rates">
            <el-progress :percentage="s.excellentRate" :stroke-width="8" color="#f78fb3" :show-text="false" />
            <span>优秀率 {{ s.excellentRate }}%</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 分数段柱状图 -->
    <el-card class="block">
      <h3 class="block-title">📊 成绩分析图表 · 分数段分布（全部科目）</h3>
      <div ref="barEl" class="chart"></div>
    </el-card>

    <!-- 折线图 -->
    <el-card class="block">
      <div class="block-head">
        <h3 class="block-title">📉 本次考试成绩分布</h3>
        <el-select v-model="lineSubject" size="small" style="width:140px" @change="renderLine">
          <el-option v-for="s in subjectStats" :key="s.subject" :label="s.subject" :value="s.subject" />
        </el-select>
      </div>
      <div ref="lineEl" class="chart tall"></div>
      <div v-if="lineData.stats" class="line-stat">
        平均分：<b>{{ lineData.stats.avg }}</b> ｜ 最高分：<b>{{ lineData.stats.max }}</b> ｜
        最低分：<b>{{ lineData.stats.min }}</b> ｜
        <span class="up">↑ 高于平均 {{ lineData.stats.above }} 人</span> ｜
        <span class="down">↓ 低于平均 {{ lineData.stats.below }} 人</span>
      </div>
    </el-card>

    <!-- 明细表 -->
    <el-card class="block">
      <h3 class="block-title">📋 成绩明细</h3>
      <el-table :data="detail.rows" size="small" stripe max-height="420">
        <el-table-column prop="rank" label="排名" width="70" />
        <el-table-column prop="stuNo" label="学号" width="100" />
        <el-table-column prop="name" label="姓名" width="90" />
        <el-table-column v-for="sub in detail.subjects" :key="sub" :label="sub" width="80">
          <template #default="{ row }">{{ row.scores[sub] ?? '—' }}</template>
        </el-table-column>
        <el-table-column prop="total" label="总分" width="90" />
        <el-table-column prop="avg" label="平均分" width="90" />
      </el-table>
    </el-card>

    <!-- 对比表 -->
    <el-card v-if="cmpExam" class="block">
      <h3 class="block-title">🔁 考试进退步对比（本次 - 对比）</h3>
      <el-table :data="compare.rows" size="small" stripe max-height="420">
        <el-table-column prop="stuNo" label="学号" width="100" />
        <el-table-column prop="name" label="姓名" width="90" />
        <el-table-column v-for="sub in compare.subjects" :key="sub" :label="sub" width="85">
          <template #default="{ row }">
            <span :class="diffClass(row.diffs[sub])">{{ formatDiff(row.diffs[sub]) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="总分变化" width="100">
          <template #default="{ row }">
            <span :class="diffClass(row.totalDiff)"><b>{{ formatDiff(row.totalDiff) }}</b></span>
          </template>
        </el-table-column>
        <el-table-column label="排名" width="110">
          <template #default="{ row }">
            <span>第{{ row.rankA }}名</span>
            <el-tag v-if="row.rankChange" size="small" :type="row.rankChange > 0 ? 'success' : 'danger'">
              {{ row.rankChange > 0 ? '↑' : '↓' }}{{ Math.abs(row.rankChange) }}
            </el-tag>
            <el-tag v-else-if="row.rankB" size="small" type="info">—</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 导入弹窗 -->
    <el-dialog v-model="importDlg" title="导入成绩" width="420px">
      <el-form label-width="90px">
        <el-form-item label="考试名称"><el-input v-model="importForm.name" placeholder="如：2026秋季第一次月考" /></el-form-item>
        <el-form-item label="考试日期"><el-date-picker v-model="importForm.date" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
        <el-form-item label="成绩文件">
          <el-upload :show-file-list="false" :http-request="doImport" accept=".xlsx,.xls" :limit="1">
            <el-button :icon="Upload">选择 Excel（.xls/.xlsx，表头：学号|姓名|科目...）</el-button>
          </el-upload>
        </el-form-item>
      </el-form>
    </el-dialog>

    <!-- 标准弹窗 -->
    <el-dialog v-model="stdDlg" title="评分标准" width="360px">
      <el-form label-width="90px">
        <el-form-item label="及格线"><el-input-number v-model="stdForm.passLine" :min="0" :max="100" /></el-form-item>
        <el-form-item label="优秀线"><el-input-number v-model="stdForm.excellentLine" :min="0" :max="100" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="stdDlg = false">取消</el-button>
        <el-button type="primary" @click="saveStd">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, nextTick, watch, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Upload, Setting, Delete } from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import http from '../api/http'

const exams = ref([])
const curExam = ref(null)
const cmpExam = ref(null)
const subjectStats = ref([])
const lineSubject = ref('')
const lineData = ref({})
const detail = reactive({ subjects: [], rows: [] })
const compare = reactive({ subjects: [], rows: [] })

const barEl = ref(null), lineEl = ref(null)
let barChart = null, lineChart = null

const importDlg = ref(false)
const importForm = reactive({ name: '', date: '', file: null })
const stdDlg = ref(false)
const stdForm = reactive({ passLine: 60, excellentLine: 85 })

async function loadExams() {
  exams.value = await http.get('/score/exams')
  if (!curExam.value && exams.value.length) curExam.value = exams.value[0].id
  await loadAll()
}

async function loadAll() {
  if (!curExam.value) { subjectStats.value = []; detail.subjects = []; detail.rows = []; return }
  subjectStats.value = await http.get('/score/subject-stats', { params: { examId: curExam.value } })
  const dist = await http.get('/score/distribution', { params: { examId: curExam.value } })
  const d = await http.get('/score/detail', { params: { examId: curExam.value } })
  detail.subjects = d.subjects; detail.rows = d.rows
  if (!lineSubject.value && subjectStats.value.length) lineSubject.value = subjectStats.value[0].subject
  await loadLine()
  nextTick(() => renderBar(dist))
}

function renderBar(dist) {
  if (!barEl.value) return
  barChart ||= echarts.init(barEl.value)
  const colors = ['#f78fb3', '#f6a86b', '#f5d76e', '#67d39a', '#6fa8f5']
  barChart.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 20, top: 20, bottom: 30 },
    xAxis: { type: 'category', data: dist.labels },
    yAxis: { type: 'value' },
    series: [{ type: 'bar', data: dist.counts.map((v, i) => ({ value: v, itemStyle: { color: colors[i] } })), barWidth: '55%', label: { show: true, position: 'top' } }]
  })
}

async function loadLine() {
  if (!lineSubject.value) return
  lineData.value = await http.get('/score/line', { params: { examId: curExam.value, subject: lineSubject.value } })
  nextTick(renderLine)
}
function renderLine() {
  loadLine()
}
function drawLine() {
  if (!lineEl.value || !lineData.value.names) return
  lineChart ||= echarts.init(lineEl.value)
  lineChart.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 20, top: 20, bottom: 60 },
    xAxis: { type: 'category', data: lineData.value.names, axisLabel: { rotate: 45, fontSize: 10 } },
    yAxis: { type: 'value', min: 0, max: 100 },
    series: [
      { name: '分数', type: 'line', data: lineData.value.scores, smooth: true,
        itemStyle: { color: '#6fa8f5' }, label: { show: true, fontSize: 9 } },
      { name: '平均线', type: 'line', data: lineData.value.names.map(() => lineData.value.avg),
        lineStyle: { type: 'dashed', color: '#f78fb3' }, itemStyle: { color: '#f78fb3' }, symbol: 'none' }
    ]
  })
}

async function loadCompare() {
  if (!cmpExam.value) { compare.subjects = []; compare.rows = []; return }
  const r = await http.get('/score/compare', { params: { examA: curExam.value, examB: cmpExam.value } })
  compare.subjects = r.subjects; compare.rows = r.rows
}

async function doImport(opt) {
  if (!importForm.name) { ElMessage.warning('请填写考试名称'); return }
  const fd = new FormData()
  fd.append('file', opt.file)
  fd.append('examName', importForm.name)
  fd.append('examDate', importForm.date || '')
  const r = await http.post('/score/import', fd, { headers: { 'Content-Type': 'multipart/form-data' } })
  ElMessage.success(`导入成功：${r.students} 名学生，${r.subjects} 个科目`)
  importDlg.value = false
  importForm.name = ''; importForm.date = ''
  await loadExams()
}

async function delExam() {
  if (!curExam.value) return
  await ElMessageBox.confirm('确定删除本次考试及其成绩？', '警告', { type: 'warning' })
  await http.delete('/score/exam/' + curExam.value)
  ElMessage.success('已删除')
  curExam.value = null
  loadExams()
}

async function openStd() {
  const s = await http.get('/dashboard/settings')
  stdForm.passLine = Number(s.pass_line || 60)
  stdForm.excellentLine = Number(s.excellent_line || 85)
  stdDlg.value = true
}
async function saveStd() {
  await http.post('/dashboard/settings', { pass_line: String(stdForm.passLine), excellent_line: String(stdForm.excellentLine) })
  ElMessage.success('已保存')
  stdDlg.value = false
  loadAll()
}

function formatDiff(v) { if (v == null) return '—'; return (v > 0 ? '+' : '') + v }
function diffClass(v) { if (v == null) return ''; return v > 0 ? 'up' : v < 0 ? 'down' : '' }

// 监听折线数据后绘图
watch(lineData, () => nextTick(drawLine), { deep: true })

// 图表依赖 DOM 引用，挂载后再加载
onMounted(loadExams)
</script>

<style scoped>
.score-grid { display: grid; grid-template-columns: repeat(5, 1fr); gap: 12px; margin-bottom: 8px; }
.score-col { margin-bottom: 8px; }
.score-card { background: #fff; border-radius: 14px; padding: 14px; box-shadow: 0 4px 14px rgba(120,130,160,.08); border-top: 3px solid #f78fb3; }
.sc-subject { font-weight: 800; color: #5a5f72; }
.sc-avg { font-size: 30px; font-weight: 800; color: #f06292; margin: 4px 0; }
.sc-range { font-size: 12px; color: #aab0c0; margin-bottom: 8px; }
.sc-rates { display: flex; align-items: center; gap: 8px; font-size: 12px; color: #8a8fa3; margin-top: 4px; }
.sc-rates .el-progress { flex: 1; }
.block { margin-bottom: 18px; }
.block-title { margin: 0 0 12px; color: #5a5f72; }
.block-head { display: flex; align-items: center; justify-content: space-between; }
.chart { height: 240px; }
.chart.tall { height: 320px; }
.line-stat { margin-top: 10px; color: #6a6f82; font-size: 14px; }
.up { color: #67c23a; font-weight: 700; }
.down { color: #f56c6c; font-weight: 700; }
:deep(.up) { color: #67c23a; font-weight: 700; }
:deep(.down) { color: #f56c6c; font-weight: 700; }
</style>
